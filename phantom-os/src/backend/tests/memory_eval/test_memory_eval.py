"""Оцінка пам'яті: 60 українських випадків. У CI — підробний LLM; зі справжнім
ключем провайдера — ще й живий прогін (без ключа тест сам пропускається)."""
from __future__ import annotations

import pytest

from .cases import CASES, CROSS_CASES
from .harness import abstains, contains, provider_key_present, run


def test_sixty_cases_cover_every_behaviour():
    types = {c["type"] for c in CASES}
    assert len(CASES) == 60
    assert {"recall", "contradiction", "owner_vs_inferred", "pending", "retraction", "forget", "unknown"} <= types
    assert sum(1 for c in CASES if not c["expect"]) >= 20  # третина — мусить бути «не знаю»


def test_grader():
    assert abstains("Не знаю — ти мені цього не казав.")
    assert abstains("У моїй пам'яті немає інформації про це")
    assert not abstains("Твою сестру звати Марта.")
    assert contains("Ти живеш у Львові", "львові") and not contains("Ти живеш у Львові", "Київ")


def test_cross_device_cases_cover_phone_and_sync():
    assert len(CROSS_CASES) == 12
    assert {c["type"] for c in CROSS_CASES} == {"phone", "sync"}
    assert any(c.get("ask_on") == "phone" for c in CROSS_CASES)
    assert any(not c["expect"] for c in CROSS_CASES)


async def test_eval_fake_llm():
    s = (await run(real=False)).summary()
    print({k: v for k, v in s.items() if k != "by_type"})
    assert s["cases"] == 72
    assert s["by_type"]["phone"]["correct"] == 6 and s["by_type"]["sync"]["correct"] == 6, s["failures"]
    assert s["guarded_dont_know"] >= 5  # сторож відповідає «не знаю» без моделі
    assert s["recall"] >= 0.95, s["failures"]
    assert s["abstention"] >= 0.95, s["failures"]
    assert s["invented_rate"] == 0.0, s["failures"]
    assert s["max_context_tokens"] <= 1500


@pytest.mark.skipif(not provider_key_present(), reason="no AI provider key configured")
async def test_eval_real_provider():
    """Хартія: ≥ 90 % recall і нуль вигаданих фактів на справжній моделі (docs/memory/EVAL.md)."""
    s = (await run(real=True)).summary()
    print(s)
    assert s["invented_rate"] == 0.0, s["failures"]
    assert s["recall"] >= 0.9, s["failures"]
