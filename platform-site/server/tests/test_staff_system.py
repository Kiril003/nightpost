"""Пульт персоналу v2: системний вигляд лише на читання.

Кожна нова ручка мусить відмовити анонімові (401), звичайному користувачеві
(403) і ролі нижче потрібної (403) — і лише тоді віддати дані. Дані — лише
лічильники й знеособлене: маскована пошта, хеш пристрою до 8 знаків, жодного
ключа, адреси RPC чи назви секрету.
"""
import sqlite3
import sys
import time
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage")

ROUTES = {
    "/api/staff/moderation": "moderator",
    "/api/staff/erasures": "admin",
    "/api/staff/money": "admin",
    "/api/staff/assistant": "admin",
    "/api/staff/system": "admin",
    "/api/staff/roles": "admin",
    "/api/staff/support/reports": "moderator",
}

SENTINEL = "sekret-sentinel-0123456789"


@pytest.fixture()
def env(tmp_path, monkeypatch):
    (tmp_path / "data").mkdir()
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "data" / "acc.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    (tmp_path / "relay").mkdir()
    for name in ("TRUTH_DB", "RELAY_DB"):
        monkeypatch.setenv(name, str(tmp_path / "relay" / f"{name.lower()}.db"))
    for name in ("VENUE_DB", "POINTS_DB"):
        monkeypatch.setenv(name, str(tmp_path / "data" / f"{name.lower()}.db"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    monkeypatch.setenv("OWNER_EMAIL", "vlasnyk@example.com")
    for name in ("GEMINI_API_KEY", "VERTEX_PROJECT", "HELIUS_WEBHOOK_AUTH", "SOLANA_CLUSTER"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("HELIUS_API_KEY", SENTINEL)
    monkeypatch.setenv("HELIUS_WEBHOOK_ID_DEVNET", SENTINEL)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import mailer
    import helius_hook
    import brain

    from fastapi.testclient import TestClient

    helius_hook._reset_for_tests()
    brain._reset_ask_counts()
    sent = []
    mailer._transport = lambda to, subject, body: sent.append((to, subject, body)) or True
    client = TestClient(server.app)

    def login(email):
        client.post("/api/auth/request", json={"email": email})
        body = [b for to, _, b in sent if to == email][-1]
        code = [w for w in body.replace("\n", " ").split() if w.isdigit() and len(w) == 6][0]
        res = client.post("/api/auth/verify", json={"email": email, "code": code})
        assert res.status_code == 200, res.text
        return res.json()["token"]

    tokens = {"owner": login("vlasnyk@example.com")}
    owner_head = {"Authorization": f"Bearer {tokens['owner']}"}
    for role in ("user", "moderator", "admin"):
        tokens[role] = login(f"{role}@example.com")
        if role != "user":
            me = client.get("/api/me", headers={"Authorization": f"Bearer {tokens[role]}"}).json()
            res = client.post(
                "/api/staff/role", headers=owner_head, json={"account_id": me["account"]["id"], "role": role}
            )
            assert res.status_code == 200, res.text
    return server, client, tokens


def _h(token):
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.parametrize("path", sorted(ROUTES))
def test_анонім_отримує_401(env, path):
    _, client, _ = env
    assert client.get(path).status_code == 401
    assert client.get(path, headers=_h("not-a-session")).status_code == 401


@pytest.mark.parametrize("path", sorted(ROUTES))
def test_користувач_отримує_403(env, path):
    _, client, tokens = env
    assert client.get(path, headers=_h(tokens["user"])).status_code == 403


@pytest.mark.parametrize("path", sorted(p for p, r in ROUTES.items() if r == "admin"))
def test_модератор_не_бачить_адмінських_ручок(env, path):
    _, client, tokens = env
    assert client.get(path, headers=_h(tokens["moderator"])).status_code == 403


@pytest.mark.parametrize("path", sorted(ROUTES))
def test_потрібна_роль_і_вища_бачать(env, path):
    _, client, tokens = env
    needed = ROUTES[path]
    allowed = ["moderator", "admin", "owner"][["moderator", "admin", "owner"].index(needed):]
    for role in allowed:
        assert client.get(path, headers=_h(tokens[role])).status_code == 200, (path, role)


@pytest.mark.parametrize("path", sorted(ROUTES))
def test_жодна_ручка_не_віддає_секрет_чи_адресу_rpc(env, path):
    _, client, tokens = env
    text = client.get(path, headers=_h(tokens["owner"])).text
    assert SENTINEL not in text
    for leak in ("api-key", "helius-rpc", "HELIUS", "VERTEX_", "GOOGLE_", "http://", "https://"):
        assert leak not in text, (path, leak)


def test_черга_видалення_маскує_субʼєкт_і_каже_стан(env):
    server, client, tokens = env
    now = int(time.time())
    key = "ab" * 32
    server.store.erase_pending_put(key, "root", now - 100, now + 3600)
    server.store.erase_pending_put("acc-1234567890", "account", now - 9000, now - 60)
    server.store.erase_pending_put("cd" * 32, "root", now - 50, now + 50)
    server.store.erase_pending_cancel("cd" * 32, now)

    rows = client.get("/api/staff/erasures", headers=_h(tokens["admin"])).json()["rows"]
    by_status = {r["status"]: r for r in rows}
    assert set(by_status) == {"waiting", "overdue", "cancelled"}
    assert by_status["waiting"]["subject"] == key[:8]
    assert by_status["waiting"]["kind"] == "root"
    assert by_status["waiting"]["due_at"] == now + 3600
    assert by_status["overdue"]["subject"] == "acc-1234"
    assert all(len(r["subject"]) <= 8 for r in rows)
    assert key not in client.get("/api/staff/erasures", headers=_h(tokens["admin"])).text


def test_модерація_зводить_заявки_й_нотатки_мапи(env, tmp_path):
    server, client, tokens = env
    import venue_claims

    owner_id = client.get("/api/me", headers=_h(tokens["user"])).json()["account"]["id"]
    venue_claims._claims_store.create_claim(
        "v_test1", owner_id, "node", 42, "Кав'ярня", 50.0, 30.0, 12, "фото вивіски"
    )
    now_ms = int(time.time() * 1000)
    with sqlite3.connect(server.truth_db.path) as conn:
        conn.execute(
            "INSERT INTO truth_moderation (cell, kind, note, hour_ms, expires_ms) VALUES (?,?,?,?,?)",
            ("881e2a0001fffff", "queue", "довга черга", now_ms, now_ms + 86400000),
        )

    data = client.get("/api/staff/moderation", headers=_h(tokens["moderator"])).json()
    assert [c["osm_name"] for c in data["claims"]] == ["Кав'ярня"]
    claim = data["claims"][0]
    assert claim["owner"] == "u***@example.com"
    assert "owner_id" not in claim
    assert [n["note"] for n in data["map_notes"]] == ["довга черга"]
    assert data["map_notes"][0]["kind"] == "queue"


def test_гроші_кажуть_кластер_і_здоровʼя_хука_без_ключів(env):
    _, client, tokens = env
    assert client.post("/api/solana/hook", json=[]).status_code == 503

    data = client.get("/api/staff/money", headers=_h(tokens["admin"])).json()
    assert data["cluster"] == "devnet"
    assert data["bills"] is None
    hook = data["hook"]
    assert hook["hook_503"] == 1
    assert hook["last_event_age_s"] is None
    assert hook["webhook"] == {"devnet": True, "mainnet": False}
    assert hook["auth"] is False
    assert data["payment_proofs"] == {"devnet": 0, "mainnet": 0}


def test_прийнята_подія_хука_оновлює_вік(env, monkeypatch):
    _, client, tokens = env
    monkeypatch.setenv("HELIUS_WEBHOOK_AUTH", "hook-auth")
    assert client.post("/api/solana/hook", json=[], headers={"Authorization": "hook-auth"}).status_code == 200
    hook = client.get("/api/staff/money", headers=_h(tokens["admin"])).json()["hook"]
    assert hook["hook_ok"] == 1
    assert hook["last_event_age_s"] is not None and hook["last_event_age_s"] <= 5


def test_помічник_лише_лічильники(env):
    _, client, tokens = env
    secret_text = "мій таємний запит про лікаря"
    res = client.post(
        "/api/brain/v1beta/models/gemini-3.8-flash:streamGenerateContent",
        json={"contents": [{"parts": [{"text": secret_text}]}]},
        headers={"X-Nightpost-Device": "d" * 32},
    )
    assert res.status_code == 503

    reply = client.get("/api/staff/assistant", headers=_h(tokens["admin"]))
    assert secret_text not in reply.text
    data = reply.json()
    assert data["upstream"] == "none"
    assert len(data["days"]) == 1
    row = data["days"][0]
    assert set(row) == {"day", "profile", "model", "upstream", "asks", "refused", "failed"}
    assert row["asks"] == 1 and row["failed"] == 1
    assert data["fallback"] == []
    assert data["today"] == {"devices": 0, "asks": 0}


def test_збій_токена_vertex_рахується_у_fallback(env, monkeypatch):
    _, client, tokens = env
    monkeypatch.setenv("VERTEX_PROJECT", "np-test")
    monkeypatch.setenv("GOOGLE_SA_JSON", "{}")
    res = client.post(
        "/api/brain/v1beta/models/gemini-3.8-flash:streamGenerateContent",
        json={"contents": [{"parts": [{"text": "привіт"}]}]},
        headers={"X-Nightpost-Device": "d" * 32},
    )
    assert res.status_code == 502

    data = client.get("/api/staff/assistant", headers=_h(tokens["admin"])).json()
    assert data["upstream"] == "vertex"
    assert [r["vertex_failed"] for r in data["fallback"]] == [1]


def test_система_каже_правду_про_те_чого_не_знає(env):
    _, client, tokens = env
    data = client.get("/api/staff/system", headers=_h(tokens["admin"])).json()
    assert data["ok"] is True
    assert data["relay"] in (True, False)
    assert data["sha"] is None
    assert data["uptime_s"] >= 0
    assert data["started_at"].endswith("+00:00")


def test_ролі_маскують_пошту_й_не_перелічують_користувачів(env):
    _, client, tokens = env
    client.get("/api/me", headers=_h(tokens["moderator"]))
    data = client.get("/api/staff/roles", headers=_h(tokens["admin"])).json()
    assert data["counts"] == {"user": 1, "moderator": 1, "admin": 1, "owner": 1}
    emails = {row["email"]: row for row in data["staff"]}
    assert set(emails) == {"v***@example.com", "m***@example.com", "a***@example.com"}
    assert emails["m***@example.com"]["role"] == "moderator"
    assert "id" not in emails["m***@example.com"]
    assert "user@example.com" not in str(data) and "u***@example.com" not in str(data)


def test_маска_пошти():
    sys.path.insert(0, str(SERVER_DIR))
    import staff_system as ss

    assert ss.mask_email("vlasnyk@example.com") == "v***@example.com"
    assert ss.mask_email("key:" + "ef" * 32 + "@nightpost.local") == "key efefefef"
    assert ss.mask_email("") == ""
    assert ss.mask_email("broken") == "***"


def test_доказ_оплати_з_кривим_evidence_не_валить_гроші(tmp_path):
    import points

    ps = points.PointsStore(tmp_path / "points.db")
    with ps.connect() as conn:
        rows = [("a", "payment.first", "r1", 1, 1, '{"cluster": "devnet"}'),
                ("b", "payment.first", "r2", 1, 1, "not json"),
                ("c", "payment.first", "r3", 1, 1, "[1, 2]")]
        conn.executemany("INSERT INTO events (subject, kind, ref, points, at, evidence) VALUES (?, ?, ?, ?, ?, ?)", rows)
    assert ps.payment_proofs() == {"devnet": 1, "mainnet": 0}


def _note(server, text="довга черга"):
    now_ms = int(time.time() * 1000)
    with sqlite3.connect(server.truth_db.path) as conn:
        cur = conn.execute(
            "INSERT INTO truth_moderation (cell, kind, note, hour_ms, expires_ms) VALUES (?,?,?,?,?)",
            ("881e2a0001fffff", "queue", text, now_ms, now_ms + 86400000),
        )
        return cur.lastrowid


def test_сховати_нотатку_мапи_лише_модератор_і_вище(env):
    server, client, tokens = env
    note_id = _note(server)
    hide_path = f"/api/staff/moderation/notes/{note_id}/hide"
    unhide_path = f"/api/staff/moderation/notes/{note_id}/unhide"
    assert client.post(hide_path).status_code == 401
    assert client.post(unhide_path).status_code == 401
    assert client.post(hide_path, headers=_h(tokens["user"])).status_code == 403
    assert client.post(unhide_path, headers=_h(tokens["user"])).status_code == 403
    assert client.post(unhide_path, headers=_h(tokens["moderator"])).status_code == 404

    assert client.post(hide_path, headers=_h(tokens["moderator"])).status_code == 200
    assert client.post(hide_path, headers=_h(tokens["moderator"])).status_code == 404
    notes = client.get("/api/staff/moderation", headers=_h(tokens["moderator"])).json()["map_notes"]
    assert len(notes) == 1 and notes[0]["id"] == note_id and notes[0]["hidden"] is True

    assert client.post(unhide_path, headers=_h(tokens["moderator"])).status_code == 200
    assert client.post(unhide_path, headers=_h(tokens["moderator"])).status_code == 404
    notes = client.get("/api/staff/moderation", headers=_h(tokens["moderator"])).json()["map_notes"]
    assert len(notes) == 1 and notes[0]["id"] == note_id and notes[0]["hidden"] is False

    events = client.get("/api/staff/events", headers=_h(tokens["admin"])).json()
    hidden = [e for e in events if e["kind"] == "map_note_hidden"]
    restored = [e for e in events if e["kind"] == "map_note_restored"]
    assert len(hidden) == 1
    assert hidden[0]["detail"] == f"moderator@example.com → {note_id} (queue @ 881e2a0001fffff)"
    assert "черга" not in hidden[0]["detail"]
    assert len(restored) == 1
    assert restored[0]["detail"] == f"moderator@example.com → {note_id} (queue @ 881e2a0001fffff)"


def test_міграція_та_прибирання_прихованих_нотаток(tmp_path):
    import truth_store as truth

    db_path = tmp_path / "legacy_truth.db"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "CREATE TABLE truth_moderation ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, cell TEXT NOT NULL, kind TEXT NOT NULL, "
            "note TEXT NOT NULL, hour_ms INTEGER NOT NULL, expires_ms INTEGER NOT NULL)"
        )
        conn.execute(
            "INSERT INTO truth_moderation (cell, kind, note, hour_ms, expires_ms) VALUES (?,?,?,?,?)",
            ("881e2a0001fffff", "queue", "старий запис", 1000, 5000),
        )
    ts = truth.TruthStore(db_path)
    assert ts.moderation_recent(now_ms=2000) == [
        {"id": 1, "cell": "881e2a0001fffff", "kind": "queue", "note": "старий запис", "hour_ms": 1000, "hidden": False}
    ]
    assert ts.moderation_hide(1, "mod-1", now_ms=2500) == {"cell": "881e2a0001fffff", "kind": "queue"}
    assert ts.moderation_recent(now_ms=2500)[0]["hidden"] is True
    ts.sweep(now_ms=6000)
    assert ts.moderation_recent(now_ms=2000) == []


def test_адмін_скасовує_очікуване_видалення(env):
    server, client, tokens = env
    now = int(time.time())
    key = "ef" * 32
    server.store.erase_pending_put(key, "root", now - 100, now + 3600)
    path = f"/api/staff/erasures/{key}/cancel"

    assert client.post(path).status_code == 401
    assert client.post(path, headers=_h(tokens["user"])).status_code == 403
    assert client.post(path, headers=_h(tokens["moderator"])).status_code == 403
    assert client.post("/api/staff/erasures/unknown-subject/cancel", headers=_h(tokens["admin"])).status_code == 404

    res = client.post(path, headers=_h(tokens["admin"]))
    assert res.status_code == 200, res.text
    assert server.store.erase_pending_get(key) is None
    assert client.post(path, headers=_h(tokens["admin"])).status_code == 404

    rows = client.get("/api/staff/erasures", headers=_h(tokens["admin"])).json()["rows"]
    assert [r for r in rows if r["status"] in ("waiting", "overdue")] == []
    assert any(r["subject"] == key[:8] and r["status"] == "cancelled" for r in rows)

    twin_a, twin_b = "ab" * 4 + "01" * 28, "ab" * 4 + "02" * 28
    server.store.erase_pending_put(twin_a, "root", now - 100, now + 3600)
    server.store.erase_pending_put(twin_b, "root", now - 100, now + 3600)
    assert client.post(f"/api/staff/erasures/{'ab' * 4}/cancel", headers=_h(tokens["admin"])).status_code == 409
    assert server.store.erase_pending_get(twin_a) is not None and server.store.erase_pending_get(twin_b) is not None

    events = client.get("/api/staff/events", headers=_h(tokens["admin"])).json()
    cancelled = [e for e in events if e["kind"] == "erase_cancelled_by_staff"]
    assert len(cancelled) == 1
    assert cancelled[0]["detail"] == f"admin@example.com → {key[:8]}"
    assert key not in cancelled[0]["detail"]


def test_адмін_скасовує_видалення_облікового_запису_за_вісьмома_знаками(env):
    server, client, tokens = env
    now = int(time.time())
    account_id = client.get("/api/me", headers=_h(tokens["user"])).json()["account"]["id"]
    server.store.erase_pending_put(account_id, "account", now - 100, now + 3600)
    server.store.set_disabled(account_id, True)
    twin = account_id[:8] + "-gone"
    server.store.erase_pending_put(twin, "account", now - 100, now + 3600)
    assert server.store.erase_pending_cancel(twin, now)

    res = client.post(f"/api/staff/erasures/{account_id[:8]}/cancel", headers=_h(tokens["admin"]))
    assert res.status_code == 200, res.text
    assert server.store.erase_pending_get(account_id) is None
    assert not server.store.account_by_id(account_id)["disabled"]


def test_рішення_по_заявці_йде_в_журнал_з_автором(env):
    server, client, tokens = env
    import venue_claims

    owner_id = client.get("/api/me", headers=_h(tokens["user"])).json()["account"]["id"]
    row, _ = venue_claims._claims_store.create_claim(
        "v_test2", owner_id, "node", 43, "Пекарня", 50.0, 30.0, 12, "фото вивіски"
    )
    claim_id = row["id"]
    res = client.post(f"/claims/{claim_id}/decision", headers=_h(tokens["moderator"]), json={"approve": False})
    assert res.status_code == 200, res.text
    assert res.json()["claim"]["status"] == "rejected"
    events = client.get("/api/staff/events", headers=_h(tokens["admin"])).json()
    rejected = [e for e in events if e["kind"] == "claim_rejected"]
    assert [e["detail"] for e in rejected] == [f"moderator@example.com → {claim_id}"]
    assert client.get("/api/staff/moderation", headers=_h(tokens["moderator"])).json()["claims"] == []


def test_сховати_та_повернути_сторінку_закладу(env):
    _, client, tokens = env
    import venues

    user_head = _h(tokens["user"])
    mod_head = _h(tokens["moderator"])
    res = client.post("/venues", headers=user_head, json={"name": "Кав'ярня на площі"})
    assert res.status_code == 201, res.text
    vid = res.json()["id"]
    page = {
        "v": 1,
        "published": True,
        "blocks": [
            {"type": "title", "text": "Кав'ярня на площі"},
            {"type": "address", "text": "Masarykovo nám. 1", "lat": 49.8356, "lon": 18.2925},
            {"type": "event", "id": "e1", "title": "Джаз", "starts": "2027-01-10T19:00"},
        ],
    }
    put_res = client.put(f"/venues/{vid}/page", headers=user_head, json=page)
    assert put_res.status_code == 200, put_res.text
    area = put_res.json()["location"]["area"]

    assert [v["id"] for v in client.get(f"/venues/near?cell={area}").json()["venues"]] == [vid]
    assert client.get(f"/venues/{vid}").status_code == 200
    mod_venues = client.get("/api/staff/moderation", headers=mod_head).json()["venues"]
    assert mod_venues == [{"id": vid, "name": "Кав'ярня на площі", "area": area, "rating": None, "hidden": False}]

    hide_path = f"/api/staff/venues/{vid}/hide"
    unhide_path = f"/api/staff/venues/{vid}/unhide"
    assert client.post(hide_path).status_code == 401
    assert client.post(unhide_path).status_code == 401
    assert client.post(hide_path, headers=user_head).status_code == 403
    assert client.post(unhide_path, headers=user_head).status_code == 403
    assert client.post(unhide_path, headers=mod_head).status_code == 404
    assert client.post("/api/staff/venues/0123456789abcdef/hide", headers=mod_head).status_code == 404

    assert client.post(hide_path, headers=mod_head).status_code == 200
    assert client.post(hide_path, headers=mod_head).status_code == 404
    assert client.get(f"/venues/near?cell={area}").json()["venues"] == []
    assert client.get(f"/venues/{vid}").status_code == 404
    assert client.get(f"/venues/{vid}", headers=mod_head).status_code == 404
    assert [v["id"] for v in client.get("/venues/mine", headers=user_head).json()["venues"]] == [vid]
    assert client.get(f"/venues/{vid}", headers=user_head).status_code == 200
    mod_venues = client.get("/api/staff/moderation", headers=mod_head).json()["venues"]
    assert len(mod_venues) == 1 and mod_venues[0]["id"] == vid and mod_venues[0]["hidden"] is True
    assert client.get(f"/venues/{vid}/facts").status_code == 404
    rate = {"pub_key": "A" * 44, "nonce": "0" * 32, "issued_ms": 1, "signature": "A" * 88, "stars": 5}
    assert client.post(f"/venues/{vid}/rate", json=rate).status_code == 404
    assert client.post(f"/events/{vid}.e1/rsvp", json={**rate, "going": True}).status_code == 404
    assert venues.cell_of(vid) is None

    assert client.post(unhide_path, headers=mod_head).status_code == 200
    assert client.post(unhide_path, headers=mod_head).status_code == 404
    assert [v["id"] for v in client.get(f"/venues/near?cell={area}").json()["venues"]] == [vid]
    assert client.get(f"/venues/{vid}/facts").status_code == 200
    assert client.post(f"/events/{vid}.e1/rsvp", json={**rate, "going": True}).status_code == 401
    assert client.post(f"/venues/{vid}/rate", json=rate).status_code == 401
    assert venues.cell_of(vid) is not None
    assert client.get(f"/venues/{vid}").status_code == 200
    mod_venues = client.get("/api/staff/moderation", headers=mod_head).json()["venues"]
    assert len(mod_venues) == 1 and mod_venues[0]["id"] == vid and mod_venues[0]["hidden"] is False

    assert client.post(hide_path, headers=mod_head).status_code == 200
    assert client.put(f"/venues/{vid}/page", headers=user_head, json={**page, "published": False}).status_code == 200
    mod_venues = client.get("/api/staff/moderation", headers=mod_head).json()["venues"]
    assert [(v["id"], v["hidden"]) for v in mod_venues] == [(vid, True)]
    assert client.post(unhide_path, headers=mod_head).status_code == 200
    assert client.get("/api/staff/moderation", headers=mod_head).json()["venues"] == []

    events = client.get("/api/staff/events", headers=_h(tokens["admin"])).json()
    hidden = [e for e in events if e["kind"] == "venue_hidden"]
    restored = [e for e in events if e["kind"] == "venue_restored"]
    assert [e["detail"] for e in hidden] == [f"moderator@example.com → {vid}"] * 2
    assert [e["detail"] for e in restored] == [f"moderator@example.com → {vid}"] * 2


def test_міграція_прихованих_закладів(tmp_path):
    import venues

    db_path = tmp_path / "legacy_venues.db"
    vid = "0123456789abcdef"
    area = "861e2a007ffffff"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "CREATE TABLE venues ("
            "id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, name TEXT NOT NULL, page TEXT NOT NULL, "
            "published INTEGER NOT NULL DEFAULT 0, rev INTEGER NOT NULL DEFAULT 0, menu_version TEXT, "
            "token TEXT, wallet TEXT, address TEXT, lat REAL, lon REAL, cell TEXT, area TEXT, "
            "created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL)"
        )
        conn.execute(
            "INSERT INTO venues (id, owner_id, name, page, published, area, created_ms, updated_ms) "
            "VALUES (?, ?, ?, ?, 1, ?, 1000, 1000)",
            (vid, "owner-1", "Старий заклад", '{"v":1,"blocks":[]}', area),
        )
    vs = venues.VenueStore(db_path)
    assert vs.moderation_recent() == [
        {"id": vid, "name": "Старий заклад", "area": area, "rating": None, "hidden": False}
    ]
    assert len(vs.near([area])) == 1
    assert vs.moderation_hide(vid, now_ms=2000) == {"id": vid, "name": "Старий заклад", "area": area}
    assert vs.moderation_hide(vid, now_ms=2000) is None
    assert vs.near([area]) == []
    assert [r["id"] for r in vs.of_owner("owner-1")] == [vid]
    assert vs.moderation_recent()[0]["hidden"] is True
    assert vs.moderation_unhide(vid) == {"id": vid, "name": "Старий заклад", "area": area}
    assert vs.moderation_unhide(vid) is None
    assert len(vs.near([area])) == 1
