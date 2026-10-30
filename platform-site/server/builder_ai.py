"""K1 Builder AI: a description, a price list (CSV/XLSX) and photo names become a page manifest v2 draft.

The price list is read here, row by row, without a model. The description goes to the model of the `act`
profile (brain's profile map, never a name from the client), and every value it returns must be found in the
description; what is not found is dropped and its block stays empty for the creator to fill.
"""
from __future__ import annotations

import base64
import binascii
import csv
import io
import json
import re
import threading
import time
import zipfile
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Any
from xml.etree import ElementTree

import httpx
from fastapi import APIRouter, Header, Request
from pydantic import BaseModel, Field

KINDS = ("book", "shop", "classes", "hiring", "delivery")
LOOK = {"book": "studio", "classes": "studio", "shop": "shop", "delivery": "shop", "hiring": "journal"}
PROFILE = "act"
TEXT_MAX = 4000
TABLE_MAX = 512 * 1024
XLSX_PART_MAX = 4 * 1024 * 1024
ROWS_MAX = 120
PHOTOS_MAX = 60
PER_DAY = 30
IP_LIMIT, IP_WINDOW = 10, 600.0

DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
EVERY_DAY = re.compile(r"щодня|щоденно|без вихідних|daily|every day|7 days|denně|každý den", re.I)
DAY_WORDS = {
    "mon": ("пн", "понеділ", "mon", "pondělí", "po"), "tue": ("вт", "вівтор", "tue", "úterý", "út"),
    "wed": ("ср", "серед", "wed", "středa", "st"), "thu": ("чт", "четвер", "thu", "čtvrtek", "čt"),
    "fri": ("пт", "пʼятниц", "п'ятниц", "fri", "pátek", "pá"), "sat": ("сб", "субот", "sat", "sobota", "so"),
    "sun": ("нд", "неділ", "sun", "neděle", "ne"),
}
COUNT_WORDS = {
    1: ("один", "одна", "one", "jeden", "jedna"), 2: ("двоє", "два", "дві", "two", "dva", "dvě"),
    3: ("троє", "три", "three", "tři"), 4: ("четверо", "чотири", "four", "čtyři"), 5: ("пʼятеро", "п'ятеро", "пʼять", "п'ять", "five", "pět"),
    6: ("шестеро", "шість", "six", "šest"), 7: ("семеро", "сім", "seven", "sedm"), 8: ("восьмеро", "вісім", "eight", "osm"),
    9: ("девʼятеро", "девʼять", "nine", "devět"), 10: ("десятеро", "десять", "ten", "deset"),
}
TOKEN_OF = (
    (re.compile(r"\bEURC\b", re.I), "EURC"), (re.compile(r"\bUSDC\b", re.I), "USDC"), (re.compile(r"\bSOL\b", re.I), "SOL"),
    (re.compile(r"€|\bEUR\b|\bєвро\b|\beur\b", re.I), "EURC"), (re.compile(r"\$|\bUSD\b|долар", re.I), "USDC"),
)
FOREIGN = ((re.compile(r"грн|₴|\bUAH\b", re.I), "UAH"), (re.compile(r"Kč|\bCZK\b", re.I), "CZK"),
           (re.compile(r"zł|\bPLN\b", re.I), "PLN"), (re.compile(r"£|\bGBP\b", re.I), "GBP"))
HEAD = {
    "name": ("назва", "послуга", "товар", "позиція", "name", "service", "product", "item", "název", "služba", "zboží"),
    "price": ("ціна", "вартість", "price", "cost", "cena"),
    "duration": ("тривалість", "час", "хв", "duration", "min", "délka", "minut"),
    "stock": ("залишок", "кількість", "наявність", "stock", "qty", "quantity", "sklad", "počet"),
    "photo": ("фото", "photo", "image", "picture", "foto", "obrázek"),
}

SYSTEM = (
    "You read a short business description written by its owner and return JSON with only the facts it states. "
    "Copy names, the address, the phone and staff names exactly as written. Never guess, never fill a gap, never "
    "translate: a field the text does not state is left out. hours: opening ranges as 24h HH:MM with the days they "
    "apply to (mon..sun), days left empty when the text names none. deposit_percent and cancel_hours only when the "
    "text gives them as a percent or in hours. services: only services named in the text with their price as written."
)
SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "name": {"type": "STRING"}, "address": {"type": "STRING"}, "phone": {"type": "STRING"}, "email": {"type": "STRING"},
        "staff": {"type": "ARRAY", "items": {"type": "STRING"}}, "staff_count": {"type": "INTEGER"},
        "hours": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "days": {"type": "ARRAY", "items": {"type": "STRING"}}, "open": {"type": "STRING"}, "close": {"type": "STRING"}}}},
        "deposit_percent": {"type": "INTEGER"}, "cancel_hours": {"type": "INTEGER"},
        "services": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "name": {"type": "STRING"}, "price": {"type": "STRING"}, "duration_min": {"type": "INTEGER"}}}},
    },
}


MESSAGES = {reason: f"venues.refusal.{reason}" for reason in ("bad_kind", "no_input", "bad_table", "table_too_big")}


class Refused(Exception):
    def __init__(self, reason: str, at: str = ""):
        super().__init__(reason)
        self.reason, self.at = reason, at


class TableIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    b64: str = Field(min_length=1, max_length=TABLE_MAX * 4 // 3 + 8)


class DraftAsk(BaseModel):
    kind: str
    lang: str = Field(default="uk", max_length=5)
    text: str = Field(default="", max_length=TEXT_MAX)
    table: TableIn | None = None
    photos: list[str] = Field(default_factory=list, max_length=PHOTOS_MAX)


# ── reading the price list ─────────────────────────────────────────────────────

def _xlsx_rows(raw: bytes) -> list[list[str]]:
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
        if any(i.file_size > XLSX_PART_MAX for i in z.infolist()):
            raise Refused("bad_table", "table")
        names = z.namelist()
        sheets = sorted(n for n in names if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", n))
        if not sheets:
            raise Refused("bad_table", "table")
        ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
        shared: list[str] = []
        if "xl/sharedStrings.xml" in names:
            for si in ElementTree.fromstring(z.read("xl/sharedStrings.xml")).iter(f"{ns}si"):
                shared.append("".join(t.text or "" for t in si.iter(f"{ns}t")))
        sheet = sheets[0] if "xl/worksheets/sheet1.xml" not in names else "xl/worksheets/sheet1.xml"
        rows: list[list[str]] = []
        for row in ElementTree.fromstring(z.read(sheet)).iter(f"{ns}row"):
            cells: dict[int, str] = {}
            for c in row.iter(f"{ns}c"):
                col = 0
                for ch in re.match(r"[A-Z]+", c.get("r", "A")).group(0):
                    col = col * 26 + ord(ch) - 64
                v = c.find(f"{ns}v")
                if c.get("t") == "s" and v is not None and (v.text or "").isdigit() and int(v.text) < len(shared):
                    cells[col - 1] = shared[int(v.text)]
                elif c.get("t") == "inlineStr":
                    cells[col - 1] = "".join(t.text or "" for t in c.iter(f"{ns}t"))
                elif v is not None:
                    cells[col - 1] = v.text or ""
            if cells:
                rows.append([cells.get(i, "") for i in range(max(cells) + 1)])
            if len(rows) > ROWS_MAX:
                break
        return rows
    except (zipfile.BadZipFile, ElementTree.ParseError, KeyError, ValueError, AttributeError):
        raise Refused("bad_table", "table")


def table_rows(name: str, b64: str) -> list[list[str]]:
    try:
        raw = base64.b64decode(b64, validate=True)
    except (binascii.Error, ValueError):
        raise Refused("bad_table", "table")
    if len(raw) > TABLE_MAX:
        raise Refused("table_too_big", "table")
    low = name.lower()
    if low.endswith(".xlsx"):
        return _xlsx_rows(raw)
    if not (low.endswith(".csv") or low.endswith(".tsv") or low.endswith(".txt")):
        raise Refused("bad_table", "table")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise Refused("bad_table", "table")
    first = text.split("\n", 1)[0]
    delim = max(("\t", ";", ","), key=first.count)
    rows = [r for r in csv.reader(io.StringIO(text), delimiter=delim) if any(c.strip() for c in r)]
    return rows[: ROWS_MAX + 1]


def _num(s: str) -> str | None:
    m = re.search(r"\d+(?:[.,]\d+)?", s.replace(" ", "").replace(" ", ""))
    if not m:
        return None
    n = m.group(0).replace(",", ".")
    if "." in n:
        n = n.rstrip("0").rstrip(".")
    return n if n not in ("", "0") else None


def _currency(*texts: str) -> tuple[str | None, str | None]:
    """(token, foreign currency): a page only takes USDC, EURC or SOL; another currency is named, not converted."""
    for t in texts:
        for rx, tok in TOKEN_OF:
            if rx.search(t):
                return tok, None
        for rx, cur in FOREIGN:
            if rx.search(t):
                return None, cur
    return None, None


def catalog(rows: list[list[str]]) -> tuple[list[dict], dict]:
    cols: dict[str, int] = {}
    head = [c.strip().lower() for c in rows[0]] if rows else []
    for key, words in HEAD.items():
        for i, h in enumerate(head):
            if i not in cols.values() and any(h.startswith(w) or w in h.split() for w in words):
                cols[key] = i
                break
    if "name" in cols:
        body = rows[1:]
        head_price = rows[0][cols["price"]] if "price" in cols else ""
    else:
        cols = {"name": 0, "price": 1} if rows and len(rows[0]) > 1 else {"name": 0}
        body, head_price = rows, ""
    items, unpriced, foreign = [], 0, []
    cell = lambda r, k: r[cols[k]].strip() if k in cols and cols[k] < len(r) else ""  # noqa: E731
    for r in body[:ROWS_MAX]:
        name = cell(r, "name")[:80]
        if not name:
            continue
        item: dict[str, Any] = {"name": name}
        raw_price = cell(r, "price")
        token, other = _currency(raw_price, head_price)
        amount = _num(raw_price)
        item["_token"] = token
        item["price"] = amount if amount and token else ""
        if raw_price and not item["price"]:
            unpriced += 1
            item["_was"] = raw_price[:40]
            if other and other not in foreign:
                foreign.append(other)
        dur = _num(cell(r, "duration"))
        if dur and dur.isdigit() and 5 <= int(dur) <= 1440 and int(dur) % 5 == 0:
            item["duration"] = int(dur)
        stock = _num(cell(r, "stock")) if "stock" in cols else None
        if "stock" in cols:
            raw_stock = cell(r, "stock")
            item["stock"] = int(stock) if stock and stock.isdigit() else (0 if raw_stock.strip() == "0" else None)
        if cell(r, "photo"):
            item["_photo"] = cell(r, "photo")
        items.append(item)
    return items, {"rows": len(items), "unpriced": unpriced, "currencies": foreign}


# ── reading the description ───────────────────────────────────────────────────

def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("’", "ʼ").replace("'", "ʼ")).strip().lower()


def _numbers(text: str) -> set[int]:
    return {int(n) for n in re.findall(r"\d+", text)}


def grounded(facts: Any, text: str) -> dict:
    """Keep only what the description states; the rest is dropped, not repaired."""
    if not isinstance(facts, dict):
        return {}
    said, nums, out = _norm(text), _numbers(text), {}
    found = lambda v: isinstance(v, str) and v.strip() and _norm(v) in said  # noqa: E731
    for key, limit in (("name", 80), ("address", 200)):
        if found(facts.get(key)):
            out[key] = facts[key].strip()[:limit]
    phone = facts.get("phone")
    if isinstance(phone, str):
        digits = re.sub(r"[^\d+]", "", phone)
        if re.fullmatch(r"\+[1-9]\d{6,14}", digits) and digits[1:] in re.sub(r"\D", "", text):
            out["phone"] = digits
    email = facts.get("email")
    if found(email) and re.fullmatch(r"[^@\s]+@[^@\s]+\.[a-z]{2,}", email.strip(), re.I):
        out["email"] = email.strip()
    staff = [s.strip()[:40] for s in facts.get("staff") or [] if found(s)]
    count = facts.get("staff_count")
    if isinstance(count, int) and 0 < count <= 30 and (count in nums or any(re.search(rf"(?<!\w){w}(?!\w)", said) for w in COUNT_WORDS.get(count, ()))):
        out["staff_count"] = count
    if staff:
        out["staff"] = staff[: out.get("staff_count", len(staff))]
    hours = []
    for h in facts.get("hours") or []:
        if not isinstance(h, dict):
            continue
        o, c = str(h.get("open", "")), str(h.get("close", ""))
        if not (re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", o) and re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", c)):
            continue
        if int(o[:2]) not in nums or int(c[:2]) not in nums:
            continue
        days = [d for d in h.get("days") or [] if d in DAYS]
        if EVERY_DAY.search(text):
            days = list(DAYS) if len(days) in (0, 7) else days
        named = [d for d in days if any(re.search(rf"(?<!\w){re.escape(w)}", said) for w in DAY_WORDS[d])]
        if not EVERY_DAY.search(text) and named != days:
            days = []
        hours.append({"days": days, "hours": {"open": o, "close": c}})
    if hours:
        out["hours"] = hours[:14]
    for key, hi in (("deposit_percent", 100), ("cancel_hours", 168)):
        v = facts.get(key)
        if isinstance(v, int) and 0 < v <= hi and v in nums and (key != "deposit_percent" or re.search(rf"{v}\s*%", text)):
            out[key] = v
    services = []
    for s in facts.get("services") or []:
        if isinstance(s, dict) and found(s.get("name")):
            price = str(s.get("price") or "")
            amount = _num(price)
            token, _ = _currency(price)
            ok_price = amount if amount and token and int(float(amount)) in nums else ""
            row = {"name": s["name"].strip()[:80], "price": ok_price, "_token": token if ok_price else None}
            d = s.get("duration_min")
            if isinstance(d, int) and d in nums and 5 <= d <= 1440 and d % 5 == 0:
                row["duration"] = d
            services.append(row)
    if services:
        out["services"] = services
    return out


async def ask_model(brain, text: str) -> tuple[str, dict]:
    """('read'|'off'|'failed', facts). The model is the `act` profile's, as the server maps it today."""
    key, vertex = brain._key(), brain._vertex()
    if not key and not vertex:
        return "off", {}
    model = brain._profile_map().get(PROFILE) or brain.PROFILE_DEFAULTS[PROFILE]
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM}]},
        "contents": [{"role": "user", "parts": [{"text": text}]}],
        "generationConfig": {"responseMimeType": "application/json", "responseSchema": SCHEMA,
                             "temperature": 0, "maxOutputTokens": brain.MAX_OUTPUT_TOKENS},
    }
    try:
        if vertex:
            project, location = vertex
            host = "aiplatform.googleapis.com" if location == "global" else f"{location}-aiplatform.googleapis.com"
            url = brain.VERTEX_UPSTREAM.format(host=host, project=project, location=location,
                                               model=brain.VERTEX_MODEL_MAP.get(model, model))
            headers = {"content-type": "application/json", "authorization": f"Bearer {await brain._bearer(location)}"}
        else:
            url = brain.UPSTREAM.format(model=model)
            headers = {"content-type": "application/json", "x-goog-api-key": key}
        url = url.replace(":streamGenerateContent?alt=sse", ":generateContent")
        async with httpx.AsyncClient(transport=brain.transport, timeout=brain.TIMEOUT, follow_redirects=False) as client:
            reply = await client.post(url, content=json.dumps(body, ensure_ascii=False).encode(), headers=headers)
        if reply.status_code != 200:
            brain.log.warning("builder ai: upstream status=%d", reply.status_code)
            return "failed", {}
        parts = reply.json()["candidates"][0]["content"]["parts"]
        return "read", json.loads("".join(p.get("text", "") for p in parts))
    except Exception as failure:  # noqa: BLE001 — any upstream fault is an honest "failed", never a guess
        brain.log.warning("builder ai: upstream failed %s", type(failure).__name__)
        return "failed", {}


# ── the page ──────────────────────────────────────────────────────────────────

def page_of(kind: str, text: str, facts: dict, items: list[dict], photos: list[str]) -> tuple[dict, dict]:
    blocks: list[dict] = []
    def add(k: str, data: dict, variant: str | None = None) -> str:
        bid = f"{k.split('.')[1].replace('_', '-')}-{sum(1 for b in blocks if b['kind'] == k) + 1}"
        blocks.append({"id": bid, "kind": k, **({"variant": variant} if variant else {}), "hidden": False, "data": data})
        return bid
    given = set(photos)
    linked = 0
    rows = items or facts.get("services", [])
    tokens = [i["_token"] for i in rows if i.get("_token")]
    token = tokens[0] if tokens else "USDC"
    def item(i: dict, n: int, prefix: str, extra: dict) -> dict:
        nonlocal linked
        price = i["price"] if i.get("_token") == token else ""
        out = {"id": f"{prefix}-{n}", "name": i["name"], "price": price, **extra}
        if not price and (i.get("_was") or (i.get("_token") and i.get("price"))):
            out["was"] = i.get("_was") or f"{i['price']} {i['_token']}"
        if i.get("_photo") in given:
            out["photo"] = i["_photo"]
            linked += 1
        return out

    add("id.name", {"name": facts.get("name", "")}, "plain")
    if text.strip():
        add("id.about", {"text": text.strip()[:1000]}, "paragraph")
    if kind in ("book", "classes"):
        svc = [item(i, n, "svc", {k: i[k] for k in ("duration",) if k in i}) for n, i in enumerate(rows, 1)]
        sid = add("cat.services", {"token": token, "items": svc or [{"id": "svc-1", "name": "", "price": ""}]}, "list")
        durs = [s["duration"] for s in svc if "duration" in s]
        slots = {"services": sid, "buffer": 0, "lead": 0, "horizon": 30, "auto": False}
        if durs:
            slots["slot"] = min(durs)
        add("book.slots", slots, "day_strip")
    elif kind in ("shop", "delivery"):
        prod = [item(i, n, "item", {"stock": i["stock"]} if i.get("stock") is not None else {}) for n, i in enumerate(rows, 1)]
        add("cat.products", {"title": "", "token": token, "items": prod or [{"id": "item-1", "name": "", "price": ""}]}, "list")
        if kind == "delivery":
            add("loc.service_area", {"token": token, "items": [{"id": "zone-1", "area": "", "fee": ""}]}, "list")
    if kind == "hiring":
        add("org.join", {"title": "", "approve": "owner"}, "card")
    staff = facts.get("staff") or []
    count = max(len(staff), facts.get("staff_count", 0))
    if count:
        add("book.staff", {"items": [{"id": f"staff-{n}", "name": staff[n - 1] if n <= len(staff) else ""}
                                     for n in range(1, count + 1)]}, "faces")
    if kind == "book" and ("deposit_percent" in facts or "cancel_hours" in facts):
        add("book.policy", {"cancel": facts.get("cancel_hours", 0),
                            **({"deposit": facts["deposit_percent"]} if "deposit_percent" in facts else {})}, "rows")
    if facts.get("hours"):
        add("hrs.week", {"rows": facts["hours"]}, "ranges")
    add("loc.address", {"text": facts.get("address", "")}, "map_line")
    contact = {k: facts[k] for k in ("phone", "email") if k in facts}
    if contact:
        add("loc.contact", contact, "rows")
    add("loc.message", {"on": True, "who": "owner"}, "card")
    return {"v": 2, "look": LOOK[kind], "tone": "amber", "blocks": blocks}, {"given": len(photos), "linked": linked}


def make_router(venues, brain) -> APIRouter:
    router = APIRouter()
    lock = threading.Lock()
    used: dict[tuple[str, str], int] = defaultdict(int)
    ip_buckets: dict[str, deque[float]] = defaultdict(deque)

    for reason, words in MESSAGES.items():
        venues.REFUSALS.setdefault(reason, words)
    refusal = venues.refusal

    @router.post("/venues/ai-draft")
    async def ai_draft(body: DraftAsk, request: Request, authorization: str = Header(default="")):
        owner = venues._owner(authorization)
        venues._gate(request, ip_buckets, IP_LIMIT, IP_WINDOW)
        if body.kind not in KINDS:
            return refusal("bad_kind", "kind")
        text = body.text.strip()
        if not text and body.table is None:
            return refusal("no_input", "text")
        photos = [p.strip()[:120] for p in body.photos if isinstance(p, str) and p.strip()]
        try:
            rows = table_rows(body.table.name, body.table.b64) if body.table else []
            items, table = catalog(rows) if rows else ([], {"rows": 0, "unpriced": 0, "currencies": []})
        except Refused as r:
            return refusal(r.reason, r.at)
        assistant, facts = "skipped", {}
        if text:
            day = datetime.now(timezone.utc).date().isoformat()
            with lock:
                for k in [k for k in used if k[1] != day]:
                    del used[k]
                over = used[(owner, day)] >= PER_DAY
                if not over:
                    used[(owner, day)] += 1
            if over:
                assistant = "limit"
            else:
                started = time.monotonic()
                assistant, raw = await ask_model(brain, text)
                facts = grounded(raw, text)
                brain.log.warning("builder ai: draft assistant=%s kind=%s ms=%d", assistant, body.kind,
                                  int((time.monotonic() - started) * 1000))
        page, pics = page_of(body.kind, text, facts, items, photos)
        return {"page": page, "assistant": assistant, "table": table, "photos": pics}

    return router
