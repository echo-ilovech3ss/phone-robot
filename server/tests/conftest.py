"""Prevent local deployment settings or secrets from influencing offline tests."""

import os

import pytest


@pytest.fixture(autouse=True)
def clean_service_environment(monkeypatch):
    for name in tuple(os.environ):
        if name.startswith(("ROBOT_", "ANTHROPIC_", "OPENROUTER_", "AI_")):
            monkeypatch.delenv(name)
