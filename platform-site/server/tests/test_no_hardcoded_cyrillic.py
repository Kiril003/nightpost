"""Guard: the server sends no hardcoded Ukrainian to people. User-facing text goes through i18n.t()."""
import ast
import re
from pathlib import Path

SERVER = Path(__file__).resolve().parent.parent
CYR = re.compile("[Ѐ-ӿ]")
LOGGERS = {"logger", "log", "logging", "print"}

ALLOW = {
    "truth_engine.py": "parsing vocabulary: words used to read Ukrainian input, not output",
    "check_pack_road.py": "developer CLI for world packs, not UI",
    "publish_world_packs.py": "developer CLI for world packs, not UI",
    "sign_world_packs.py": "developer CLI for world packs, not UI",
    "upload_to_r2.py": "developer CLI, not UI",
    "i18n.py": "the catalogue itself: the one place Ukrainian text is allowed to live",
    "builder_ai.py": "parsing vocabulary: day, count, currency and column words read from Ukrainian input, not output",
}

# Ratchet: a file may only shrink; files not listed must be 0.
LEDGER = {
    'siren_vector.py': 1,  # test-vector note also written into the Kotlin tree's siren_vector.json
}


def _root(node):
    while isinstance(node, ast.Attribute):
        node = node.value
    return node.id if isinstance(node, ast.Name) else None


def cyrillic_constants(path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    skip = set()
    for n in ast.walk(tree):
        if isinstance(n, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            b = n.body
            if b and isinstance(b[0], ast.Expr) and isinstance(b[0].value, ast.Constant):
                skip.add(id(b[0].value))
        if isinstance(n, ast.Call) and _root(n.func) in LOGGERS:
            skip.update(id(x) for x in ast.walk(n))
    return [
        (x.lineno, x.value[:60])
        for x in ast.walk(tree)
        if isinstance(x, ast.Constant) and isinstance(x.value, str)
        and id(x) not in skip and CYR.search(x.value)
    ]


def test_allow_reasons_are_real():
    for f, why in ALLOW.items():
        assert len(why) > 10, f
        assert (SERVER / f).exists(), f


def test_no_hardcoded_cyrillic_in_server():
    problems = []
    for p in sorted(SERVER.glob("*.py")):
        if p.name in ALLOW:
            continue
        found = cyrillic_constants(p)
        allowed = LEDGER.get(p.name, 0)
        if len(found) > allowed:
            sample = "; ".join(f"{ln}:{s!r}" for ln, s in found[:4])
            problems.append(f"{p.name}: {len(found)} > ledger {allowed} ({sample})")
        elif len(found) < allowed:
            problems.append(f"{p.name}: only {len(found)} left, lower LEDGER from {allowed}")
    assert not problems, "\n" + "\n".join(problems)
