"""Спільний контракт тіл: одне джерело дієслів, драбини й ризику.

Червоне без `symbiote/contract.{json,py}`: реєстр і драбина набрані руками,
а лист із чужим чи завеликим аргументом доходить до рук тіла.
"""
from __future__ import annotations

import os

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-contract")

from symbiote import contract
from symbiote.commands import SymbioteCommand, command_registry, dispatch_command


def test_contract_is_whole():
    assert contract.contract_problems() == []


def test_registry_is_the_contract():
    got = {v["verb"]: v for v in command_registry.all()}
    # ПК приймає і дієслова телефонного контракту, і тіла ПК (`pc_body`).
    assert set(got) == set(contract.ALL_VERBS)
    assert set(contract.VERBS) <= set(got)
    for verb, spec in contract.ALL_VERBS.items():
        reg = got[verb]
        assert reg["body_kind"] == spec["body"], verb
        assert reg["sphere"] == spec["sphere"], verb
        # вид для драбини: читання буфера (consent: ask) поводиться як дія
        assert reg["kind"] == contract.ladder_kind(verb), verb
        assert reg["requires"] == spec["requires"], verb
        assert reg["risk"] == spec["risk"], verb
        assert reg["irreversible"] == (spec["risk"] == "irreversible"), verb


def test_ladder_reads_the_contract():
    from symbiote import ladder

    # Те саме джерело, а не однаковий передрук.
    assert ladder.SPHERES is contract.ALL_SPHERES
    assert set(contract.SPHERES) <= set(ladder.SPHERES)
    assert ladder.LEVELS is contract.LEVELS


def test_every_scale_maps_onto_the_charter_scale():
    from agent.schemas import RiskLevel

    assert set(contract.RISK_FROM_AGENT) == {r.name for r in RiskLevel}
    assert set(contract.RISK_FROM_AGENT.values()) <= set(contract.RISKS)
    assert set(contract.RISK_FROM_PHONE.values()) <= set(contract.RISKS)
    assert contract.RISK_ORDER == ("read", "local", "outward", "irreversible")


@pytest.mark.parametrize(
    "verb,args,ok",
    [
        ("phone.speak", {"text": "привіт"}, True),
        ("phone.speak", {}, False),
        ("phone.speak", {"text": "а" * 501}, False),
        ("phone.ring", {"volume": "11"}, False),
        ("pc.open", {"url": "https://phantom-os.dev"}, True),
        ("pc.open", {}, False),
        ("pc.type", {"text": 5}, False),
        ("pc.lock", {}, True),
        ("nope.verb", {}, False),
        # SEE/ACT — очі й руки телефонного тіла.
        ("phone.screen.read", {}, True),
        ("phone.state", {}, True),
        ("phone.tap", {"ref": "n3"}, True),
        ("phone.tap", {}, False),  # треба ref/text/x
        ("phone.type", {"text": "привіт"}, True),
        ("phone.type", {}, False),
        ("phone.app.open", {"app": "Налаштування"}, True),
        ("phone.intent", {"action": "dial", "data": "112"}, True),
        ("phone.intent", {}, False),
        ("phone.clipboard.set", {"text": "код"}, True),
    ],
)
def test_check_args(verb, args, ok):
    assert (contract.check_args(verb, args) == "") is ok


def test_see_act_verbs_are_in_the_contract():
    """Очі й руки телефона — у спільному контракті з правильною шкалою."""
    v = contract.VERBS
    assert v["phone.screen.read"]["sphere"] == "screen"
    assert v["phone.screen.read"]["risk"] == "read"
    assert v["phone.tap"]["sphere"] == "screen" and v["phone.tap"]["kind"] == "act"
    # Набір тексту на телефоні незворотний — як і на ПК.
    assert v["phone.type"]["risk"] == "irreversible"
    assert contract.is_irreversible("phone.type")
    assert v["phone.intent"]["risk"] == "outward"
    assert v["phone.clipboard.set"]["sphere"] == "clipboard"


@pytest.mark.asyncio
async def test_dispatch_refuses_bad_args_before_hands(monkeypatch):
    from symbiote import desktop_hands

    touched: list[str] = []

    async def fake(verb, args):
        touched.append(verb)
        return True, "", {}

    monkeypatch.setattr(desktop_hands, "execute", fake)
    accepted, why, _ = await dispatch_command(
        user_id="u1", command=SymbioteCommand(verb="pc.type", args={"txt": "x"}),
    )
    assert accepted is False
    assert "txt" in why
    assert touched == []


def test_route_answers_400_on_bad_args(auth_root_client):
    r = auth_root_client.post(
        "/api/v1/symbiote/command",
        json={"verb": "pc.clipboard", "args": {"text": "x", "extra": "y"}},
    )
    assert r.status_code == 400, r.text
    assert r.json()["detail"]["code"] == "bad_args"


def test_contract_route_serves_the_contract(auth_root_client):
    r = auth_root_client.get("/api/v1/symbiote/contract")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["version"] == contract.VERSION
    assert set(body["verbs"]) == set(contract.VERBS)
