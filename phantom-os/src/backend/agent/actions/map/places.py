"""map.list_places / map.remove_marker — місця власника на мапі.

Розум уже вмів ставити мітки (`map.add_marker`), але не бачив, які мітки є,
і не міг прибрати жодної — мапа для нього була лише для запису. Читаються
ті самі рядки `MapPOI`, що й у `GET /map/pois`, з тим самим правилом:
таємні мітки видно лише в стані GHOST.

Видалення — це видалення даних власника, тож за статутом воно завжди з
окремим «так»: MEDIUM вище типової стелі агента, ядро спитає людину.
"""
from __future__ import annotations

import time
from typing import ClassVar, Optional

from pydantic import Field

from ...schemas import ActionResult, RiskLevel
from ..base import Action, ActionContext


def _ms(t0: float) -> int:
    return int((time.monotonic() - t0) * 1000)


def _ghost() -> bool:
    try:
        from core.context_engine import context_engine

        return context_engine.get_snapshot().get("system", {}).get("state") == "GHOST"
    except Exception:  # noqa: BLE001 — без двигуна контексту таємне лишається таємним
        return False


class MapListPlaces(Action):
    """Мітки власника на мапі (дім, робота, збережене, свої)."""

    name: ClassVar[str] = "map.list_places"
    risk_level: ClassVar[RiskLevel] = RiskLevel.SAFE
    reversible: ClassVar[bool] = True

    category: Optional[str] = Field(default=None, description="intel|threat|saved|home|work|custom")
    query: str = Field(default="", max_length=120, description="Частина назви або нотатки.")
    limit: int = Field(default=50, ge=1, le=500)

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from sqlalchemy import select

        from db.database import AsyncSessionLocal
        from db.models import MapPOI

        stmt = select(MapPOI).where(MapPOI.user_id == ctx.user_id)
        if self.category:
            stmt = stmt.where(MapPOI.category == self.category)
        stmt = stmt.order_by(MapPOI.created_at.desc())
        async with AsyncSessionLocal() as db:
            rows = list((await db.execute(stmt)).scalars().all())
        ghost = _ghost()
        needle = self.query.casefold().strip()
        places = [
            {
                "id": p.id, "name": p.name, "category": p.category,
                "lat": p.lat, "lon": p.lon, "notes": p.notes, "icon": p.icon,
            }
            for p in rows
            if (not p.is_secret or ghost)
            and (not needle or needle in f"{p.name} {p.notes or ''}".casefold())
        ][: self.limit]
        return ActionResult(
            ok=True,
            output={"places": places, "narrative": f"Міток: {len(places)}."},
            elapsed_ms=_ms(t0),
        )


class MapRemoveMarker(Action):
    """Прибрати мітку власника — видалення даних, завжди з «так» людини."""

    name: ClassVar[str] = "map.remove_marker"
    risk_level: ClassVar[RiskLevel] = RiskLevel.MEDIUM
    requires_consent: ClassVar[bool] = True
    reversible: ClassVar[bool] = False

    marker_id: str = Field(..., min_length=1, max_length=64)

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from sqlalchemy import select

        from db.database import AsyncSessionLocal
        from db.models import MapPOI

        async with AsyncSessionLocal() as db:
            row = (
                await db.execute(
                    select(MapPOI).where(MapPOI.id == self.marker_id, MapPOI.user_id == ctx.user_id)
                )
            ).scalar_one_or_none()
            if row is None:
                return ActionResult(ok=False, error="мітку не знайдено", elapsed_ms=_ms(t0))
            name = row.name
            await db.delete(row)
            await db.commit()
        return ActionResult(
            ok=True,
            output={"removed": self.marker_id, "narrative": f"Мітку «{name}» прибрано."},
            side_effects=[f"map: прибрано мітку {name}"],
            elapsed_ms=_ms(t0),
        )
