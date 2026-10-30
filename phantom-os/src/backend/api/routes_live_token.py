"""«Подзвонити PHANTOM»: одноразовий ключ до Gemini Live для спарованого телефона.

  POST /api/v1/live/token   (device JWT) → {"token", "model", "expires_at", "start_before"}

Ключ Gemini живе на ПК і з нього не виходить. Телефон дістає ефемерний токен:
одна сесія, почати протягом хвилини, жити до 30 хвилин. На кожне
перепідключення (ротація 15-хвилинної сесії, goAway) телефон просить новий.
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Tuple

import httpx
from fastapi import APIRouter, Depends, HTTPException, status

from config import config
from security.device_auth import get_current_device

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/live", tags=["live"])

AUTH_TOKENS_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
LIVE_MODEL = "gemini-3.8-live"
TOKEN_TTL = timedelta(minutes=30)
START_WINDOW = timedelta(minutes=1)


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


async def _mint(api_key: str, body: dict[str, Any]) -> httpx.Response:
    async with httpx.AsyncClient(timeout=10.0) as client:
        return await client.post(
            AUTH_TOKENS_URL,
            headers={"x-goog-api-key": api_key, "Content-Type": "application/json"},
            json=body,
        )


@router.post("/token")
async def live_token(device: Tuple[Any, Any] = Depends(get_current_device)) -> dict[str, str]:
    api_key = (config.ai_gemini_api_key or "").strip()
    if not api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "no_key", "message": "На цьому комп'ютері не задано ключ Gemini"},
        )
    now = datetime.now(timezone.utc)
    expires = now + TOKEN_TTL
    start_before = now + START_WINDOW
    # Без liveConnectConstraints: обмеження полів замикають і systemInstruction
    # з tools, а їх телефон шле сам.
    body = {"uses": 1, "expireTime": _iso(expires), "newSessionExpireTime": _iso(start_before)}
    try:
        res = await _mint(api_key, body)
    except httpx.HTTPError as exc:
        logger.warning("live token: upstream unreachable: %s", type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "upstream_unreachable", "message": "Gemini зараз недоступний"},
        ) from exc
    name = ""
    if res.status_code == 200:
        try:
            name = str(res.json().get("name") or "")
        except ValueError:
            name = ""
    if not name:
        logger.warning("live token: upstream refused (%s)", res.status_code)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "upstream_refused", "status": res.status_code, "message": "Gemini не видав токен"},
        )
    row, _payload = device
    logger.info("live token minted for device %s", getattr(row, "id", "?"))
    return {
        "token": name,
        "model": LIVE_MODEL,
        "expires_at": _iso(expires),
        "start_before": _iso(start_before),
    }
