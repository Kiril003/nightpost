"""Safe defaults: loopback bind, never an empty JWT secret, pinned licence key."""
import os
import stat

import pytest


def test_host_defaults_to_loopback(monkeypatch):
    monkeypatch.delenv("HOST", raising=False)
    from config import PhantomConfig
    assert PhantomConfig(_env_file=None).host == "127.0.0.1"


def test_empty_jwt_secret_is_generated_and_persisted(monkeypatch, tmp_path):
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
    from config import JWT_SECRET_FILE, PhantomConfig, ensure_jwt_secret

    first = PhantomConfig(_env_file=None)
    assert first.jwt_secret_key == ""
    ensure_jwt_secret(first)
    assert len(first.jwt_secret_key) >= 32
    path = tmp_path / JWT_SECRET_FILE
    assert path.read_text().strip() == first.jwt_secret_key
    if os.name == "posix":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600

    # Restart: same secret (it also derives the at-rest PII keys).
    second = PhantomConfig(_env_file=None)
    ensure_jwt_secret(second)
    assert second.jwt_secret_key == first.jwt_secret_key


def test_explicit_jwt_secret_wins(monkeypatch, tmp_path):
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    from config import JWT_SECRET_FILE, PhantomConfig, ensure_jwt_secret

    cfg = PhantomConfig(_env_file=None, jwt_secret_key="operator-chosen")
    ensure_jwt_secret(cfg)
    assert cfg.jwt_secret_key == "operator-chosen"
    assert not (tmp_path / JWT_SECRET_FILE).exists()


def test_packaged_build_pins_production_licence_key(monkeypatch):
    from licensing import verifier
    monkeypatch.setenv("PHANTOM_PACKAGED", "1")
    monkeypatch.setenv("PHANTOM_LICENSE_PUBKEY", "00" * 32)
    monkeypatch.setenv("PHANTOM_LICENSE_DEV", "1")
    assert verifier.trusted_public_key_hex() == verifier.PRODUCTION_PUBLIC_KEY_HEX


def test_dev_licence_key_needs_explicit_flag(monkeypatch):
    from licensing import verifier
    monkeypatch.delenv("PHANTOM_PACKAGED", raising=False)
    monkeypatch.delenv("PHANTOM_LICENSE_PUBKEY", raising=False)
    monkeypatch.delenv("PHANTOM_LICENSE_DEV", raising=False)
    assert verifier.trusted_public_key_hex() == verifier.PRODUCTION_PUBLIC_KEY_HEX
    monkeypatch.setenv("PHANTOM_LICENSE_DEV", "1")
    assert verifier.trusted_public_key_hex() == verifier.DEV_PUBLIC_KEY_HEX


def test_activator_default_server_is_the_live_one(monkeypatch):
    import importlib
    monkeypatch.delenv("PHANTOM_LICENSE_SERVER", raising=False)
    from licensing import activator
    importlib.reload(activator)
    assert activator.DEFAULT_SERVER == "https://api.phantom-os.dev"
