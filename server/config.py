"""Environment-only configuration; secrets are never included in validation output."""

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        frozen=True, extra="ignore", hide_input_in_errors=True,
    )

    anthropic_api_key: SecretStr | None = None
    anthropic_model: str = Field(min_length=1, max_length=200)
    robot_api_token: SecretStr
    robot_host: str = "127.0.0.1"
    robot_port: int = Field(default=8000, ge=1, le=65535)
    robot_provider_timeout_seconds: float = Field(default=20, ge=0.01, le=60)
    robot_body_timeout_seconds: float = Field(default=10, ge=1, le=30)
    robot_max_body_bytes: int = Field(default=16384, ge=4096, le=65536)
    robot_token_limit: int = Field(default=10, ge=1, le=1000)
    robot_global_limit: int = Field(default=120, ge=1, le=10000)
    robot_rate_window_seconds: int = Field(default=60, ge=1, le=3600)

    @field_validator("robot_api_token")
    @classmethod
    def validate_token(cls, value: SecretStr) -> SecretStr:
        token = value.get_secret_value()
        if not 32 <= len(token) <= 256 or not token.isascii() or any(c.isspace() for c in token):
            raise ValueError("ROBOT_API_TOKEN must be 32–256 non-whitespace ASCII characters")
        return value

    @field_validator("anthropic_model", "robot_host")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip() or value != value.strip():
            raise ValueError("Configuration value must be nonblank without surrounding whitespace")
        return value
