"""Виконавець Намірів на моєму ПК (розд. 07 §8.2, §9.2).

    запечатаний Намір (SMS-шлюз, LAN, сховок — байдуже)
      → чи це ключ МОГО спареного телефона? ні — тиша, без жодної відповіді
      → id бачили? — виконання вже було; лише видати відповідь цією смугою
      → дедлайн минув? — «прострочено», нічого не виконано
      → обробник із білого списку, з таймаутом
      → відповідь кожною дозволеною смугою — своїм щаблем драбини

Гонка смуг (§2.2): той самий Намір може приїхати і SMS, і сховком. Правила:
  * виконання — рівно одне, хоч би скільки копій і хоч би одночасно;
  * сховок везе щабель 2 рівно раз: виконавець кладе його туди сам, щойно
    виконав, якою б смугою не прийшов Намір (якщо reply_to дозволяє сховок);
  * повтор тією самою вузькою смугою (шлюз не отримав відповідь і питає
    знов) — ті самі байти; новий шифротекст був би зайвим;
  * SMS-копія, що прийшла ПІСЛЯ квитанції телефона на відповідь зі сховку,
    відповіді не отримує: людина вже має повнішу, а SMS коштує грошей;
  * смуга, якої немає в reply_to, відповіді не везе ніколи (маска приватності).
"""
from __future__ import annotations

import asyncio
import logging
from collections import OrderedDict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable, Optional, Protocol

from .handlers import HANDLERS, Answer, Context, Handler, Ports
from .lanes import RUNG, SMS_BUDGET, budget_for, lanes_of
from .wire import (
    EPOCH,
    INTENT,
    Intent,
    Status,
    open_intent,
    pair_hint,
    parse_header,
    seal_answer,
)

logger = logging.getLogger(__name__)

__all__ = ["IntentExecutor", "JournalEntry", "LANES", "OP_NAMES", "OwnPhone", "RelayLane"]

#: §2.2: deadline 0 — «будь-коли за 7 діб».
ANYTIME = timedelta(days=7)
#: Скільки ПК пам'ятає виконані id. Довше за найдовший дедлайн, інакше
#: запізніла копія з повільної смуги виконалась би вдруге.
SEEN_TTL = ANYTIME + timedelta(days=1)
SEEN_MAX = 4096
#: §12.4: «таймаут обробника 20 с, після — чесне "ПК думає довше"».
HANDLER_TIMEOUT_S = 20.0

LANES = ("sms", "lan", "relay")
OP_NAMES = {0x01: "alerts", 0x02: "who_online", 0x04: "ask_ai", 0x07: "page_gist", 0x0B: "siren"}


@dataclass(frozen=True)
class OwnPhone:
    """Мій спарений телефон — єдиний, від кого ПК бере Наміри."""

    phone_id: str
    pc_id: str
    pair_key: bytes
    owner_user_id: str


class RelayLane(Protocol):
    """Сховок назад до телефона: лист `PH-ANS:` через `phone_courier`."""

    async def send(self, phone: OwnPhone, intent_id: bytes, sealed: bytes) -> bool:
        """True — відповідь лежить у черзі сховку (зараз чи вже раніше)."""

    async def acked(self, phone: OwnPhone, intent_id: bytes) -> bool:
        """Телефон дав квитанцію на відповідь зі сховку."""


@dataclass(frozen=True)
class JournalEntry:
    """«Що мій ПК робив від мого імені» (§8.3) — без змісту прохань.

    `delivered` — що зроблено з відповіддю для смуги, якою прийшла копія:
    `sent` (віддано цій смузі), `again` (повтор тих самих байтів),
    `masked` (смуга заборонена reply_to), `skipped` (телефон уже має відповідь
    зі сховку), `queued` (лягла в чергу сховку).
    """

    at: datetime
    owner_user_id: str
    phone_id: str
    op: str
    lane: str
    budget: int
    status: int
    answer_bytes: int
    duplicate: bool
    delivered: str = "sent"


@dataclass
class _Done:
    at: datetime
    intent: Intent
    counter: int
    answer: Answer
    sealed: dict[int, bytes] = field(default_factory=dict)
    relay_queued: bool = False


class IntentExecutor:
    def __init__(
        self,
        phones: Callable[[], Awaitable[list[OwnPhone]]],
        ports: Ports,
        *,
        relay: Optional[RelayLane] = None,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        handlers: Optional[dict[int, Handler]] = None,
        handler_timeout_s: float = HANDLER_TIMEOUT_S,
    ) -> None:
        self._phones = phones
        self._ports = ports
        self._relay = relay
        self._clock = clock
        self._handlers = dict(HANDLERS if handlers is None else handlers)
        self._timeout = handler_timeout_s
        self._seen: OrderedDict[tuple[str, bytes], _Done] = OrderedDict()
        self._inflight: dict[tuple[str, bytes], asyncio.Future[_Done]] = {}
        self.journal: deque[JournalEntry] = deque(maxlen=200)

    def attach_relay(self, relay: Optional[RelayLane]) -> None:
        self._relay = relay

    async def handle(self, frame: bytes, lane: str = "sms") -> Optional[bytes]:
        """Запечатана відповідь для смуги `lane` або None.

        None — або Намір не від мого телефона, або цією смугою відповідь не
        їде (маска, вже доставлено сховком). Для `relay` відповідь віддає сам
        виконавець через `RelayLane`; повернуті байти — лише для звіту.
        """
        if lane not in LANES:
            raise ValueError(f"невідома смуга: {lane}")
        head = parse_header(frame)
        if head is None or head.kind != INTENT:
            return None
        found = await self._open(frame, head.pair)
        if found is None:
            return None
        phone, intent = found
        key = (phone.phone_id, intent.id)
        now = self._clock()
        self._forget_old(now)

        duplicate = True
        done = self._seen.get(key)
        if done is None:
            waiting = self._inflight.get(key)
            if waiting is not None:
                done = await asyncio.shield(waiting)
            else:
                duplicate = False
                done = await self._run(key, phone, intent, head.counter, now, lane)
        return await self._deliver(phone, done, lane, now, duplicate)

    async def _run(
        self, key: tuple[str, bytes], phone: OwnPhone, intent: Intent, counter: int, now: datetime, lane: str
    ) -> _Done:
        future: asyncio.Future[_Done] = asyncio.get_running_loop().create_future()
        self._inflight[key] = future
        try:
            answer = await self._execute(phone, intent, now, lane)
            done = _Done(at=now, intent=intent, counter=counter, answer=answer)
            self._seen[key] = done
            while len(self._seen) > SEEN_MAX:
                self._seen.popitem(last=False)
            future.set_result(done)
        except BaseException as exc:
            future.set_exception(exc)
            # Нікому не чекати — але й не лишати «необроблений виняток у Future».
            future.exception()
            raise
        finally:
            self._inflight.pop(key, None)
        # Щабель 2 — у сховок одразу, хоч би якою смугою прийшов Намір (§8.2).
        if "relay" in self._allowed(intent, lane) and lane != "relay":
            await self._queue_relay(phone, done, now, duplicate=False, lane_seen=lane, note=False)
        return done

    @staticmethod
    def _allowed(intent: Intent, lane: str) -> frozenset[str]:
        # reply_to = 0 — телефон не назвав смуг: відповідь їде тією, що привезла,
        # і підрізається під найвужчу (1 SMS), щоб пройти будь-якою щілиною.
        return lanes_of(intent.reply_to) or frozenset({lane})

    def _budget(self, intent: Intent, lane: str) -> int:
        if not lanes_of(intent.reply_to):
            return SMS_BUDGET[1]
        return budget_for(lane, intent.reply_to)

    def _sealed(self, phone: OwnPhone, done: _Done, lane: str) -> bytes:
        rung = RUNG[lane]
        if rung not in done.sealed:
            plain = done.answer.plain(rung, self._budget(done.intent, lane))
            done.sealed[rung] = seal_answer(
                plain, phone.pair_key, phone.phone_id, phone.pc_id, done.intent.id, done.counter, rung=rung
            )
        return done.sealed[rung]

    async def _deliver(
        self, phone: OwnPhone, done: _Done, lane: str, now: datetime, duplicate: bool
    ) -> Optional[bytes]:
        if lane not in self._allowed(done.intent, lane):
            self._note(phone, done, lane, now, b"", duplicate, "masked")
            return None
        if lane == "relay":
            return await self._queue_relay(phone, done, now, duplicate=duplicate, lane_seen=lane, note=True)
        if (
            duplicate
            and lane == "sms"
            and done.relay_queued
            and self._relay is not None
            and await self._relay.acked(phone, done.intent.id)
        ):
            self._note(phone, done, lane, now, b"", duplicate, "skipped")
            return None
        again = RUNG[lane] in done.sealed
        sealed = self._sealed(phone, done, lane)
        self._note(phone, done, lane, now, sealed, duplicate, "again" if again else "sent")
        return sealed

    async def _queue_relay(
        self, phone: OwnPhone, done: _Done, now: datetime, *, duplicate: bool, lane_seen: str, note: bool
    ) -> Optional[bytes]:
        sealed = self._sealed(phone, done, "relay")
        if self._relay is None:
            # Сховок не налаштовано: чесно — відповіді цією смугою немає.
            if note:
                self._note(phone, done, "relay", now, b"", duplicate, "masked")
            return None
        if done.relay_queued:
            if note:
                self._note(phone, done, "relay", now, sealed, duplicate, "again")
            return sealed
        done.relay_queued = await self._relay.send(phone, done.intent.id, sealed)
        self._note(phone, done, "relay", now, sealed, duplicate, "queued" if done.relay_queued else "masked")
        return sealed if done.relay_queued else None

    async def _open(self, frame: bytes, hint: bytes) -> Optional[tuple[OwnPhone, Intent]]:
        for phone in await self._phones():
            if pair_hint(phone.phone_id, phone.pc_id) != hint:
                continue
            intent = open_intent(frame, phone.pair_key, phone.phone_id, phone.pc_id)
            if intent is not None:
                return phone, intent
        return None

    async def _execute(self, phone: OwnPhone, intent: Intent, now: datetime, lane: str) -> Answer:
        created = EPOCH + timedelta(minutes=intent.created_min)
        if created > now:
            # Годинник телефона попереду: дедлайн рахуємо від нашого «зараз»,
            # інакше хибний годинник робив би Намір вічним.
            created = now
        life = timedelta(minutes=intent.deadline_min) if intent.deadline_min else ANYTIME
        if now > created + life:
            return Answer(Status.EXPIRED)
        handler = self._handlers.get(intent.op)
        if handler is None:
            return Answer(Status.UNSUPPORTED)
        allowed = self._allowed(intent, lane)
        narrowest = min(self._budget(intent, ln) for ln in allowed)
        widest = max(self._budget(intent, ln) for ln in allowed)
        ctx = Context(owner_user_id=phone.owner_user_id, pair_key=phone.pair_key, budget=widest - 1, now=now)
        try:
            answer = await asyncio.wait_for(handler(intent, ctx, self._ports), timeout=self._timeout)
        except asyncio.TimeoutError:
            return Answer(Status.TIMEOUT)
        except Exception:  # noqa: BLE001 — обробник упав: людина має це побачити
            logger.exception("intents: обробник op=0x%02x упав", intent.op)
            return Answer(Status.FAILED)
        if 1 + len(answer.body) > widest or (not answer.text and 1 + len(answer.body) > narrowest):
            # Обробник зобов'язаний вміщатися. Не вмістився — це наша помилка,
            # і чесніше сказати «не зміг», ніж послати те, що смуга не довезе.
            logger.error("intents: op=0x%02x дав %d Б при бюджеті %d", intent.op, len(answer.body), narrowest)
            return Answer(Status.FAILED)
        return answer

    def _forget_old(self, now: datetime) -> None:
        while self._seen:
            first = next(iter(self._seen.values()))
            if now - first.at <= SEEN_TTL:
                break
            self._seen.popitem(last=False)

    def _note(
        self,
        phone: OwnPhone,
        done: _Done,
        lane: str,
        now: datetime,
        answer: bytes,
        duplicate: bool,
        delivered: str,
    ) -> None:
        self.journal.append(
            JournalEntry(
                at=now,
                owner_user_id=phone.owner_user_id,
                phone_id=phone.phone_id,
                op=OP_NAMES.get(done.intent.op, f"0x{done.intent.op:02x}"),
                lane=lane,
                budget=self._budget(done.intent, lane),
                status=done.answer.status,
                answer_bytes=len(answer),
                duplicate=duplicate,
                delivered=delivered,
            )
        )

    def journal_for(self, owner_user_id: str) -> list[JournalEntry]:
        return [e for e in reversed(self.journal) if e.owner_user_id == owner_user_id]
