from __future__ import annotations

import os

import pytest

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-journal-keys")

from symbiote.feed import feed


@pytest.fixture(autouse=True)
def _isolated(tmp_path):
    from symbiote.ladder import pending_asks, stop_switch

    feed.use_path(tmp_path / "feed.jsonl")
    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    yield
    stop_switch.set(False)
    pending_asks.drop_all()


@pytest.mark.asyncio
async def test_stop_on_journal_row_carries_status_key(auth_root_user):
    from symbiote.ladder import journal_list, journal_write

    await journal_write(
        user_id=auth_root_user.id,
        verb="symbiote.stop",
        status="stop_on",
        direction="to_pc",
    )
    rows = await journal_list(auth_root_user.id, limit=5)
    assert rows
    top = rows[0]
    assert top["verb"] == "symbiote.stop"
    assert top["status"] == "stop_on"
    assert top["what"] == "Усе зупинено"
    assert top["status_title"] == "Усе зупинено"



async def _fresh(user_id, device_id):
    from symbiote.ladder import journal_list

    before = {r["id"] for r in await journal_list(user_id, limit=200)}
    async def rows():
        return [r for r in await journal_list(user_id, limit=50, device_id=device_id) if r["id"] not in before]
    return rows


@pytest.mark.asyncio
async def test_pc_stop_reaches_the_phone_journal(auth_root_user):
    from symbiote.ladder import journal_write

    rows = await _fresh(auth_root_user.id, "phone-1")
    await journal_write(user_id=auth_root_user.id, verb="symbiote.stop", status="stop_on", direction="here")
    await journal_write(user_id=auth_root_user.id, verb="pc.notify", status="done", direction="here")
    assert [(r["verb"], r["status"], r["direction"], r["who"]) for r in await rows()] == [("symbiote.stop", "stop_on", "here", "pc")]


@pytest.mark.asyncio
async def test_other_device_rows_stay_hidden(auth_root_user):
    from symbiote.ladder import journal_write

    rows = await _fresh(auth_root_user.id, "phone-1")
    await journal_write(user_id=auth_root_user.id, verb="symbiote.stop", status="stop_on", device_id="phone-2", direction="to_pc")
    await journal_write(user_id=auth_root_user.id, verb="pc.notify", status="done", device_id="phone-2", direction="to_pc")
    assert await rows() == []
