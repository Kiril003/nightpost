#!/usr/bin/env python3
"""Зведення одного прогону стенда «підвал»: python3 report.py <WORK>/<мітка> [--md]."""
from __future__ import annotations

import json
import statistics
import sys

IP_TCP = 40


def load(prefix: str) -> tuple[dict, dict]:
    with open(prefix + ".json") as f:
        run = json.load(f)
    with open(prefix + ".stats.json") as f:
        stats = json.load(f)
    return run, stats


def bytes_between(timeline: list, a: float, b: float) -> int:
    def at(t: float) -> int:
        best = 0
        for row in timeline:
            if row[0] <= t:
                best = row[1] + row[2]
        return best

    return at(b) - at(a)


def summarise(prefix: str) -> dict:
    run, stats = load(prefix)
    msgs = run["messages"]
    texts = [m for m in msgs if m["kind"] == "text"]
    media = [m for m in msgs if m["kind"] == "media"]
    arrived = [m["arrived_ms"] for m in texts if m["arrived_ms"] is not None]
    total = stats["up_bytes"] + stats["down_bytes"]
    segs = stats["up_segments"] + stats["down_segments"]
    end = run["seconds"]
    out = {
        "texts": len(texts),
        "first_ms": min(arrived) if arrived else None,
        "p50_ms": statistics.median(arrived) if arrived else None,
        "max_ms": max(arrived) if arrived else None,
        "in_60s": sum(1 for v in arrived if v <= 60_000),
        "in_300s": sum(1 for v in arrived if v <= 300_000),
        "receipted": sum(1 for m in texts if m["delivered_ms"] is not None),
        "dup_copies": sum(max(0, m["copies_at_b"] - 1) for m in msgs),
        "extra_handoffs": sum(max(0, m["handoffs"] - 1) for m in msgs),
        "stuck": [m["id"] for m in texts if m["final_state"] not in ("HELD", "DELIVERED")],
        "media_arrived": sum(1 for m in media if m["arrived_ms"] is not None),
        "dials": run["dials_a"] + run["dials_b"],
        "connections": stats["connections"],
        "bytes": total,
        "bytes_wire_est": total + segs * IP_TCP,
        "bytes_per_text": round(total / max(1, len(arrived))),
        "idle_bytes_per_min": round(bytes_between(stats["timeline"], end - 60, end)),
        "narrow": run.get("narrow_a"),
    }
    return out


def main() -> None:
    prefix = sys.argv[1]
    s = summarise(prefix)
    print(json.dumps(s, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
