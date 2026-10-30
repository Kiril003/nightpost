"""Справжній світ для обробників Намірів: тривоги, присутність, розум, мережа.

Кожен порт або дає дані, або піднімає `SourceSilent`. Порожнє ніколи не
підміняє «не знаю»: джерело без ключа, мережа впала, розум не відповів —
усе це «джерело мовчить», а не «тривог немає» і не порожня відповідь.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import ipaddress
import logging
import socket
import time
from datetime import datetime, timezone
from html.parser import HTMLParser
from typing import Any, Optional
from urllib.parse import urljoin, urlsplit

import httpx
from sqlalchemy import select

from .handlers import (
    PAGE_MAX_BYTES,
    PAGE_TIMEOUT_S,
    AlertRow,
    AlertsSnapshot,
    Page,
    Presence,
    SourceSilent,
    refuse_url,
)
from .wire import contact_tag

logger = logging.getLogger(__name__)

__all__ = ["LivePorts", "page_text"]

#: Тривоги з ПК не опитуються частіше: SMS-шлюз може принести кілька Намірів
#: поспіль, а alarms.in.ua карає за частоту саме під час тривоги.
ALERTS_CACHE_S = 20.0
MAX_REDIRECTS = 3
ALERTS_FETCH_TIMEOUT_S = 4.0
_SKIP_TAGS = {"script", "style", "noscript", "template", "svg", "head", "nav", "footer", "form"}


class _TextOnly(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title = ""
        self._in_title = False
        self._skip = 0
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: Any) -> None:
        if tag == "title":
            self._in_title = True
        elif tag in _SKIP_TAGS:
            self._skip += 1

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        elif tag in _SKIP_TAGS and self._skip:
            self._skip -= 1

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title += data
        elif not self._skip and data.strip():
            self._parts.append(data.strip())

    @property
    def text(self) -> str:
        return " ".join(self._parts)


def page_text(html: str) -> Page:
    parser = _TextOnly()
    parser.feed(html)
    parser.close()
    return Page(title=" ".join(parser.title.split()), text=" ".join(parser.text.split()))


async def _public_only(url: str) -> None:
    """Ім'я хоста мусить вести лише на публічні адреси — інакше відмова."""
    reason = refuse_url(url)
    if reason:
        raise SourceSilent(f"адреса заборонена: {reason}")
    parts = urlsplit(url)
    host = parts.hostname or ""
    port = parts.port or (443 if parts.scheme == "https" else 80)
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise SourceSilent(f"ім'я не розв'язується: {exc}") from exc
    for info in infos:
        if not ipaddress.ip_address(info[4][0]).is_global:
            raise SourceSilent("ім'я веде в домашню мережу")


class LivePorts:
    def __init__(self) -> None:
        self._alerts: Optional[tuple[float, AlertsSnapshot]] = None
        self._siren: Optional[tuple[float, bytes]] = None

    async def alerts(self) -> AlertsSnapshot:
        cached = self._alerts
        if cached is not None and time.monotonic() - cached[0] < ALERTS_CACHE_S:
            return cached[1]
        from geo.sources.alarms_ua import AlarmsUnavailable, get_default_alarms_ua

        adapter = get_default_alarms_ua()
        if not adapter.configured():
            raise SourceSilent("ALARMS_UA_KEY не задано — тривоги не опитуються")
        try:
            rows = await adapter.fetch()
        except AlarmsUnavailable as exc:
            raise SourceSilent(str(exc)) from exc
        snap = AlertsSnapshot(
            alerts=tuple(
                AlertRow(r.oblast_id, r.alert_type, _parse_time(r.started_at)) for r in rows
            ),
            fetched_at=datetime.now(timezone.utc),
        )
        self._alerts = (time.monotonic(), snap)
        return snap

    async def siren(self) -> bytes:
        """Знімок «Сирени-маяка» з сайту, кеш на ALERTS_CACHE_S."""
        cached = self._siren
        if cached is not None and time.monotonic() - cached[0] < ALERTS_CACHE_S:
            return cached[1]
        raw = await _site_siren()
        self._siren = (time.monotonic(), raw)
        return raw

    async def presence(self, owner_user_id: str, tag: bytes, pair_key: bytes) -> Optional[Presence]:
        from db.database import AsyncSessionLocal
        from db.models import MessengerContact, MessengerConversation, MessengerMessage

        async with AsyncSessionLocal() as db:
            contacts = (
                await db.execute(
                    select(MessengerContact).where(
                        MessengerContact.owner_user_id == owner_user_id,
                        MessengerContact.mine.is_(False),
                    )
                )
            ).scalars().all()
            contact = next((c for c in contacts if contact_tag(pair_key, c.peer_node_id) == tag), None)
            if contact is None:
                return None
            conv_ids = (
                await db.execute(
                    select(MessengerConversation.id).where(
                        MessengerConversation.owner_user_id == owner_user_id,
                        MessengerConversation.contact_id == contact.id,
                    )
                )
            ).scalars().all()
            if not conv_ids:
                return Presence(last_heard=None, lane=None, my_last_state=None)
            theirs = (
                await db.execute(
                    select(MessengerMessage)
                    .where(
                        MessengerMessage.conversation_id.in_(conv_ids),
                        MessengerMessage.author_id == contact.peer_node_id,
                    )
                    .order_by(MessengerMessage.sent_at.desc())
                    .limit(1)
                )
            ).scalar_one_or_none()
            mine = (
                await db.execute(
                    select(MessengerMessage)
                    .where(
                        MessengerMessage.conversation_id.in_(conv_ids),
                        MessengerMessage.author_id != contact.peer_node_id,
                        MessengerMessage.deleted_at.is_(None),
                    )
                    .order_by(MessengerMessage.sent_at.desc())
                    .limit(1)
                )
            ).scalar_one_or_none()
        return Presence(
            last_heard=theirs.sent_at if theirs else None,
            lane=theirs.transport if theirs else None,
            my_last_state=mine.delivery_state if mine else None,
        )

    async def think(self, system: str, prompt: str) -> str:
        from ai.provider import ai_router

        try:
            resp = await ai_router.generate_no_tools(prompt, system, [])
        except Exception as exc:  # noqa: BLE001 — квота, мережа, жодного провайдера
            raise SourceSilent(f"розум не відповів: {exc}") from exc
        text = getattr(resp, "content", None)
        if not isinstance(text, str) or not text.strip():
            raise SourceSilent("розум дав порожню відповідь")
        return text

    async def fetch(self, url: str) -> Page:
        """Лише GET, без кукі, без JS; 2 МБ і 10 с (§8.3). Кожне перенаправлення
        перевіряється наново — інакше публічна адреса могла б переслати на роутер."""
        async with httpx.AsyncClient(
            timeout=PAGE_TIMEOUT_S,
            follow_redirects=False,
            headers={"User-Agent": "PHANTOM-OS/0.9 (intent page_gist)", "Accept": "text/html,text/plain"},
        ) as client:
            for _ in range(MAX_REDIRECTS + 1):
                await _public_only(url)
                try:
                    async with client.stream("GET", url) as resp:
                        if resp.is_redirect and resp.headers.get("location"):
                            url = urljoin(url, resp.headers["location"])
                            continue
                        if resp.status_code != 200:
                            raise SourceSilent(f"сторінка відповіла {resp.status_code}")
                        kind = resp.headers.get("content-type", "")
                        if "html" not in kind and "text/plain" not in kind:
                            raise SourceSilent(f"не текст: {kind or 'невідомий тип'}")
                        body = bytearray()
                        async for chunk in resp.aiter_bytes():
                            body.extend(chunk)
                            if len(body) > PAGE_MAX_BYTES:
                                break
                        text = bytes(body[:PAGE_MAX_BYTES]).decode(resp.encoding or "utf-8", errors="replace")
                except httpx.HTTPError as exc:
                    raise SourceSilent(f"сторінка недоступна: {exc}") from exc
                if "html" in kind:
                    return page_text(text)
                return Page(title="", text=" ".join(text.split()))
        raise SourceSilent("забагато перенаправлень")


async def _site_siren() -> bytes:
    from config import config

    base = (config.relay_store_url or "").rstrip("/") if config.relay_store_enabled else ""
    if not base:
        raise SourceSilent("сайт не налаштовано (relay_store_url)")
    try:
        async with httpx.AsyncClient(timeout=ALERTS_FETCH_TIMEOUT_S) as client:
            resp = await client.get(f"{base}/api/alerts/snapshot")
    except httpx.HTTPError as exc:
        raise SourceSilent(f"сайт не відповів: {exc}") from exc
    if resp.status_code != 200:
        raise SourceSilent(f"сайт: {resp.status_code}")
    try:
        text = str(resp.json().get("snapshot") or "")
        raw = base64.b64decode(text + "=" * (-len(text) % 4), validate=True)
    except (ValueError, binascii.Error, AttributeError) as exc:
        raise SourceSilent("сайт віддав не знімок") from exc
    return raw


def _parse_time(raw: Optional[str]) -> Optional[datetime]:
    if not raw:
        return None
    try:
        when = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    return when if when.tzinfo else when.replace(tzinfo=timezone.utc)
