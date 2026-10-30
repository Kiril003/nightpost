"""Виконавець Намірів на ПК (розд. 07 §8): довіра, ідемпотентність, дедлайн, бюджет смуги.

Тут ПК і телефон говорять байтами, не об'єктами: тест запечатує Намір так,
як це робитиме телефон, і відчиняє відповідь його ключем. Світ (тривоги,
розум, мережа) — підробний, щоб довести саме правила виконавця.
"""
from __future__ import annotations

import asyncio
import os
from datetime import datetime, timedelta, timezone

import pytest

from intents import lanes
from intents.executor import IntentExecutor, OwnPhone
from intents.handlers import (
    OBLAST_ORDER,
    AlertRow,
    AlertsSnapshot,
    Page,
    Presence,
    SourceSilent,
    refuse_url,
)
from intents.wire import (
    OP_ALERTS,
    OP_ASK,
    OP_PAGE_GIST,
    OP_SIREN,
    OP_WHO_ONLINE,
    SEAL_OVERHEAD,
    Intent,
    Status,
    contact_tag,
    minutes_of,
    open_answer,
    seal_intent,
)

NOW = datetime(2026, 10, 2, 14, 2, tzinfo=timezone.utc)
PHONE = OwnPhone(phone_id="a1b2c3d4e5f60718", pc_id="0f1e2d3c4b5a6978", pair_key=os.urandom(32), owner_user_id="owner")


class FakePorts:
    def __init__(self) -> None:
        self.calls: dict[str, int] = {"alerts": 0, "presence": 0, "think": 0, "fetch": 0}
        self.snapshot: AlertsSnapshot | None = AlertsSnapshot(
            alerts=(
                AlertRow("kharkiv", "air_raid", NOW - timedelta(minutes=11)),
                AlertRow("sumy", "artillery_shelling", None),
            ),
            fetched_at=NOW - timedelta(seconds=20),
        )
        self.answer_text = "Притисніть рану чистою тканиною й тримайте тиск. " * 40
        self.presence_row: Presence | None = None
        self.think_delay = 0.0
        self.siren_bytes: bytes | None = bytes(range(92))

    async def alerts(self) -> AlertsSnapshot:
        self.calls["alerts"] += 1
        if self.snapshot is None:
            raise SourceSilent("тест: джерело мовчить")
        return self.snapshot

    async def presence(self, owner_user_id, tag, pair_key):
        self.calls["presence"] += 1
        if contact_tag(pair_key, "mama-node") == tag:
            return self.presence_row
        return None

    async def think(self, system: str, prompt: str) -> str:
        self.calls["think"] += 1
        if self.think_delay:
            await asyncio.sleep(self.think_delay)
        return self.answer_text

    async def siren(self) -> bytes:
        self.calls["siren"] = self.calls.get("siren", 0) + 1
        if self.siren_bytes is None:
            raise SourceSilent("тест: сайт мовчить")
        return self.siren_bytes

    async def fetch(self, url: str) -> Page:
        self.calls["fetch"] += 1
        return Page(title="Новини", text="Укренерго: графіки відключень на завтра не застосовуються. " * 20)


def _executor(ports: FakePorts, phones=(PHONE,), **kw) -> IntentExecutor:
    async def mine():
        return list(phones)

    return IntentExecutor(mine, ports, clock=lambda: NOW, **kw)


def _intent(op: int, args: bytes = b"", *, reply=None, created=None, deadline=5, iid=None) -> Intent:
    return Intent(
        id=iid or os.urandom(8),
        op=op,
        created_min=minutes_of(created or NOW),
        deadline_min=deadline,
        flags=0,
        reply_to=lanes.reply_to(sms_parts=1) if reply is None else reply,
        args=args,
    )


def _send(intent: Intent, phone: OwnPhone = PHONE, counter: int = 7) -> bytes:
    return seal_intent(intent, phone.pair_key, phone.phone_id, phone.pc_id, counter=counter)


def _open(frame: bytes, intent: Intent) -> tuple[int, bytes]:
    plain = open_answer(frame, PHONE.pair_key, PHONE.phone_id, PHONE.pc_id, intent.id)
    assert plain is not None, "телефон не відчинив відповідь своїм ключем"
    return plain[0] & 0x0F, plain[1:]


@pytest.mark.asyncio
async def test_alerts_for_my_oblast_fit_one_sms():
    ports = FakePorts()
    intent = _intent(OP_ALERTS, bytes([OBLAST_ORDER.index("kharkiv"), 0, 0, 0]))
    frame = await _executor(ports).handle(_send(intent))

    assert frame is not None
    assert len(frame) <= 96 + SEAL_OVERHEAD
    status, body = _open(frame, intent)
    assert status == Status.OK
    bitmap = int.from_bytes(body[0:4], "big")
    assert bitmap == (1 << OBLAST_ORDER.index("kharkiv")) | (1 << OBLAST_ORDER.index("sumy"))
    assert body[4] == 1  # повітряна
    assert int.from_bytes(body[5:7], "big") == 11
    assert body[7] == 20  # вік даних, с


@pytest.mark.asyncio
async def test_silent_source_is_not_reported_as_no_alerts():
    ports = FakePorts()
    ports.snapshot = None
    intent = _intent(OP_ALERTS, bytes([OBLAST_ORDER.index("lviv")]))
    status, body = _open(await _executor(ports).handle(_send(intent)), intent)
    assert status == Status.SOURCE_SILENT
    assert body == b""


@pytest.mark.asyncio
async def test_intent_from_a_key_that_is_not_my_phone_gets_silence():
    ports = FakePorts()
    stranger = OwnPhone(PHONE.phone_id, PHONE.pc_id, os.urandom(32), "owner")
    intent = _intent(OP_ALERTS, b"\x07")
    assert await _executor(ports).handle(_send(intent, stranger)) is None
    assert ports.calls["alerts"] == 0


@pytest.mark.asyncio
async def test_a_tampered_byte_gets_silence():
    ports = FakePorts()
    frame = bytearray(_send(_intent(OP_ASK, "що робити?".encode())))
    frame[12] ^= 0x01
    assert await _executor(ports).handle(bytes(frame)) is None
    assert ports.calls["think"] == 0


@pytest.mark.asyncio
async def test_second_copy_of_the_same_id_is_not_executed_twice():
    ports = FakePorts()
    ex = _executor(ports)
    intent = _intent(OP_ASK, "як зупинити кровотечу без джгута?".encode())
    first = await ex.handle(_send(intent, counter=1))
    # Та сама копія, що приїхала іншою смугою.
    again = await ex.handle(_send(intent, counter=1))
    assert ports.calls["think"] == 1
    assert again == first
    assert [e.duplicate for e in ex.journal] == [False, True]


@pytest.mark.asyncio
async def test_copies_racing_on_two_lanes_execute_once():
    ports = FakePorts()
    ports.think_delay = 0.05
    ex = _executor(ports)
    intent = _intent(OP_ASK, "де найближче укриття?".encode())
    a, b = await asyncio.gather(ex.handle(_send(intent, counter=3)), ex.handle(_send(intent, counter=3)))
    assert ports.calls["think"] == 1
    assert a == b is not None


@pytest.mark.asyncio
async def test_expired_intent_is_answered_but_not_executed():
    ports = FakePorts()
    intent = _intent(OP_ALERTS, b"\x07", created=NOW - timedelta(minutes=10), deadline=5)
    status, _ = _open(await _executor(ports).handle(_send(intent)), intent)
    assert status == Status.EXPIRED
    assert ports.calls["alerts"] == 0


@pytest.mark.asyncio
async def test_zero_deadline_means_anytime_within_seven_days():
    ports = FakePorts()
    fresh = _intent(OP_ALERTS, b"\x07", created=NOW - timedelta(days=6), deadline=0)
    stale = _intent(OP_ALERTS, b"\x07", created=NOW - timedelta(days=8), deadline=0)
    ex = _executor(ports)
    assert _open(await ex.handle(_send(fresh)), fresh)[0] == Status.OK
    assert _open(await ex.handle(_send(stale)), stale)[0] == Status.EXPIRED


@pytest.mark.asyncio
async def test_op_outside_the_whitelist_is_refused_without_running_anything():
    ports = FakePorts()
    for op in (0x03, 0x08, 0x09, 0x0A, 0x42, 0xFF):
        intent = _intent(op, b"rm -rf /")
        status, body = _open(await _executor(ports).handle(_send(intent)), intent)
        assert status == Status.UNSUPPORTED and body == b""
    assert sum(ports.calls.values()) == 0


@pytest.mark.parametrize("parts,limit", [(1, 96), (2, 205), (3, 320)])
@pytest.mark.asyncio
async def test_ai_answer_is_shaped_to_the_sms_budget(parts, limit):
    ports = FakePorts()
    intent = _intent(OP_ASK, "як зупинити кровотечу?".encode(), reply=lanes.reply_to(sms_parts=parts))
    frame = await _executor(ports).handle(_send(intent))
    assert len(frame) <= limit + SEAL_OVERHEAD
    status, body = _open(frame, intent)
    assert status == Status.OK
    text = body.decode("utf-8")  # не розірвана посеред літери
    assert text.endswith("…")
    assert len(body) + 1 <= limit


class FakeRelay:
    """Сховок назад до телефона: що лягло в чергу і на що телефон дав квитанцію."""

    def __init__(self) -> None:
        self.sent: list[tuple[bytes, bytes]] = []
        self.acks: set[bytes] = set()

    async def send(self, phone, intent_id, sealed):
        if not any(i == intent_id for i, _ in self.sent):
            self.sent.append((intent_id, sealed))
        return True

    async def acked(self, phone, intent_id):
        return intent_id in self.acks


def _rung_of(frame: bytes) -> int:
    return {0x02: 1, 0x03: 2}[frame[0]]


@pytest.mark.asyncio
async def test_relay_lane_carries_the_longer_answer():
    ports, relay = FakePorts(), FakeRelay()
    ex = _executor(ports, relay=relay)
    both = _intent(OP_ASK, "питання".encode(), reply=lanes.reply_to(sms_parts=1, relay=True))
    sms = await ex.handle(_send(both), "sms")
    # SMS везе щабель 1, а щабель 2 ПК сам поклав у сховок — одним виконанням.
    assert ports.calls["think"] == 1
    assert _rung_of(sms) == 1 and len(_open(sms, both)[1]) < 96
    [(iid, full)] = relay.sent
    assert iid == both.id and _rung_of(full) == 2
    status, long = _open(full, both)
    assert status == Status.OK and 96 < len(long) <= lanes.RELAY_BUDGET
    # Щаблі — різні ключі: шифротекст одного не відчиняється ключем іншого.
    assert full[0] != sms[0]


@pytest.mark.asyncio
async def test_relay_copy_after_sms_does_not_execute_or_queue_again():
    ports, relay = FakePorts(), FakeRelay()
    ex = _executor(ports, relay=relay)
    intent = _intent(OP_ALERTS, b"\x07", reply=lanes.reply_to(sms_parts=1, relay=True))
    await ex.handle(_send(intent), "sms")
    await ex.handle(_send(intent), "relay")
    await ex.handle(_send(intent), "relay")
    assert ports.calls["alerts"] == 1
    assert len(relay.sent) == 1


@pytest.mark.asyncio
async def test_sms_copy_after_relay_receipt_spends_no_sms():
    ports, relay = FakePorts(), FakeRelay()
    ex = _executor(ports, relay=relay)
    intent = _intent(OP_ASK, "питання".encode(), reply=lanes.reply_to(sms_parts=2, relay=True))
    await ex.handle(_send(intent), "relay")
    assert len(relay.sent) == 1
    # Сховок ще не підтвердив — SMS-копія має право на свою відповідь.
    early = await ex.handle(_send(intent), "sms")
    assert early is not None and _rung_of(early) == 1
    # Телефон дав квитанцію на відповідь зі сховку — наступна SMS-копія мовчить.
    relay.acks.add(intent.id)
    assert await ex.handle(_send(intent), "sms") is None
    assert ports.calls["think"] == 1
    assert [e.delivered for e in ex.journal] == ["queued", "sent", "skipped"]


@pytest.mark.asyncio
async def test_quiet_person_never_gets_an_sms_answer():
    """§9.3: P2 у режимі «Тиха людина» ніколи не потрапляє в SMS."""
    ports, relay = FakePorts(), FakeRelay()
    ex = _executor(ports, relay=relay)
    intent = _intent(OP_ASK, "питання".encode(), reply=lanes.reply_to(relay=True))
    # Навіть якщо сам Намір приніс SMS-шлюз — назад SMS не їде.
    assert await ex.handle(_send(intent), "sms") is None
    assert len(relay.sent) == 1 and _rung_of(relay.sent[0][1]) == 2
    assert ex.journal[-1].delivered == "masked" and ex.journal[-1].lane == "sms"


@pytest.mark.asyncio
async def test_without_a_relay_the_relay_lane_answers_nothing():
    ports = FakePorts()
    intent = _intent(OP_ASK, "питання".encode(), reply=lanes.reply_to(relay=True))
    assert await _executor(ports).handle(_send(intent), "relay") is None


@pytest.mark.asyncio
async def test_slow_brain_answers_timeout_not_silence():
    ports = FakePorts()
    ports.think_delay = 0.2
    intent = _intent(OP_ASK, "питання".encode())
    status, _ = _open(await _executor(ports, handler_timeout_s=0.05).handle(_send(intent)), intent)
    assert status == Status.TIMEOUT


@pytest.mark.asyncio
async def test_who_online_names_the_contact_only_by_keyed_tag():
    ports = FakePorts()
    ports.presence_row = Presence(last_heard=NOW - timedelta(minutes=4), lane="relay", my_last_state="delivered")
    ex = _executor(ports)
    mama = _intent(OP_WHO_ONLINE, contact_tag(PHONE.pair_key, "mama-node"))
    status, body = _open(await ex.handle(_send(mama)), mama)
    assert status == Status.OK
    assert int.from_bytes(body[0:2], "big") == 4
    assert body[2] == 1 and body[3] == 3
    # Тег під чужим ключем — інший, і контакт не знаходиться.
    other = _intent(OP_WHO_ONLINE, contact_tag(os.urandom(32), "mama-node"))
    assert _open(await ex.handle(_send(other)), other)[0] == Status.NOT_FOUND


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1:8000/api/v1/settings",
        "http://192.168.1.1/",
        "http://10.0.0.5/admin",
        "http://[::1]/",
        "http://169.254.169.254/latest/meta-data",
        "http://localhost/",
        "http://nas.local/",
        "file:///etc/passwd",
        "ftp://example.com/x",
        "https://user:pw@example.com/",
    ],
)
@pytest.mark.asyncio
async def test_page_gist_never_reads_the_home_network(url):
    ports = FakePorts()
    assert refuse_url(url) is not None
    intent = _intent(OP_PAGE_GIST, url.encode())
    status, _ = _open(await _executor(ports).handle(_send(intent)), intent)
    assert status == Status.REFUSED
    assert ports.calls["fetch"] == 0


@pytest.mark.asyncio
async def test_page_gist_returns_the_gist_within_budget():
    ports = FakePorts()
    ports.answer_text = "Графіки відключень на завтра не застосовуються."
    intent = _intent(OP_PAGE_GIST, b"ua.energy/news", reply=lanes.reply_to(sms_parts=3))
    status, body = _open(await _executor(ports).handle(_send(intent)), intent)
    assert status == Status.OK
    assert body.decode() == "Графіки відключень на завтра не застосовуються."
    assert ports.calls == {"alerts": 0, "presence": 0, "think": 1, "fetch": 1}


@pytest.mark.asyncio
async def test_siren_snapshot_travels_as_is_in_one_sms():
    ports = FakePorts()
    intent = _intent(OP_SIREN)
    frame = await _executor(ports).handle(_send(intent))
    assert len(frame) <= 96 + SEAL_OVERHEAD
    status, body = _open(frame, intent)
    assert status == Status.OK and body == bytes(range(92))


@pytest.mark.asyncio
async def test_siren_silent_site_is_not_an_empty_snapshot():
    ports = FakePorts()
    ports.siren_bytes = None
    intent = _intent(OP_SIREN)
    status, body = _open(await _executor(ports).handle(_send(intent)), intent)
    assert status == Status.SOURCE_SILENT and body == b""
    ports.siren_bytes = b"short"
    other = _intent(OP_SIREN)
    assert _open(await _executor(ports).handle(_send(other)), other)[0] == Status.SOURCE_SILENT
