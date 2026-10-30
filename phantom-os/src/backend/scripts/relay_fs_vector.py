"""Спільний вектор конверта v5 (RELAY_FS.md): ПК (node/relay_fs.py) ↔ телефон (RelayFs.kt).

Усі ключі — ТЕСТОВІ, із фіксованих зерен; у збірки не йдуть.

    .venv/bin/python -m scripts.relay_fs_vector   # з phantom-os/src/backend — переписати файл
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from messenger.crypto.x3dh import X3DH_INFO
from node import relay_fs as fs

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/relay_fs_vector.json"


def _x(seed: int) -> X25519PrivateKey:
    return X25519PrivateKey.from_private_bytes(bytes((seed + i) & 0xFF for i in range(32)))


def _ed(seed: int) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(bytes((seed + i) & 0xFF for i in range(32)))


def _b(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def build() -> dict:
    a_ed, a_dh = _ed(0x10), _x(0x30)
    b_ed, b_dh = _ed(0x50), _x(0x70)
    spk, opk = _x(0x90), _x(0xB0)
    ek1, ek2 = _x(0xD0), _x(0xE0)
    a = fs.Identity(a_ed.public_key().public_bytes_raw(), a_dh.public_key().public_bytes_raw())
    b = fs.Identity(b_ed.public_key().public_bytes_raw(), b_dh.public_key().public_bytes_raw())
    spk_id, opk_id = 7, 42
    spk_pub, opk_pub = spk.public_key().public_bytes_raw(), opk.public_key().public_bytes_raw()
    text = json.dumps({"id": "m-1", "fromId": "aaaa", "toId": "bbbb", "body": "Привіт зі сховку", "sentAtMs": 1790000000000},
                      ensure_ascii=False, separators=(",", ":")).encode()
    with_opk = fs.seal_letter(text, "aaaa", "bbbb", a, a_dh, b, (spk_id, spk_pub), (opk_id, opk_pub), ephemeral=ek1)
    spk_only = fs.seal_letter(text, "aaaa", "bbbb", a, a_dh, b, (spk_id, spk_pub), None, ephemeral=ek2)
    return {
        "why": "Конверт v5 (docs/crypto/RELAY_FS.md). Kotlin RelayFsVectorTest запечатує й відчиняє рівно ці "
               "байти; pytest test_relay_fs.py звіряє файл із node/relay_fs.py. Ключі ТЕСТОВІ.",
        "x3dh_info": X3DH_INFO.decode(),
        "letter_info": fs.LETTER_INFO.decode(),
        "a": {"ed_priv": _b(a_ed.private_bytes_raw()), "dh_priv": _b(a_dh.private_bytes_raw()), "ed": _b(a.ed), "dh": _b(a.dh)},
        "b": {"ed_priv": _b(b_ed.private_bytes_raw()), "dh_priv": _b(b_dh.private_bytes_raw()), "ed": _b(b.ed), "dh": _b(b.dh)},
        "spk": {"id": spk_id, "priv": _b(spk.private_bytes_raw()), "pub": _b(spk_pub), "sig": _b(fs.sign_spk(b_ed, spk_id, spk_pub))},
        "opk": {"id": opk_id, "priv": _b(opk.private_bytes_raw()), "pub": _b(opk_pub)},
        "plaintext": text.decode(),
        "with_opk": {"ek_priv": _b(ek1.private_bytes_raw()), "frame": with_opk},
        "spk_only": {"ek_priv": _b(ek2.private_bytes_raw()), "frame": spk_only},
    }


def render() -> str:
    return json.dumps(build(), ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    VECTOR.write_text(render(), encoding="utf-8")
    sys.stdout.write(f"записано {VECTOR}\n")
