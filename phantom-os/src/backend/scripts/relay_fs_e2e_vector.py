"""Наскрізний вектор v5 «телефон ↔ ПК» (RELAY_FS.md §9): пачки, листи в обидва боки.

Відмінність від `relay_fs_vector.py`: там — одне ядро шифру; тут — ПОВНИЙ шлях
із розкладом ключів (`RelayFsKeys` обох боків, однакові зерна генератора),
пачками PH-PKB і підписаними листами. Kotlin (`RelayFsE2eTest`) відтворює
половину телефона байт у байт і відчиняє лист ПК; pytest
(`test_relay_fs_e2e.py`) веде половину телефона крізь справжній `PhoneCourier`.

Усі ключі ТЕСТОВІ, із фіксованих зерен; у збірки не йдуть.

    .venv/bin/python -m scripts.relay_fs_e2e_vector   # з phantom-os/src/backend
"""
from __future__ import annotations

import base64
import hashlib
import json
import sys
from pathlib import Path
from typing import Callable

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from messenger import relay_fs_keys as rfk
from node import peer_channel
from node import peer_letter as pl
from node import relay_fs as fs

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/relay_fs_e2e.json"

CLOCK_MS = 1_790_000_000_000


def seed(label: str) -> bytes:
    return hashlib.sha256(label.encode()).digest()


def sequence(prefix: str) -> Callable[[], X25519PrivateKey]:
    """Генератор ключів RelayFsKeys: i-й ключ — sha256(prefix + i). Kotlin рахує так само."""
    counter = iter(range(10_000))
    return lambda: X25519PrivateKey.from_private_bytes(seed(f"{prefix}{next(counter)}"))


def _b(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def identity(ed: Ed25519PrivateKey, dh: X25519PrivateKey) -> fs.Identity:
    return fs.Identity(ed.public_key().public_bytes_raw(), dh.public_key().public_bytes_raw())


def build() -> dict:
    pc_ed = Ed25519PrivateKey.from_private_bytes(seed("e2e/pc/ed"))
    pc_dh = X25519PrivateKey.from_private_bytes(seed("e2e/pc/dh"))
    phone_ed = Ed25519PrivateKey.from_private_bytes(seed("e2e/phone/ed"))
    phone_dh = X25519PrivateKey.from_private_bytes(seed("e2e/phone/dh"))
    pc, phone = identity(pc_ed, pc_dh), identity(phone_ed, phone_dh)
    pc_id, phone_id = peer_channel.peer_id_of(pc.ed), peer_channel.peer_id_of(phone.ed)

    pc_keys = rfk.RelayFsKeys(rfk.MemorySlot(), clock=lambda: CLOCK_MS, keypair=sequence("e2e/pc/fs/"))
    phone_keys = rfk.RelayFsKeys(rfk.MemorySlot(), clock=lambda: CLOCK_MS, keypair=sequence("e2e/phone/fs/"))

    pc_bundle = pc_keys.bundle_for(phone_id, pc_ed.sign)
    phone_bundle = phone_keys.bundle_for(pc_id, phone_ed.sign)
    pc_keys.absorb(phone_id, phone_bundle)
    phone_keys.absorb(pc_id, pc_bundle)

    phone_letter = pl.sign(pl.PeerLetter("e2e-phone-1", phone_id, pc_id, "Телефон → ПК: лист під v5", CLOCK_MS + 123), phone_ed)
    pc_letter = pl.sign(pl.PeerLetter("e2e-pc-1", pc_id, phone_id, "ПК → телефон: лист під v5", CLOCK_MS + 456), pc_ed)
    from_phone = rfk.seal_letter(phone_letter, phone, phone_dh, pc, phone_keys, enabled=True,
                                 ephemeral=X25519PrivateKey.from_private_bytes(seed("e2e/phone/ek/1")))
    from_pc = rfk.seal_letter(pc_letter, pc, pc_dh, phone, pc_keys, enabled=True,
                              ephemeral=X25519PrivateKey.from_private_bytes(seed("e2e/pc/ek/1")))
    assert isinstance(from_phone, rfk.Sealed) and isinstance(from_pc, rfk.Sealed)
    return {
        "why": "Наскрізний v5 «телефон ↔ ПК» (docs/crypto/RELAY_FS.md §9). Зерна: sha256(мітка). Генератор "
               "ключів RelayFsKeys: i-й ключ — sha256(префікс + i). Kotlin RelayFsE2eTest відтворює phone.* "
               "байт у байт; pytest test_relay_fs_e2e.py веде ці листи крізь PhoneCourier. Ключі ТЕСТОВІ.",
        "clock_ms": CLOCK_MS,
        "seeds": {
            "pc_ed": "e2e/pc/ed", "pc_dh": "e2e/pc/dh", "pc_fs_prefix": "e2e/pc/fs/", "pc_ek": "e2e/pc/ek/1",
            "phone_ed": "e2e/phone/ed", "phone_dh": "e2e/phone/dh", "phone_fs_prefix": "e2e/phone/fs/",
            "phone_ek": "e2e/phone/ek/1",
        },
        "pc": {"id": pc_id, "ed": _b(pc.ed), "dh": _b(pc.dh)},
        "phone": {"id": phone_id, "ed": _b(phone.ed), "dh": _b(phone.dh)},
        "phone_bundle": fs.encode_bundle(phone_bundle),
        "pc_bundle": fs.encode_bundle(pc_bundle),
        "phone_letter": {"json": phone_letter.to_json(), "frame": from_phone.frame},
        "pc_letter": {"json": pc_letter.to_json(), "frame": from_pc.frame},
    }


def main() -> int:
    VECTOR.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"записано {VECTOR.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
