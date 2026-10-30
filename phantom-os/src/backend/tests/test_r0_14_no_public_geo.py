import asyncio

import pytest

from config import PhantomConfig, config

FLAGS = ("AGENT_OVERPASS_ENABLED", "AGENT_IP_LOCATOR_ENABLED")


@pytest.fixture
def defaults(monkeypatch):
    for name in FLAGS:
        monkeypatch.delenv(name, raising=False)
    fresh = PhantomConfig(_env_file=None)
    for attr in ("agent_overpass_enabled", "agent_ip_locator_enabled"):
        monkeypatch.setattr(config, attr, getattr(fresh, attr))
    return fresh


def test_public_overpass_and_ip_lookup_are_off_by_default(defaults):
    assert defaults.agent_overpass_enabled is False
    assert defaults.agent_ip_locator_enabled is False


def test_nearby_never_reaches_overpass_by_default(defaults, monkeypatch):
    import httpx

    def boom(*_a, **_k):
        raise AssertionError("public Overpass was called")

    monkeypatch.setattr(httpx.AsyncClient, "post", boom)
    monkeypatch.setattr(httpx.AsyncClient, "get", boom)
    from agent.localization.adapters.overpass import OverpassQuery

    assert asyncio.run(OverpassQuery().features_near(50.45, 30.52, radius_m=500)) == []


def test_node_ip_is_not_sent_to_ip_locators_by_default(defaults):
    from agent.localization.sources.ip_estimate import IpEstimateSource

    class Locator:
        calls = 0

        def can_request_or_has_cache(self):
            return True

        async def locate_current_ip(self):
            Locator.calls += 1

    src = IpEstimateSource(locator=Locator())
    assert src.is_available() is False
    assert asyncio.run(src.get_position()) is None
    assert Locator.calls == 0


def test_config_without_the_flags_counts_as_off(monkeypatch):
    import agent.localization.adapters.overpass as overpass
    import agent.localization.sources.ip_estimate as ip_estimate

    monkeypatch.setattr(overpass, "config", object())
    monkeypatch.setattr(ip_estimate, "config", object())

    class Locator:
        def can_request_or_has_cache(self):
            return True

    assert ip_estimate.IpEstimateSource(locator=Locator()).is_available() is False
    assert asyncio.run(overpass.OverpassQuery().features_near(50.45, 30.52, radius_m=500)) == []


def test_nearby_tells_the_client_osm_lookup_is_off(defaults, monkeypatch):
    from types import SimpleNamespace

    import memory.geo_query
    from api import routes_map

    async def no_memories(*_a, **_k):
        return []

    class Db:
        async def execute(self, _stmt):
            return SimpleNamespace(scalars=lambda: SimpleNamespace(all=lambda: []))

    monkeypatch.setattr(memory.geo_query, "find_memories_near", no_memories)
    res = asyncio.run(routes_map.get_nearby(50.45, 30.52, 500, SimpleNamespace(user_id="u"), Db()))
    assert res["osm"] == [] and res["osm_enabled"] is False
