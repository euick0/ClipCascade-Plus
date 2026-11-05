"""Loads the official desktop client's code from this repository, unmodified."""
import os, sys

SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), *[".."] * 5, "ClipCascade_Desktop", "src"))
sys.path.insert(0, SRC)

from utils.cipher_manager import CipherManager  # noqa: E402
from stomp_ws.frame import Frame  # noqa: E402


class StubConfig:
    """CipherManager only reads config.data. The real Config class needs a desktop environment."""

    def __init__(self, **data):
        self.data = data


def cipher_for(username, password, salt="", rounds=664937):
    manager = CipherManager(StubConfig(username=username, salt=salt, hash_rounds=rounds))
    manager.config.data["hashed_password"] = manager.hash_password(password)
    return manager
