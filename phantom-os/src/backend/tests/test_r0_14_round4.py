import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from config import config


@pytest.fixture
def osm_off(monkeypatch):
    monkeypatch.setattr(config, "agent_overpass_enabled", False)


def test_pharmacies_nearby_with_osm_off_is_not_a_confident_zero(osm_off, monkeypatch):
    import agent.localization.resolver as resolver
    import db.database
    from ai import tool_executor

    monkeypatch.setattr(resolver, "get_resolver", lambda: SimpleNamespace(last_estimate=lambda: SimpleNamespace(lat=50.08, lon=14.43)))

    def no_db():
        raise RuntimeError("no db in this test")

    monkeypatch.setattr(db.database, "AsyncSessionLocal", no_db)
    import agent.localization.adapters.overpass as overpass
    asked = []
    monkeypatch.setattr(overpass, "get_default_overpass", lambda: asked.append(1))
    res = asyncio.run(tool_executor._tool_search_nearby_places({"query": "аптеки поруч"}, "u"))
    nearby = res["nearby"]
    assert nearby["extras"]["osm_enabled"] is False
    assert "0 обʼєктів" not in nearby["narrative"]
    assert "вимкнено" in nearby["narrative"]
    assert nearby["extras"]["osm_total"] is None
    assert asked == []


@pytest.mark.parametrize("key", ["agent_overpass_enabled", "agent_ip_locator_enabled"])
def test_privacy_flags_are_env_only(key):
    from api import routes_settings

    with pytest.raises(HTTPException) as got:
        asyncio.run(routes_settings.get_setting(key, _user=None))
    assert got.value.status_code == 404
    with pytest.raises(HTTPException) as put:
        asyncio.run(routes_settings.set_setting(key, SimpleNamespace(value=True), user=None))
    assert put.value.status_code == 404


class _Spy:
    def __init__(self, name):
        self.name = name
        self.calls = 0

    async def available(self):
        return True

    async def route(self, req):
        self.calls += 1
        from geo.routing.adapters import RoutingError

        raise RoutingError("spy")


def _req():
    from geo.routing import RouteRequest

    return RouteRequest(waypoints=[{"lat": 50.08, "lon": 14.43}, {"lat": 50.09, "lon": 14.44}], profile="car")


def test_osrm_is_never_called_without_a_route_tap():
    from geo.routing.adapters import RoutingError
    from geo.routing.router_facade import RouterFacade

    osrm = _Spy("osrm")
    with pytest.raises(RoutingError):
        asyncio.run(RouterFacade([osrm], cache=None).route(_req()))
    assert osrm.calls == 0


def test_osrm_answers_the_route_tap(monkeypatch):
    import geo.routing
    from api import routes_map
    from geo.routing.router_facade import RouterFacade

    osrm = _Spy("osrm")
    monkeypatch.setattr(geo.routing, "get_router", lambda: RouterFacade([osrm], cache=None))
    body = routes_map._RouteRequestBody(waypoints=[[50.08, 14.43], [50.09, 14.44]])
    with pytest.raises(HTTPException) as got:
        asyncio.run(routes_map.post_route(body, token_data=None))
    assert got.value.status_code == 502
    assert osrm.calls == 1


def test_air_quality_lookup_sends_a_coarse_point(monkeypatch):
    import httpx

    from geo.sources.environmental import EnvironmentalAdapter

    seen = []

    class Client:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url):
            seen.append(url)
            raise httpx.ConnectError("offline")

    monkeypatch.setattr(httpx, "AsyncClient", Client)
    asyncio.run(EnvironmentalAdapter().fetch_us_aqi(50.087654, 14.421234))
    assert seen and "latitude=50.1&" in seen[0] and "longitude=14.4&" in seen[0]
