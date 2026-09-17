"""Environment-only configuration; secrets are never included in validation output."""

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        frozen=True, extra="ignore", hide_input_in_errors=True,
    )

    ai_provider: str = Field(default="openrouter", description="openrouter, openai_compatible, or anthropic")
    openrouter_api_key: SecretStr | None = None
    ai_api_key: SecretStr | None = None
    ai_model: str = Field(default="meta-llama/llama-3.3-70b-instruct:free", min_length=1, max_length=200)
    ai_base_url: str = Field(default="https://openrouter.ai/api/v1", min_length=1, max_length=500)

    anthropic_api_key: SecretStr | None = None
    anthropic_model: str | None = None
    robot_api_token: SecretStr
    robot_host: str = "127.0.0.1"
    robot_port: int = Field(default=8000, ge=1, le=65535)
    robot_provider_timeout_seconds: float = Field(default=20, ge=0.01, le=60)
    robot_body_timeout_seconds: float = Field(default=10, ge=1, le=30)
    robot_max_body_bytes: int = Field(default=16384, ge=4096, le=65536)
    robot_token_limit: int = Field(default=10, ge=1, le=1000)
    robot_global_limit: int = Field(default=120, ge=1, le=10000)
    robot_rate_window_seconds: int = Field(default=60, ge=1, le=3600)

    @property
    def is_local_provider(self) -> bool:
        base = self.ai_base_url.lower()
        return "localhost" in base or "127.0.0.1" in base or "0.0.0.0" in base

    def get_api_key(self) -> str | None:
        key = self.openrouter_api_key or self.ai_api_key or self.anthropic_api_key
        return key.get_secret_value() if key else None

    @property
    def effective_model(self) -> str:
        if self.ai_provider == "anthropic" and self.anthropic_model:
            return self.anthropic_model
        return self.ai_model

    @field_validator("robot_api_token")
    @classmethod
    def validate_token(cls, value: SecretStr) -> SecretStr:
        token = value.get_secret_value()
        if not 32 <= len(token) <= 256 or not token.isascii() or any(c.isspace() for c in token):
            raise ValueError("ROBOT_API_TOKEN must be 32–256 non-whitespace ASCII characters")
        return value

    @field_validator("ai_model", "robot_host", "ai_base_url")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip() or value != value.strip():
            raise ValueError("Configuration value must be nonblank without surrounding whitespace")
        return value
