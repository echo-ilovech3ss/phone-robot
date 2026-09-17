"""One stateless text request, no tools, no retries, no alternate providers."""

import logging
from typing import Protocol

from anthropic import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AsyncAnthropic,
    RateLimitError,
)

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


def silence_transport_logging() -> None:
    # SDK debug mode may include request bodies. Stop these namespaces at a
    # NullHandler even if ANTHROPIC_LOG or the root logger enables debug output.
    for name in ("anthropic", "httpx", "httpcore"):
        logger = logging.getLogger(name)
        logger.handlers = [logging.NullHandler()]
        logger.propagate = False


class AnthropicProvider:
    def __init__(self, settings: Settings) -> None:
        if settings.anthropic_api_key is None or not settings.anthropic_api_key.get_secret_value().strip():
            raise ValueError("ANTHROPIC_API_KEY is required to start the real provider")
        silence_transport_logging()
        self._model = settings.anthropic_model
        self._client = AsyncAnthropic(
            api_key=settings.anthropic_api_key.get_secret_value(),
            base_url="https://api.anthropic.com",
            timeout=settings.robot_provider_timeout_seconds,
            max_retries=0,
        )

    async def respond(self, message: str) -> str:
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
