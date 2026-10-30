import asyncio
from datetime import datetime, timezone

import api.routes_pair as rp
from db.models import Profile, User


def test_computer_name_returns_custom_system_hostname(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "studio-rig")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "ignored.local")
    assert rp._computer_name("phantom") == "studio-rig"


def test_computer_name_falls_back_to_short_hostname_when_default(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "phantom")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "studio-pc.local")
    assert rp._computer_name() == "studio-pc"


def test_computer_name_returns_empty_when_matching_username(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "phantom")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "Phantom.local")
    assert rp._computer_name("phantom") == ""

    monkeypatch.setattr(rp.config, "system_hostname", "Operator")
    assert rp._computer_name("operator") == ""


def test_computer_name_caps_at_64_chars(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "a" * 80)
    assert rp._computer_name("phantom") == "a" * 64


def test_claim_response_carries_computer_name(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "phantom")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "studio-pc.local")
    monkeypatch.setattr(rp, "derive_shared_key", lambda *_a, **_kw: b"\x01" * 32)
    monkeypatch.setattr(rp, "verify_client_proof", lambda **_kw: None)

    session = rp.session_store.create(created_by_user_id="u-1")
    owner = User(id="u-1", username="phantom", role="ROOT")
    profile = Profile(
        id="p-1",
        user_id="u-1",
        display_name="Primary",
        role="ROOT",
        is_primary=True,
        archived_at=None,
    )

    class _FakeDb:
        async def get(self, model, key):
            if model is User and key == "u-1":
                return owner
            if model is Profile and key == "p-1":
                return profile
            return None

        def add(self, _row):
            pass

        async def commit(self):
            pass

        async def refresh(self, row):
            row.id = "dev-1"
            row.paired_at = datetime.now(tz=timezone.utc)

    req = rp.PairClaimRequest(
        pair_id=session.pair_id,
        client_pub="A" * 44,
        device_pub_ed25519="B" * 44,
        nonce_echo=session.nonce_b64,
        client_proof="C" * 44,
        device={"name": "Pixel", "model": "Pixel 8", "platform": "android"},
        profile_id="p-1",
    )
    resp = asyncio.run(rp.pair_claim(req, db=_FakeDb()))
    payload = resp.model_dump()
    assert payload["computer_name"] == "studio-pc"


def test_computer_name_hides_hostname_that_contains_the_os_user(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "phantom")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "kyrylo-laptop")
    monkeypatch.setattr(rp.getpass, "getuser", lambda: "kyrylo")
    assert rp._computer_name("phantom") == ""


def test_computer_name_keeps_hostname_unrelated_to_any_user(monkeypatch):
    monkeypatch.setattr(rp.config, "system_hostname", "phantom")
    monkeypatch.setattr(rp.socket, "gethostname", lambda: "studio-pc")
    monkeypatch.setattr(rp.getpass, "getuser", lambda: "kyrylo")
    assert rp._computer_name("phantom") == "studio-pc"
