"""Драбина дозволів: сфери, рівні, незворотне, зворотне читання, вимикач."""
from symbiote.commands import command_registry
from symbiote.ladder import (
    LEVELS,
    SPHERES,
    StopSwitch,
    caps_with_level,
    decide,
    journal_args,
    ladder_from_caps,
)


def test_after_pairing_every_sphere_only_sees():
    from symbiote.contract import PC_SPHERES, SPHERES as PHONE_SPHERES

    ladder = ladder_from_caps(["sensors", "approvals"])
    assert set(ladder) == set(SPHERES)
    assert {ladder[s] for s in PHONE_SPHERES} == {"see"}
    # Тіло ПК: вміст (екран, файли, оболонка, браузер, видалення) — вимкнено.
    for sphere in ("sight", "files", "erase", "shell", "browser"):
        assert sphere in PC_SPHERES and ladder[sphere] == "off", sphere


def test_old_control_flag_is_read_back_without_migration():
    ladder = ladder_from_caps(["sensors", "approvals", "control"])
    assert ladder["keyboard"] == "report"
    assert ladder["clipboard"] == "report"
    assert ladder["place"] == "see"


def test_level_is_stored_next_to_old_flags_and_wins():
    caps = caps_with_level(["sensors", "control"], "keyboard", "off")
    assert "sensors" in caps and "control" in caps
    assert ladder_from_caps(caps)["keyboard"] == "off"
    caps = caps_with_level(caps, "keyboard", "ask")
    assert [c for c in caps if c.startswith("ladder:keyboard")] == ["ladder:keyboard=ask"]


def test_unknown_sphere_or_level_is_refused():
    import pytest

    with pytest.raises(ValueError):
        caps_with_level([], "money", "silent")
    with pytest.raises(ValueError):
        caps_with_level([], "place", "god")


def test_each_rung_means_what_it_says():
    assert decide("see", kind="read").action == "do"
    assert decide("off", kind="read").action == "refuse"
    assert decide("see", kind="act").action == "refuse"
    assert decide("suggest", kind="act").action == "suggest"
    assert decide("ask", kind="act").action == "ask"
    assert decide("report", kind="act").action == "report"
    assert decide("silent", kind="act").action == "do"


def test_irreversible_never_goes_above_yes():
    for level in ("ask", "report", "silent"):
        assert decide(level, kind="act", irreversible=True).action == "ask"
    assert decide("suggest", kind="act", irreversible=True).action == "suggest"


def test_stop_beats_every_rung():
    for level in LEVELS:
        verdict = decide(level, kind="read", stopped=True)
        assert verdict.action == "refuse"
        assert "зупинено" in verdict.why.lower()


def test_refusal_speaks_human():
    why = decide("see", kind="act", sphere_title="Клавіатура").why
    assert "Клавіатура" in why and "бачити" in why


def test_every_verb_has_a_sphere_and_typing_is_irreversible():
    for spec in command_registry.all():
        if spec["kind"] == "stop":
            continue
        assert spec["sphere"] in SPHERES, spec
        assert spec["kind"] in ("read", "act"), spec
    assert command_registry.spec("pc.type")["irreversible"] is True
    assert command_registry.spec("pc.notify")["irreversible"] is False
    assert command_registry.spec("phone.find")["sphere"] == "sound"
    assert command_registry.spec("phone.stop")["kind"] == "stop"
    assert command_registry.spec("phone.locate")["kind"] == "read"


def test_stop_switch_survives_restart(tmp_path):
    first = StopSwitch(tmp_path / "stop.json")
    assert first.is_on() is False
    first.set(True)
    assert StopSwitch(tmp_path / "stop.json").is_on() is True
    first.set(False)
    assert StopSwitch(tmp_path / "stop.json").is_on() is False


def test_journal_keeps_no_typed_text():
    assert journal_args("pc.type", {"text": "пароль123"}) == {"chars": 9}
    assert journal_args("pc.clipboard", {"text": "секрет"}) == {"chars": 6}
    assert journal_args("pc.open", {"url": "https://x.ua"}) == {"url": "https://x.ua"}
