"""Мова телефона з боку ПК — звірена зі зліпками самого телефона.

Джерела векторів — не цей файл:
  * PH2: `PeerSecondNodeTest.kt` (його ж тримає стенд `phantom_peer.py`);
  * PH-REA: `PeerMarkTest.kt` («4p2k77iP» — ❤️ у base64url);
  * ключ пари, адреси, конверт ПК→телефон, квитанція:
    `phantom-companion/core-net/src/test/resources/pc_pair_channel_vector.json`,
    який читає Kotlin-тест `PcPairChannelVectorTest`.

Вектор читається ПРЯМО з дерева телефона в цьому ж моно-репо, без копії:
копія — це друге джерело правди, і в старому дереві сторож від неї «чесно»
пропускався тижнями. Тут його немає — значить червоне, не skip.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from node import peer_channel, peer_invite as pi, peer_letter as pl, peer_relay as pr

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/pc_pair_channel_vector.json"

# PeerSecondNodeTest.kt — ті самі рядки, що в phantom_peer._selftest_invite.
ED = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo"
DH = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo"
WITH_PORT = (
    "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
    "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
    "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8NDc5MjE:e39f92"
)
SILENT = (
    "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU"
    "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz"
    "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98fDA:51767f"
)


def _raw(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4))


def _vector() -> dict:
    assert VECTOR.exists(), f"вектор телефона зник: {VECTOR} — звіряти ПК нема з чим"
    return json.loads(VECTOR.read_text(encoding="utf-8"))


# ── PH2 ──────────────────────────────────────────────────────────────────────


def test_pc_prints_the_phones_own_invite_byte_for_byte():
    pid = peer_channel.peer_id_of(_raw(ED))
    assert pi.encode(pid, "Ноутбук", ED, DH, "10.0.2.2", 47921) == WITH_PORT
    # Без порту адреса не їде — саме таку форму друкує ПК.
    assert pi.encode(pid, "Ноутбук", ED, DH, "10.0.2.2", 0) == SILENT


def test_pc_reads_the_phones_invite():
    got = pi.decode(WITH_PORT)
    assert (got.peer_id, got.name, got.pub_key, got.dh_pub_key) == (
        "d1d27b5fa7533574", "Ноутбук", ED, DH,
    )
    assert (got.host, got.port) == ("10.0.2.2", 47921)
    assert pi.decode(pi.link(SILENT)).host == ""


@pytest.mark.parametrize(
    "code, why",
    [
        (SILENT[:-1] + "0", "контрольна"),
        ("PH1:" + SILENT.split(":", 1)[1], "старої збірки"),
        ("PH2:" + "A" * 5000 + ":000000", "задовгий"),
        ("просто текст", "не код"),
    ],
)
def test_broken_or_foreign_invites_are_refused_aloud(code, why):
    with pytest.raises(pi.InviteRefused, match=why):
        pi.decode(code)


def test_an_invite_whose_name_is_not_its_key_is_refused():
    """Ім'я входить у сіль і в адреси: чуже ім'я під своїм ключем — захоплення."""
    forged = pi.encode("0123456789abcdef", "Хтось", ED, DH)
    with pytest.raises(pi.InviteRefused, match="не відповідає"):
        pi.decode(forged)


def test_an_invite_without_an_encryption_key_is_refused():
    pid = peer_channel.peer_id_of(_raw(ED))
    with pytest.raises(pi.InviteRefused, match="шифрування"):
        pi.decode(pi.encode(pid, "Без ключа", ED, "не-ключ"))


# ── PH-REA ───────────────────────────────────────────────────────────────────


def test_reaction_frame_matches_peer_mark_test():
    target = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2"
    assert pi.encode_reaction(target, True, "❤️") == f"PH-REA:{target};+;4p2k77iP"
    assert pi.decode_reaction(f"PH-REA:{target};-;4p2k77iP") == pi.Reaction(target, False, "❤️")
    for broken in ("PH-REA:", f"PH-REA:{target}", f"PH-REA:{target};?;4p2k77iP",
                   f"PH-REA:{target};+;", "PH-REA:не той;рядок;+;4p2k77iP"):
        assert pi.decode_reaction(broken) is None, broken
    assert pi.encode_reaction(target, True, "x" * 65) is None


# ── Спільний вектор ПК↔телефон ──────────────────────────────────────────────


def test_pair_key_relay_key_and_boxes_match_the_phone():
    v = _vector()
    key = peer_channel.channel_key(
        _raw(v["node"]["x25519_priv_b64"]), v["phone"]["x25519_pub_b64"],
        v["node"]["node_id"], v["phone"]["peer_id"],
    )
    assert base64.b64encode(key).decode() == v["expected"]["pair_key_b64"]
    relay = pr.relay_key(key, v["node"]["node_id"], v["phone"]["peer_id"])
    assert base64.b64encode(relay).decode() == v["expected"]["relay_key_b64"]
    e = v["epoch"]
    assert pr.msg_tag(relay, v["phone"]["peer_id"], v["node"]["node_id"], e) == v["expected"]["msg_tag_phone_to_node"]
    assert pr.msg_tag(relay, v["node"]["node_id"], v["phone"]["peer_id"], e) == v["expected"]["msg_tag_node_to_phone"]
    assert pr.ack_tag(relay, v["phone"]["peer_id"], v["node"]["node_id"], e) == v["expected"]["ack_tag_phone_to_node"]


def test_the_letter_kotlin_opens_is_the_letter_the_pc_seals():
    """Той самий кадр, який відчиняє `PcPairChannelVectorTest`, — і тим самим
    ключем ПК запечатує його байт-у-байт, коли nonce той самий."""
    v = _vector()
    block = v["letter_from_node"]
    pair = _raw(v["expected"]["pair_key_b64"])
    opened = pl.open_envelope(block["frame"], pair)
    assert opened is not None and opened.body == block["body"]
    assert pl.verify(opened, _raw(v["node"]["ed25519_pub_b64"]), expected_from=v["node"]["node_id"])
    assert not pl.verify(opened, _raw(v["phone"]["ed25519_pub_b64"]))
    resealed, nonce = pl.seal_envelope(opened, pair, nonce=_raw(block["nonce_b64"]))
    assert resealed == block["frame"] and nonce == block["nonce_b64"]
    assert pl.receipt(pair, opened.id, opened.from_id, opened.to_id,
                      block["receipt_over_nonce_b64"]) == block["receipt_b64"]
