"""Телефон і сервер правди говорять однією коміркою й одним реченням.

`h3_vectors.json` пише `phantom-companion/scripts/h3-vectors.cjs` з h3-js;
`truth_observe_vectors.json` — сам `observe_message`. Kotlin читає ті самі
файли (`HereWireMirrorTest`); тут стережемо, що копії не розійшлись.
"""
import json
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import truth_store as truth  # noqa: E402

HERE = Path(__file__).parent / "vectors"
PHONE = SERVER_DIR.parents[1] / "phantom-companion" / "core-truth" / "src" / "test" / "resources"


def test_копії_векторів_однакові():
    for name in ("h3_vectors.json", "truth_observe_vectors.json"):
        assert (HERE / name).read_bytes() == (PHONE / name).read_bytes(), name


def test_комірка_телефона_приймається_і_область_та_сама():
    rows = json.loads((HERE / "h3_vectors.json").read_text(encoding="utf-8"))["rows"]
    assert len(rows) >= 1000
    for lat, lng, r8, r6, p6 in rows:
        assert truth.is_cell(r8), (lat, lng, r8)
        assert truth.is_area(r6) and truth.is_area(p6)
        assert truth.area_of(r8) == p6, (lat, lng, r8)


def test_речення_те_саме():
    for c in json.loads((HERE / "truth_observe_vectors.json").read_text(encoding="utf-8"))["cases"]:
        presence = tuple(c["presence"]) if c["presence"] else None
        got = truth.observe_message(c["pub_key"], c["nonce"], c["issued_ms"], c["cell"], c["object"], c["kind"],
                                    c["claim"], c["reading"], presence, c["note"])
        assert got == c["sentence"].encode()
