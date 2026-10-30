"""Вектор мови Наміру, з яким звіряється Kotlin (`PeerIntentVectorTest`).

Файл лежить у test resources core-net, а не тут: його читає телефон. Цей тест
тримає ПК чесним — якщо `intents/wire.py` зміниться, а вектор ні, Kotlin
звірятиметься зі старою правдою, і розбіжність випливе лише на апараті.
"""
from __future__ import annotations

import base64
import json

from intents.wire import Intent, open_answer, open_intent
from scripts.intent_wire_vector import VECTOR, render


def test_the_vector_file_is_what_the_pc_computes_today():
    assert VECTOR.exists(), "вектор не згенеровано: python -m scripts.intent_wire_vector"
    assert VECTOR.read_text(encoding="utf-8") == render(), (
        "intents/wire.py розійшовся з вектором телефона — перегенеруй і прожени PeerIntentVectorTest"
    )


def test_every_sealed_frame_in_the_vector_opens_on_the_pc():
    v = json.loads(VECTOR.read_text(encoding="utf-8"))
    key = base64.b64decode(v["pair_key_b64"])
    for case in v["cases"]:
        intent = open_intent(base64.b64decode(case["sealed_b64"]), key, v["phone_id"], v["pc_id"])
        assert intent is not None and intent.to_bytes().hex() == case["plain_hex"]
        assert intent == Intent.from_bytes(bytes.fromhex(case["plain_hex"]))
        for rung in case["answer"].values():
            plain = open_answer(
                base64.b64decode(rung["sealed_b64"]), key, v["phone_id"], v["pc_id"], intent.id
            )
            assert plain is not None and plain.hex() == rung["plain_hex"]
            assert len(plain) <= rung["budget"]
