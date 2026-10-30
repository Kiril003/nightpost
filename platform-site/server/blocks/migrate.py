"""Pure lossless migration between manifest v1 and manifest v2."""
from __future__ import annotations

import copy
from typing import Any

DEFAULT_TONE = "amber"


def up(v1: dict[str, Any]) -> dict[str, Any]:
    blocks_out: list[dict[str, Any]] = []
    for idx, block in enumerate(v1.get("blocks", [])):
        btype = block.get("type")
        if btype == "title":
            blocks_out.append({
                "id": f"name-{idx}",
                "kind": "id.name",
                "variant": "plain",
                "hidden": False,
                "data": {"name": block["text"]},
            })
        elif btype == "about":
            blocks_out.append({
                "id": f"about-{idx}",
                "kind": "id.about",
                "variant": "paragraph",
                "hidden": False,
                "data": {"text": block["text"]},
            })
        elif btype == "address":
            blocks_out.append({
                "id": f"address-{idx}",
                "kind": "loc.address",
                "variant": "map_line",
                "hidden": False,
                "data": {
                    "text": block["text"],
                    "point": {"lat": block["lat"], "lon": block["lon"]},
                },
            })
        elif btype == "hours":
            rows = [
                {
                    "days": [r["day"]],
                    "hours": {"open": r["open"], "close": r["close"]},
                }
                for r in block.get("rows", [])
            ]
            blocks_out.append({
                "id": f"week-{idx}",
                "kind": "hrs.week",
                "variant": "strip",
                "hidden": False,
                "data": {"rows": rows},
            })
        elif btype == "menu":
            data: dict[str, Any] = {}
            if "title" in block:
                data["title"] = block["title"]
            data["token"] = block["token"]
            data["items"] = [copy.deepcopy(item) for item in block.get("items", [])]
            blocks_out.append({
                "id": f"menu-{idx}",
                "kind": "cat.menu",
                "variant": "two_col",
                "hidden": False,
                "data": data,
            })
        elif btype == "event":
            data = {
                "title": block["title"],
                "starts": block["starts"],
            }
            if "ends" in block:
                data["ends"] = block["ends"]
            if "note" in block:
                data["note"] = block["note"]
            if "price" in block and "token" in block:
                data["price"] = {"amount": block["price"], "token": block["token"]}
            blocks_out.append({
                "id": block["id"],
                "kind": "evt.single",
                "variant": "card",
                "hidden": False,
                "data": data,
            })
        elif btype == "wallet":
            blocks_out.append({
                "id": f"wallet-{idx}",
                "kind": "pay.wallet",
                "variant": "address_row",
                "hidden": False,
                "data": {"address": block["address"]},
            })
    return {"v": 2, "tone": DEFAULT_TONE, "blocks": blocks_out}


def down(v2: dict[str, Any]) -> dict[str, Any]:
    blocks_out: list[dict[str, Any]] = []
    for block in v2.get("blocks", []):
        if not isinstance(block, dict):
            continue
        if block.get("hidden", False):
            continue
        if block.get("audience", "public") != "public":
            continue
        kind = block.get("kind")
        data = block.get("data")
        if not isinstance(data, dict):
            continue
        if kind == "id.name":
            blocks_out.append({"type": "title", "text": data["name"]})
        elif kind == "id.about":
            blocks_out.append({"type": "about", "text": data["text"]})
        elif kind == "loc.address":
            pt = data["point"]
            blocks_out.append({
                "type": "address",
                "text": data["text"],
                "lat": pt["lat"],
                "lon": pt["lon"],
            })
        elif kind == "hrs.week":
            rows = []
            for r in data.get("rows", []):
                hrs = r["hours"]
                for d in r.get("days", []):
                    rows.append({"day": d, "open": hrs["open"], "close": hrs["close"]})
            blocks_out.append({"type": "hours", "rows": rows})
        elif kind == "cat.menu":
            m: dict[str, Any] = {"type": "menu"}
            if "title" in data:
                m["title"] = data["title"]
            m["token"] = data["token"]
            items = []
            for it in data.get("items", []):
                raw_price = it["price"]
                price_str = raw_price["amount"] if isinstance(raw_price, dict) else raw_price
                clean_it: dict[str, Any] = {
                    "id": it["id"],
                    "name": it["name"],
                    "price": price_str,
                }
                if "note" in it:
                    clean_it["note"] = it["note"]
                items.append(clean_it)
            m["items"] = items
            blocks_out.append(m)
        elif kind == "evt.single":
            ev: dict[str, Any] = {
                "type": "event",
                "id": block["id"],
                "title": data["title"],
                "starts": data["starts"],
            }
            if "ends" in data:
                ev["ends"] = data["ends"]
            if "note" in data:
                ev["note"] = data["note"]
            if "price" in data and isinstance(data["price"], dict):
                ev["price"] = data["price"]["amount"]
                ev["token"] = data["price"]["token"]
            blocks_out.append(ev)
        elif kind == "pay.wallet":
            blocks_out.append({"type": "wallet", "address": data["address"]})
    return {"v": 1, "blocks": blocks_out}
