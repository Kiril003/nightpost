"""Спільний вектор «Сирени-маяка»: сервер (siren.py) ↔ телефон (SirenSnapshot.kt).

Ключ вектора — ТЕСТОВИЙ (зерно 00..1f), у збірки його не закріплюють ніколи.

    .venv/bin/python siren_vector.py   # з platform-site/server — переписати файл
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

import siren

REPO = Path(__file__).resolve().parents[2]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/siren_vector.json"

_KEY = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))


def build() -> dict:
    cases = []
    for alerts, seq, observed in (
        ({"kharkiv": 1, "sumy": 2, "zhytomyr": 15}, 1_790_000_000, 1_790_000_100),
        ({}, 1_790_000_500, 1_790_000_520),
        ({o: 1 for o in siren.OBLAST_ORDER}, 1_790_001_000, 1_790_001_000),
    ):
        snap = siren.Snapshot(key_id=7, seq=seq, observed_at=observed, alerts=alerts)
        raw = siren.sign(snap, _KEY)
        cases.append({
            "alerts": alerts, "seq": seq, "observed_at": observed,
            "body_hex": siren.body(snap).hex(), "snapshot_b64": base64.b64encode(raw).decode(),
        })
    return {
        "why": "Сирена-маяк (розд. 07 §10). Kotlin SirenSnapshotVectorTest перевіряє рівно ці байти; "
               "pytest test_siren.py звіряє файл із siren.py. Ключ — ТЕСТОВИЙ, не закріплювати.",
        "version": siren.VERSION,
        "domain_hex": siren.DOMAIN.hex(),
        "key_id": 7,
        "public_hex": _KEY.public_key().public_bytes_raw().hex(),
        "fresh_s": siren.FRESH_S,
        "skew_s": siren.SKEW_S,
        "oblast_order": list(siren.OBLAST_ORDER),
        "cases": cases,
    }


def render() -> str:
    return json.dumps(build(), ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    VECTOR.write_text(render(), encoding="utf-8")
    sys.stdout.write(f"written {VECTOR}\n")
