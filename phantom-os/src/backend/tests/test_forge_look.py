"""Кузня дивиться на результат (29.09, власник: «цей результат повна лажа»).

Кадр існує й об'єкти в ньому — ще не якість. Складена перевірка: спершу
виконувана частина, потім погляд моделі із зором; оцінка моделлю —
позначений слабкий доказ, а її зауваження йдуть працівнику як причина.
"""
from __future__ import annotations

import json

import pytest

from agent.forge.work import Check, Workspace, verify


@pytest.mark.asyncio
async def test_all_parts_must_pass_and_weakest_proof_is_reported(tmp_path):
    ws = Workspace(tmp_path)
    ws.write("a.txt", "так")
    seen = []

    async def judge(check, ws_):
        seen.append(check.rubric)
        return (check.rubric == "добре", f"оцінка за «{check.rubric}»")

    good = Check(kind="all", parts=[{"kind": "file", "path": "a.txt", "contains": "так"},
                                    {"kind": "look", "image": "a.txt", "rubric": "добре"}])
    proof = await verify(good, ws, None, judge)
    assert proof.ok and proof.strength == "judged"

    bad = Check(kind="all", parts=[{"kind": "file", "path": "a.txt", "contains": "так"},
                                   {"kind": "look", "image": "a.txt", "rubric": "погано"}])
    proof = await verify(bad, ws, None, judge)
    assert not proof.ok and "оцінка за «погано»" in proof.detail

    first_fails = Check(kind="all", parts=[{"kind": "file", "path": "nope.txt"}, {"kind": "look", "rubric": "добре"}])
    assert not (await verify(first_fails, ws, None, judge)).ok
    assert seen == ["добре", "погано"], "погляд не кличеться, коли виконувана частина вже впала"


@pytest.mark.asyncio
async def test_look_judge_sends_the_frame_and_turns_problems_into_feedback(tmp_path, monkeypatch):
    # Око — здібність «бачити» маршрутизатора (стовп 1), не прямий Gemini.
    from ai import model_router
    from agent.forge.service import look_judge

    ws = Workspace(tmp_path)
    (tmp_path / "f.png").write_bytes(b"\x89PNG fake")
    got = {}

    async def fake_see(image, prompt, **kw):
        got.update(image=image, prompt=prompt, **kw)
        return model_router.Seen(
            text=json.dumps({"score": 4, "problems": ["будинок — голий куб → додай вікна, двері, штукатурку"]}),
            provider="anthropic",
        )

    monkeypatch.setattr(model_router, "see", fake_see)
    ok, detail = await look_judge(Check(kind="look", image="f.png", rubric="затишний двір", min_score=7), ws)
    assert not ok and "4/10 (поріг 7)" in detail and "голий куб" in detail
    assert got["image"] == b"\x89PNG fake" and "затишний двір" in got["prompt"] and got["want_json"] is True


def test_render_with_look_becomes_render_then_look(tmp_path):
    from agent.forge import bodies, service

    if bodies.find_blender() is None:
        pytest.skip("Blender на цій машині не видно пісочниці")
    _ws, note, macros = service._engine_workspace(tmp_path)
    c = macros["render"]({"kind": "render", "scene": "s.py", "out": "r.png", "width": 64, "height": 64,
                          "require": ["House"], "look": "затишний двір"})
    assert c.kind == "all" and [p["kind"] for p in c.parts] == ["command", "look"]
    assert c.parts[1]["image"] == "r.png" and '"look"' in note
