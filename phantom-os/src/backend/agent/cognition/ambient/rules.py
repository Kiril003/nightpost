"""Default ambient care rules. Each is one small attentive behaviour.

Rules read thresholds from config lazily (hot-reload friendly) with sane
defaults, and return a ready-to-surface Ukrainian message or None. They guard
every field — missing perception (None) never fires a rule. This is the set the
symbiote ships with; the catalogue is meant to grow to hundreds.
"""
from __future__ import annotations

from typing import Optional

from agent.cognition.ambient.guardian import AmbientRule


def _cfg(name: str, default):
    from config import config
    return getattr(config, name, default)


def _num(d: Optional[dict], *path):
    cur = d
    for p in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(p)
    return cur if isinstance(cur, (int, float)) else None


# ── environment ─────────────────────────────────────────────────────────────
def _air_quality(snap, prev) -> Optional[str]:
    aqi = _num(snap, "env", "aqi")
    if aqi is None:
        return None
    hazardous = float(_cfg("ambient_aqi_hazardous", 200))
    unhealthy = float(_cfg("ambient_aqi_unhealthy", 150))
    if aqi >= hazardous:
        return (f"Повітря тут небезпечне (AQI {int(aqi)}). Раджу зайти всередину, "
                f"закрити вікна й увімкнути очищення повітря.")
    if aqi >= unhealthy:
        return (f"Якість повітря нездорова (AQI {int(aqi)}). Варто обмежити час "
                f"надворі й уникати навантажень.")
    return None


# ── system ──────────────────────────────────────────────────────────────────
def _disk_full(snap, prev) -> Optional[str]:
    d = _num(snap, "system", "disk_percent")
    if d is None:
        return None
    thr = float(_cfg("ambient_disk_full_pct", 92))
    return (f"Диск майже заповнений ({d:.0f}%). Звільни місце, щоб уникнути збоїв."
            if d >= thr else None)


def _cpu_hot(snap, prev) -> Optional[str]:
    c = _num(snap, "system", "cpu_percent")
    if c is None:
        return None
    thr = float(_cfg("ambient_cpu_high_pct", 92))
    return (f"Процесор під сильним навантаженням ({c:.0f}%). Перевір, що його так "
            f"вантажить." if c >= thr else None)


def _internet_lost(snap, prev) -> Optional[str]:
    was = (prev or {}).get("system", {}).get("internet_available")
    now = (snap or {}).get("system", {}).get("internet_available")
    return ("Зник інтернет. Переходжу в офлайн-режим — деякі можливості тимчасово "
            "обмежені." if was is True and now is False else None)


# ── wellbeing / health ──────────────────────────────────────────────────────
def _deep_night_awake(snap, prev) -> Optional[str]:
    """Deep night and the person is still talking to us — the only honest
    sign of "still awake" PHANTOM has (no body sensors)."""
    when = snap.get("when") or {}
    hour = when.get("hour")
    idle = (snap.get("history") or {}).get("last_interaction_ago_s")
    lo = int(_cfg("ambient_late_hour_start", 2))
    hi = int(_cfg("ambient_late_hour_end", 5))
    awake = isinstance(idle, (int, float)) and idle < 600
    if awake and isinstance(hour, int) and lo <= hour < hi:
        return "Вже глибока ніч, а ти ще тут. Подбай про сон — я постережу."
    return None


def default_rules() -> list[AmbientRule]:
    hour = 3600.0
    return [
        AmbientRule("air_quality", "environment", 7, 2 * hour, _air_quality),
        AmbientRule("disk_full", "system", 6, 6 * hour, _disk_full),
        AmbientRule("cpu_hot", "system", 4, 1 * hour, _cpu_hot),
        AmbientRule("internet_lost", "system", 3, 0.5 * hour, _internet_lost),
        AmbientRule("deep_night_awake", "wellbeing", 4, 6 * hour, _deep_night_awake),
    ]
