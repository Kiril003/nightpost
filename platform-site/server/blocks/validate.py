"""Generic manifest v2 validator driven by the block registry."""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections import defaultdict
from datetime import date
from decimal import Decimal
from typing import Any, Callable
from urllib.parse import urlparse

import h3cell
import solana_pay
import venues

_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,36}")
_VENUE_REF_RE = re.compile(r"[A-Za-z0-9_-]{1,64}")
_BLOB_RE = re.compile(r"[A-Za-z0-9_-]{8,64}")
_CONTACT_RE = re.compile(r"[A-Za-z0-9_+/=-]{16,128}")
_HHMM_RE = re.compile(r"([01]\d|2[0-3]):[0-5]\d")
_DATE_RE = re.compile(r"\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])")
_DATETIME_RE = re.compile(r"\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):[0-5]\d")
_PHONE_RE = re.compile(r"\+[1-9]\d{6,14}")
_EMAIL_RE = re.compile(r"[^\s@]+@[^\s@]+\.[^\s@]+")
_MD_LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


class _Ctx:
    def __init__(self, registry: dict, block_data: dict[str, Any], records: dict[str, dict]):
        self.registry = registry
        self.block_data = block_data
        self.records = records
        self.item_ids: set[str] = set()
        self.pending_refs: list[tuple[str, dict, str]] = []


def _refusal_for(fspec: dict, pspec: dict, default: str) -> str:
    return fspec.get("refusal") or pspec.get("refusal") or default


def _check_clean_str(value: Any, max_len: int, at: str, *, multiline: bool = False, refusal: str = "bad_text") -> str:
    if not isinstance(value, str):
        raise venues.Refused(refusal, at)
    text = value.strip()
    bad = any(
        unicodedata.category(c) in ("Cc", "Cf") and not (multiline and c == "\n")
        for c in text
    )
    if not text or len(text) > max_len or bad:
        raise venues.Refused(refusal, at)
    return text


def _check_https_url(url: Any, max_len: int, at: str, refusal: str) -> str:
    if not isinstance(url, str) or len(url) > max_len or any(unicodedata.category(c) in ("Cc", "Cf") for c in url):
        raise venues.Refused(refusal, at)
    if not url.startswith("https://"):
        raise venues.Refused(refusal, at)
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password or " " in url:
        raise venues.Refused(refusal, at)
    return url


def _valid_calendar_date(s: str, at: str, refusal: str) -> date:
    if not isinstance(s, str) or not _DATE_RE.fullmatch(s):
        raise venues.Refused(refusal, at)
    y, m, d = int(s[0:4]), int(s[5:7]), int(s[8:10])
    if y < 1:
        raise venues.Refused(refusal, at)
    try:
        return date(y, m, d)
    except ValueError:
        raise venues.Refused(refusal, at) from None


def _validate_text(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_text")
    max_len = int(fspec.get("max", pspec.get("max", 200)))
    if ref == "bad_item_id":
        if not isinstance(val, str) or not _ID_RE.fullmatch(val):
            raise venues.Refused("bad_item_id", at)
        return val
    return _check_clean_str(val, max_len, at, multiline=False, refusal=ref)


def _validate_richtext(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_text")
    max_len = int(fspec.get("max", pspec.get("max", 4000)))
    text = _check_clean_str(val, max_len, at, multiline=True, refusal=ref)
    for m in _MD_LINK_RE.finditer(text):
        _check_https_url(m.group(1).strip(), 300, at, ref)
    return text


def _validate_number(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> int | str | float:
    ref = _refusal_for(fspec, pspec, "bad_number")
    want_int = bool(fspec.get("int", False))
    if isinstance(val, bool):
        raise venues.Refused(ref, at)
    if want_int:
        if not isinstance(val, int):
            raise venues.Refused(ref, at)
        num = Decimal(val)
    elif isinstance(val, int):
        num = Decimal(val)
    elif isinstance(val, str):
        if not re.fullmatch(r"-?(0|[1-9]\d*)(\.\d+)?", val):
            raise venues.Refused(ref, at)
        num = Decimal(val)
    elif isinstance(val, float):
        try:
            num = Decimal(str(val))
        except Exception:
            raise venues.Refused(ref, at) from None
    else:
        raise venues.Refused(ref, at)

    min_v = fspec.get("min", pspec.get("min"))
    max_v = fspec.get("max", pspec.get("max"))
    step_v = fspec.get("step", pspec.get("step"))
    if min_v is not None and num < Decimal(str(min_v)):
        raise venues.Refused(ref, at)
    if max_v is not None and num > Decimal(str(max_v)):
        raise venues.Refused(ref, at)
    if step_v is not None:
        base = Decimal(str(min_v)) if min_v is not None else Decimal(0)
        if (num - base) % Decimal(str(step_v)) != 0:
            raise venues.Refused(ref, at)
    return val


def _validate_percent(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> int:
    ref = _refusal_for(fspec, pspec, "bad_percent")
    if isinstance(val, bool) or not isinstance(val, int) or not 0 <= val <= 100:
        raise venues.Refused(ref, at)
    step = int(fspec.get("step", pspec.get("step", 1)))
    if step not in (1, 5) or val % step != 0:
        raise venues.Refused(ref, at)
    return val


def _check_amount_str(amount: Any, decimals: int, at: str, refusal: str) -> str:
    pattern = rf"(0|[1-9]\d{{0,8}})(\.\d{{1,{decimals}}})?"
    if not isinstance(amount, str) or not re.fullmatch(pattern, amount) or Decimal(amount) <= 0:
        raise venues.Refused(refusal, at)
    return amount


def _validate_money(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str | dict:
    ref = _refusal_for(fspec, pspec, "bad_price")
    decimals_map = pspec.get("decimals", {"USDC": 6, "SOL": 9, "EURC": 6})
    if fspec.get("token") == "$token":
        parent_tok = ctx.block_data.get("token")
        if not isinstance(parent_tok, str) or parent_tok not in decimals_map:
            raise venues.Refused("bad_token", at)
        if isinstance(val, str):
            return _check_amount_str(val, decimals_map[parent_tok], at, ref)
        if isinstance(val, dict):
            if set(val.keys()) != {"amount", "token"}:
                raise venues.Refused("unknown_field" if set(val.keys()) - {"amount", "token"} else "missing_field", at)
            if val["token"] != parent_tok:
                raise venues.Refused("mixed_tokens", f"{at}.token")
            _check_amount_str(val["amount"], decimals_map[parent_tok], f"{at}.amount", ref)
            return {"amount": val["amount"], "token": val["token"]}
        raise venues.Refused(ref, at)

    if not isinstance(val, dict):
        raise venues.Refused(ref, at)
    extra = set(val.keys()) - {"amount", "token"}
    if extra:
        raise venues.Refused("unknown_field", f"{at}.{sorted(extra)[0]}")
    missing = {"amount", "token"} - set(val.keys())
    if missing:
        raise venues.Refused("missing_field", f"{at}.{sorted(missing)[0]}")
    tok = val["token"]
    if not isinstance(tok, str) or tok not in decimals_map:
        raise venues.Refused("bad_token", f"{at}.token")
    amt = _check_amount_str(val["amount"], decimals_map[tok], f"{at}.amount", ref)
    return {"amount": amt, "token": tok}


def _validate_token(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_token")
    allowed = ctx.registry["vocab"].get("token", ["USDC", "SOL", "EURC"])
    if not isinstance(val, str) or val not in allowed:
        raise venues.Refused(ref, at)
    return val


def _validate_time(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_time")
    if not isinstance(val, str) or not _HHMM_RE.fullmatch(val):
        raise venues.Refused(ref, at)
    return val


def _validate_timerange(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_timerange")
    if not isinstance(val, dict) or set(val.keys()) != {"open", "close"}:
        raise venues.Refused(ref, at)
    o, c = val["open"], val["close"]
    if not (isinstance(o, str) and _HHMM_RE.fullmatch(o) and isinstance(c, str) and _HHMM_RE.fullmatch(c)):
        raise venues.Refused(ref, at)
    return {"open": o, "close": c}


def _validate_duration(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> int:
    ref = _refusal_for(fspec, pspec, "bad_duration")
    min_d = int(fspec.get("min", pspec.get("min", 5)))
    max_d = int(fspec.get("max", pspec.get("max", 1440)))
    step = int(fspec.get("step", pspec.get("step", 5)))
    if isinstance(val, bool) or not isinstance(val, int) or not (min_d <= val <= max_d) or val % step != 0:
        raise venues.Refused(ref, at)
    return val


def _validate_date(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_date")
    _valid_calendar_date(val, at, ref)
    return val


def _validate_daterange(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_daterange")
    if not isinstance(val, dict) or set(val.keys()) != {"from", "to"}:
        raise venues.Refused(ref, at)
    d_from = _valid_calendar_date(val["from"], f"{at}.from", ref)
    d_to = _valid_calendar_date(val["to"], f"{at}.to", ref)
    max_days = int(fspec.get("max_days", pspec.get("max_days", 366)))
    delta = (d_to - d_from).days
    if delta < 0 or delta > max_days:
        raise venues.Refused(ref, at)
    return {"from": val["from"], "to": val["to"]}


def _validate_datetime(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_datetime")
    if not isinstance(val, str) or not _DATETIME_RE.fullmatch(val):
        raise venues.Refused(ref, at)
    _valid_calendar_date(val[:10], at, ref)
    return val


def _validate_dayset(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> list[str]:
    ref = _refusal_for(fspec, pspec, "bad_dayset")
    days_vocab = ctx.registry["vocab"].get("day", ["mon", "tue", "wed", "thu", "fri", "sat", "sun"])
    if not isinstance(val, list) or not (1 <= len(val) <= 7):
        raise venues.Refused(ref, at)
    if len(set(val)) != len(val) or any(not isinstance(d, str) or d not in days_vocab for d in val):
        raise venues.Refused(ref, at)
    return list(val)


def _validate_recurrence(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_recurrence")
    if not isinstance(val, dict):
        raise venues.Refused(ref, at)
    allowed_keys = {"freq", "days", "nth", "until", "count", "skip"}
    if set(val.keys()) - allowed_keys or "freq" not in val:
        raise venues.Refused(ref, at)
    freq = val["freq"]
    if freq not in ("weekly", "monthly"):
        raise venues.Refused(ref, at)
    max_occ = int(pspec.get("max_occurrences", 52))
    out: dict[str, Any] = {"freq": freq}
    if "days" in val:
        out["days"] = _validate_dayset(val["days"], {}, ctx.registry["primitives"]["dayset"], ctx, f"{at}.days")
    if "nth" in val:
        nth = val["nth"]
        if isinstance(nth, bool) or not isinstance(nth, int) or nth not in (1, 2, 3, 4, -1):
            raise venues.Refused(ref, f"{at}.nth")
        out["nth"] = nth
    if freq == "weekly" and "days" not in out:
        raise venues.Refused(ref, at)
    if freq == "monthly" and "nth" not in out and "days" not in out:
        raise venues.Refused(ref, at)
    if "until" in val:
        _valid_calendar_date(val["until"], f"{at}.until", ref)
        out["until"] = val["until"]
    if "count" in val:
        cnt = val["count"]
        if isinstance(cnt, bool) or not isinstance(cnt, int) or not (1 <= cnt <= max_occ):
            raise venues.Refused(ref, f"{at}.count")
        out["count"] = cnt
    if "until" not in out and "count" not in out:
        raise venues.Refused(ref, at)
    if "skip" in val:
        skips = val["skip"]
        if not isinstance(skips, list) or len(skips) > max_occ:
            raise venues.Refused(ref, f"{at}.skip")
        clean_skips = []
        for idx, s in enumerate(skips):
            d = _valid_calendar_date(s, f"{at}.skip[{idx}]", ref)
            if "until" in out and s > out["until"]:
                raise venues.Refused(ref, f"{at}.skip[{idx}]")
            if "days" in out:
                wday = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"][d.weekday()]
                if wday not in out["days"]:
                    raise venues.Refused(ref, f"{at}.skip[{idx}]")
            clean_skips.append(s)
        out["skip"] = clean_skips
    return out


def _validate_image(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_image")
    if not isinstance(val, dict):
        raise venues.Refused(ref, at)
    allowed = {"blob", "w", "h", "alt", "focus", "bytes"}
    if set(val.keys()) - allowed or not {"blob", "w", "h", "alt"} <= set(val.keys()):
        raise venues.Refused(ref, at)
    blob = val["blob"]
    if not isinstance(blob, str) or not _BLOB_RE.fullmatch(blob):
        raise venues.Refused(ref, f"{at}.blob")
    w, h = val["w"], val["h"]
    if any(isinstance(x, bool) or not isinstance(x, int) or not (1 <= x <= 16384) for x in (w, h)):
        raise venues.Refused(ref, at)
    alt = val["alt"]
    if not isinstance(alt, str) or len(alt) > int(pspec.get("max_alt", 120)):
        raise venues.Refused(ref, f"{at}.alt")
    if any(unicodedata.category(c) in ("Cc", "Cf") for c in alt):
        raise venues.Refused(ref, f"{at}.alt")
    out: dict[str, Any] = {"blob": blob, "w": w, "h": h, "alt": alt.strip()}
    if "focus" in val:
        foc = val["focus"]
        if not isinstance(foc, dict) or set(foc.keys()) != {"x", "y"}:
            raise venues.Refused(ref, f"{at}.focus")
        for k in ("x", "y"):
            if isinstance(foc[k], bool) or not isinstance(foc[k], (int, float)) or not (0 <= float(foc[k]) <= 1):
                raise venues.Refused(ref, f"{at}.focus.{k}")
        out["focus"] = {"x": foc["x"], "y": foc["y"]}
    if "bytes" in val:
        b = val["bytes"]
        max_b = int(pspec.get("max_bytes", 8_388_608))
        if isinstance(b, bool) or not isinstance(b, int) or not (1 <= b <= max_b):
            raise venues.Refused(ref, f"{at}.bytes")
        out["bytes"] = b
    return out


def _validate_gallery(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> list[dict]:
    ref = _refusal_for(fspec, pspec, "bad_gallery")
    min_i = int(fspec.get("min", pspec.get("min", 1)))
    max_i = int(fspec.get("max", pspec.get("max", 24)))
    if not isinstance(val, list) or not (min_i <= len(val) <= max_i):
        raise venues.Refused(ref, at)
    img_pspec = ctx.registry["primitives"]["image"]
    return [_validate_image(item, {}, img_pspec, ctx, f"{at}[{i}]") for i, item in enumerate(val)]


def _validate_video(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_video")
    if not isinstance(val, dict):
        raise venues.Refused(ref, at)
    allowed = {"blob", "poster", "secs", "bytes"}
    if set(val.keys()) - allowed or not {"blob", "poster", "secs"} <= set(val.keys()):
        raise venues.Refused(ref, at)
    blob = val["blob"]
    if not isinstance(blob, str) or not _BLOB_RE.fullmatch(blob):
        raise venues.Refused(ref, f"{at}.blob")
    secs = val["secs"]
    max_secs = int(pspec.get("max_secs", 60))
    if isinstance(secs, bool) or not isinstance(secs, (int, float)) or not (0 < float(secs) <= max_secs):
        raise venues.Refused(ref, f"{at}.secs")
    poster = _validate_image(val["poster"], {"refusal": ref}, ctx.registry["primitives"]["image"], ctx, f"{at}.poster")
    out: dict[str, Any] = {"blob": blob, "poster": poster, "secs": secs}
    if "bytes" in val:
        b = val["bytes"]
        max_b = int(pspec.get("max_bytes", 41_943_040))
        if isinstance(b, bool) or not isinstance(b, int) or not (1 <= b <= max_b):
            raise venues.Refused(ref, f"{at}.bytes")
        out["bytes"] = b
    return out


def _validate_audio(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_audio")
    if not isinstance(val, dict) or set(val.keys()) != {"blob", "secs", "title"}:
        raise venues.Refused(ref, at)
    blob = val["blob"]
    if not isinstance(blob, str) or not _BLOB_RE.fullmatch(blob):
        raise venues.Refused(ref, f"{at}.blob")
    secs = val["secs"]
    max_secs = int(pspec.get("max_secs", 600))
    if isinstance(secs, bool) or not isinstance(secs, (int, float)) or not (0 < float(secs) <= max_secs):
        raise venues.Refused(ref, f"{at}.secs")
    title = _check_clean_str(val["title"], int(pspec.get("max_title", 80)), f"{at}.title", refusal=ref)
    return {"blob": blob, "secs": secs, "title": title}


def _validate_file(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_file")
    if not isinstance(val, dict) or set(val.keys()) != {"blob", "name", "mime", "bytes"}:
        raise venues.Refused(ref, at)
    blob = val["blob"]
    if not isinstance(blob, str) or not _BLOB_RE.fullmatch(blob):
        raise venues.Refused(ref, f"{at}.blob")
    if val["mime"] != pspec.get("mime", "application/pdf"):
        raise venues.Refused(ref, f"{at}.mime")
    b = val["bytes"]
    max_b = int(pspec.get("max_bytes", 10_485_760))
    if isinstance(b, bool) or not isinstance(b, int) or not (1 <= b <= max_b):
        raise venues.Refused(ref, f"{at}.bytes")
    name = _check_clean_str(val["name"], int(pspec.get("max_name", 120)), f"{at}.name", refusal=ref)
    return {"blob": blob, "name": name, "mime": val["mime"], "bytes": b}


def _validate_link(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_link")
    if not isinstance(val, dict) or not ({"url"} <= set(val.keys()) <= {"url", "label"}):
        raise venues.Refused(ref, at)
    url = _check_https_url(val["url"], int(pspec.get("max_url", 300)), f"{at}.url", ref)
    out: dict[str, Any] = {"url": url}
    if "label" in val:
        out["label"] = _check_clean_str(val["label"], int(pspec.get("max_label", 80)), f"{at}.label", refusal=ref)
    return out


def _validate_phone(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_phone")
    if not isinstance(val, str) or not _PHONE_RE.fullmatch(val):
        raise venues.Refused(ref, at)
    return val


def _validate_email(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_email")
    max_len = int(fspec.get("max", pspec.get("max", 120)))
    if not isinstance(val, str) or len(val) > max_len or not _EMAIL_RE.fullmatch(val):
        raise venues.Refused(ref, at)
    if any(unicodedata.category(c) in ("Cc", "Cf") for c in val):
        raise venues.Refused(ref, at)
    return val


def _coord_num(val: Any, limit: float, at: str, ref: str) -> float:
    if isinstance(val, bool) or not isinstance(val, (int, float)):
        raise venues.Refused(ref, at)
    try:
        f = float(val)
    except OverflowError:
        raise venues.Refused(ref, at) from None
    if not (-limit <= f <= limit):
        raise venues.Refused(ref, at)
    return f


def _validate_point(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_point")
    if not isinstance(val, dict):
        raise venues.Refused(ref, at)
    extra = set(val.keys()) - {"lat", "lon"}
    if extra:
        raise venues.Refused("unknown_field", f"{at}.{sorted(extra)[0]}")
    missing = {"lat", "lon"} - set(val.keys())
    if missing:
        raise venues.Refused("missing_field", f"{at}.{sorted(missing)[0]}")
    lat = _coord_num(val["lat"], 90.0, f"{at}.lat", ref)
    lon = _coord_num(val["lon"], 180.0, f"{at}.lon", ref)
    return {"lat": lat, "lon": lon}


def _validate_route(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> list[dict]:
    ref = _refusal_for(fspec, pspec, "bad_route")
    min_pts = int(fspec.get("min", pspec.get("min", 2)))
    max_pts = int(fspec.get("max", pspec.get("max", 50)))
    if not isinstance(val, list) or not (min_pts <= len(val) <= max_pts):
        raise venues.Refused(ref, at)
    pt_pspec = ctx.registry["primitives"]["point"]
    return [_validate_point(pt, {}, pt_pspec, ctx, f"{at}[{i}]") for i, pt in enumerate(val)]


def _validate_person(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> dict:
    ref = _refusal_for(fspec, pspec, "bad_person")
    if not isinstance(val, dict) or not ({"contact"} <= set(val.keys()) <= {"contact", "role"}):
        raise venues.Refused(ref, at)
    contact = val["contact"]
    if not isinstance(contact, str) or not _CONTACT_RE.fullmatch(contact):
        raise venues.Refused(ref, f"{at}.contact")
    out: dict[str, Any] = {"contact": contact}
    if "role" in val:
        out["role"] = _check_clean_str(val["role"], int(pspec.get("max_role", 40)), f"{at}.role", refusal=ref)
    return out


def _validate_choice(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_choice")
    options = fspec.get("options")
    if options is None and "vocab" in fspec:
        options = ctx.registry["vocab"].get(fspec["vocab"], [])
    if not isinstance(val, str) or not options or val not in options:
        raise venues.Refused(ref, at)
    return val


def _validate_tagset(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> list[str]:
    ref = _refusal_for(fspec, pspec, "bad_tagset")
    vocab_name = fspec.get("vocab")
    allowed = set(ctx.registry["vocab"].get(vocab_name, ())) if vocab_name else set()
    max_tags = int(fspec.get("max", pspec.get("max", 24)))
    if not isinstance(val, list) or not (1 <= len(val) <= max_tags):
        raise venues.Refused(ref, at)
    if len(set(val)) != len(val) or any(not isinstance(t, str) or t not in allowed for t in val):
        raise venues.Refused(ref, at)
    return list(val)


def _validate_toggle(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> bool:
    ref = _refusal_for(fspec, pspec, "bad_toggle")
    if not isinstance(val, bool):
        raise venues.Refused(ref, at)
    return val


def _validate_ref(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_ref")
    if not isinstance(val, str) or not val:
        raise venues.Refused(ref, at)
    scope = fspec.get("scope", "page")
    if scope == "venue":
        if not _VENUE_REF_RE.fullmatch(val):
            raise venues.Refused(ref, at)
        return val
    parts = val.split("/")
    if len(parts) not in (1, 2) or not all(_ID_RE.fullmatch(p) for p in parts):
        raise venues.Refused(ref, at)
    if fspec.get("item") and len(parts) != 2:
        raise venues.Refused(ref, at)
    ctx.pending_refs.append((val, fspec, at))
    return val


def _validate_list(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> list[dict]:
    ref = _refusal_for(fspec, pspec, "bad_list")
    if not isinstance(val, list):
        raise venues.Refused(ref, at)
    min_i = int(fspec.get("min", pspec.get("min", 1)))
    max_i = int(fspec.get("max", pspec.get("max", 200)))
    if len(val) < min_i:
        raise venues.Refused("no_items" if ref == "bad_list" and "of" in fspec and fspec["of"] == "item" else ref, at)
    if len(val) > max_i:
        raise venues.Refused("too_many_items" if fspec.get("of") == "item" else ref, at)

    rec_name = fspec.get("of")
    rec_spec = ctx.records.get(rec_name) if rec_name else None
    if not rec_spec:
        raise venues.Refused(ref, at)

    out_rows: list[dict] = []
    unique_seen: dict[str, set] = defaultdict(set)
    for k, row in enumerate(val):
        rat = f"{at}[{k}]"
        if not isinstance(row, dict):
            raise venues.Refused(ref, rat)
        extra = set(row.keys()) - set(rec_spec.keys())
        if extra:
            raise venues.Refused(ref if ref == "bad_hours" else "unknown_field", rat if ref == "bad_hours" else f"{rat}.{sorted(extra)[0]}")
        missing = {fn for fn, fs in rec_spec.items() if fs.get("req")} - set(row.keys())
        if missing:
            raise venues.Refused(ref if ref == "bad_hours" else "missing_field", rat if ref == "bad_hours" else f"{rat}.{sorted(missing)[0]}")

        clean_row: dict[str, Any] = {}
        for fn, fs in rec_spec.items():
            if fn not in row:
                continue
            f_at = f"{rat}.{fn}"
            p_name = fs["p"]
            p_spec = ctx.registry["primitives"][p_name]
            validated = PRIMITIVE_VALIDATORS[p_name](row[fn], fs, p_spec, ctx, f_at)
            if fn == "id":
                if not isinstance(validated, str) or not _ID_RE.fullmatch(validated):
                    raise venues.Refused("bad_item_id", f_at)
                if validated in ctx.item_ids:
                    raise venues.Refused("duplicate_item", f_at)
                ctx.item_ids.add(validated)
                if len(ctx.item_ids) > venues.MAX_ITEMS:
                    raise venues.Refused("too_many_items", rat)
            if fs.get("unique"):
                if validated in unique_seen[fn]:
                    raise venues.Refused("duplicate_item", f_at)
                unique_seen[fn].add(validated)
            clean_row[fn] = validated
        out_rows.append(clean_row)
    return out_rows


def _validate_wallet(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> str:
    ref = _refusal_for(fspec, pspec, "bad_wallet")
    raw = solana_pay.b58decode(val) if isinstance(val, str) and 32 <= len(val) <= 44 else None
    if raw is None or len(raw) != int(pspec.get("bytes", 32)):
        raise venues.Refused(ref, at)
    return val


def _validate_derived(val: Any, fspec: dict, pspec: dict, ctx: _Ctx, at: str) -> None:
    raise venues.Refused(_refusal_for(fspec, pspec, "derived_field"), at)


PRIMITIVE_VALIDATORS: dict[str, Callable[[Any, dict, dict, _Ctx, str], Any]] = {
    "text": _validate_text,
    "richtext": _validate_richtext,
    "number": _validate_number,
    "percent": _validate_percent,
    "money": _validate_money,
    "token": _validate_token,
    "time": _validate_time,
    "timerange": _validate_timerange,
    "duration": _validate_duration,
    "date": _validate_date,
    "daterange": _validate_daterange,
    "datetime": _validate_datetime,
    "dayset": _validate_dayset,
    "recurrence": _validate_recurrence,
    "image": _validate_image,
    "gallery": _validate_gallery,
    "video": _validate_video,
    "audio": _validate_audio,
    "file": _validate_file,
    "link": _validate_link,
    "phone": _validate_phone,
    "email": _validate_email,
    "point": _validate_point,
    "route": _validate_route,
    "person": _validate_person,
    "choice": _validate_choice,
    "tagset": _validate_tagset,
    "toggle": _validate_toggle,
    "ref": _validate_ref,
    "list": _validate_list,
    "wallet": _validate_wallet,
    "derived": _validate_derived,
}


def _rule_one_token(
    blocks: list[dict], registry: dict, block_locs: list[int]
) -> str | None:
    one_token_kinds = set(registry["page_rules"].get("one_token", ()))
    page_token: str | None = None
    for idx, b in zip(block_locs, blocks):
        if b["kind"] not in one_token_kinds:
            continue
        tok = b["data"].get("token")
        if not tok:
            continue
        if page_token is not None and tok != page_token:
            raise venues.Refused("mixed_tokens", f"blocks[{idx}].data.token")
        page_token = tok
    return page_token


def _rule_resolve_refs(
    pending_refs: list[tuple[str, dict, str]],
    instances_by_id: dict[str, dict],
    items_by_block: dict[str, set[str]],
) -> None:
    for ref_val, fspec, at in pending_refs:
        parts = ref_val.split("/")
        target_block = instances_by_id.get(parts[0])
        if target_block is None:
            raise venues.Refused("bad_ref", at)
        want_kind = fspec.get("target")
        if want_kind and target_block["kind"] != want_kind:
            raise venues.Refused("bad_ref", at)
        if len(parts) == 2 and parts[1] not in items_by_block.get(parts[0], set()):
            raise venues.Refused("bad_ref", at)


def _rule_publish(active_blocks: list[dict], registry: dict) -> None:
    pub_rule = registry["page_rules"].get("publish", {})
    registered_kinds = set(registry["blocks"].keys())
    present_kinds: set[str] = set()
    for b in active_blocks:
        kind = b["kind"]
        if kind == "loc.address":
            pt = b["data"].get("point")
            if not isinstance(pt, dict) or "lat" not in pt or "lon" not in pt:
                continue
        present_kinds.add(kind)

    req_all = [k for k in pub_rule.get("all", ()) if k in registered_kinds]
    if not all(k in present_kinds for k in req_all):
        raise venues.Refused("needs_address", "blocks")

    any_groups = [
        [k for k in grp if k in registered_kinds]
        for grp in pub_rule.get("any", ())
    ]
    any_groups = [grp for grp in any_groups if grp]
    if any_groups and not any(all(k in present_kinds for k in grp) for grp in any_groups):
        raise venues.Refused("needs_address", "blocks")


_PUBKEY = re.compile(r"[1-9A-HJ-NP-Za-km-z]{32,44}")
MAX_INVITE = 256


def _page_visibility(page: dict, registry: dict) -> tuple[str, list[str]]:
    visibility = page.get("visibility", "public")
    if not isinstance(visibility, str) or visibility not in registry["vocab"]["visibility"]:
        raise venues.Refused("bad_choice", "visibility")
    if visibility != "group":
        if "invite" in page:
            raise venues.Refused("unknown_field", "invite")
        return visibility, []
    invite = page.get("invite")
    if not isinstance(invite, list) or not 1 <= len(invite) <= MAX_INVITE:
        raise venues.Refused("bad_invite", "invite")
    for j, key in enumerate(invite):
        if not isinstance(key, str) or not _PUBKEY.fullmatch(key) or key in invite[:j]:
            raise venues.Refused("bad_invite", f"invite[{j}]")
    return visibility, list(invite)



def _surfaces(val: Any, allowed: list[str], at: str) -> None:
    if (not isinstance(val, list) or not val or len(set(map(str, val))) != len(val)
            or any(not isinstance(v, str) or v not in allowed for v in val)):
        raise venues.Refused("bad_choice", at)


def validate_page_v2(page: Any, registry: dict, *, publish: bool = False) -> dict:
    if not isinstance(page, dict) or type(page.get("v")) is not int or page["v"] != 2:
        raise venues.Refused("bad_version", "v")
    extra_top = set(page.keys()) - {"v", "tone", "look", "on", "visibility", "invite", "blocks"}
    if extra_top:
        raise venues.Refused("unknown_field", sorted(extra_top)[0])
    visibility, invite = _page_visibility(page, registry)
    if "tone" not in page:
        raise venues.Refused("missing_field", "tone")
    tones = registry["vocab"].get("tone", ["amber", "sage", "sea", "iris", "rose", "graphite"])
    if not isinstance(page["tone"], str) or page["tone"] not in tones:
        raise venues.Refused("bad_choice", "tone")
    if "look" in page and (not isinstance(page["look"], str) or page["look"] not in registry["vocab"].get("look", ())):
        raise venues.Refused("bad_choice", "look")
    if "on" in page:
        _surfaces(page["on"], registry["vocab"]["surface"], "on")

    blocks = page.get("blocks")
    if not isinstance(blocks, list):
        raise venues.Refused("not_a_list", "blocks")

    rules = registry["page_rules"]
    if len(blocks) > int(rules.get("max_blocks", 60)):
        raise venues.Refused("too_many_blocks", "blocks")
    if len(json.dumps(page, ensure_ascii=False).encode("utf-8")) > int(rules.get("max_bytes", 98304)):
        raise venues.Refused("too_big", "")

    audiences = registry["vocab"]["audience"]
    seen_ids: set[str] = set()
    kind_counts: dict[str, int] = defaultdict(int)
    global_items: set[str] = set()
    all_pending_refs: list[tuple[str, dict, str]] = []
    instances_by_id: dict[str, dict] = {}
    items_by_block: dict[str, set[str]] = defaultdict(set)

    clean_blocks: list[dict] = []
    active_blocks: list[dict] = []
    active_locs: list[int] = []

    for i, block in enumerate(blocks):
        at = f"blocks[{i}]"
        if not isinstance(block, dict):
            raise venues.Refused("unknown_block", at)
        allowed_bkeys = {"id", "kind", "variant", "hidden", "audience", "size", "tone", "on", "data"}
        extra_b = set(block.keys()) - allowed_bkeys
        if extra_b:
            raise venues.Refused("unknown_field", f"{at}.{sorted(extra_b)[0]}")
        for req_k in ("id", "kind", "data"):
            if req_k not in block:
                if req_k == "kind":
                    raise venues.Refused("unknown_block", at)
                raise venues.Refused("missing_field", f"{at}.{req_k}")

        kind = block["kind"]
        if not isinstance(kind, str) or kind not in registry["blocks"]:
            raise venues.Refused("unknown_block", at)
        bspec = registry["blocks"][kind]

        bid = block["id"]
        if not isinstance(bid, str) or not _ID_RE.fullmatch(bid):
            raise venues.Refused("bad_id", f"{at}.id")
        if bid in seen_ids:
            raise venues.Refused("duplicate_event" if kind == "evt.single" else "duplicate_id", f"{at}.id")
        seen_ids.add(bid)

        kind_counts[kind] += 1
        max_k = int(bspec["max"])
        if kind_counts[kind] > max_k:
            if kind == "evt.single":
                raise venues.Refused("too_many_events", at)
            if max_k == 1:
                raise venues.Refused("duplicate_block", at)
            raise venues.Refused("too_many_of_kind", at)

        variant = block.get("variant", bspec["variants"][0])
        if not isinstance(variant, str) or variant not in bspec["variants"]:
            raise venues.Refused("bad_choice", f"{at}.variant")

        hidden = block.get("hidden", False)
        if not isinstance(hidden, bool):
            raise venues.Refused("bad_toggle", f"{at}.hidden")

        audience = block.get("audience", "public")
        if not isinstance(audience, str) or audience not in audiences:
            raise venues.Refused("bad_choice", f"{at}.audience")

        size = block.get("size")
        if "size" in block and (not isinstance(size, str) or size not in registry["vocab"]["size"]):
            raise venues.Refused("bad_choice", f"{at}.size")
        btone = block.get("tone")
        if "tone" in block and (not isinstance(btone, str) or btone not in tones):
            raise venues.Refused("bad_choice", f"{at}.tone")
        if "on" in block:
            _surfaces(block["on"], bspec["surfaces"], f"{at}.on")

        data = block["data"]
        if not isinstance(data, dict):
            raise venues.Refused("unknown_field", f"{at}.data")

        fspecs = bspec["fields"]
        extra_d = set(data.keys()) - set(fspecs.keys())
        if extra_d:
            raise venues.Refused("unknown_field", f"{at}.data.{sorted(extra_d)[0]}")

        for fn, fs in fspecs.items():
            if fs["p"] == "derived" and fn in data:
                raise venues.Refused("derived_field", f"{at}.data.{fn}")
            if fs.get("req") and fn not in data:
                raise venues.Refused("missing_field", f"{at}.data.{fn}")

        ctx = _Ctx(registry, data, bspec.get("records", {}))
        ctx.item_ids = set(global_items)
        clean_data: dict[str, Any] = {}
        ordered_fields = sorted(fspecs.keys(), key=lambda k: (0 if fspecs[k]["p"] == "token" else 1))
        for fn in ordered_fields:
            if fn not in data:
                continue
            fs = fspecs[fn]
            p_name = fs["p"]
            p_spec = registry["primitives"][p_name]
            clean_data[fn] = PRIMITIVE_VALIDATORS[p_name](data[fn], fs, p_spec, ctx, f"{at}.data.{fn}")

        ordered_clean_data = {fn: clean_data[fn] for fn in fspecs if fn in clean_data}

        if kind == "evt.single" and "ends" in ordered_clean_data:
            if ordered_clean_data["ends"] <= ordered_clean_data["starts"]:
                raise venues.Refused("bad_datetime", f"{at}.data.ends")

        new_items = ctx.item_ids - global_items
        global_items = ctx.item_ids
        items_by_block[bid] = new_items
        all_pending_refs.extend(ctx.pending_refs)

        clean_inst: dict[str, Any] = {
            "id": bid,
            "kind": kind,
            "variant": variant,
            "hidden": hidden,
        }
        if "audience" in block:
            clean_inst["audience"] = audience
        if "size" in block:
            clean_inst["size"] = size
        if "tone" in block:
            clean_inst["tone"] = btone
        if "on" in block:
            clean_inst["on"] = list(block["on"])
        clean_inst["data"] = ordered_clean_data

        clean_blocks.append(clean_inst)
        instances_by_id[bid] = clean_inst
        if not hidden and audience == "public":
            active_blocks.append(clean_inst)
            active_locs.append(i)

    _rule_one_token(clean_blocks, registry, list(range(len(clean_blocks))))
    _rule_resolve_refs(all_pending_refs, instances_by_id, items_by_block)
    if publish:
        _rule_publish(active_blocks, registry)

    name: str | None = None
    location: dict | None = None
    location_by: str | None = None
    page_token: str | None = None
    wallet: str | None = None
    menus: list[dict] = []

    one_token_kinds = set(registry["page_rules"].get("one_token", ()))
    for b in active_blocks:
        kind = b["kind"]
        d = b["data"]
        if kind == "id.name" and name is None:
            name = d["name"]
        elif kind == "loc.address" and location_by != kind or kind == "loc.meet_point" and location is None:
            pt = d["point"]
            cell = h3cell.cell(pt["lat"], pt["lon"])
            location = {"address": d["text"], "lat": pt["lat"], "lon": pt["lon"], "cell": cell, "area": h3cell.area(cell)}
            location_by = kind
        elif kind == "pay.wallet" and wallet is None:
            wallet = d["address"]
        if kind in one_token_kinds and "token" in d and page_token is None:
            page_token = d["token"]
        if kind == "cat.menu":
            menu_canon: dict[str, Any] = {
                "type": "menu",
                "token": d["token"],
                "items": d["items"],
            }
            if "title" in d:
                menu_canon["title"] = d["title"]
            menus.append(menu_canon)

    menu_version = None
    if menus:
        canon = json.dumps(menus, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        menu_version = hashlib.sha256(canon.encode("utf-8")).hexdigest()[:16]

    return {
        "page": {"v": 2, "tone": page["tone"], **({"look": page["look"]} if "look" in page else {}), **({"on": list(page["on"])} if "on" in page else {}), **({"visibility": visibility} if "visibility" in page else {}),
                 **({"invite": invite} if invite else {}), "blocks": clean_blocks},
        "visibility": visibility,
        "name": name,
        "location": location,
        "token": page_token,
        "menu_version": menu_version,
        "wallet": wallet,
    }
