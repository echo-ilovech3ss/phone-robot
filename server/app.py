"""Optional text-only service. Start from the repository root with python -m server."""

import asyncio
import logging
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .config import Settings
from .guards import RequestGuards
from .provider import AnthropicProvider, ProviderRateLimited, ProviderUnavailable, TextProvider

logger = logging.getLogger(__name__)
MAX_REPLY_CHARACTERS = 2000


class ChatRequest(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", frozen=True)
    message: str = Field(min_length=1, max_length=1000)

    @field_validator("message")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Message must contain text")
        return value


class ChatReply(BaseModel):
    model_config = ConfigDict(frozen=True)
    reply: str = Field(min_length=1, max_length=MAX_REPLY_CHARACTERS)


class HealthReply(BaseModel):
    status: str


def create_app(
    settings: Settings | None = None,
    *,
    provider: TextProvider | None = None,
    clock: Callable[[], float] | None = None,
) -> FastAPI:
    config = settings if settings is not None else Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        owned_provider = AnthropicProvider(config) if provider is None else None
        app.state.provider = owned_provider if owned_provider is not None else provider
        try:
            yield
        finally:
            if owned_provider is not None:
                await owned_provider.close()

    app = FastAPI(
        title="Phone Robot Text Service", lifespan=lifespan,
        docs_url=None, redoc_url=None, openapi_url=None,
    )
    app.add_middleware(RequestGuards, settings=config, clock=clock or time.monotonic)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
        # FastAPI's default validation response may echo private user input.
        return JSONResponse({"detail": "Invalid request; message must be 1–1000 text characters"}, 422)

    @app.get("/health", response_model=HealthReply)
    async def health() -> HealthReply:
        # Liveness only: no upstream probe, credentials, configuration or billing.
        return HealthReply(status="ok")

    @app.post("/v1/chat", response_model=ChatReply)
    async def chat(payload: ChatRequest, request: Request) -> ChatReply:
        return await generate_reply(request.app.state.provider, payload.message, config)

    return app


async def generate_reply(provider: TextProvider, message: str, settings: Settings) -> ChatReply:
    try:
        # SDK timeouts bound socket operations; this additionally bounds total time.
        async with asyncio.timeout(settings.robot_provider_timeout_seconds):
            reply = (await provider.respond(message)).strip()
        if not reply:
            raise ProviderUnavailable()
    except TimeoutError:
        logger.warning("Text provider timed out")
        raise HTTPException(504, "Text provider timed out") from None
    except ProviderRateLimited:
        logger.warning("Text provider rate limited")
        raise HTTPException(429, "Text provider rate limited", headers={"Retry-After": "60"}) from None
    except ProviderUnavailable:
        logger.warning("Text provider unavailable")
        raise HTTPException(502, "Text provider unavailable") from None
    except Exception:
        # Never log exception objects: provider errors may contain prompts or keys.
        logger.error("Unexpected text provider failure")
        raise HTTPException(502, "Text provider unavailable") from None
    if len(reply) > MAX_REPLY_CHARACTERS:
        reply = reply[: MAX_REPLY_CHARACTERS - 1].rstrip() + "…"
    return ChatReply(reply=reply)
