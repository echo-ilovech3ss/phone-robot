"""Offline behavioral tests: every provider is injected, never credential-backed."""

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

import httpx
import pytest

from server.app import create_app
from server.config import Settings
from server.provider import ProviderRateLimited, ProviderUnavailable

pytestmark = pytest.mark.asyncio
TOKEN = "test-only-token-not-for-production-12345678"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@dataclass
class FakeProvider:
    reply: str = "Hello. What would you like to talk about?"
    error: Exception | None = None
    delay: float = 0
    calls: list[str] = field(default_factory=list)

    async def respond(self, message: str) -> str:
        self.calls.append(message)
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self.reply


@asynccontextmanager
async def client_for(provider=None, clock=None, **overrides):
    settings = Settings(
        _env_file=None,
        anthropic_api_key=None,
        anthropic_model="test-model",
        robot_api_token=TOKEN,
        **overrides,
    )
    app = create_app(settings, provider=provider or FakeProvider(), clock=clock)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            yield client


@pytest.mark.parametrize("authorization", [None, "Basic bad", "Bearer wrong", "Bearer "])
async def test_auth_rejection_never_calls_provider(authorization):
    provider = FakeProvider()
    headers = {} if authorization is None else {"Authorization": authorization}
    async with client_for(provider) as client:
        response = await client.post("/v1/chat", headers=headers, json={"message": "private"})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert provider.calls == []
    assert "private" not in response.text


async def test_duplicate_auth_headers_are_rejected():
    async with client_for() as client:
        response = await client.post(
            "/v1/chat",
            headers=[("Authorization", f"Bearer {TOKEN}"), ("Authorization", "Bearer bad")],
            json={"message": "hello"},
        )
    assert response.status_code == 401


async def test_health_is_public_and_contains_health_only():
    async with client_for() as client:
        response = await client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["cache-control"] == "no-store"


async def test_success_and_reply_character_bound():
    provider = FakeProvider(reply="R" * 2001)
    async with client_for(provider) as client:
        response = await client.post("/v1/chat", headers=AUTH, json={"message": "Hello"})
    assert response.status_code == 200
    assert response.json() == {"reply": "R" * 1999 + "…"}
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("payload", [
    {"message": 123}, {"message": ""}, {"message": " \n "},
    {"message": "x" * 1001}, {"message": "x", "image": "not-allowed"},
    {"message": ["hello"]}, {},
])
async def test_strict_message_validation_does_not_echo_input(payload):
    provider = FakeProvider()
    async with client_for(provider) as client:
        response = await client.post("/v1/chat", headers=AUTH, json=payload)
    assert response.status_code == 422
    assert provider.calls == []
    assert set(response.json()) == {"detail"}
    assert "not-allowed" not in response.text


async def test_accepts_full_unicode_message_boundary():
    async with client_for() as client:
        response = await client.post("/v1/chat", headers=AUTH, json={"message": "🙂" * 1000})
    assert response.status_code == 200


async def test_rejects_declared_oversize_before_json_parsing():
    provider = FakeProvider()
    async with client_for(provider) as client:
        response = await client.post(
            "/v1/chat", headers={**AUTH, "Content-Type": "application/json"},
            content=b"{" + b"x" * 16384,
        )
    assert response.status_code == 413
    assert provider.calls == []


@pytest.mark.parametrize("declared_length", [None, "1"])
async def test_streaming_body_limit_ignores_missing_or_false_content_length(declared_length):
    provider = FakeProvider()

    async def chunks():
        for _ in range(5):
            yield b"x" * 4096

    headers = {**AUTH, "Content-Type": "application/json"}
    if declared_length is not None:
        headers["Content-Length"] = declared_length
    async with client_for(provider) as client:
        response = await client.post("/v1/chat", headers=headers, content=chunks())
    assert response.status_code == 413
    assert provider.calls == []


async def test_invalid_json_is_safe_and_non_json_is_rejected():
    async with client_for() as client:
        malformed = await client.post(
            "/v1/chat", headers={**AUTH, "Content-Type": "application/json"},
            content=b'{"message":"private',
        )
        other_type = await client.post("/v1/chat", headers=AUTH, content="private")
    assert malformed.status_code == 422
    assert "private" not in malformed.text
    assert other_type.status_code == 415


async def test_per_token_limit_blocks_spending_and_recovers_after_window():
    now = [100.0]
    provider = FakeProvider()
    async with client_for(provider, clock=lambda: now[0], robot_token_limit=1) as client:
        first = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
        second = await client.post("/v1/chat", headers=AUTH, json={"message": "blocked"})
        now[0] += 60
        third = await client.post("/v1/chat", headers=AUTH, json={"message": "again"})
    assert (first.status_code, second.status_code, third.status_code) == (200, 429, 200)
    assert int(second.headers["retry-after"]) > 0
    assert provider.calls == ["hello", "again"]


async def test_global_limit_blocks_authenticated_provider_spending():
    provider = FakeProvider()
    async with client_for(provider, robot_global_limit=1) as client:
        first = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
        second = await client.post("/v1/chat", headers=AUTH, json={"message": "blocked"})
    assert (first.status_code, second.status_code) == (200, 429)
    assert provider.calls == ["hello"]


async def test_bad_auth_cannot_exhaust_chat_or_health_quota():
    provider = FakeProvider()
    async with client_for(provider, robot_global_limit=1) as client:
        first = await client.post("/v1/chat", json={"message": "bad auth"})
        limited = await client.post("/v1/chat", json={"message": "bad auth"})
        health = await client.get("/health")
        valid = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
    assert (first.status_code, limited.status_code) == (401, 429)
    assert (health.status_code, valid.status_code) == (200, 200)
    assert provider.calls == ["hello"]


@pytest.mark.parametrize("error,expected", [
    (TimeoutError("secret upstream info"), 504),
    (ProviderRateLimited(), 429),
    (ProviderUnavailable(), 502),
    (RuntimeError("secret upstream info"), 502),
])
async def test_provider_errors_are_safe_and_not_retried(error, expected):
    provider = FakeProvider(error=error)
    async with client_for(provider) as client:
        response = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
    assert response.status_code == expected
    assert "secret" not in response.text
    assert provider.calls == ["hello"]


async def test_total_deadline_cancels_slow_provider():
    provider = FakeProvider(delay=5)
    async with client_for(provider, robot_provider_timeout_seconds=0.01) as client:
        response = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
    assert response.status_code == 504
    assert provider.calls == ["hello"]


async def test_empty_provider_response_is_an_error_not_a_fake_reply():
    async with client_for(FakeProvider(reply=" \n ")) as client:
        response = await client.post("/v1/chat", headers=AUTH, json={"message": "hello"})
    assert response.status_code == 502
