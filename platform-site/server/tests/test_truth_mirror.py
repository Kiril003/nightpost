"""Сервер і телефон рахують довіру однією математикою — перевірено числами Kotlin.

`vectors/truth_vectors.json` пише САМ `:core-truth` (`scripts/truth-vectors.sh`):
входи й відповіді. Тут той самий вхід іде в `truth_engine`, і відповідь мусить
збігтися до 1e-12. Розійдеться формула, поріг, півжиття чи слово у фільтрі —
почервоніє тут, а не в людини, якій сервер і телефон скажуть різне.
"""
import json
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import truth_engine as te  # noqa: E402

V = json.loads((Path(__file__).parent / "vectors" / "truth_vectors.json").read_text(encoding="utf-8"))
EPS = 1e-12


def voter(j):
    return te.Voter(j["id"], j["created_at_ms"], j["network_id"], j["invited_by"])


def observation(j):
    kind = te.BY_NAME[j["kind"]]
    p = j["presence"]
    return te.Observation(
        j["voter"],
        te.FactKey(j["cell"], j["object"], kind),
        kind.reading(j["reading"]),
        j["at_ms"],
        te.Presence(p["cell"], p["from_ms"], p["to_ms"]) if p else None,
    )


def test_словник_той_самий():
    assert [k["name"] for k in V["kinds"]] == [k.name for k in te.KINDS]
    for j, k in zip(V["kinds"], te.KINDS):
        assert (j["wire"], j["half_life_ms"], j["readings"], j["positive"], j["remote_factor"], j["importance"],
                j["embargo_during_alert"]) == (
            k.wire, k.half_life_ms, [r.wire for r in k.readings], k.positive.wire if k.positive else None,
            k.remote_factor, k.importance, k.embargo_during_alert)
    assert {r["name"]: (r["uk"], r["en"]) for r in V["refusals"]} == te.REFUSALS


def test_вага_репутації():
    for j in V["weights"]:
        s = te.Standing(j["confirmed"], j["refuted"], j["as_of_ms"]).at(j["now_ms"])
        assert te.reputation_weight(s) == pytest.approx(j["weight"], abs=EPS)


def test_вік_ключа():
    for j in V["age"]:
        v = None if j["created_at_ms"] is None else te.Voter("a", j["created_at_ms"])
        assert te.age_factor(v, j["at_ms"]) == pytest.approx(j["factor"], abs=EPS)


def test_маса_кола():
    for j in V["circle_mass"]:
        assert te.circle_mass(j["weights"]) == pytest.approx(j["mass"], abs=EPS)


def test_кола_ті_самі():
    for j in V["circles"]:
        voters = [voter(v) for v in j["voters"]]
        got = te.circles(voters, j["as_of_ms"])
        assert [got[v.id] for v in voters] == j["roots"]


@pytest.mark.parametrize("i", range(len(V["verdicts"])))
def test_вирок(i):
    j = V["verdicts"][i]
    kind = te.BY_NAME[j["kind"]]
    key = te.FactKey(j["cell"], j["object"], kind)
    rep = {r["id"]: r["w"] for r in j["reputation"]}
    v = te.verdict(key, [observation(o) for o in j["observations"]], {x["id"]: voter(x) for x in j["voters"]},
                   rep.__getitem__, j["now_ms"])
    e = j["verdict"]
    assert v.state == e["state"]
    assert (v.reading.wire if v.reading else None) == e["reading"]
    assert v.probability == pytest.approx(e["probability"], abs=EPS)
    assert v.mass == pytest.approx(e["mass"], abs=EPS)
    assert (v.supporters, v.last_support_ms, v.newest_ms, v.dissent, v.contested) == (
        e["supporters"], e["last_support_ms"], e["newest_ms"], e["dissent"], e["contested"])
    for c in j["claims"]:
        r = te.claim_reading(c["claim"], kind, None, v)
        assert (r.wire if r else None) == c["reading"]


@pytest.mark.parametrize("i", range(len(V["settle"])))
def test_звіряння_й_репутація(i):
    j = V["settle"][i]
    obs = [observation(o) for o in j["observations"]]
    voters = {x["id"]: voter(x) for x in j["voters"]}
    standings: dict = {}
    got = te.settle(obs, list(range(len(obs))), voters, standings, j["now_ms"])
    assert [{"index": a, "outcome": b} for a, b in got] == j["outcomes"]
    for s in j["standings"]:
        mine = standings.get(s["id"], te.Standing(as_of_ms=j["now_ms"])).at(j["now_ms"])
        assert mine.confirmed == pytest.approx(s["confirmed"], abs=EPS)
        assert mine.refuted == pytest.approx(s["refuted"], abs=EPS)
        assert te.reputation_weight(mine) == pytest.approx(s["weight"], abs=EPS)


def test_фільтр_на_вході_рішення_ті_самі():
    checked = 0
    for j in V["intake"]:
        if "text" in j:
            assert te.forbidden_text(j["text"]) == j["forbidden"], j["text"]
            continue
        r = te.check(j["kind"], j["reading"], j["cell"], j["object"], j["note"])
        assert r.refused == j["refused"], j
        if r.refused is None:
            assert (r.kind.name, r.reading.wire, r.note) == (
                j["accepted_kind"], j["accepted_reading"], j["note_for_moderation"])
        checked += 1
    assert checked >= 300


def test_смуги_користі():
    for j in V["band"]:
        assert te.band(j["n"]) == j["band"]


def test_ембарго_під_тривогою():
    for j in V["embargo"]:
        kind = te.BY_NAME[j["kind"]]
        o = te.Observation("x", te.FactKey("c", None, kind), kind.readings[0], j["at_ms"])
        alerts = [te.AlertWindow(a["start_ms"], a["end_ms"]) for a in j["alerts"]]
        assert te.release_at_ms(o, alerts) == j["release_ms"]
