import pytest
import httpx
from pydantic import ValidationError

from server.config import Settings
from server.provider import AnthropicProvider, OpenAICompatibleProvider, create_provider
from server.provider import ProviderRateLimited, ProviderUnavailable


@pytest.mark.parametrize("token", ["x" * 31, "x" * 257, "x" * 31 + " ", "🙂" * 32])
def test_rejects_unsafe_bearer_configuration(token):
    with pytest.raises(ValidationError):
        Settings(robot_api_token=token)


def test_model_must_be_nonblank():
    with pytest.raises(ValidationError):
        Settings(ai_model="   ", robot_api_token="x" * 32)


def test_openrouter_refuses_missing_credentials_before_network():
    config = Settings(ai_model="test-model", robot_api_token="x" * 32)
    with pytest.raises(ValueError, match="AI_API_KEY"):
        OpenAICompatibleProvider(config)


def test_provider_refuses_missing_model():
    config = Settings(ai_api_key="sk-test", robot_api_token="x" * 32)
    with pytest.raises(ValueError, match="AI_MODEL"):
        OpenAICompatibleProvider(config)


def test_local_provider_allows_no_credentials():
    config = Settings(ai_base_url="http://127.0.0.1:11434/v1", ai_model="llama3.2", robot_api_token="x" * 32)
    provider = OpenAICompatibleProvider(config)
    assert provider._model == "llama3.2"


def test_anthropic_provider_refuses_missing_credentials():
    config = Settings(ai_provider="anthropic", anthropic_model="claude-3-haiku-20240307", robot_api_token="x" * 32)
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        AnthropicProvider(config)


def test_create_provider_dispatches_correctly():
    openrouter_config = Settings(openrouter_api_key="sk-or-v1-test", ai_model="test-model", robot_api_token="x" * 32)
    p1 = create_provider(openrouter_config)
    assert isinstance(p1, OpenAICompatibleProvider)

    anthropic_config = Settings(ai_provider="anthropic", anthropic_api_key="sk-ant-test", anthropic_model="claude-3-haiku-20240307", robot_api_token="x" * 32)
    p2 = create_provider(anthropic_config)
    assert isinstance(p2, AnthropicProvider)


@pytest.mark.asyncio
async def test_openai_compatible_provider_success():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "Bearer test-key"
        assert request.headers["http-referer"] == "https://github.com/echo-ilovech3ss/phone-robot"
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "I am a friendly phone robot."}}]},
        )

    config = Settings(openrouter_api_key="test-key", ai_model="test-model", robot_api_token="x" * 32)
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    reply = await provider.respond("Hi!")
    assert reply == "I am a friendly phone robot."
    await provider.close()


@pytest.mark.asyncio
async def test_openai_compatible_provider_rate_limited():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": "rate limited"})

    config = Settings(openrouter_api_key="test-key", ai_model="test-model", robot_api_token="x" * 32)
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    with pytest.raises(ProviderRateLimited):
        await provider.respond("Hi!")
    await provider.close()


@pytest.mark.asyncio
async def test_openai_compatible_provider_error_and_empty_choices():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "server error"})

    config = Settings(openrouter_api_key="test-key", ai_model="test-model", robot_api_token="x" * 32)
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    with pytest.raises(ProviderUnavailable):
        await provider.respond("Hi!")
    await provider.close()
@pytest.mark.parametrize("overrides", [
    {"robot_token_limit": 0}, {"robot_global_limit": 10001},
    {"robot_rate_window_seconds": 0}, {"robot_max_body_bytes": 65537},
    {"robot_provider_timeout_seconds": 0},
])
def test_refuses_unbounded_or_disabled_limits(overrides):
    with pytest.raises(ValidationError):
        Settings(anthropic_model="test-model", robot_api_token="x" * 32, **overrides)
