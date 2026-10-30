"""Рушій краудправди — порт `:core-truth` (телефон) один до одного.

Джерело правди — Kotlin у `phantom-companion/core-truth`. Тут та сама
математика, ті самі числа й той самий порядок підсумовування, бо сервер і
телефон мусять казати про факт одне й те саме. Розбіжність ловить
`tests/test_truth_mirror.py` на векторах, які рахує сам Kotlin
(`scripts/truth-vectors.sh`).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Iterable

MINUTE = 60_000
HOUR = 60 * MINUTE
DAY = 24 * HOUR


@dataclass(frozen=True)
class Reading:
    wire: str
    uk: str
    en: str


OPEN = Reading("open", "відчинено", "open")
CLOSED = Reading("closed", "зачинено", "closed")
STEP_FREE = Reading("step_free", "без сходинок", "step-free")
HAS_STEPS = Reading("has_steps", "зі сходинками", "has steps")
WORKING = Reading("working", "працює", "working")
NOT_WORKING = Reading("not_working", "не працює", "not working")
ON = Reading("on", "є", "on")
OFF = Reading("off", "немає", "off")
QUEUE_NONE = Reading("q0", "без черги", "no queue")
QUEUE_SHORT = Reading("q15", "черга до 15 хв", "queue under 15 min")
QUEUE_MEDIUM = Reading("q45", "черга 15–45 хв", "queue 15–45 min")
QUEUE_LONG = Reading("q60", "черга понад 45 хв", "queue over 45 min")
PASSABLE = Reading("passable", "проїзд відкрито", "road open")
BLOCKED = Reading("blocked", "перекрито", "road closed")


@dataclass(frozen=True)
class FactKind:
    name: str
    wire: str
    half_life_ms: int
    readings: tuple[Reading, ...]
    positive: Reading | None
    remote_factor: float
    importance: float
    embargo_during_alert: bool

    @property
    def binary(self) -> bool:
        return len(self.readings) == 2

    def opposite(self, r: Reading) -> Reading | None:
        if self.binary and r in self.readings:
            return next(x for x in self.readings if x != r)
        return None

    def reading(self, wire: str) -> Reading | None:
        return next((r for r in self.readings if r.wire == wire), None)


KINDS: tuple[FactKind, ...] = (
    FactKind("SHELTER_OPEN", "shelter.open", 7 * DAY, (OPEN, CLOSED), OPEN, 0.3, 3.0, False),
    FactKind("SHELTER_STEP_FREE", "shelter.step_free", 90 * DAY, (STEP_FREE, HAS_STEPS), STEP_FREE, 0.5, 1.0, False),
    FactKind("RESILIENCE_POINT", "point.resilience", 6 * HOUR, (WORKING, NOT_WORKING), WORKING, 0.3, 2.5, False),
    FactKind("PHARMACY", "pharmacy.working", 3 * HOUR, (WORKING, NOT_WORKING), WORKING, 0.2, 1.5, False),
    FactKind("SHOP", "shop.working", 3 * HOUR, (WORKING, NOT_WORKING), WORKING, 0.2, 1.0, False),
    FactKind("ATM", "atm.working", 2 * HOUR, (WORKING, NOT_WORKING), WORKING, 0.2, 1.2, False),
    FactKind("POWER", "utility.power", 1 * HOUR, (ON, OFF), ON, 0.2, 2.0, True),
    FactKind("WATER", "utility.water", 6 * HOUR, (ON, OFF), ON, 0.2, 2.0, True),
    FactKind("SIGNAL", "utility.signal", 2 * HOUR, (ON, OFF), ON, 0.0, 1.5, True),
    FactKind("QUEUE", "queue.wait", 30 * MINUTE, (QUEUE_NONE, QUEUE_SHORT, QUEUE_MEDIUM, QUEUE_LONG), None, 0.0, 1.0, False),
    FactKind("ROAD", "road.passable", 4 * HOUR, (PASSABLE, BLOCKED), PASSABLE, 0.1, 2.0, True),
)
BY_WIRE = {k.wire: k for k in KINDS}
BY_NAME = {k.name: k for k in KINDS}
QUEUE = BY_NAME["QUEUE"]


@dataclass(frozen=True)
class FactKey:
    cell: str
    object_id: str | None
    kind: FactKind


@dataclass(frozen=True)
class Voter:
    id: str
    created_at_ms: int
    network_id: str | None = None
    invited_by: str | None = None


@dataclass(frozen=True)
class Presence:
    cell: str
    from_ms: int
    to_ms: int

    def covers(self, cell: str, at_ms: int, slack_ms: int) -> bool:
        return self.cell == cell and self.from_ms - slack_ms <= at_ms <= self.to_ms + slack_ms


@dataclass(frozen=True)
class Observation:
    voter: str
    key: FactKey
    reading: Reading
    at_ms: int
    presence: Presence | None = None
    #: Сервер не тримає вікна присутності — лише вирок «покривало», знятий
    #: при прийомі. None — рахувати з [presence], як телефон.
    on_site: bool | None = None


LIKELY, UNKNOWN, STALE = "LIKELY", "UNKNOWN", "STALE"


@dataclass(frozen=True)
class Verdict:
    key: FactKey
    state: str
    reading: Reading | None
    probability: float
    mass: float
    supporters: int
    last_support_ms: int | None
    newest_ms: int | None
    dissent: bool
    contested: bool = False


# ── незалежність ─────────────────────────────────────────────────────────────

YOUNG_MS = 30 * DAY
TAIL = 0.5


def circle_mass(weights: Iterable[float]) -> float:
    acc = 0.0
    for i, w in enumerate(sorted(weights, reverse=True)):
        acc = acc + w * TAIL ** i
    return acc


def linked(a: Voter, b: Voter, as_of_ms: int) -> bool:
    if a.invited_by == b.id or b.invited_by == a.id:
        return True
    if a.invited_by is not None and a.invited_by == b.invited_by:
        return True
    if a.network_id is not None and a.network_id == b.network_id:
        if as_of_ms - a.created_at_ms < YOUNG_MS or as_of_ms - b.created_at_ms < YOUNG_MS:
            return True
    return False


def circles(voters: Iterable[Voter], as_of_ms: int) -> dict[str, str]:
    seen: dict[str, Voter] = {}
    for v in voters:
        seen.setdefault(v.id, v)
    items = list(seen.values())
    parent = {v.id: v.id for v in items}

    def find(x: str) -> str:
        r = x
        while parent[r] != r:
            r = parent[r]
        c = x
        while parent[c] != r:
            n = parent[c]
            parent[c] = r
            c = n
        return r

    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            if linked(items[i], items[j], as_of_ms):
                ra, rb = find(items[i].id), find(items[j].id)
                if ra != rb:
                    parent[ra] = rb
    return {v.id: find(v.id) for v in items}


def components(voters: Iterable[Voter], as_of_ms: int) -> dict[str, str]:
    """Той самий поділ на кола, що [circles], але за O(n), а не O(n²).

    [circles] — дзеркало Kotlin один до одного, аж до того, ЯКИЙ голосувальник
    стає коренем кола (це звіряє `test_truth_mirror`). Вироку й звірянню корінь
    байдужий — їм треба лише «ці двоє в одному колі чи ні». А попарне
    порівняння на сервері — це відмова в обслуговуванні: 2000 ключів на одному
    факті коштували 286 мс на кожен GET області й на кожне свідчення під
    глобальним замком запису, 8000 — секунди.

    Чому поділ той самий. [linked] звʼязує пару рівно трьома способами:
      * один запросив другого — ребро до запрошувача, якщо він серед голосів;
      * спільний запрошувач — усі з ним в одній групі;
      * спільна мережа, і хоч один із пари молодший за [YOUNG_MS]. Якщо в
        мережі є хоч один молодий, кожен її член звʼязаний із ним, тож уся
        мережа — одне коло; якщо молодих немає, мережа не звʼязує нікого.
    `test_truth_review` звіряє це з [circles] на випадкових наборах.
    """
    seen: dict[str, Voter] = {}
    for v in voters:
        seen.setdefault(v.id, v)
    parent = {vid: vid for vid in seen}

    def find(x: str) -> str:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    by_inviter: dict[str, str] = {}
    by_network: dict[str, list[str]] = {}
    young_network: set[str] = set()
    for v in seen.values():
        if v.invited_by is not None:
            if v.invited_by in seen:
                union(v.id, v.invited_by)
            first = by_inviter.setdefault(v.invited_by, v.id)
            union(v.id, first)
        if v.network_id is not None:
            by_network.setdefault(v.network_id, []).append(v.id)
            if as_of_ms - v.created_at_ms < YOUNG_MS:
                young_network.add(v.network_id)
    for net in young_network:
        members = by_network[net]
        for m in members[1:]:
            union(m, members[0])
    return {vid: find(vid) for vid in seen}


# ── репутація ────────────────────────────────────────────────────────────────

NEWCOMER = 0.5
CEILING = 3.0
MATURITY_SCALE = 5.0
REP_HALF_LIFE_MS = 180 * DAY
AGE_RAMP_MS = 14 * DAY
AGE_FLOOR = 0.25


@dataclass(frozen=True)
class Standing:
    confirmed: float = 0.0
    refuted: float = 0.0
    as_of_ms: int = 0

    def at(self, now_ms: int) -> "Standing":
        if now_ms <= self.as_of_ms:
            return self
        f = 0.5 ** ((now_ms - self.as_of_ms) / REP_HALF_LIFE_MS)
        return Standing(self.confirmed * f, self.refuted * f, now_ms)


def reputation_weight(s: Standing) -> float:
    n = s.confirmed + s.refuted
    score = (s.confirmed + 1.0) / (n + 2.0)
    maturity = 1.0 - 0.5 ** (n / MATURITY_SCALE)
    return NEWCOMER * (1.0 - maturity) + CEILING * score * score * maturity


def age_factor(voter: Voter | None, at_ms: int) -> float:
    if voter is None:
        return AGE_FLOOR
    t = min(max((at_ms - voter.created_at_ms) / AGE_RAMP_MS, 0.0), 1.0)
    return AGE_FLOOR + (1.0 - AGE_FLOOR) * t


# ── довіра до факту ──────────────────────────────────────────────────────────

PRIOR_MASS = 1.0
LIKELY_AT = 0.7
STALE_AFTER_HALF_LIVES = 2.0
PRESENCE_SLACK_MS = 15 * MINUTE


def decay(kind: FactKind, age_ms: int) -> float:
    return 0.5 ** (max(age_ms, 0) / kind.half_life_ms)


def presence_factor(o: Observation) -> float:
    if o.on_site is not None:
        on_site = o.on_site
    else:
        on_site = o.presence is not None and o.presence.covers(o.key.cell, o.at_ms, PRESENCE_SLACK_MS)
    return 1.0 if on_site else o.key.kind.remote_factor


def agrees(kind: FactKind, a: Reading, b: Reading) -> bool:
    if kind == QUEUE:
        return abs(kind.readings.index(a) - kind.readings.index(b)) <= 1
    return a == b


def latest_per_voter(key: FactKey, observations: Iterable[Observation], now_ms: int) -> list[Observation]:
    best: dict[str, Observation] = {}
    for o in observations:
        if o.key != key or o.at_ms > now_ms:
            continue
        cur = best.get(o.voter)
        if cur is None or o.at_ms > cur.at_ms:
            best[o.voter] = o
    return list(best.values())


def verdict(
    key: FactKey,
    observations: Iterable[Observation],
    voters: dict[str, Voter],
    reputation: Callable[[str], float],
    now_ms: int,
) -> Verdict:
    kind = key.kind
    obs = latest_per_voter(key, observations, now_ms)
    if not obs:
        return Verdict(key, UNKNOWN, None, 1.0 / len(kind.readings), 0.0, 0, None, None, False)
    newest = max(obs, key=lambda o: o.at_ms)
    circ = components([voters.get(o.voter) or Voter(o.voter, o.at_ms) for o in obs], newest.at_ms)

    def weight(o: Observation) -> float:
        return reputation(o.voter) * age_factor(voters.get(o.voter), o.at_ms) * presence_factor(o) * decay(kind, now_ms - o.at_ms)

    support: dict[Reading, float] = {}
    for r in kind.readings:
        groups: dict[str, list[Observation]] = {}
        for o in obs:
            if o.reading == r:
                groups.setdefault(circ.get(o.voter, o.voter), []).append(o)
        total = 0.0
        for members in groups.values():
            total += circle_mass([weight(m) for m in members])
        support[r] = total
    mass = 0.0
    for v in support.values():
        mass += v

    def newest_of(r: Reading) -> float:
        times = [o.at_ms for o in obs if o.reading == r]
        return max(times) if times else float("-inf")

    top = kind.readings[0]
    for r in kind.readings[1:]:
        if (support[r], newest_of(r)) > (support[top], newest_of(top)):
            top = r
    p = (support[top] + PRIOR_MASS / len(kind.readings)) / (mass + PRIOR_MASS)
    stale = now_ms - newest.at_ms > STALE_AFTER_HALF_LIVES * kind.half_life_ms
    state = STALE if stale else (LIKELY if p >= LIKELY_AT else UNKNOWN)
    horizon = int(STALE_AFTER_HALF_LIVES * kind.half_life_ms)
    backers = [o for o in obs if o.reading == top and now_ms - o.at_ms <= horizon]
    if backers:
        last_support = max(o.at_ms for o in backers)
    else:
        tops = [o.at_ms for o in obs if o.reading == top]
        last_support = max(tops) if tops else None
    return Verdict(
        key=key,
        state=state,
        reading=top,
        probability=p,
        mass=mass,
        supporters=len(backers),
        last_support_ms=last_support,
        newest_ms=newest.at_ms,
        dissent=not agrees(kind, newest.reading, top),
        contested=any(not agrees(kind, r, top) and support[r] > 0.0 for r in kind.readings),
    )


def claim_reading(claim: str, kind: FactKind, reading_wire: str | None, current: Verdict | None) -> Reading | None:
    """`Claim.toReading`: підтвердити можна лише ймовірне, заперечити — лише двозначне."""
    if claim in ("assert", "update"):
        return kind.reading(reading_wire or "")
    likely = current.reading if current is not None and current.state == LIKELY else None
    if claim == "confirm":
        return likely
    if claim == "deny":
        return kind.opposite(likely) if likely is not None else None
    return None


# ── звіряння свідчень ────────────────────────────────────────────────────────

MIN_CIRCLES = 2
OPEN_OUTCOME, CONFIRMED, REFUTED = "OPEN", "CONFIRMED", "REFUTED"


def judge(o: Observation, all_obs: list[Observation], circle_of: Callable[[str], str], now_ms: int) -> str:
    window = o.key.kind.half_life_ms // 2
    if now_ms < o.at_ms + window:
        return OPEN_OUTCOME
    own = circle_of(o.voter)
    near = [
        x for x in all_obs
        if x.key == o.key and x.voter != o.voter and abs(x.at_ms - o.at_ms) <= window and circle_of(x.voter) != own
    ]
    agree = len({circle_of(x.voter) for x in near if agrees(o.key.kind, x.reading, o.reading)})
    disagree = len({circle_of(x.voter) for x in near if not agrees(o.key.kind, x.reading, o.reading)})
    if agree >= MIN_CIRCLES and agree > disagree:
        return CONFIRMED
    if disagree >= MIN_CIRCLES and disagree >= 2 * agree:
        return REFUTED
    return OPEN_OUTCOME


def judge_all(context: list[Observation], targets: list[int], circle_of: Callable[[str], str]) -> dict[int, str]:
    """[judge] для кожного з [targets] (індекси в [context], чиє вікно вже
    закрилось) — та сама відповідь, але за O(n·k), а не O(n²).

    [judge] переглядає ВЕСЬ контекст для кожного свідчення; у прибиранні це
    квадрат під глобальним замком запису: 2000 свідчень одного факту —
    5,5 с, протягом яких жодне нове свідчення не лягає. Тут вікно ковзає по
    свідченнях, відсортованих за часом, а для кожного стану тримається
    лічильник «скільки свідчень кожного кола в вікні погоджуються / не
    погоджуються з ним». Кількість різних кіл мінус власне — рівно те, що
    лічить [judge]: власне коло відкидається і умовою `circle != own`, і
    (для того самого голосувальника) `voter != o.voter`, бо голосувальник
    завжди у своєму колі. `test_truth_review` звіряє з [judge] навмання."""
    out: dict[int, str] = {}
    by_key: dict[FactKey, list[int]] = {}
    for i, o in enumerate(context):
        by_key.setdefault(o.key, []).append(i)
    wanted = set(targets)
    for key, idxs in by_key.items():
        mine = [i for i in idxs if i in wanted]
        if not mine:
            continue
        kind = key.kind
        window = kind.half_life_ms // 2
        order = sorted(idxs, key=lambda i: context[i].at_ms)
        agree_cnt = {r: {} for r in kind.readings}
        dis_cnt = {r: {} for r in kind.readings}

        def bump(x: Observation, step: int) -> None:
            c = circle_of(x.voter)
            for r in kind.readings:
                table = agree_cnt[r] if agrees(kind, x.reading, r) else dis_cnt[r]
                n = table.get(c, 0) + step
                if n:
                    table[c] = n
                else:
                    del table[c]

        lo = hi = 0
        for i in sorted(mine, key=lambda i: context[i].at_ms):
            o = context[i]
            while hi < len(order) and context[order[hi]].at_ms <= o.at_ms + window:
                bump(context[order[hi]], +1)
                hi += 1
            while lo < hi and context[order[lo]].at_ms < o.at_ms - window:
                bump(context[order[lo]], -1)
                lo += 1
            own = circle_of(o.voter)
            agree = len(agree_cnt[o.reading]) - (1 if own in agree_cnt[o.reading] else 0)
            disagree = len(dis_cnt[o.reading]) - (1 if own in dis_cnt[o.reading] else 0)
            if agree >= MIN_CIRCLES and agree > disagree:
                out[i] = CONFIRMED
            elif disagree >= MIN_CIRCLES and disagree >= 2 * agree:
                out[i] = REFUTED
            else:
                out[i] = OPEN_OUTCOME
    return out


# ── тривога ──────────────────────────────────────────────────────────────────

AFTER_ALERT_MS = 60 * MINUTE
NEVER = 2 ** 63 - 1


@dataclass(frozen=True)
class AlertWindow:
    start_ms: int
    end_ms: int | None


def release_at_ms(o: Observation, alerts: Iterable[AlertWindow]) -> int:
    if not o.key.kind.embargo_during_alert:
        return o.at_ms
    hit = [a for a in alerts if o.at_ms >= a.start_ms and (a.end_ms is None or o.at_ms <= a.end_ms)]
    if not hit:
        return o.at_ms
    if any(a.end_ms is None for a in hit):
        return NEVER
    return max(a.end_ms for a in hit) + AFTER_ALERT_MS


# ── видима користь ───────────────────────────────────────────────────────────


def band(n: int) -> int:
    if n <= 0:
        return 0
    if n < 5:
        return 1
    if n < 100:
        return n // 5 * 5
    return n // 10 * 10


# ── фільтр на вході ──────────────────────────────────────────────────────────

NOTE_MAX = 280

REFUSALS = {
    "UNKNOWN_KIND": ("Такого виду фактів немає.", "No such kind of fact."),
    "BAD_READING": ("Такого стану для цього факту немає.", "No such state for this fact."),
    "NO_PLACE": ("Факт без місця нічого не каже.", "A fact without a place says nothing."),
    "NOTE_TOO_LONG": ("Примітка задовга.", "The note is too long."),
    "FORBIDDEN_CATEGORY": (
        "Про військових, ППО, влучання й рух техніки повідомляти заборонено законом воєнного стану — це видає їх ворогу.",
        "Reporting troops, air defence, strikes or vehicle movements is banned under martial law — it gives them away.",
    ),
    "FORBIDDEN_TEXT": (
        "У примітці є те, що заборонено публікувати під час воєнного стану. Прибери її або надішли без примітки.",
        "The note mentions something banned from publication under martial law. Remove it or send without a note.",
    ),
}

FORBIDDEN_KIND_ROOTS = (
    "military", "army", "troop", "air_defen", "air-defen", "airdefen", "strike", "impact", "explosion",
    "shelling", "checkpoint", "vehicle", "weapon", "drone", "missile", "artillery", "tcc",
)

FORBIDDEN_ACRONYMS = frozenset({
    "ппо", "пво", "зрк", "бпла", "зсу", "всу", "тцк", "бтр", "бмп", "рсзв", "дрг", "ппошник",
})

FORBIDDEN_STEMS = (
    "зенітн", "дрон", "шахед", "шахід", "герань", "ракет",
    "приліт", "прильот", "прилет", "влучан", "влучил", "влучив", "попадан", "вибух", "взрив", "взрыв",
    "уламк", "обломк", "обстріл", "обстрел",
    "військ", "войск", "военн", "солдат", "військкомат", "воєнкомат", "военкомат",
    "позиці", "позици", "окоп", "бліндаж", "блиндаж", "блокпост", "блокпіст",
    "танк", "артилер", "гармат", "міномет", "миномет", "хаймарс",
    "troop", "military", "soldier", "airdefen", "missile", "drone", "shahed", "strike", "explosion",
    "artillery", "tank", "checkpoint", "himars",
)

LOOKALIKE = {
    "a": "а", "e": "е", "o": "о", "p": "р", "c": "с", "x": "х", "y": "у",
    "i": "і", "k": "к", "m": "м", "t": "т", "h": "н", "b": "в",
}


def utf16_len(s: str) -> int:
    """Довжина так, як її лічить Kotlin: емодзі — дві одиниці, не одна."""
    return len(s.encode("utf-16-le")) // 2


def _tokens(s: str) -> list[str]:
    out: list[str] = []
    cur: list[str] = []
    for ch in s:
        if not ch.isalpha() and ch != "'":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return [t for t in (x.replace("'", "") for x in out) if t]


def _join_spelled_out(toks: list[str]) -> list[str]:
    out = list(toks)
    run = ""
    for t in toks + [""]:
        if len(t) == 1:
            run += t
        else:
            if len(run) > 1:
                out.append(run)
            run = ""
    return out


def forbidden_text(text: str) -> bool:
    lower = text.lower().replace("ё", "е").replace("’", "'").replace("ʼ", "'")
    latin = _tokens(lower)
    cyr = _tokens("".join(LOOKALIKE.get(c, c) for c in lower))
    for toks in (latin, cyr):
        if any(t in FORBIDDEN_ACRONYMS or any(t.startswith(s) for s in FORBIDDEN_STEMS) for t in _join_spelled_out(toks)):
            return True
        if any(a + b in ("airdefense", "airdefence") for a, b in zip(toks, toks[1:])):
            return True
    return False


@dataclass(frozen=True)
class Intake:
    refused: str | None = None
    kind: FactKind | None = None
    reading: Reading | None = None
    note: str | None = None


def check(kind_wire: str, reading_wire: str, cell: str | None, object_id: str | None, note: str | None) -> Intake:
    """`IntakeGate.check`. Військове — відмова ДО словника: такого виду і так немає,
    але людина має почути чому, а не «невідомий вид»."""
    kw = kind_wire.strip().lower()
    if any(root in kw for root in FORBIDDEN_KIND_ROOTS) or forbidden_text(kw):
        return Intake("FORBIDDEN_CATEGORY")
    kind = BY_WIRE.get(kw)
    if kind is None:
        return Intake("UNKNOWN_KIND")
    reading = kind.reading(reading_wire.strip().lower())
    if reading is None:
        return Intake("BAD_READING")
    if cell is None or not cell.strip():
        return Intake("NO_PLACE")
    if object_id is not None and forbidden_text(object_id):
        return Intake("FORBIDDEN_TEXT")
    clean = note.strip() if note is not None else None
    clean = clean or None
    if clean is not None:
        if utf16_len(clean) > NOTE_MAX:
            return Intake("NOTE_TOO_LONG")
        if forbidden_text(clean):
            return Intake("FORBIDDEN_TEXT")
    return Intake(None, kind, reading, clean)



def settle(
    context: list[Observation],
    pending: list[int],
    voters: dict[str, Voter],
    standings: dict[str, Standing],
    now_ms: int,
) -> list[tuple[int, str]]:
    """`ReputationLedger.settle`: кожне свідчення судиться рівно раз — коли закрилось
    його вікно. [pending] — індекси в [context] ще не суджених, у порядку контексту;
    [standings] оновлюється на місці."""
    circ = components([voters.get(o.voter) or Voter(o.voter, o.at_ms) for o in context], now_ms)

    def circle_of(v: str) -> str:
        return circ.get(v, v)

    due = [i for i in pending if now_ms >= context[i].at_ms + context[i].key.kind.half_life_ms // 2]
    outcomes = judge_all(context, due, circle_of)
    out: list[tuple[int, str]] = []
    for i in due:
        o = context[i]
        outcome = outcomes[i]
        s = standings.get(o.voter, Standing(as_of_ms=now_ms)).at(now_ms)
        if outcome == CONFIRMED:
            s = Standing(s.confirmed + 1, s.refuted, s.as_of_ms)
        elif outcome == REFUTED:
            s = Standing(s.confirmed, s.refuted + 1, s.as_of_ms)
        standings[o.voter] = s
        out.append((i, outcome))
    return out
