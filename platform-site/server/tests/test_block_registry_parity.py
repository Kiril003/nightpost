"""The site TypeScript mirror (src/lib/blocks) must give the server verdict on every fixture."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

TESTS_DIR = Path(__file__).resolve().parent
SERVER_DIR = TESTS_DIR.parent
SITE_DIR = SERVER_DIR.parent
for p in (str(SERVER_DIR), str(TESTS_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)

import venues
from blocks import loader, migrate, validate
from test_block_validate import PRIMITIVE_CASES, REGISTRY, VERDICTS_DIR, _reg_with_test_block

FIXTURES = SERVER_DIR / "blocks" / "fixtures"
NAME_BLOCK = {"id": "name-0", "kind": "id.name", "variant": "plain", "hidden": False, "data": {"name": "Cafe X"}}

pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="node is required for the TS mirror")


def _py_verdict(page, registry, publish=False) -> dict:
    try:
        res = validate.validate_page_v2(page, registry, publish=publish)
    except venues.Refused as e:
        return {"ok": False, "refused": e.reason, "at": e.at}
    return {"ok": True, "page": res["page"], "visibility": res["visibility"], "name": res["name"], "token": res["token"],
            "menu_version": res["menu_version"], "wallet": res["wallet"],
            "location": res["location"] and {k: res["location"][k] for k in ("address", "lat", "lon")}}


def _probe_cases() -> list[tuple[str, dict, dict]]:
    out = []
    for prim, (fspec, ok_val, bad_val, _) in sorted(PRIMITIVE_CASES.items()):
        records = (
            {"entry": {"id": {"p": "text", "max": 36, "req": True}, "label": {"p": "text", "max": 40, "req": True}}}
            if prim == "list" else {}
        )
        reg = _reg_with_test_block(prim, {} if prim == "derived" else fspec, records)
        if prim == "derived":
            reg["blocks"]["med.probe"]["fields"]["val"] = {"p": "derived", "source": "status"}
        for tag, val in (("ok", ok_val), ("bad", bad_val)):
            data = {} if prim == "derived" and tag == "ok" else {"val": val}
            page = {"v": 2, "tone": "amber", "blocks": [NAME_BLOCK, {"id": "probe-1", "kind": "med.probe", "variant": "plain", "hidden": False, "data": data}]}
            out.append((f"{prim}:{tag}", page, reg))
    return out


@pytest.fixture(scope="module")
def node_out():
    verdicts = [json.loads(f.read_text(encoding="utf-8")) for f in sorted(VERDICTS_DIR.glob("*.json"))]
    v1 = [json.loads(f.read_text(encoding="utf-8")) for f in sorted((FIXTURES / "v1").glob("*.json"))]
    probes = _probe_cases()
    payload = {
        "verdicts": [{"page": c["page"], "publish": c.get("publish", False)} for c in verdicts]
        + [{"page": page, "registry": reg} for _, page, reg in probes],
        "up": v1,
        "down": [migrate.up(p) for p in v1],
    }
    proc = subprocess.run(
        ["node", "scripts/blocks-parity.mjs"], cwd=SITE_DIR, input=json.dumps(payload),
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, proc.stderr
    return json.loads(proc.stdout), verdicts, probes, v1


def test_verdict_fixtures_match_the_server(node_out):
    out, verdicts, _, _ = node_out
    files = sorted(VERDICTS_DIR.glob("*.json"))
    assert len(files) >= 30
    for f, case, got in zip(files, verdicts, out["verdicts"]):
        want = _py_verdict(case["page"], REGISTRY, bool(case.get("publish", False)))
        assert got == want, f"{f.name}: ts={got} py={want}"


def test_every_primitive_matches_the_server(node_out):
    out, verdicts, probes, _ = node_out
    got_all = out["verdicts"][len(verdicts):]
    assert len(got_all) == len(probes) == 2 * len(PRIMITIVE_CASES)
    for (label, page, reg), got in zip(probes, got_all):
        assert got == _py_verdict(page, reg), label


def test_migrate_matches_the_server(node_out):
    out, _, _, v1 = node_out
    for page, up_ts, down_ts in zip(v1, out["up"], out["down"]):
        assert up_ts == migrate.up(page)
        assert down_ts == migrate.down(migrate.up(page)) == page
