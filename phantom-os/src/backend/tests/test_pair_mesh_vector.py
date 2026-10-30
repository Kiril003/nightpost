"""Паринг телефона з ПК — на тому самому векторі, що й Kotlin.

Телефон підписує доказ разом зі своїм сітьовим блоком (`PairProofs.meshTail`),
а ПК рахував доказ без нього — і кожне спарювання новою збіркою падало в
400 bad_proof. Вектор лежить у дереві телефона й читається прямо звідти:
копія була б другим джерелом правди.

Друга половина — після спарювання ПК і телефон бачать одне одного як «мій
пристрій», а не як чужу людину.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from node import peer_channel
from node import peer_invite as pi
from security import pair_crypto as pc
from security.pair_crypto import _HKDF_INFO, PairingError, derive_shared_key

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/pair_mesh_vector.json"
V = json.loads(VECTOR.read_text(encoding="utf-8"))


def _mesh(block: dict) -> "pc.MeshBlock":
    return pc.MeshBlock(block["peer_id"], block["pub_ed25519_b64"], block["dh_x25519_b64"])


def _shared() -> bytes:
    server = x25519.X25519PrivateKey.from_private_bytes(
        base64.b64decode(V["server_x25519_priv_b64"])
    )
    return derive_shared_key(
        server, V["client_x25519_pub_b64"], base64.b64decode(V["nonce_b64"])
    )


def test_vector_speaks_the_same_hkdf():
    assert V["hkdf_info"].encode() == _HKDF_INFO
    assert base64.b64encode(_shared()).decode() == V["expected"]["shared_key_b64"]


def test_the_phones_proof_with_its_mesh_block_is_accepted():
    pc.verify_client_proof(
        shared_key=_shared(),
        pair_id=V["pair_id"],
        device_pub_ed25519_b64=V["device_pub_ed25519_b64"],
        proof_b64=V["expected"]["client_proof_b64"],
        mesh=_mesh(V["phone_mesh"]),
    )


def test_stripping_the_block_breaks_the_proof():
    with pytest.raises(PairingError):
        pc.verify_client_proof(
            shared_key=_shared(),
            pair_id=V["pair_id"],
            device_pub_ed25519_b64=V["device_pub_ed25519_b64"],
            proof_b64=V["expected"]["client_proof_b64"],
            mesh=None,
        )


def test_an_older_phone_without_the_block_still_pairs():
    pc.verify_client_proof(
        shared_key=_shared(),
        pair_id=V["pair_id"],
        device_pub_ed25519_b64=V["device_pub_ed25519_b64"],
        proof_b64=V["expected"]["client_proof_without_mesh_b64"],
    )


def test_server_proof_covers_the_node_block():
    shared = _shared()
    assert (
        pc.build_server_proof(
            shared_key=shared, device_jwt=V["device_jwt"], node=_mesh(V["node_mesh"])
        )
        == V["expected"]["server_proof_b64"]
    )
    assert (
        pc.build_server_proof(shared_key=shared, device_jwt=V["device_jwt"])
        == V["expected"]["server_proof_without_mesh_b64"]
    )


# ── живий маршрут ────────────────────────────────────────────────────────────


def _phone_mesh_keys():
    ed = ed25519.Ed25519PrivateKey.generate().public_key().public_bytes_raw()
    dh = x25519.X25519PrivateKey.generate().public_key().public_bytes_raw()
    return peer_channel.peer_id_of(ed), ed, dh


def _claim_body(qr: dict, pair_id: str, *, mesh: bool, name: str = "SM-A525F"):
    """Те саме, що робить `PairFlowClient.claim`, разом із хвостом доказу."""
    server_pub = x25519.X25519PublicKey.from_public_bytes(base64.b64decode(qr["server_pub"]))
    client_priv = x25519.X25519PrivateKey.generate()
    shared_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=base64.b64decode(qr["nonce"]),
        info=_HKDF_INFO,
    ).derive(client_priv.exchange(server_pub))
    device_pub = ed25519.Ed25519PrivateKey.generate().public_key().public_bytes_raw()
    tail, extra = b"", {}
    if mesh:
        peer_id, ed, dh = _phone_mesh_keys()
        tail = peer_id.encode() + ed + dh
        extra = {
            "peer_id": peer_id,
            # Телефон шле без набивки (NO_PADDING) — ПК мусить це прийняти.
            "peer_pub_ed25519": base64.b64encode(ed).decode().rstrip("="),
            "peer_dh_x25519": base64.b64encode(dh).decode().rstrip("="),
        }
    proof = hmac.new(shared_key, pair_id.encode() + device_pub + tail, hashlib.sha256).digest()
    body = {
        "pair_id": pair_id,
        "client_pub": base64.b64encode(client_priv.public_key().public_bytes_raw()).decode(),
        "device_pub_ed25519": base64.b64encode(device_pub).decode(),
        "nonce_echo": qr["nonce"],
        "client_proof": base64.b64encode(proof).decode(),
        "device": {"name": name, "model": f"samsung {name}", "platform": "android"},
        **extra,
    }
    return body, shared_key


def _pair(root, anon, *, mesh: bool = True):
    init = root.post("/api/v1/pair/init")
    assert init.status_code == 200, init.text
    payload = init.json()
    body, shared_key = _claim_body(payload["qr"], payload["pair_id"], mesh=mesh)
    claim = anon.post("/api/v1/pair/claim", json=body)
    return claim, body, shared_key


@pytest.mark.asyncio
async def test_claim_with_the_mesh_block_passes_and_answers_with_the_node(
    auth_root_client, unauth_client
):
    claim, _body, shared_key = _pair(auth_root_client, unauth_client)
    assert claim.status_code == 200, claim.text
    data = claim.json()
    node = pc.MeshBlock(data["node_id"], data["node_pub_ed25519"], data["node_dh_x25519"])
    assert node.is_complete

    # Ім'я вузла — те саме, яким ПК назветься в запрошенні PH2 і в листах
    # телефону: інакше телефон тримав би два рядки для одного ПК.
    invite = pi.decode(auth_root_client.get("/api/v1/messenger/phone/invite").json()["code"])
    assert (invite.peer_id, invite.pub_key, invite.dh_pub_key) == (
        node.peer_id,
        node.pub_ed25519_b64,
        node.dh_x25519_b64,
    )
    assert data["server_proof"] == pc.build_server_proof(
        shared_key=shared_key, device_jwt=data["device_jwt"], node=node
    )


@pytest.mark.asyncio
async def test_after_pairing_the_phone_is_my_device_on_the_pc(auth_root_client, unauth_client):
    claim, body, _ = _pair(auth_root_client, unauth_client)
    assert claim.status_code == 200, claim.text

    contacts = auth_root_client.get("/api/v1/messenger/contacts").json()
    phone = next(c for c in contacts if c["peer_node_id"] == body["peer_id"])
    assert phone["mine"] is True
    # Не модель апарата і не службове ім'я — людська назва.
    assert phone["display_name"] == "Мій телефон"

    conversations = auth_root_client.get("/api/v1/messenger/conversations").json()
    talk = next(c for c in conversations if c["contact_id"] == phone["id"])
    assert talk["mine"] is True


@pytest.mark.asyncio
async def test_pairing_the_same_phone_again_keeps_one_contact(auth_root_client, unauth_client):
    init = auth_root_client.post("/api/v1/pair/init").json()
    body, _ = _claim_body(init["qr"], init["pair_id"], mesh=True)
    assert unauth_client.post("/api/v1/pair/claim", json=body).status_code == 200

    again = auth_root_client.post("/api/v1/pair/init").json()
    body2, _ = _claim_body(again["qr"], again["pair_id"], mesh=False)
    # Той самий телефон, інший паринг: повторюємо його сітьовий блок.
    body2 = _reproof(again, body)
    assert unauth_client.post("/api/v1/pair/claim", json=body2).status_code == 200

    contacts = auth_root_client.get("/api/v1/messenger/contacts").json()
    assert [c["peer_node_id"] for c in contacts].count(body["peer_id"]) == 1


def _reproof(init: dict, previous: dict) -> dict:
    qr, pair_id = init["qr"], init["pair_id"]
    server_pub = x25519.X25519PublicKey.from_public_bytes(base64.b64decode(qr["server_pub"]))
    client_priv = x25519.X25519PrivateKey.generate()
    shared_key = HKDF(
        algorithm=hashes.SHA256(), length=32, salt=base64.b64decode(qr["nonce"]), info=_HKDF_INFO
    ).derive(client_priv.exchange(server_pub))
    device_pub = base64.b64decode(previous["device_pub_ed25519"])
    tail = (
        previous["peer_id"].encode()
        + base64.b64decode(previous["peer_pub_ed25519"] + "==")
        + base64.b64decode(previous["peer_dh_x25519"] + "==")
    )
    proof = hmac.new(shared_key, pair_id.encode() + device_pub + tail, hashlib.sha256).digest()
    return {
        **previous,
        "pair_id": pair_id,
        "client_pub": base64.b64encode(client_priv.public_key().public_bytes_raw()).decode(),
        "nonce_echo": qr["nonce"],
        "client_proof": base64.b64encode(proof).decode(),
    }


@pytest.mark.asyncio
async def test_a_phone_added_by_invite_stays_a_person(auth_root_client):
    # Запрошення PH2 від чужого телефона — це людина, а не мій пристрій.
    peer_id, ed, dh = _phone_mesh_keys()
    b = lambda raw: base64.b64encode(raw).decode().rstrip("=")  # noqa: E731
    code = pi.encode(peer_id, "Оля", b(ed), b(dh), "", 0)
    added = auth_root_client.post("/api/v1/messenger/phone/contacts", json={"code": code})
    assert added.status_code == 201, added.text
    contacts = auth_root_client.get("/api/v1/messenger/contacts").json()
    assert next(c for c in contacts if c["peer_node_id"] == peer_id)["mine"] is False


@pytest.mark.asyncio
async def test_an_older_phone_pairs_without_the_block(auth_root_client, unauth_client):
    claim, _body, _ = _pair(auth_root_client, unauth_client, mesh=False)
    assert claim.status_code == 200, claim.text
    # Старий телефон не вміє перевірити ключі вузла — їх йому й не дають.
    assert claim.json()["node_id"] == ""


@pytest.mark.asyncio
async def test_the_owner_can_take_the_mark_back_and_give_it_again(
    auth_root_client, unauth_client
):
    # Ті самі двері, що й міст телефона («Це не мій пристрій»): вебу на ПК
    # не можна показувати кнопку, яка б'є в 404.
    claim, body, _ = _pair(auth_root_client, unauth_client)
    assert claim.status_code == 200, claim.text
    off = auth_root_client.post(
        "/api/v1/messenger/mine", json={"peer_id": body["peer_id"], "mine": False}
    )
    assert off.status_code == 200, off.text
    assert off.json()["mine"] is False
    on = auth_root_client.post(
        "/api/v1/messenger/mine", json={"peer_id": body["peer_id"], "mine": True}
    )
    assert on.json()["mine"] is True
    missing = auth_root_client.post(
        "/api/v1/messenger/mine", json={"peer_id": "0000000000000000", "mine": True}
    )
    assert missing.status_code == 404


@pytest.mark.asyncio
async def test_a_name_that_is_not_the_keys_fingerprint_is_not_adopted(
    auth_root_client, unauth_client
):
    init = auth_root_client.post("/api/v1/pair/init").json()
    body, _ = _claim_body(init["qr"], init["pair_id"], mesh=True)
    forged = _reproof(init, {**body, "peer_id": "0123456789abcdef"})
    claim = unauth_client.post("/api/v1/pair/claim", json=forged)
    # Спарювання проходить (доказ чесний), але мого пристрою з чужим ім'ям немає.
    assert claim.status_code == 200, claim.text
    assert claim.json()["node_id"] == ""
    contacts = auth_root_client.get("/api/v1/messenger/contacts").json()
    assert all(c["peer_node_id"] != "0123456789abcdef" for c in contacts)
