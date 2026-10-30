"""Обробники Намірів — БІЛИЙ СПИСОК, і нічого понад нього.

Жодного «виконай довільну команду»: кожен op — окрема функція з власними
межами, а невідомий op отримує «не вмію» ще до того, як щось почне працювати
(розд. 07 §8.2–8.3). Обробник віддає тіло відповіді, вже підрізане під бюджет
смуги; печатку ставить виконавець.

Світ (тривоги, присутність, розум, мережа) приходить через порти, тож
обробники можна перевірити без інтернету, а в продукті їх живить `ports.py`.
"""
from __future__ import annotations

import ipaddress
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Awaitable, Callable, Optional, Protocol
from urllib.parse import urlsplit

from .lanes import fit_text
from .wire import Intent, Status

logger = logging.getLogger(__name__)

__all__ = [
    "ALERT_TYPES",
    "AlertRow",
    "AlertsSnapshot",
    "Answer",
    "Context",
    "HANDLERS",
    "OBLAST_ORDER",
    "Page",
    "Ports",
    "Presence",
    "SourceSilent",
    "alerts",
    "ask",
    "page_gist",
    "refuse_url",
    "who_online",
]

#: Номер області в Намірі й біт у бітмапі відповіді. ЗАМОРОЖЕНО: телефон
#: тримає ту саму таблицю, тож нова область лише дописується в кінець.
OBLAST_ORDER: tuple[str, ...] = (
    "cherkasy", "chernihiv", "chernivtsi", "crimea", "dnipro", "donetsk",
    "ivano-frankivsk", "kharkiv", "kherson", "khmelnytskyi", "kirovohrad",
    "kyiv-city", "kyiv", "luhansk", "lviv", "mykolaiv", "odesa", "poltava",
    "rivne", "sevastopol", "sumy", "ternopil", "vinnytsia", "volyn",
    "zakarpattia", "zaporizhzhia", "zhytomyr",
)

#: Тип тривоги в 1 байті. 0 — тривоги в моїй області немає (джерело ЖИВЕ).
ALERT_TYPES = {
    "air_raid": 1,
    "artillery_shelling": 2,
    "urban_fights": 3,
    "chemical": 4,
    "nuclear": 5,
}
_ALERT_OTHER = 0x0F

#: Коди смуги, якою контакт востаннє приходив, і стану мого останнього листа
#: (0xFF — листів йому ще не було).
LANE_CODES = {"relay": 1, "lan": 2, "sms": 3, "ble": 4}
STATE_CODES = {"local": 0, "queued": 1, "sent": 2, "delivered": 3, "failed": 4}

#: Скільки знаків максимум просимо в розуму (§3.3.4: «≤ 600 знаків, без вступу»).
ASK_MAX_CHARS = 600
#: Межі сторінки для `page_gist` (§8.3).
PAGE_MAX_BYTES = 2 * 1024 * 1024
PAGE_TIMEOUT_S = 10.0


class SourceSilent(Exception):
    """Джерело не відповіло. Відповідь — «джерело мовчить», не «нічого немає»."""


@dataclass(frozen=True)
class AlertRow:
    oblast_id: str
    alert_type: str
    started_at: Optional[datetime]


@dataclass(frozen=True)
class AlertsSnapshot:
    alerts: tuple[AlertRow, ...]
    fetched_at: datetime


@dataclass(frozen=True)
class Presence:
    last_heard: Optional[datetime]
    lane: Optional[str]
    my_last_state: Optional[str]


@dataclass(frozen=True)
class Page:
    title: str
    text: str


class Ports(Protocol):
    async def alerts(self) -> AlertsSnapshot: ...

    async def presence(self, owner_user_id: str, tag: bytes, pair_key: bytes) -> Optional[Presence]: ...

    async def think(self, system: str, prompt: str) -> str: ...

    async def fetch(self, url: str) -> Page: ...

    async def siren(self) -> bytes: ...


@dataclass(frozen=True)
class Answer:
    status: int
    body: bytes = b""
    #: Тіло — текст UTF-8: вужча смуга може підрізати його по слову. Двійкове
    #: тіло (тривоги, присутність) мусить вміщатися в найвужчу смугу цілим.
    text: bool = False

    def plain(self, rung: int, budget: int) -> bytes:
        """Відкрита відповідь для щабля `rung` у `budget` байтів (з байтом стану)."""
        body = fit_text(self.body.decode("utf-8"), budget - 1) if self.text else self.body
        return bytes([((rung & 0xF) << 4) | (self.status & 0xF)]) + body


@dataclass(frozen=True)
class Context:
    owner_user_id: str
    pair_key: bytes
    budget: int  # байти тіла в найширшій дозволеній смузі, вже без байта стану
    now: datetime


Handler = Callable[[Intent, Context, Ports], Awaitable[Answer]]


def _minutes_since(then: Optional[datetime], now: datetime) -> int:
    if then is None:
        return 0xFFFF
    if then.tzinfo is None:
        then = then.replace(tzinfo=timezone.utc)
    return max(0, min(0xFFFE, int((now - then).total_seconds() // 60)))


# ── 0x01 тривоги моєї області ────────────────────────────────────────────────


async def alerts(intent: Intent, ctx: Context, ports: Ports) -> Answer:
    """Тіло (8 Б): бітмапа 27 областей 4 · тип у моїй 1 · хвилин від початку 2 · вік даних, с 1."""
    mine = intent.args[0] if intent.args else 0xFF
    try:
        snap = await ports.alerts()
    except SourceSilent as exc:
        logger.info("intents: тривоги — джерело мовчить: %s", exc)
        return Answer(Status.SOURCE_SILENT)
    bitmap = 0
    my_type, my_since = 0, 0xFFFF
    for row in snap.alerts:
        try:
            index = OBLAST_ORDER.index(row.oblast_id)
        except ValueError:
            continue
        bitmap |= 1 << index
        if index == mine:
            my_type = ALERT_TYPES.get(row.alert_type, _ALERT_OTHER)
            my_since = _minutes_since(row.started_at, ctx.now)
    fetched = snap.fetched_at if snap.fetched_at.tzinfo else snap.fetched_at.replace(tzinfo=timezone.utc)
    age = max(0, min(255, int((ctx.now - fetched).total_seconds())))
    body = bitmap.to_bytes(4, "big") + bytes([my_type]) + my_since.to_bytes(2, "big") + bytes([age])
    return Answer(Status.OK, body)


# ── 0x02 «чи на зв'язку X» ──────────────────────────────────────────────────


async def who_online(intent: Intent, ctx: Context, ports: Ports) -> Answer:
    """Тіло (4 Б): хвилин відтоді, як ПК чув контакт, 2 · смуга 1 · стан мого останнього листа 1.

    Батареї тут немає: ПК її не знає, а вигадувати — заборонено.
    """
    if len(intent.args) < 4:
        return Answer(Status.NOT_FOUND)
    found = await ports.presence(ctx.owner_user_id, intent.args[:4], ctx.pair_key)
    if found is None:
        return Answer(Status.NOT_FOUND)
    body = (
        _minutes_since(found.last_heard, ctx.now).to_bytes(2, "big")
        + bytes([LANE_CODES.get(found.lane or "", 0)])
        + bytes([STATE_CODES.get(found.my_last_state or "", 0xFF)])
    )
    return Answer(Status.OK, body)


# ── 0x04 питання PHANTOM ────────────────────────────────────────────────────


def _char_budget(budget: int) -> int:
    # Кирилиця — 2 Б на літеру в UTF-8: просимо стільки знаків, скільки влізе.
    return max(40, min(ASK_MAX_CHARS, budget // 2))


async def ask(intent: Intent, ctx: Context, ports: Ports) -> Answer:
    question = intent.args.decode("utf-8", errors="replace").strip()
    if not question:
        return Answer(Status.NOT_FOUND)
    chars = _char_budget(ctx.budget)
    system = (
        "Ти — PHANTOM. Людина питає без інтернету, відповідь поїде SMS. "
        f"Відповідай мовою питання, одразу по суті, без вступу й без markdown, не довше {chars} знаків."
    )
    try:
        text = await ports.think(system, question)
    except SourceSilent:
        return Answer(Status.SOURCE_SILENT)
    return Answer(Status.OK, fit_text(text, ctx.budget), text=True)


# ── 0x07 суть сторінки ──────────────────────────────────────────────────────


def refuse_url(url: str) -> Optional[str]:
    """Причина відмови або None. Лише http(s) і лише публічні адреси.

    ПК стоїть у домашній мережі: без цього Намір міг би попросити його
    «прочитати» роутер, NAS або сам вузол на 127.0.0.1.
    """
    try:
        parts = urlsplit(url)
    except ValueError:
        return "адреса не читається"
    if parts.scheme not in ("http", "https"):
        return "лише http і https"
    host = parts.hostname or ""
    if not host:
        return "немає хоста"
    if parts.username or parts.password:
        return "адреса з обліковими даними"
    if host == "localhost" or host.endswith(".local") or host.endswith(".localhost"):
        return "домашня мережа"
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return None
    if not ip.is_global:
        return "домашня мережа"
    return None


async def page_gist(intent: Intent, ctx: Context, ports: Ports) -> Answer:
    url = intent.args.decode("utf-8", errors="replace").strip()
    if not url:
        return Answer(Status.NOT_FOUND)
    if "://" not in url:
        url = "https://" + url
    if refuse_url(url):
        return Answer(Status.REFUSED)
    try:
        page = await ports.fetch(url)
    except SourceSilent:
        return Answer(Status.SOURCE_SILENT)
    if not page.text.strip():
        return Answer(Status.NOT_FOUND)
    chars = _char_budget(ctx.budget)
    system = (
        "Стисни сторінку до суті для людини без інтернету. Мовою сторінки, без вступу, "
        f"без markdown, не довше {chars} знаків. Лише те, що є на сторінці."
    )
    prompt = f"Заголовок: {page.title}\n\n{page.text[:12000]}"
    try:
        gist = await ports.think(system, prompt)
    except SourceSilent:
        # Розуму немає — чесний витяг: заголовок і початок тексту, без «переказу».
        gist = f"{page.title}. {page.text}" if page.title else page.text
    return Answer(Status.OK, fit_text(gist, ctx.budget), text=True)


# ── 0x0B «Сирена-маяк» ──────────────────────────────────────────────────────

SIREN_BYTES = 92


async def siren(intent: Intent, ctx: Context, ports: Ports) -> Answer:
    """Підписаний знімок тривог із сайту — як є, 92 Б.

    ПК його не перевіряє і не мусить: підробити підпис він не може, а
    телефон вірить лише закріпленому ключу й сам рахує вік (розд. 07 §10).
    """
    try:
        snap = await ports.siren()
    except SourceSilent:
        return Answer(Status.SOURCE_SILENT)
    if len(snap) != SIREN_BYTES:
        return Answer(Status.SOURCE_SILENT)
    return Answer(Status.OK, snap)


#: op → обробник. Те, чого тут немає, не виконується ніколи.
HANDLERS: dict[int, Handler] = {
    0x01: alerts,
    0x02: who_online,
    0x04: ask,
    0x07: page_gist,
    0x0B: siren,
}
