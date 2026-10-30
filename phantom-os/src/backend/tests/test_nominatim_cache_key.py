"""R0-11 — геокодер не ходить на публічний Nominatim без налаштованої адреси."""
from __future__ import annotations

import httpx
import pytest

from agent.localization.adapters import nominatim as nom_mod
from agent.localization.adapters.nominatim import (
    GeocoderUnavailable,
    NominatimGeocoder,
)
from config import config

BASE = "https://geo.example.test"


class _Recorder:
    def __init__(self, search=None, reverse=None, exc=None):
        self.calls: list[tuple[str, dict]] = []
        self._search = search if search is not None else [
            {"lat": "49.8", "lon": "18.2", "display_name": "Místo", "type": "city"}
        ]
        self._reverse = reverse or {"lat": "49.8", "lon": "18.2", "display_name": "Místo", "address": {}}
        self._exc = exc

    def __call__(self, *a, **k):
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False

    async def get(self, url, params=None):
        self.calls.append((url, dict(params or {})))
        if self._exc is not None:
            raise self._exc
        body = self._search if url.endswith("/search") else self._reverse
        return httpx.Response(200, json=body, request=httpx.Request("GET", url))


@pytest.fixture
def recorder(monkeypatch):
    rec = _Recorder()
    monkeypatch.setattr(nom_mod.httpx, "AsyncClient", rec)
    return rec


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setattr(config, "agent_nominatim_enabled", True)
    monkeypatch.setattr(config, "geocoder_base_url", BASE)


def test_defaults_are_off_and_unconfigured():
    fields = type(config).model_fields
    assert fields["geocoder_base_url"].default is None
    assert fields["agent_nominatim_enabled"].default is False


@pytest.mark.asyncio
async def test_no_base_url_makes_no_request(recorder, monkeypatch):
    monkeypatch.setattr(config, "agent_nominatim_enabled", True)
    monkeypatch.setattr(config, "geocoder_base_url", None)
    g = NominatimGeocoder()
    assert await g.geocode("Ostrava") == []
    assert await g.reverse(49.8, 18.2) is None
    assert recorder.calls == []


@pytest.mark.asyncio
async def test_strict_without_base_raises_not_configured(recorder, monkeypatch):
    monkeypatch.setattr(config, "geocoder_base_url", None)
    with pytest.raises(GeocoderUnavailable) as exc:
        await NominatimGeocoder().geocode("Ostrava", strict=True)
    assert exc.value.reason == "geocoder_not_configured"


@pytest.mark.asyncio
async def test_strict_network_error_raises_unavailable(configured, monkeypatch):
    rec = _Recorder(exc=httpx.ConnectError("down"))
    monkeypatch.setattr(nom_mod.httpx, "AsyncClient", rec)
    with pytest.raises(GeocoderUnavailable) as exc:
        await NominatimGeocoder().geocode("Ostrava", strict=True)
    assert exc.value.reason == "geocoder_unavailable"


@pytest.mark.asyncio
async def test_requests_go_to_configured_base_only(configured, recorder):
    g = NominatimGeocoder()
    await g.geocode("Ostrava")
    await g.reverse(49.8, 18.2)
    assert [u for u, _ in recorder.calls] == [f"{BASE}/search", f"{BASE}/reverse"]
    assert all("openstreetmap.org" not in u for u, _ in recorder.calls)


@pytest.mark.asyncio
async def test_cache_key_includes_limit(configured, recorder):
    g = NominatimGeocoder()
    await g.geocode("Ostrava", limit=1)
    await g.geocode("Ostrava", limit=5)
    await g.geocode("Ostrava", limit=5)
    assert [p["limit"] for _, p in recorder.calls] == [1, 5]


@pytest.mark.asyncio
async def test_viewbox_and_language_forwarded_and_keyed(configured, recorder):
    g = NominatimGeocoder()
    vb = (18.1, 49.7, 18.4, 49.9)
    await g.geocode("Ostrava", viewbox=vb, lang="cs")
    await g.geocode("Ostrava", viewbox=vb, lang="uk")
    await g.geocode("Ostrava", viewbox=vb, lang="uk")
    assert len(recorder.calls) == 2
    first = recorder.calls[0][1]
    assert first["viewbox"] == "18.1,49.7,18.4,49.9"
    assert first["accept-language"] == "cs"
    assert recorder.calls[1][1]["accept-language"] == "uk"


@pytest.mark.asyncio
async def test_history_enricher_skips_without_base(monkeypatch):
    monkeypatch.setattr(config, "agent_nominatim_enabled", True)
    monkeypatch.setattr(config, "geocoder_base_url", None)
    called = []

    class _Spy:
        async def reverse(self, lat, lon):
            called.append((lat, lon))
            return None

    monkeypatch.setattr(nom_mod, "get_default_nominatim", lambda: _Spy())
    from agent.localization.history_writer import LocationHistoryEnricher

    assert await LocationHistoryEnricher()._cycle() == 0
    assert called == []


def test_base_url_not_reachable_through_settings_api():
    from api import routes_settings

    assert "geocoder_base_url" in routes_settings.ENV_ONLY_KEYS
