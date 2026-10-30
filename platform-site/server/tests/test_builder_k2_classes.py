"""K2 classes kit: a visitor joins a course with dated sessions; the server counts the seats and the owner gets the enrolment."""
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_builder_k2_booking import _publish  # noqa: E402
from test_venues import _create, _login, _page_v2, env  # noqa: E402,F401


def _day(n: int) -> str:
    return (datetime.now(timezone.utc).date() + timedelta(days=n)).isoformat()


def _course_page(*, seats=12, sessions=None, hidden=False, audience=None, price="240"):
    page = _page_v2()
    data = {"title": "Photo basics", "token": "USDC", "price": price,
            "items": [{"id": "m1", "name": "Manual camera settings"}, {"id": "m2", "name": "Light"}]}
    if sessions != "none":
        data["sessions"] = sessions or [
            {"id": "s1", "name": "Camera and optics", "date": _day(-5), "time": "19:00"},
            {"id": "s2", "name": "Light and exposure", "date": _day(2), "time": "19:00"},
            {"id": "s3", "name": "Mini project", "date": _day(4), "time": "18:30"}]
    if seats is not None:
        data["seats"] = seats
    course = {"id": "course-1", "kind": "cls.course", "variant": "numbered", "hidden": hidden, "data": data}
    if audience:
        course["audience"] = audience
    page["blocks"].append(course)
    return page


def _join(**kw):
    return {"block": "course-1", "name": "Eva", "contact": "eva@example.test", **kw}


def _fast(monkeypatch):
    monkeypatch.setattr(sys.modules["venue_requests"], "SEND_LIMIT", 50)


def test_an_enrolment_reaches_the_owner_with_the_course_from_the_page(env, monkeypatch):
    _fast(monkeypatch)
    head, vid = _publish(env, _course_page(), email="course@maker.cz")
    c = env["client"]
    res = c.post(f"/venues/{vid}/requests", json=_join(text="Beginner", price="1"))
    assert res.status_code == 201, res.text
    enroll = res.json()["enroll"]
    assert enroll == {"course": "Photo basics", "price": "240", "token": "USDC", "sessions": 3,
                      "next": {"name": "Light and exposure", "date": _day(2), "time": "19:00"}}, \
        "the course, its price and the next session come from the page, not the visitor"

    mine = c.get(f"/venues/{vid}/requests", headers=head).json()["requests"]
    assert (mine[0]["kind"], mine[0]["enroll"], mine[0]["text"], mine[0]["order"]) == ("enroll", enroll, "Beginner", None)


def test_seats_run_out_and_the_page_reads_what_is_left(env, monkeypatch):
    _fast(monkeypatch)
    head, vid = _publish(env, _course_page(seats=2), email="seats@maker.cz")
    c = env["client"]
    assert c.post(f"/venues/{vid}/requests", json=_join()).status_code == 201
    again = c.post(f"/venues/{vid}/requests", json=_join(contact=" EVA@example.test "))
    assert again.status_code == 409 and again.json()["refused"] == "already_enrolled"
    assert c.post(f"/venues/{vid}/requests", json=_join(name="Petr", contact="petr@example.test")).status_code == 201
    full = c.post(f"/venues/{vid}/requests", json=_join(name="Ivan", contact="ivan@example.test"))
    assert full.status_code == 409 and full.json()["refused"] == "course_full"
    assert c.get(f"/venues/{vid}/requests/stock?block=course-1").json() == {"left": {"seats": 0}}

    assert c.put(f"/venues/{vid}/page", headers=head, json={**_course_page(seats=3), "published": True}).status_code == 200
    assert c.get(f"/venues/{vid}/requests/stock?block=course-1").json() == {"left": {"seats": 1}}, \
        "a bigger group adds seats; it does not forget who already joined"
    assert c.post(f"/venues/{vid}/requests", json=_join(name="Ivan", contact="ivan@example.test")).status_code == 201


def test_a_course_takes_people_only_while_it_has_seats_and_dates_ahead(env, monkeypatch):
    _fast(monkeypatch)
    c = env["client"]

    def refused(page, email):
        _, vid = _publish(env, page, email=email)
        res = c.post(f"/venues/{vid}/requests", json=_join())
        assert res.status_code in (409, 422), res.text
        assert res.json()["message_uk"] and res.json()["message_en"]
        return res.json()["refused"]

    assert refused(_course_page(seats=None), "noseats@maker.cz") == "enroll_off"
    assert refused(_course_page(sessions="none"), "nodates@maker.cz") == "enroll_off"
    past = [{"id": "s1", "name": "Old", "date": _day(-9), "time": "19:00"}, {"id": "s2", "name": "Older", "date": _day(-3), "time": "19:00"}]
    assert refused(_course_page(sessions=past), "over@maker.cz") == "course_over"
    assert refused(_course_page(hidden=True), "hidcourse@maker.cz") == "no_such_block"
    assert refused(_course_page(audience="pass"), "passcourse@maker.cz") == "no_such_block"


def test_a_session_date_must_be_a_real_date(env):
    head = _login(env, "baddate@maker.cz")
    vid = _create(env, head, "Studio")
    page = _course_page(sessions=[{"id": "s1", "name": "One", "date": "2026-13-01", "time": "19:00"}])
    res = env["client"].put(f"/venues/{vid}/page", headers=head, json={**page, "published": True})
    assert res.status_code == 422 and res.json()["refused"] == "bad_date", res.text
