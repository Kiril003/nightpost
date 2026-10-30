"""
Стара тема `ui_theme` знята з вузла (04.10).

Тем у PHANTOM дві — Ніч і День, — і їх ставить клієнт за сонцем
(frontend services/theme.ts). Вузол досі пропонував у Налаштуваннях вибір
sunrise-warm / amber-night / cyberdeck-cold / pro-console, і ПК, обравши
«Cyberdeck», перефарбовував корінь у тему, якої в CSS більше немає.

Тут перевіряється, що ключа більше немає ніде, звідки його можна вибрати
чи записати: ні в моделі, ні в розділі «Тема», ні в підписах — а запис
`PUT /settings/ui_theme` відповідає 404 (клієнт цю відмову ковтає).
"""
from __future__ import annotations

import os

import pytest
from fastapi import HTTPException

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-no-legacy-theme")
os.environ.setdefault("AI_GEMINI_API_KEY", "fake-api-key-for-tests")

from api.routes_settings import (  # noqa: E402
    CATEGORY_SPEC,
    LABEL_OVERRIDES,
    SetValueRequest,
    set_setting,
)
from config import PhantomConfig  # noqa: E402


def test_model_has_no_ui_theme() -> None:
    assert "ui_theme" not in PhantomConfig.model_fields


def test_theme_section_does_not_offer_ui_theme() -> None:
    offered = [key for group in CATEGORY_SPEC for key in group["keys"]]
    assert "ui_theme" not in offered
    assert "ui_theme" not in LABEL_OVERRIDES
    # Решта розділу «Тема» лишається: мова, щільність, кегль.
    theme = next(group for group in CATEGORY_SPEC if group["id"] == "theme")
    assert "ui_language" in theme["keys"]


def test_persisted_row_is_skipped_not_fatal() -> None:
    """Збережений рядок старого вузла не валить старт: ключ невідомий — пропуск."""
    cfg = PhantomConfig()
    cfg.apply_overrides({"ui_theme": "cyberdeck-cold"})
    assert not hasattr(cfg, "ui_theme")


@pytest.mark.asyncio
async def test_put_ui_theme_is_404() -> None:
    with pytest.raises(HTTPException) as exc:
        await set_setting(
            key="ui_theme",
            req=SetValueRequest(value="cyberdeck-cold"),
            user=None,  # type: ignore[arg-type]
        )
    assert exc.value.status_code == 404
