"""Python 3.11+. Run python -m server with environment configured.

Exactly one process/replica is supported by the in-memory rate limiter. Keep the
loopback bind and use an HTTPS reverse proxy for the Android client's endpoint.
Do not configure proxy/SDK request-body logging; access logging is disabled here.
"""

import sys

import uvicorn
from pydantic import ValidationError

from .app import create_app
from .config import Settings


def main() -> None:
    try:
        settings = Settings()
        if settings.anthropic_api_key is None or not settings.anthropic_api_key.get_secret_value().strip():
            raise ValueError("ANTHROPIC_API_KEY is required")
    except (ValidationError, ValueError):
        # Do not print validation exceptions, environment values or credentials.
        print(
            "Server configuration invalid. Set ANTHROPIC_API_KEY, ANTHROPIC_MODEL "
            "and ROBOT_API_TOKEN (32–256 characters); check server/.env.example.",
            file=sys.stderr,
        )
        raise SystemExit(2) from None
    uvicorn.run(
        create_app(settings), host=settings.robot_host, port=settings.robot_port,
        workers=1, access_log=False, proxy_headers=False,
        limit_concurrency=32, timeout_keep_alive=5,
    )


if __name__ == "__main__":
    main()
