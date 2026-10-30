"""Конверт v5 на ПК — дзеркало телефона (docs/crypto/RELAY_FS.md).

Головна властивість — пряма секретність: довічний ключ отримувача, витягнутий
з телефона, не відчиняє листа, чиї одноразові ключі вже стерто. Той самий
тест проти v3 показує саме ту дірку, яку v5 закриває.
"""
from __future__ import annotations

import json
import os

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from node import peer_channel
from node import peer_letter as pl
from node import relay_fs as fs
from scripts.relay_fs_vector import VECTOR, render


class Phone:
    def __init__(self):
        self.ed = Ed25519PrivateKey.generate()
        self.dh = X25519PrivateKey.generate()
        self.me = fs.Identity(self.ed.public_key().public_bytes_raw(), self.dh.public_key().public_bytes_raw())
        self.spk = {1: X25519PrivateKey.generate()}
        self.opks = {i: X25519PrivateKey.generate() for i in range(10, 14)}

    def spk_pub(self, i=1):
        return (i, self.spk[i].public_key().public_bytes_raw())

    def opk_pub(self, i):
        return (i, self.opks[i].public_key().public_bytes_raw())

    def open(self, frame, sender: "Phone"):
        got = fs.open_letter(frame, self.me, self.dh, sender.me, self.spk.get, self.opks.get)
        if got is not None and got[1] is not None:
            del self.opks[got[1]]  # стерти ОДРАЗУ після відкриття
        return got


def test_round_trip_with_one_time_and_with_signed_prekey_only():
    a, b = Phone(), Phone()
    f1 = fs.seal_letter(b"one", "a", "b", a.me, a.dh, b.me, b.spk_pub(), b.opk_pub(10))
    f2 = fs.seal_letter(b"two", "a", "b", a.me, a.dh, b.me, b.spk_pub(), None)
    assert b.open(f1, a)[0] == b"one"
    assert b.open(f2, a)[0] == b"two"
    assert json.loads(f2).get("opk") is None


def test_used_one_time_key_is_gone_so_a_replay_does_not_open():
    a, b = Phone(), Phone()
    frame = fs.seal_letter(b"x", "a", "b", a.me, a.dh, b.me, b.spk_pub(), b.opk_pub(11))
    assert b.open(frame, a) is not None
    assert 11 not in b.opks
    assert b.open(frame, a) is None


def test_seized_long_term_key_does_not_open_past_v5_letters():
    a, b = Phone(), Phone()
    frame = fs.seal_letter(b"secret", "a", "b", a.me, a.dh, b.me, b.spk_pub(), b.opk_pub(12))
    assert b.open(frame, a) is not None
    b.spk.clear()  # SPK обернувся й стерся
    # Нападник має довічні ключі ОБОХ телефонів, але не стерті prekey.
    assert fs.open_letter(frame, b.me, b.dh, a.me, lambda i: None, lambda i: None) is None
    assert fs.open_letter(frame, b.me, b.dh, a.me, lambda i: b.dh, lambda i: b.dh) is None


def test_v3_is_exactly_the_hole_long_term_key_opens_the_past():
    """Той самий сценарій проти v3: довічний X25519 відчиняє минулий лист. Цей тест
    фіксує дірку; властивість вище для v3 була б червоною."""
    a, b = Phone(), Phone()
    b64 = lambda raw: __import__("base64").b64encode(raw).decode()
    ka = peer_channel.channel_key(a.dh.private_bytes_raw(), b64(b.me.dh), "aaaa", "bbbb")
    letter = pl.PeerLetter(id="m", from_id="aaaa", to_id="bbbb", body="secret", sent_at_ms=1)
    frame, _ = pl.seal_envelope(letter, ka)
    stolen = peer_channel.channel_key(b.dh.private_bytes_raw(), b64(a.me.dh), "bbbb", "aaaa")
    assert pl.open_envelope(frame, stolen).body == "secret"


def test_out_of_order_and_foreign_or_tampered_frames():
    a, b, c = Phone(), Phone(), Phone()
    frames = [fs.seal_letter(str(i).encode(), "a", "b", a.me, a.dh, b.me, b.spk_pub(), b.opk_pub(10 + i)) for i in range(3)]
    for i in (2, 0, 1):
        assert b.open(frames[i], a)[0] == str(i).encode()
    f = fs.seal_letter(b"x", "a", "b", a.me, a.dh, b.me, b.spk_pub(), b.opk_pub(13))
    assert fs.open_letter(f, b.me, b.dh, c.me, b.spk.get, b.opks.get) is None  # інший відправник
    env = json.loads(f)
    for field, value in (("from", "z"), ("to", "z"), ("spk", 2), ("opk", 12)):
        bad = dict(env, **{field: value})
        assert fs.open_letter(json.dumps(bad), b.me, b.dh, a.me, b.spk.get, b.opks.get) is None, field
    flipped = dict(env, ek=fs._b64(os.urandom(32)))
    assert fs.open_letter(json.dumps(flipped), b.me, b.dh, a.me, b.spk.get, b.opks.get) is None


def test_signed_prekey_signature_uses_the_pc_context():
    b = Phone()
    i, pub = b.spk_pub()
    sig = fs.sign_spk(b.ed, i, pub)
    assert fs.verify_spk(b.me.ed, i, pub, sig)
    assert not fs.verify_spk(b.me.ed, i + 1, pub, sig)
    assert not fs.verify_spk(Phone().me.ed, i, pub, sig)


def test_vector_file_is_what_the_pc_computes_today():
    assert VECTOR.read_text(encoding="utf-8") == render()
    v = json.loads(VECTOR.read_text(encoding="utf-8"))
    import base64
    d = lambda s: base64.b64decode(s)
    b_dh = X25519PrivateKey.from_private_bytes(d(v["b"]["dh_priv"]))
    a = fs.Identity(d(v["a"]["ed"]), d(v["a"]["dh"]))
    b = fs.Identity(d(v["b"]["ed"]), d(v["b"]["dh"]))
    spk = X25519PrivateKey.from_private_bytes(d(v["spk"]["priv"]))
    opk = X25519PrivateKey.from_private_bytes(d(v["opk"]["priv"]))
    for case in ("with_opk", "spk_only"):
        got = fs.open_letter(v[case]["frame"], b, b_dh, a, lambda i: spk, lambda i: opk)
        assert got is not None and got[0].decode() == v["plaintext"]
