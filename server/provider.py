"""Stateless text requests: OpenRouter (free tier default), OpenAI-compatible, or Anthropic."""

import logging
from typing import Protocol

import httpx

from .config import Settings

SYSTEM_PROMPT = (
    "You are a friendly phone robot's conversational voice. Reply in plain text, "
    "usually one or two short sentences, at most 80 words. Be honest and calm. "
    "You receive only the user's current text, not images, audio, identity or history. "
    "You cannot see people, control hardware, move, access files, or perform actions. "
    "Never claim to have performed an action. If asked to do one, explain that "
    "device actions require the app's local controls. Do not output command payloads."
)


class ProviderRateLimited(Exception):
    """The text provider is temporarily rate limited."""


class ProviderUnavailable(Exception):
    """The text provider could not produce a usable response."""


class TextProvider(Protocol):
    async def respond(self, message: str) -> str:
        """Return conversational text, never executable actions."""

    async def close(self) -> None:
        """Release any client connections."""


def silence_transport_logging() -> None:
    for name in ("anthropic", "httpx", "httpcore"):
        logger = logging.getLogger(name)
        logger.handlers = [logging.NullHandler()]
        logger.propagate = False


class OpenAICompatibleProvider:
    """Connects to OpenRouter (default with free models) or any OpenAI-compatible API."""

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        key = settings.get_api_key()
        if not key and not settings.is_local_provider:
            raise ValueError("AI_API_KEY (or OPENROUTER_API_KEY) is required to start the provider")
        silence_transport_logging()
        self._model = settings.effective_model
        self._base_url = str(settings.ai_base_url).rstrip("/")
        headers = {
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/echo-ilovech3ss/phone-robot",
            "X-Title": "Phone Robot",
        }
        if key:
            headers["Authorization"] = f"Bearer {key}"
        self._client = httpx.AsyncClient(
            base_url=self._base_url,
            headers=headers,
            transport=transport,
            timeout=httpx.Timeout(settings.robot_provider_timeout_seconds),
        )

    async def respond(self, message: str) -> str:
        try:
            response = await self._client.post(
                "/chat/completions",
                json={
                    "model": self._model,
                    "messages": [
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": message},
                    ],
                    "max_tokens": 256,
                    "temperature": 0.7,
                },
            )
        except httpx.TimeoutException:
            raise TimeoutError("Provider deadline exceeded") from None
        except httpx.NetworkError:
            raise ProviderUnavailable() from None

        if response.status_code == 429:
            raise ProviderRateLimited() from None
        if response.status_code != 200:
            raise ProviderUnavailable() from None

        try:
            data = response.json()
            choices = data.get("choices", [])
            if not choices:
                raise ProviderUnavailable() from None
            reply = choices[0].get("message", {}).get("content", "").strip()
            if not reply:
                raise ProviderUnavailable() from None
            return reply
        except Exception:
            raise ProviderUnavailable() from None

    async def close(self) -> None:
        await self._client.aclose()


class AnthropicProvider:
    def __init__(self, settings: Settings) -> None:
        if settings.anthropic_api_key is None or not settings.anthropic_api_key.get_secret_value().strip():
            raise ValueError("ANTHROPIC_API_KEY is required to start the real provider")
        from anthropic import (
            APIConnectionError,
            APIStatusError,
            APITimeoutError,
            AsyncAnthropic,
            RateLimitError,
        )
        silence_transport_logging()
        self._model = settings.anthropic_model or "claude-3-haiku-20240307"
        self._client = AsyncAnthropic(
            api_key=settings.anthropic_api_key.get_secret_value(),
            base_url="https://api.anthropic.com",
            timeout=settings.robot_provider_timeout_seconds,
            max_retries=0,
        )

    async def respond(self, message: str) -> str:
        from anthropic import (
            APIConnectionError,
            APIStatusError,
            APITimeoutError,
            RateLimitError,
        )
        try:
            result = await self._client.messages.create(
                model=self._model,
                max_tokens=256,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": message}],
            )
        except APITimeoutError:
            raise TimeoutError("Provider deadline exceeded") from None
        except RateLimitError:
            raise ProviderRateLimited() from None
        except (APIConnectionError, APIStatusError):
            raise ProviderUnavailable() from None
        return "\n".join(block.text for block in result.content if block.type == "text")

    async def close(self) -> None:
        await self._client.close()


def create_provider(settings: Settings) -> TextProvider:
    if settings.ai_provider == "anthropic" or (
        settings.anthropic_api_key and not settings.openrouter_api_key and not settings.ai_api_key
    ):
        return AnthropicProvider(settings)
    return OpenAICompatibleProvider(settings)
