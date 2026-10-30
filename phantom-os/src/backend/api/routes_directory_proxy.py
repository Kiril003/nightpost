"""`GET /api/v1/messenger/directory/lookup/{digest}` — довідник імен через вузол.

Навіщо: вікно PHANTOM на ПК живе на `tauri://localhost` (Linux) або
`http://tauri.localhost` (Windows). Сервер довідника пускає браузер лише з
сайту (CORS за `SITE_ORIGINS`), тож пошук людини за іменем із вікна ПК
відбивався ще до сервера, і людина читала «Довідник не відповів».
Вузол ходить туди як звичайна програма — без CORS.

Вузлу тут нічого не довіряють: запис підписаний ключем самої людини, і підпис
звіряє веб (`services/peerDirectory.ts`), а не цей маршрут. Сюди їде лише
sha256 імені — самого імені вузол не бачить так само, як і сервер.
"""
from __future__ import annotations

import logging
import re

import httpx
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse

from db.models import User
from security.device_auth import get_user_or_device_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/messenger", tags=["messenger"])

#: Адреса запису — голий sha256 імені. Інше — не питання до довідника.
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
#: Довідник не поспішає лише тоді, коли його нема; людина не чекає вічно.
TIMEOUT_S = 8.0
#: Лише для тестів: підмінна дорога замість мережі.
_transport: httpx.AsyncBaseTransport | None = None


def directory_base() -> str:
    from config import config

    return (getattr(config, "directory_url", "") or "").strip().rstrip("/")


@router.get("/directory/lookup/{digest}")
async def lookup(digest: str, _user: User = Depends(get_user_or_device_user)) -> JSONResponse:
    if not _DIGEST.fullmatch(digest):
        raise HTTPException(status_code=400, detail="Це не адреса запису в довіднику.")
    base = directory_base()
    if not base:
        # Довідник вимкнено в налаштуваннях вузла: це стан, а не збій.
        raise HTTPException(status_code=503, detail="Довідник вимкнено на цьому комп'ютері.")
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT_S, follow_redirects=False, transport=_transport) as client:
            resp = await client.get(f"{base}/directory/lookup/{digest}")
    except httpx.HTTPError as exc:
        logger.info("довідник не відповів: %s", exc)
        raise HTTPException(status_code=502, detail="Довідник зараз не відповідає.") from exc
    # Три відповіді довідника, які веб розрізняє: є запис / нема / закрито.
    if resp.status_code == 404:
        raise HTTPException(status_code=404, detail="Такого імені в довіднику немає.")
    if resp.status_code == 503:
        raise HTTPException(status_code=503, detail="Довідник зараз закритий.")
    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail="Довідник зараз не відповідає.")
    try:
        body = resp.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="Довідник відповів нерозбірливо.") from exc
    if not isinstance(body, dict):
        raise HTTPException(status_code=502, detail="Довідник відповів нерозбірливо.")
    return JSONResponse(body)
