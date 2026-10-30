"""
Мапа з регіону на цьому комп'ютері має назви без інтернету: гліфи Noto Sans
віддає вузол (`/map/offline/glyphs`). До 26.09 гліфи йшли лише з
tiles.openfreemap.org — без мережі мапа була б без жодного підпису.
"""
from __future__ import annotations

import api.routes_geo_offline as routes

BASE = "/api/v1/map/offline/glyphs"


def test_cyrillic_range_served_without_token(unauth_client):
    res = unauth_client.get(f"{BASE}/Noto%20Sans%20Regular/1024-1279.pbf")
    assert res.status_code == 200
    assert res.content == (routes.GLYPHS_DIR / "1024-1279.pbf").read_bytes()
    assert res.headers["content-type"] == "application/x-protobuf"


def test_bold_stack_gets_regular(unauth_client):
    res = unauth_client.get(f"{BASE}/Noto%20Sans%20Bold/0-255.pbf")
    assert res.status_code == 200
    assert res.content == (routes.GLYPHS_DIR / "0-255.pbf").read_bytes()


def test_missing_range_and_traversal(unauth_client):
    assert unauth_client.get(f"{BASE}/x/65024-65279.pbf").status_code == 404
    assert unauth_client.get(f"{BASE}/x/..%2F..%2Fmain.pbf").status_code in (400, 404)
