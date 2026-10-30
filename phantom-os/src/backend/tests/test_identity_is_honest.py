"""PHANTOM's self-description must match the product: a PC + phone, no boards."""
from pathlib import Path

_GHOSTS = ("radxa", "q6a", "esp32", "oled", "npu", "hexagon")


def test_self_narrative_names_pc_and_phone_and_no_hardware():
    text = (Path(__file__).resolve().parents[1]
            / "agent" / "cognition" / "will" / "self_narrative.md").read_text(encoding="utf-8")
    low = text.lower()
    assert "пк" in low and "телефон" in low
    for ghost in _GHOSTS:
        assert ghost not in low, ghost


def test_chat_identity_has_no_fake_body():
    from ai.personality import PHANTOM_IDENTITY
    low = PHANTOM_IDENTITY.lower()
    assert "телефон" in low
    assert "сенсори, камера, дисплей" not in low
    for ghost in _GHOSTS:
        assert ghost not in low, ghost
