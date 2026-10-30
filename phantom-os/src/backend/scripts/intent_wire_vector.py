"""Спільний вектор мови Наміру: Python (intents/wire.py) ↔ Kotlin (PeerIntent.kt).

Вектор — детермінований: фіксовані ключ пари, імена, id, лічильник. Python
його рахує, Kotlin (`PeerIntentVectorTest`) відтворює ті самі байти, а pytest
(`test_intents_wire_vector.py`) звіряє, що файл досі збігається з кодом ПК.
Розійшлись — Намір із телефона просто не відчиниться на ПК, і ніхто не
скаже чому.

    python -m scripts.intent_wire_vector   # з phantom-os/src/backend — переписати файл
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from intents import lanes
from intents.handlers import Answer, OBLAST_ORDER
from intents.wire import (
    ANSWER,
    ANSWER_FULL,
    EPOCH,
    OP_ALERTS,
    OP_ASK,
    Intent,
    Status,
    answer_key,
    contact_tag,
    intent_key,
    pair_hint,
    seal_answer,
    seal_intent,
)

REPO = Path(__file__).resolve().parents[4]
VECTOR = REPO / "phantom-companion/core-net/src/test/resources/intent_wire_vector.json"

PAIR_KEY = bytes(range(0x40, 0x60))
PHONE_ID = "a1b2c3d4e5f60718"
PC_ID = "0f1e2d3c4b5a6978"


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def build() -> dict:
    alerts = Intent(
        id=bytes.fromhex("0102030405060708"),
        op=OP_ALERTS,
        created_min=399_000,
        deadline_min=30,
        flags=(3 << 6) | (1 << 4) | 0x2,
        reply_to=lanes.reply_to(sms_parts=2, relay=True),
        args=bytes([OBLAST_ORDER.index("kharkiv"), 0, 0, 0]),
    )
    ask = Intent(
        id=bytes.fromhex("f0e0d0c0b0a09080"),
        op=OP_ASK,
        created_min=399_001,
        deadline_min=5,
        flags=0x80,
        reply_to=lanes.reply_to(sms_parts=1, relay=True),
        args="як зупинити кровотечу без джгута?".encode(),
    )
    alerts_body = (
        ((1 << OBLAST_ORDER.index("kharkiv")) | (1 << OBLAST_ORDER.index("sumy"))).to_bytes(4, "big")
        + bytes([1]) + (11).to_bytes(2, "big") + bytes([20])
    )
    answer_alerts = Answer(Status.OK, alerts_body)
    answer_ask = Answer(
        Status.OK,
        "Притисніть рану чистою тканиною і тримайте тиск щонайменше 10 хвилин. Підніміть руку вище серця.".encode(),
        text=True,
    )
    cases = []
    for intent, answer, counter in ((alerts, answer_alerts, 41), (ask, answer_ask, 0xFFFFFFFE)):
        sealed = seal_intent(intent, PAIR_KEY, PHONE_ID, PC_ID, counter=counter)
        rungs = {}
        for rung, lane, kind in ((1, "sms", ANSWER), (2, "relay", ANSWER_FULL)):
            plain = answer.plain(rung, lanes.budget_for(lane, intent.reply_to))
            rungs[str(rung)] = {
                "kind": kind,
                "budget": lanes.budget_for(lane, intent.reply_to),
                "key_b64": _b64(answer_key(PAIR_KEY, PHONE_ID, PC_ID, intent.id, kind)),
                "plain_hex": plain.hex(),
                "sealed_b64": _b64(
                    seal_answer(plain, PAIR_KEY, PHONE_ID, PC_ID, intent.id, counter, rung=rung)
                ),
            }
        cases.append({
            "id_hex": intent.id.hex(),
            "op": intent.op,
            "created_min": intent.created_min,
            "deadline_min": intent.deadline_min,
            "flags": intent.flags,
            "reply_to": intent.reply_to,
            "args_hex": intent.args.hex(),
            "plain_hex": intent.to_bytes().hex(),
            "counter": counter,
            "sealed_b64": _b64(sealed),
            "answer": rungs,
        })
    return {
        "why": (
            "Мова Наміру (розд. 07 §2.2). Kotlin PeerIntentVectorTest запечатує й відчиняє рівно ці "
            "байти, pytest test_intents_wire_vector.py звіряє файл із intents/wire.py. Переписати: "
            "cd phantom-os/src/backend && .venv/bin/python -m scripts.intent_wire_vector"
        ),
        "epoch_ms": int(EPOCH.timestamp() * 1000),
        "pair_key_b64": _b64(PAIR_KEY),
        "phone_id": PHONE_ID,
        "pc_id": PC_ID,
        "pair_hint_hex": pair_hint(PHONE_ID, PC_ID).hex(),
        "intent_key_b64": _b64(intent_key(PAIR_KEY, PHONE_ID, PC_ID)),
        "contact_tag": {"peer_node_id": "mama-node", "tag_hex": contact_tag(PAIR_KEY, "mama-node").hex()},
        "reply_to": {
            "sms1": lanes.reply_to(sms_parts=1),
            "sms3_relay": lanes.reply_to(sms_parts=3, relay=True),
            "relay_lan": lanes.reply_to(relay=True, lan=True),
        },
        "budgets": {"sms": lanes.SMS_BUDGET, "relay": lanes.RELAY_BUDGET},
        "oblast_order": list(OBLAST_ORDER),
        "status": {k: v for k, v in vars(Status).items() if k.isupper()},
        "cases": cases,
    }


def render() -> str:
    return json.dumps(build(), ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    VECTOR.write_text(render(), encoding="utf-8")
    sys.stdout.write(f"записано {VECTOR}\n")
