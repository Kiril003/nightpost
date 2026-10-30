"""Команда агентів працює на людину, а не в легенді (27.09).

Наживо з ключем власника: доручення «план тренувань для початківця» дало
«Протокол "Нульовий рівень"» про «біологічного носія», поділене на 4 «чверті»,
а останній крок видав звіт про роботу замість самого плану.
"""
from __future__ import annotations

from agent.fabric.pipelines import build_scriptorium
from agent.fabric.service import PolisService


def test_worker_is_a_specialist_for_a_person_not_a_citizen():
    system = PolisService._role_system("documentation_writer")
    assert "громадянин" not in system.lower() and "поліс" not in system.lower()
    assert "людин" in system
    assert "202" in system  # справжня дата, а не заглушка «202X»


def test_small_order_is_one_document_not_four_quarters():
    g = build_scriptorium("Склади план тренувань на тиждень для початківця.")
    titles = [n.title for n in g.nodes.values()]
    assert len(titles) == 3
    assert "Готовий документ" in titles
    assert not any("чверть" in t for t in titles)


def test_big_order_still_splits():
    g = build_scriptorium("x" * 2500)
    assert len(g.nodes) == 6


def test_last_step_delivers_the_document_itself():
    g = build_scriptorium("Лист орендодавцю про ремонт.")
    final = next(n for n in g.nodes.values() if n.title == "Готовий документ")
    assert "САМ документ" in final.prompt
