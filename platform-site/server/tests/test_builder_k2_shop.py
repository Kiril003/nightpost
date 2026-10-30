"""K2 shop kit: a visitor fills a cart from a page's catalogue; the server counts stock and the total, the owner gets the order."""
import re
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_builder_k2_booking import _publish  # noqa: E402
from test_venues import _create, _login, _page_v2, env  # noqa: E402,F401


def _shop_page(*, area=True, pickup=True, stock_bread=3, hidden=False, audience=None):
    page = _page_v2()
    products = {"id": "shop-1", "kind": "cat.products", "variant": "grid", "hidden": hidden,
                "data": {"title": "Bread", "token": "USDC", "items": [
                    {"id": "rye", "name": "Rye loaf", "price": "4.5", "stock": stock_bread, "group": "Bread"},
                    {"id": "bun", "name": "Bun", "price": "1.2", "stock": 10, "group": "Pastry"},
                    {"id": "gone", "name": "Seeded", "price": "3", "stock": 0}]}}
    if audience:
        products["audience"] = audience
    page["blocks"].append(products)
    if area:
        data = {"title": "Delivery", "token": "USDC", "items": [
            {"id": "centre", "area": "Centre", "fee": "2", "min": "10", "eta": 45},
            {"id": "far", "area": "Poruba", "fee": "3.5"}]}
        if pickup is not None:
            data["pickup"] = pickup
        page["blocks"].append({"id": "area-1", "kind": "loc.service_area", "variant": "list", "hidden": False, "data": data})
    return page


def _order(**kw):
    return {"block": "shop-1", "items": [{"id": "rye", "qty": 2}], "fulfil": "pickup",
            "name": "Eva", "contact": "eva@example.test", **kw}


def _fast(monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)


def test_an_order_reaches_the_owner_with_a_total_the_server_counted(env, monkeypatch):
    _fast(monkeypatch)
    head, vid = _publish(env, _shop_page(), email="shop@maker.cz")
    c = env["client"]
    res = c.post(f"/venues/{vid}/requests", json=_order(
        items=[{"id": "rye", "qty": 2}, {"id": "bun", "qty": 3}], fulfil="delivery", zone="centre",
        address="Nádražní 5", total="0.01", text="Ring twice"))
    assert res.status_code == 201, res.text
    order = res.json()["order"]
    assert order == {
        "lines": [{"id": "rye", "name": "Rye loaf", "qty": 2, "price": "4.5"},
                  {"id": "bun", "name": "Bun", "qty": 3, "price": "1.2"}],
        "subtotal": "12.6", "fee": "2", "total": "14.6", "token": "USDC",
        "fulfil": "delivery", "zone": "Centre", "address": "Nádražní 5",
    }, "9 + 3.6 + a 2 USDC fee; the client's total is ignored"

    mine = c.get(f"/venues/{vid}/requests", headers=head).json()["requests"]
    assert (mine[0]["kind"], mine[0]["order"], mine[0]["text"]) == ("order", order, "Ring twice")


def test_stock_runs_out_and_the_page_reads_what_is_left(env, monkeypatch):
    _fast(monkeypatch)
    head, vid = _publish(env, _shop_page(stock_bread=3), email="stock@maker.cz")
    c = env["client"]
    assert c.post(f"/venues/{vid}/requests", json=_order()).status_code == 201
    over = c.post(f"/venues/{vid}/requests", json=_order(name="Petr"))
    assert over.status_code == 409 and over.json()["refused"] == "out_of_stock", "3 in stock, 2 sold: 2 more do not fit"
    assert c.post(f"/venues/{vid}/requests", json=_order(items=[{"id": "rye", "qty": 1}], name="Petr")).status_code == 201
    gone = c.post(f"/venues/{vid}/requests", json=_order(items=[{"id": "gone", "qty": 1}]))
    assert gone.status_code == 409 and gone.json()["refused"] == "out_of_stock"
    left = c.get(f"/venues/{vid}/requests/stock?block=shop-1").json()["left"]
    assert left == {"rye": 0, "bun": 10, "gone": 0}

    page = _shop_page(stock_bread=8)
    assert c.put(f"/venues/{vid}/page", headers=head, json={**page, "published": True}).status_code == 200
    assert c.get(f"/venues/{vid}/requests/stock?block=shop-1").json()["left"]["rye"] == 8, \
        "a new count from the owner is the new truth"


def test_the_cart_is_checked_line_by_line(env, monkeypatch):
    _fast(monkeypatch)
    _, vid = _publish(env, _shop_page(), email="cart@maker.cz")
    c = env["client"]

    def refused(**kw):
        res = c.post(f"/venues/{vid}/requests", json=_order(**kw))
        assert res.status_code in (409, 422), res.text
        assert res.json()["message_uk"] and res.json()["message_en"]
        return res.json()["refused"]

    assert refused(items=[]) == "empty_cart"
    assert refused(items=[{"id": "cake", "qty": 1}]) == "bad_item"
    assert refused(fulfil=None) == "bad_fulfil"
    assert refused(fulfil="delivery", zone="moon", address="x") == "bad_zone"
    assert refused(fulfil="delivery", zone="far", address=" ") == "no_address"
    assert refused(fulfil="delivery", zone="centre", address="Nádražní 5") == "below_min", "9 USDC under a 10 minimum"
    ok = c.post(f"/venues/{vid}/requests", json=_order(fulfil="delivery", zone="far", address="Opavská 1"))
    assert ok.status_code == 201 and ok.json()["order"]["total"] == "12.5"
    twice = c.post(f"/venues/{vid}/requests", json=_order(items=[{"id": "bun", "qty": 1}, {"id": "bun", "qty": 2}]))
    assert twice.json()["order"]["lines"] == [{"id": "bun", "name": "Bun", "qty": 3, "price": "1.2"}]


def test_pickup_and_delivery_follow_what_the_page_offers(env, monkeypatch):
    _fast(monkeypatch)
    c = env["client"]
    _, no_area = _publish(env, _shop_page(area=False), email="noarea@maker.cz")
    assert c.post(f"/venues/{no_area}/requests", json=_order()).status_code == 201, "no delivery block: pickup only"
    res = c.post(f"/venues/{no_area}/requests", json=_order(fulfil="delivery", zone="centre", address="x"))
    assert res.json()["refused"] == "no_delivery"

    _, no_pickup = _publish(env, _shop_page(pickup=False), email="nopick@maker.cz")
    assert c.post(f"/venues/{no_pickup}/requests", json=_order()).json()["refused"] == "no_pickup"


def test_a_hidden_or_members_only_catalogue_takes_no_orders(env, monkeypatch):
    _fast(monkeypatch)
    c = env["client"]
    _, hidden = _publish(env, _shop_page(hidden=True), email="hid@maker.cz")
    assert c.post(f"/venues/{hidden}/requests", json=_order()).json()["refused"] == "no_such_block"
    _, members = _publish(env, _shop_page(audience="pass"), email="mem@maker.cz")
    assert c.post(f"/venues/{members}/requests", json=_order()).json()["refused"] == "no_such_block"
    assert c.get(f"/venues/{hidden}/requests/stock?block=shop-1").status_code == 404


def test_request_refusals_speak_vy_in_ukrainian(env):
    import venue_requests as vr

    ty = re.compile(r"(?<!\w)(ти|тобі|тебе|твій|твоя|вибери|обери|залиш|спробуй|вкажи|додай|напиши)(?!\w)", re.I)
    import i18n

    assert [k for k, key in vr.REFUSALS.items() if ty.search(i18n.t(key, "uk"))] == []


def test_two_buyers_racing_for_the_last_item_never_oversell(tmp_path, monkeypatch):
    """Two workers on one file, each with its own in-process lock: both read the stock before either writes."""
    import threading

    import venue_requests as vr

    path = tmp_path / "requests.db"
    a, b = vr.RequestStore(path), vr.RequestStore(path)
    meet = threading.Barrier(2, timeout=1.5)
    used = vr.RequestStore._used

    def slow_used(conn, *args):
        n = used(conn, *args)
        try:
            meet.wait()
        except threading.BrokenBarrierError:
            pass
        return n

    monkeypatch.setattr(vr.RequestStore, "_used", staticmethod(slow_used))
    row = {"venue_id": "v1", "block_id": "shop-1", "kind": "order", "service_id": None, "at_ms": None,
           "at_local": None, "text": "", "from_name": "Eva", "contact": "e@example.test", "order": {"lines": []}}
    out: list[str] = []

    def buy(store):
        try:
            store.add(dict(row), None, 1_000, lines=[("rye", 1, 1)])
            out.append("201")
        except vr.venues.Refused as e:
            out.append("409" if e.reason == "out_of_stock" else e.reason)

    threads = [threading.Thread(target=buy, args=(s,)) for s in (a, b)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(20)
    assert sorted(out) == ["201", "409"], out
    assert a.left("v1", "shop-1", {"rye": 1}) == {"rye": 0}
