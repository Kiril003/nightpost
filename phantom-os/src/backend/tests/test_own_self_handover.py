"""Передача особи телефона на ПК (docs/ONE_SELF.md, крок 1).

Телефон віддає ПК свої насіння Ed25519/X25519 лише в свіжому сеансі QR «особа»:
ефемерний ECDH з одноразовим ключем із QR, один раз, ніколи за PIN. Вектор
лежить у дереві телефона й читається прямо звідти — копія була б другим
джерелом правди.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from node import own_self as osp
from node import peer_channel
from node import peer_letter as pl
from security import pair_crypto as pc
from security.pair_crypto import _HKDF_INFO, PairingError

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/own_self_vector.json"
V = json.loads(VECTOR.read_text(encoding="utf-8"))


def _raw(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4))


def _open_vector(**over):
    args = dict(
        server_priv=x25519.X25519PrivateKey.from_private_bytes(_raw(V["server_x25519_priv_b64"])),
        client_pub_b64=V["client_x25519_pub_b64"],
        nonce_bytes=_raw(V["nonce_b64"]),
        pair_id=V["pair_id"],
        self_id=V["me"]["self_id"],
        node_id=V["node_id"],
        iv_b64=V["iv_b64"],
        sealed_b64=V["expected"]["sealed_b64"],
    )
    args.update(over)
    return osp.open_packet(**args)


# ── вектор ──────────────────────────────────────────────────────────────────


def test_the_vector_speaks_the_same_key_and_aad():
    assert V["info"].encode() == osp.INFO
    server = x25519.X25519PrivateKey.from_private_bytes(_raw(V["server_x25519_priv_b64"]))
    key = osp.packet_key(server, V["client_x25519_pub_b64"], _raw(V["nonce_b64"]))
    assert base64.b64encode(key).decode() == V["expected"]["key_b64"]
    aad = osp.aad(V["pair_id"], V["me"]["self_id"], V["node_id"])
    assert base64.b64encode(aad).decode() == V["expected"]["aad_b64"]


def test_what_the_phone_sealed_the_pc_opens():
    keys = _open_vector()
    assert keys.self_id == V["me"]["self_id"]
    assert keys.ed25519_pub_b64 == V["me"]["ed25519_pub_b64"]
    assert keys.x25519_pub_b64 == V["me"]["x25519_pub_b64"]
    assert keys.ed25519_seed == _raw(V["me"]["ed25519_seed_b64"])
    assert keys.x25519_private == _raw(V["me"]["x25519_priv_b64"])


def test_after_the_handover_the_pc_signs_as_me():
    # Той самий лист, який підписав телефон, — ті самі байти підпису, і
    # перевіряє їх мій публічний ключ, а не ключ вузла.
    keys = _open_vector()
    letter = V["letter"]
    signed = pl.sign(
        pl.PeerLetter(
            id=letter["id"],
            from_id=letter["fromId"],
            to_id=letter["toId"],
            body=letter["body"],
            sent_at_ms=letter["sentAtMs"],
        ),
        keys.signing_key(),
    )
    assert signed.signature == V["expected"]["letter_signature"]
    assert pl.verify(signed, _raw(V["me"]["ed25519_pub_b64"]), expected_from=keys.self_id)


def test_the_done_proof_matches_the_phone():
    key = _raw(V["expected"]["key_b64"])
    proof = osp.done_proof(key, V["pair_id"], V["me"]["self_id"], V["node_id"])
    assert proof == V["expected"]["done_proof_b64"]


@pytest.mark.parametrize(
    "over",
    [
        {"pair_id": "own-self-vector-2"},
        {"self_id": "0000000000000000"},
        {"node_id": "0000000000000000"},
        {"nonce_bytes": bytes(24)},
    ],
)
def test_a_packet_moved_to_another_session_or_node_does_not_open(over):
    with pytest.raises(PairingError) as err:
        _open_vector(**over)
    assert err.value.code == "bad_seal"


def test_a_name_that_is_not_the_keys_fingerprint_is_refused():
    # Запечатано чесно, але ім'я в запиті — чуже: ключ не дає цього імені.
    server = x25519.X25519PrivateKey.generate()
    nonce = os.urandom(24)
    phone = _phone()
    forged = "0123456789abcdef"
    body = _seal(server.public_key().public_bytes_raw(), nonce, "p-1", forged, "n-1", phone)
    with pytest.raises(PairingError) as err:
        osp.open_packet(
            server_priv=server,
            client_pub_b64=body["client_pub"],
            nonce_bytes=nonce,
            pair_id="p-1",
            self_id=forged,
            node_id="n-1",
            iv_b64=body["iv"],
            sealed_b64=body["sealed"],
        )
    assert err.value.code == "bad_identity"


def test_the_opened_keys_never_print_themselves():
    keys = _open_vector()
    seed = _raw(V["me"]["ed25519_seed_b64"])
    for shown in (repr(keys), str(keys), f"{keys}"):
        for leak in _spellings(seed) + _spellings(keys.x25519_private):
            assert leak not in shown


# ── живий маршрут ────────────────────────────────────────────────────────────


class _Phone:
    def __init__(self) -> None:
        self.ed_seed = os.urandom(32)
        self.dh_priv = os.urandom(32)
        ed = ed25519.Ed25519PrivateKey.from_private_bytes(self.ed_seed)
        self.ed_pub = ed.public_key().public_bytes_raw()
        self.dh_pub = (
            x25519.X25519PrivateKey.from_private_bytes(self.dh_priv).public_key().public_bytes_raw()
        )
        self.self_id = peer_channel.peer_id_of(self.ed_pub)


def _phone() -> _Phone:
    return _Phone()


def _b64n(raw: bytes) -> str:
    return base64.b64encode(raw).decode().rstrip("=")


def _seal(server_pub: bytes, nonce: bytes, pair_id: str, self_id: str, node_id: str, phone):
    """Те, що робить `OwnSelf.seal` на телефоні, — незалежно від `node/own_self.py`."""
    client = x25519.X25519PrivateKey.generate()
    shared = client.exchange(x25519.X25519PublicKey.from_public_bytes(server_pub))
    key = HKDF(hashes.SHA256(), 32, nonce, b"PHANTOM OS/own-self-v1").derive(shared)
    iv = os.urandom(12)
    aad = "\x1f".join(["PHANTOM OS/own-self-v1", pair_id, self_id, node_id]).encode()
    sealed = AESGCM(key).encrypt(iv, phone.ed_seed + phone.dh_priv, aad)
    return {
        "client_pub": base64.b64encode(client.public_key().public_bytes_raw()).decode(),
        "iv": base64.b64encode(iv).decode(),
        "sealed": base64.b64encode(sealed).decode(),
        "_key": key,
    }


def _pair_as_my_phone(root, anon, phone: _Phone) -> None:
    """Звичайне спарювання з сітьовим блоком — після нього телефон «мій»."""
    init = root.post("/api/v1/pair/init").json()
    qr = init["qr"]
    client = x25519.X25519PrivateKey.generate()
    shared_key = HKDF(
        hashes.SHA256(), 32, base64.b64decode(qr["nonce"]), _HKDF_INFO
    ).derive(client.exchange(x25519.X25519PublicKey.from_public_bytes(base64.b64decode(qr["server_pub"]))))
    device_pub = ed25519.Ed25519PrivateKey.generate().public_key().public_bytes_raw()
    tail = phone.self_id.encode() + phone.ed_pub + phone.dh_pub
    proof = hmac.new(shared_key, init["pair_id"].encode() + device_pub + tail, hashlib.sha256).digest()
    claim = anon.post(
        "/api/v1/pair/claim",
        json={
            "pair_id": init["pair_id"],
            "client_pub": base64.b64encode(client.public_key().public_bytes_raw()).decode(),
            "device_pub_ed25519": base64.b64encode(device_pub).decode(),
            "nonce_echo": qr["nonce"],
            "client_proof": base64.b64encode(proof).decode(),
            "device": {"name": "A52", "model": "samsung A52", "platform": "android"},
            "peer_id": phone.self_id,
            "peer_pub_ed25519": _b64n(phone.ed_pub),
            "peer_dh_x25519": _b64n(phone.dh_pub),
        },
    )
    assert claim.status_code == 200, claim.text


def _self_init(root) -> dict:
    init = root.post("/api/v1/pair/init", json={"purpose": "self"})
    assert init.status_code == 200, init.text
    return init.json()


def _self_body(init: dict, phone: _Phone, *, self_id: str | None = None, node_id: str | None = None):
    qr = init["qr"]
    sid = self_id or phone.self_id
    nid = node_id or qr["node_id"]
    sealed = _seal(
        base64.b64decode(qr["server_pub"]), base64.b64decode(qr["nonce"]), init["pair_id"], sid, nid, phone
    )
    body = {
        "pair_id": init["pair_id"],
        "client_pub": sealed["client_pub"],
        "nonce_echo": qr["nonce"],
        "self_id": sid,
        "node_id": nid,
        "iv": sealed["iv"],
        "sealed": sealed["sealed"],
    }
    return body, sealed["_key"]


def _spellings(raw: bytes) -> list:
    b64 = base64.b64encode(raw).decode()
    return [raw.hex(), b64, b64.rstrip("="), base64.urlsafe_b64encode(raw).decode().rstrip("=")]


@pytest.mark.asyncio
async def test_the_self_qr_names_the_node_and_has_no_pin(auth_root_client):
    init = _self_init(auth_root_client)
    assert init["pin"] == ""
    assert init["qr"]["purpose"] == "self"
    invite = auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"]
    from node import peer_invite as pi

    assert init["qr"]["node_id"] == pi.decode(invite).peer_id
    # Звичайний QR спарювання лишається як був — зі своїм PIN.
    plain = auth_root_client.post("/api/v1/pair/init").json()
    assert plain["pin"] and "purpose" not in plain["qr"]


@pytest.mark.asyncio
async def test_my_phone_hands_over_its_self_and_the_pc_keeps_it_sealed(
    auth_root_client, unauth_client, _root_payload
):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    init = _self_init(auth_root_client)
    body, key = _self_body(init, phone)

    done = unauth_client.post("/api/v1/pair/self", json=body)
    assert done.status_code == 200, done.text
    data = done.json()
    assert data["self_id"] == phone.self_id
    assert data["proof"] == osp.done_proof(key, init["pair_id"], phone.self_id, body["node_id"])

    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from messenger import own_self as store

    owner = _root_payload["id"]
    async with AsyncSessionLocal() as db:
        mine = await store.load(db, _keys(), owner)
    assert mine is not None
    assert mine.self_id == phone.self_id
    assert mine.ed25519_seed == phone.ed_seed
    assert mine.x25519_private == phone.dh_priv

    # Під ключем ІНШОГО вузла той самий рядок не відчиняється.
    from messenger.crypto.keys import KeyStore

    async with AsyncSessionLocal() as db:
        assert await store.load(db, KeyStore.generate(), owner) is None


@pytest.mark.asyncio
async def test_the_seed_is_never_in_the_log_or_on_disk_in_the_clear(
    auth_root_client, unauth_client, caplog
):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    init = _self_init(auth_root_client)
    body, _ = _self_body(init, phone)
    caplog.set_level(logging.DEBUG)
    assert unauth_client.post("/api/v1/pair/self", json=body).status_code == 200

    leaks = _spellings(phone.ed_seed) + _spellings(phone.dh_priv)
    for leak in leaks:
        assert leak not in caplog.text

    from db.database import engine

    db_path = Path(engine.url.database)
    for path in (db_path, Path(f"{db_path}-wal")):
        if path.exists():
            blob = path.read_bytes()
            for secret in (phone.ed_seed, phone.dh_priv):
                assert secret not in blob
                for leak in _spellings(secret):
                    assert leak.encode() not in blob


@pytest.mark.asyncio
async def test_the_same_session_twice_is_refused(auth_root_client, unauth_client):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    init = _self_init(auth_root_client)
    body, _ = _self_body(init, phone)
    assert unauth_client.post("/api/v1/pair/self", json=body).status_code == 200
    again = unauth_client.post("/api/v1/pair/self", json=body)
    assert again.status_code == 410
    assert again.json()["detail"]["code"] == "session_expired"


@pytest.mark.asyncio
async def test_a_packet_sealed_for_another_session_is_refused(auth_root_client, unauth_client):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    first = _self_init(auth_root_client)
    second = _self_init(auth_root_client)
    body, _ = _self_body(first, phone)
    moved = {**body, "pair_id": second["pair_id"], "nonce_echo": second["qr"]["nonce"]}
    refused = unauth_client.post("/api/v1/pair/self", json=moved)
    assert refused.status_code == 400
    assert refused.json()["detail"]["code"] == "bad_seal"


@pytest.mark.asyncio
async def test_an_expired_qr_is_refused(auth_root_client, unauth_client):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    init = _self_init(auth_root_client)
    session = pc.session_store.get(init["pair_id"])
    session.created_at = time.monotonic() - pc.PAIR_TTL_SECONDS - 1
    body, _ = _self_body(init, phone)
    refused = unauth_client.post("/api/v1/pair/self", json=body)
    assert refused.status_code == 410


@pytest.mark.asyncio
async def test_a_pairing_session_reached_by_pin_cannot_carry_the_self(
    auth_root_client, unauth_client
):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    plain = auth_root_client.post("/api/v1/pair/init").json()
    # Той, хто дістав сеанс за PIN, бачить рівно цей QR.
    resolved = unauth_client.get(f"/api/v1/pair/resolve/{plain['pin']}")
    assert resolved.status_code == 200
    body, _ = _self_body({"pair_id": plain["pair_id"], "qr": {**resolved.json(), "node_id": "x"}}, phone)
    refused = unauth_client.post("/api/v1/pair/self", json=body)
    assert refused.status_code == 409
    assert refused.json()["detail"]["code"] == "wrong_purpose"


@pytest.mark.asyncio
async def test_no_pin_ever_reaches_a_self_session(auth_root_client, unauth_client):
    init = _self_init(auth_root_client)
    session = pc.session_store.get(init["pair_id"])
    assert session.pin == ""
    assert pc.session_store.get_by_pin("") is None


@pytest.mark.asyncio
async def test_a_self_session_cannot_be_spent_on_pairing(auth_root_client, unauth_client):
    init = _self_init(auth_root_client)
    refused = unauth_client.post(
        "/api/v1/pair/claim",
        json={
            "pair_id": init["pair_id"],
            "client_pub": base64.b64encode(bytes(32)).decode(),
            "device_pub_ed25519": base64.b64encode(bytes(32)).decode(),
            "nonce_echo": init["qr"]["nonce"],
            "client_proof": base64.b64encode(bytes(32)).decode(),
        },
    )
    assert refused.status_code == 409
    assert refused.json()["detail"]["code"] == "wrong_purpose"


@pytest.mark.asyncio
async def test_a_phone_that_is_not_mine_is_refused(auth_root_client, unauth_client):
    stranger = _phone()
    init = _self_init(auth_root_client)
    body, _ = _self_body(init, stranger)
    refused = unauth_client.post("/api/v1/pair/self", json=body)
    assert refused.status_code == 403
    assert refused.json()["detail"]["code"] == "not_my_phone"


@pytest.mark.asyncio
async def test_a_phone_that_took_its_mark_back_is_refused(auth_root_client, unauth_client):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    auth_root_client.post("/api/v1/messenger/mine", json={"peer_id": phone.self_id, "mine": False})
    init = _self_init(auth_root_client)
    body, _ = _self_body(init, phone)
    refused = unauth_client.post("/api/v1/pair/self", json=body)
    assert refused.status_code == 403


@pytest.mark.asyncio
async def test_a_packet_for_another_node_is_refused(auth_root_client, unauth_client):
    phone = _phone()
    _pair_as_my_phone(auth_root_client, unauth_client, phone)
    init = _self_init(auth_root_client)
    body, _ = _self_body(init, phone, node_id="fedcba9876543210")
    refused = unauth_client.post("/api/v1/pair/self", json=body)
    assert refused.status_code == 400
    assert refused.json()["detail"]["code"] == "wrong_node"


def test_the_migration_is_one_head_after_contact_mine():
    from alembic.script import ScriptDirectory

    from db.database import _alembic_cfg

    script = ScriptDirectory.from_config(_alembic_cfg())
    heads = script.get_heads()
    assert len(heads) == 1
    head = script.get_revision(heads[0])
    chain = []
    rev = head
    while rev is not None and len(chain) < 5:
        chain.append(rev.revision)
        rev = script.get_revision(rev.down_revision) if rev.down_revision else None
    assert "b5e0c7a2d914" in chain
    assert "b5e0c7a2d914" != heads[0]
