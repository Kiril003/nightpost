"""Листи телефона на ПК: повний зміст і жодного сирого дроту в стрічці.

Кадри тут — рівно ті рядки, які складає телефон (PeerRevise, PeerRead,
PeerTyping, PeerPayload, PeerLive, PeerCard, PeerFile у core-net), і вектори
взято з їхніх Kotlin-тестів. Бік телефона — стенд із test_messenger_phone_relay.
"""
from __future__ import annotations

import base64
import json

import pytest

from db.database import AsyncSessionLocal
from tests.test_messenger_phone_relay import _feed, _pair, _rows, relay  # noqa: F401

# PeerCardTest.payload — JSON із двокрапками, комами й кирилицею.
KANBAN = "{\"id\":\"kanban_1\",\"title\":\"Вихід\",\"columns\":[{\"id\":\"todo\",\"cards\":[]}]}"
# Класичний вектор ламаної Google: (38.5,-120.2) (40.7,-120.95) (43.252,-126.453).
SHAPE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
WAYPOINTS = "_p~iF~ps|U_ulLnnqC"
ATTACHMENT = json.dumps({
    "blob_id": "0123456789abcdef0123456789abcdef", "key_hex": "aa" * 32, "nonce_hex": "bb" * 12,
    "sha256": "cc" * 32, "size": 48213, "name": "IMG_2041.jpg", "mime": "image/jpeg",
})


def _b64url(text: str) -> str:
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def _card(mark: str, payload: str) -> str:
    return f"PH-CRD:{mark};" + base64.b64encode(payload.encode()).decode().rstrip("=")


async def _round(walker, phone):
    """Захід, доки лист телефона не забрано зі сховку.

    Спільна база тестів набирає десятки доданих телефонів, а один захід бере
    лише 64 адреси (`peer_relay.plan` веде вікно далі з кожним заходом), тож
    лист найсвіжішого телефона може чекати кілька заходів.
    """
    from node import peer_relay as pr

    tag = pr.msg_tag(phone.rk, phone.id, phone.pc["peerId"], phone.epoch())
    for _ in range(64):
        async with AsyncSessionLocal() as s:
            await walker.round(s)
        if not phone.store.under(tag):
            return
    raise AssertionError("лист телефона так і не забрано зі сховку")


def _no_wire(feed: list[dict]) -> None:
    for row in feed:
        shown = json.dumps(row, ensure_ascii=False)
        assert "PH-" not in shown, f"сирий дріт у стрічці: {row}"
        # Опис вкладення (з ключем) — тіло лише рядка-вкладення: веб розбирає
        # його на фото, файл, голосове чи кружок, як і вкладення між ПК.
        # У слові людини, картці чи службовому рядку йому не місце.
        if row["kind"] not in ("image", "file", "voice", "video-note"):
            assert "key_hex" not in shown and "blob_id" not in shown, f"опис вкладення сирим: {row}"


@pytest.mark.anyio
async def test_no_phone_frame_reaches_the_feed_raw(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    said = phone.send("о шостій")
    for body in (
        "PH-LOC:50.45010,30.52340;12",
        f"PH-RTE:4102444800000;3370;420;foot;{WAYPOINTS};{SHAPE}",
        _card("kb", KANBAN),
        f"PH-EDT:{said['id']};{_b64url('о сьомій')}",
        "PH-RDU:1788517702898",
        "PH-TYP:1",
        "PH-TYP:0",
        "PH-SYS:щось",
        "PH-ZZZ:нова збірка",
        "PH-HIST:x",
        "PH-PRF:x",
        "PH-WHR:x",
        "PH-CAS:x",
        ATTACHMENT,
        "PH-FCH:0123456789abcdef0123456789abcdef:0:1:AAAA",
    ):
        phone.send(body)
    await _round(walker, phone)
    _no_wire(_feed(auth_root_client, conv))


@pytest.mark.anyio
async def test_place_route_and_card_arrive_whole(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    place = phone.send("PH-LOC:50.45010,30.52340;12")
    phone.send(f"PH-RTE:4102444800000;3370;420;foot;{WAYPOINTS};{SHAPE}")
    phone.send(_card("kb", KANBAN))
    await _round(walker, phone)
    point, route, card = _feed(auth_root_client, conv)

    assert point["kind"] == "geo:point"
    assert json.loads(point["body"]) == {"lat": 50.4501, "lon": 30.5234, "at": place["sentAtMs"], "acc": 12}

    assert route["kind"] == "route" and route["body"] is None
    assert (route["route_distance_m"], route["route_duration_s"], route["route_profile"]) == (3370, 420, "foot")
    assert route["route_expires_at_ms"] == 4102444800000
    assert route["route_waypoints"] == [[38.5, -120.2], [40.7, -120.95]]
    assert route["route_shape"] == [[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]]

    # Те саме поле, що веб бере на телефоні: kind — слово веба, тіло — JSON картки.
    assert card["kind"] == "widget:kanban" and json.loads(card["body"]) == json.loads(KANBAN)


@pytest.mark.anyio
async def test_edit_and_unsend_change_the_line_in_place(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    first = phone.send("о шостій")
    second = phone.send("це зітру")
    await _round(walker, phone)
    phone.send(f"PH-EDT:{first['id']};{_b64url('о сьомій; не о шостій')}")
    phone.send(f"PH-UNS:{second['id']}")
    await _round(walker, phone)

    feed = _feed(auth_root_client, conv)
    assert len(feed) == 2 and await _rows(conv) == 2, "правка лягла новим листом"
    edited, gone = feed
    assert edited["body"] == "о сьомій; не о шостій" and edited["edited_at"]
    assert gone["deleted_at"] and gone["body"] is None


@pytest.mark.anyio
async def test_an_edit_that_overtook_its_line_waits_for_it(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    import time as _t
    from tests.test_messenger_phone_relay import pp

    late = pp.new_message(phone.id, phone.pc["peerId"], "раз", int(_t.time() * 1000) - 5_000, phone.seed, "")
    phone.send(f"PH-EDT:{late['id']};{_b64url('два')}")
    await _round(walker, phone)
    assert _feed(auth_root_client, conv) == []
    # Сам рядок приїжджає пізніше — з тим самим id.
    frame, _ = pp.seal_envelope(late, phone.ch)
    from node import peer_relay as pr
    import uuid as _u
    tag = pr.msg_tag(phone.rk, phone.id, phone.pc["peerId"], phone.epoch())
    store.boxes[_u.uuid4().hex] = (tag, pr.wrap(frame, phone.rk, tag).blob)
    await _round(walker, phone)
    [row] = _feed(auth_root_client, conv)
    assert row["body"] == "два" and row["edited_at"]


@pytest.mark.anyio
async def test_nobody_edits_a_line_they_did_not_write(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-1", "author_id": "me", "author_name": "ПК", "body": "моє"}).json()
    [(letter, _)] = phone.read()
    phone.send(f"PH-EDT:{letter['id']};{_b64url('чуже')}")
    phone.send(f"PH-UNS:{letter['id']}")
    await _round(walker, phone)
    [mine] = _feed(auth_root_client, conv)
    assert mine["id"] == sent["id"] and mine["body"] == "моє"
    assert not mine["edited_at"] and not mine["deleted_at"]


@pytest.mark.anyio
async def test_read_moves_state_and_typing_is_no_letter(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-1", "author_id": "me", "author_name": "ПК", "body": "бачиш?"}).json()
    [(letter, _)] = phone.read()
    phone.send("PH-TYP:1")
    phone.send(f"PH-RDU:{letter['sentAtMs']}")
    await _round(walker, phone)
    [mine] = _feed(auth_root_client, conv)
    assert mine["id"] == sent["id"] and mine["read_at"], "прочитане не стало станом"
    assert await _rows(conv) == 1

    # Старший кадр, що доїхав пізніше, точку не відсуває.
    phone.send(f"PH-RDU:{letter['sentAtMs'] - 60_000}")
    await _round(walker, phone)
    assert _feed(auth_root_client, conv)[0]["read_at"] == mine["read_at"]


def _media(data: bytes, **extra) -> tuple[str, list[str]]:
    """Опис і шматки так, як їх шле телефон (PeerFile): шифротекст непрозорий."""
    import hashlib
    import os

    from node import peer_frames as pf

    blob_id = os.urandom(16).hex()
    body = json.dumps({
        "blob_id": blob_id, "key_hex": "aa" * 32, "nonce_hex": "bb" * 12,
        "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), **extra,
    }, ensure_ascii=False)
    return body, pf.chunks_of(blob_id, data)


@pytest.mark.anyio
async def test_a_photo_from_the_phone_lands_with_its_bytes(auth_root_client, relay):
    from messenger import blobs

    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    data = bytes(range(256)) * 200  # 51 200 Б — три шматки
    body, chunks = _media(data, name="IMG_2041.jpg", mime="image/jpeg")
    assert len(chunks) == 3
    phone.send(body)
    for c in chunks:
        phone.send(c)
    await _round(walker, phone)
    [row] = _feed(auth_root_client, conv)
    assert row["kind"] == "image" and json.loads(row["body"])["blob_id"] == json.loads(body)["blob_id"]
    assert row["attachment_state"] == "stored"
    assert blobs.read_bytes(json.loads(body)["blob_id"]) == data
    _no_wire(_feed(auth_root_client, conv))


@pytest.mark.anyio
async def test_bytes_that_do_not_match_the_description_never_become_a_blob(auth_root_client, relay):
    from messenger import blobs

    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    body, chunks = _media(b"x" * 5000, name="IMG_1.jpg", mime="image/jpeg")
    phone.send(body)
    phone.send(chunks[0].replace(chunks[0].split(":")[-1], "eXl5eXl5"))  # чужі байти під тим самим id
    await _round(walker, phone)
    [row] = _feed(auth_root_client, conv)
    assert row["kind"] == "image" and row["attachment_state"] is None
    assert blobs.read_bytes(json.loads(body)["blob_id"]) is None


@pytest.mark.anyio
async def test_chunks_that_overtake_the_description_wait_for_it(auth_root_client, relay):
    from messenger import blobs

    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    data = b"\x05" * 30_000
    body, chunks = _media(data, name="voice_1727850000.webm", mime="audio/webm", kind="voice",
                          duration=4.2, waveform=[0.1, 0.8, 0.4])
    for c in chunks:
        phone.send(c)
    await _round(walker, phone)
    assert blobs.read_bytes(json.loads(body)["blob_id"]) is None  # опису ще немає — відбитку теж
    phone.send(body)
    await _round(walker, phone)
    [row] = _feed(auth_root_client, conv)
    assert row["kind"] == "voice" and row["attachment_state"] == "stored"
    said = json.loads(row["body"])
    assert said["duration"] == 4.2 and said["waveform"] == [0.1, 0.8, 0.4]
    assert blobs.read_bytes(said["blob_id"]) == data


@pytest.mark.anyio
async def test_pc_voice_note_reaches_the_phone_with_its_bytes(auth_root_client, relay):
    """ПК → телефон: опис під 32-знаковим іменем, за ним шматки; телефон складає й звіряє відбиток."""
    import base64
    import hashlib

    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]
    data = bytes((i * 7) % 251 for i in range(60_000))
    up = auth_root_client.post("/api/v1/messenger/files/upload", data={"conversation_id": conv},
                               files={"blob": ("voice.bin", data, "application/octet-stream")})
    assert up.status_code in (200, 201), up.text
    blob_id = up.json()["blob_id"]
    # Телефону байти везуть шматки — ні пушу на адресу ПК, ні хмари.
    assert up.json()["state"] == "stored"
    body = json.dumps({"name": "voice_1.webm", "size": len(data), "mime": "audio/webm",
                       "sha256": hashlib.sha256(data).hexdigest(), "blob_id": blob_id, "key_hex": "aa" * 32,
                       "nonce_hex": "bb" * 12, "kind": "voice", "duration": 3, "waveform": [0.2, 0.9]})
    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-voice", "author_id": "me", "author_name": "ПК", "kind": "voice", "body": body})
    assert sent.status_code in (200, 201), sent.text
    async with AsyncSessionLocal() as s:
        while await walker.flush(s):
            pass
    letters = [m for m, _ in phone.read()]
    described = json.loads(next(m["body"] for m in letters if m["body"].startswith("{")))
    assert len(described["blob_id"]) == 32 and described["kind"] == "voice" and described["waveform"] == [0.2, 0.9]
    parts = sorted((m["body"].split(":") for m in letters if m["body"].startswith("PH-FCH:")), key=lambda p: int(p[2]))
    assert [p[1] for p in parts] == [described["blob_id"]] * 3 and parts[0][3] == "3"
    joined = b"".join(base64.b64decode(p[4] + "=" * (-len(p[4]) % 4)) for p in parts)
    assert hashlib.sha256(joined).hexdigest() == described["sha256"] and joined == data


@pytest.mark.anyio
async def test_pc_edits_and_unsends_reach_the_phone(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    sent = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-1", "author_id": "me", "author_name": "ПК", "body": "о шостій"}).json()
    [(letter, _)] = phone.read()
    patched = auth_root_client.patch(f"/api/v1/messenger/conversations/{conv}/messages/{sent['id']}",
                                     json={"body": "о сьомій"})
    assert patched.status_code == 200, patched.text
    assert patched.json()["body"] == "о сьомій"
    [(edit, _)] = phone.read()
    assert edit["body"] == f"PH-EDT:{letter['id']};{_b64url('о сьомій')}"

    gone = auth_root_client.delete(
        f"/api/v1/messenger/conversations/{conv}/messages/{sent['id']}?for_everyone=true")
    assert gone.status_code == 200, gone.text
    [(unsend, _)] = phone.read()
    assert unsend["body"] == f"PH-UNS:{letter['id']}"

    # Лист телефона ПК не переписує: він не автор.
    theirs = phone.send("їхнє")
    await _round(walker, phone)
    row = next(m for m in _feed(auth_root_client, conv) if m["client_id"] == theirs["id"])
    refused = auth_root_client.patch(f"/api/v1/messenger/conversations/{conv}/messages/{row['id']}",
                                     json={"body": "підробка"})
    assert refused.status_code == 400


@pytest.mark.anyio
async def test_pc_place_and_card_reach_the_phone(auth_root_client, relay):
    store, walker = relay
    phone, added = _pair(auth_root_client, store)
    conv = added["conversation_id"]

    point = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-geo", "author_id": "me", "author_name": "ПК", "kind": "geo:point",
        "body": json.dumps({"lat": 50.4501, "lon": 30.5234, "at": 1788517702898, "acc": 12})}).json()
    card = auth_root_client.post(f"/api/v1/messenger/conversations/{conv}/messages", json={
        "client_id": "pc-card", "author_id": "me", "author_name": "ПК", "kind": "widget:kanban",
        "body": KANBAN}).json()
    assert point["delivery_state"] != "failed" and card["delivery_state"] != "failed"
    bodies = sorted(m["body"] for m, _ in phone.read())
    assert bodies == sorted(["PH-LOC:50.45010,30.52340;12", _card("kb", KANBAN)])
