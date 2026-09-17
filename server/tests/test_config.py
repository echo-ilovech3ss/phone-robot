import pytest
from pydantic import ValidationError

from server.config import Settings
from server.provider import AnthropicProvider


@pytest.mark.parametrize("token", ["x" * 31, "x" * 257, "x" * 31 + " ", "🙂" * 32])
def test_rejects_unsafe_bearer_configuration(token):
    with pytest.raises(ValidationError):
        Settings(anthropic_model="test-model", robot_api_token=token)


def test_model_must_be_explicit():
    with pytest.raises(ValidationError):
        Settings(robot_api_token="x" * 32)


def test_real_provider_refuses_missing_credentials_before_network():
    config = Settings(anthropic_model="test-model", robot_api_token="x" * 32)
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        AnthropicProvider(config)


@pytest.mark.parametrize("overrides", [
    {"robot_token_limit": 0}, {"robot_global_limit": 10001},
    {"robot_rate_window_seconds": 0}, {"robot_max_body_bytes": 65537},
    {"robot_provider_timeout_seconds": 0},
])
def test_refuses_unbounded_or_disabled_limits(overrides):
    with pytest.raises(ValidationError):
        Settings(anthropic_model="test-model", robot_api_token="x" * 32, **overrides)
