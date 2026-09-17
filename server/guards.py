"""Single-process limits: one event loop, one configured token, fixed memory.

Run exactly one worker/replica. Limits reset on restart and fixed-window
boundaries can allow two bursts. Multi-process deployments need a shared limiter.
Terminate HTTPS at a trusted reverse proxy; never expose cleartext beyond loopback.
"""

import asyncio
import hashlib
import hmac
import math
import time
from collections.abc import Callable
from dataclasses import dataclass

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .config import Settings


@dataclass(frozen=True)
class Window:
    start: float
    count: int = 0

    def consume(self, now: float, duration: int, limit: int) -> tuple["Window", int]:
        current = Window(now) if now - self.start >= duration else self
        if current.count >= limit:
            return current, max(1, math.ceil(duration - (now - current.start)))
        return Window(current.start, current.count + 1), 0


class RequestGuards:
    def __init__(self, app: ASGIApp, settings: Settings, clock: Callable[[], float] = time.monotonic):
        self.app = app
        self.settings = settings
        self.clock = clock
        self._global = Window(clock())
        self._token = Window(clock())
        self._public = Window(clock())
        self._health = Window(clock())
        self._expected_token_hash = hashlib.sha256(
            settings.robot_api_token.get_secret_value().encode("ascii")
        ).digest()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_private(message: Message) -> None:
            if message["type"] == "http.response.start":
                message = {
                    **message,
                    "headers": [*message.get("headers", []), (b"cache-control", b"no-store")],
                }
            await send(message)

        rejection = self._authorize(scope)
        if rejection is not None:
            await rejection(scope, receive, send_private)
            return
        headers = Headers(scope=scope)
        rejection = self._check_headers(headers, scope)
        if rejection is not None:
            await rejection(scope, receive, send_private)
            return
        try:
            async with asyncio.timeout(self.settings.robot_body_timeout_seconds):
                body = await self._read_body(receive)
        except TimeoutError:
            await JSONResponse({"detail": "Request body timed out"}, 408)(scope, receive, send_private)
            return
        except BodyTooLarge:
            await JSONResponse({"detail": "Request body too large"}, 413)(scope, receive, send_private)
            return
        if body is None:  # Client disconnected; no provider call or response.
            return

        delivered = False

        async def replay() -> Message:
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}

        await self.app(scope, replay, send_private)

    def _authorize(self, scope: Scope) -> JSONResponse | None:
        # No await in the check-and-consume sequence: atomic on one ASGI loop.
        now = self.clock()
        duration = self.settings.robot_rate_window_seconds
        if scope["path"] == "/health" and scope["method"] == "GET":
            self._health, retry = self._health.consume(now, duration, self.settings.robot_global_limit)
            return self._rate_limited(retry) if retry else None
        if scope["path"] != "/v1/chat" or scope["method"] != "POST":
            self._public, retry = self._public.consume(now, duration, self.settings.robot_global_limit)
            return self._rate_limited(retry) if retry else None
        values = Headers(scope=scope).getlist("authorization")
        scheme, _, token = values[0].partition(" ") if len(values) == 1 else ("", "", "")
        token_hash = hashlib.sha256(token.encode("utf-8")).digest()
        valid = hmac.compare_digest(token_hash, self._expected_token_hash)
        if scheme.lower() != "bearer" or not valid:
            self._public, retry = self._public.consume(now, duration, self.settings.robot_global_limit)
            if retry:
                return self._rate_limited(retry)
            return JSONResponse(
                {"detail": "Unauthorized"}, 401, headers={"WWW-Authenticate": "Bearer"},
            )
        self._global, retry = self._global.consume(now, duration, self.settings.robot_global_limit)
        if retry:
            return self._rate_limited(retry)
        self._token, retry = self._token.consume(now, duration, self.settings.robot_token_limit)
        return self._rate_limited(retry) if retry else None

    def _check_headers(self, headers: Headers, scope: Scope) -> JSONResponse | None:
        lengths = headers.getlist("content-length")
        if lengths:
            if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdecimal():
                return JSONResponse({"detail": "Invalid Content-Length"}, 400)
            # Compare digit count before conversion so enormous integers stay bounded.
            digits = lengths[0].lstrip("0") or "0"
            maximum = self.settings.robot_max_body_bytes
            if len(digits) > len(str(maximum)) or int(digits) > maximum:
                return JSONResponse({"detail": "Request body too large"}, 413)
        if scope["path"] == "/v1/chat" and scope["method"] == "POST":
            media_type = headers.get("content-type", "").split(";", 1)[0].strip().lower()
            if media_type != "application/json" or headers.get("content-encoding", "identity").lower() != "identity":
                return JSONResponse({"detail": "Uncompressed application/json required"}, 415)
        return None

    async def _read_body(self, receive: Receive) -> bytes | None:
        body = bytearray()
        while True:
            event = await receive()
            if event["type"] == "http.disconnect":
                return None
            chunk = event.get("body", b"")
            if len(body) + len(chunk) > self.settings.robot_max_body_bytes:
                raise BodyTooLarge()
            body.extend(chunk)
            if not event.get("more_body", False):
                return bytes(body)

    @staticmethod
    def _rate_limited(retry: int) -> JSONResponse:
        return JSONResponse(
            {"detail": "Request rate limit reached"}, 429,
            headers={"Retry-After": str(retry)},
        )


class BodyTooLarge(Exception):
    """Raised before buffering bytes beyond the body limit."""
