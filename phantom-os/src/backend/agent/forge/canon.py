"""Канон — одна правда світу або проєкту, і суперечності ловить код, не модель.

Навіщо. Великі витвори (серіал на двадцять сезонів, гра, дослідження на
тисячі джерел) розвалюються не через слабкі окремі шматки, а через те, що
шматки суперечать один одному: персонаж третього плану, вбитий у першому
сезоні, розмовляє у двадцятому; двоє «єдиних дітей» виявляються братами.
Модель цього не втримує — ні слабка, ні сильна: вікно контексту скінченне.

Тому правда світу живе не в промптах, а тут: сутності, факти з часом і
джерелом, правила. Кожен новий факт перевіряється детерміновано. Модель
лише пропонує факти; канон вирішує, чи вони сумісні з усім, що вже є.

Час — `Stamp`: кортеж цілих, що порівнюється лексикографічно:
(сезон, серія, сцена) для серіалу, (етап, крок) для проєкту, (рік,) для
історії. `None` — поза часом (незмінні властивості).
"""
from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable

Stamp = tuple[int, ...]


@dataclass(frozen=True)
class Fact:
    subject: str
    predicate: str
    object: str
    at: Stamp | None = None
    source: str = ""

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["at"] = list(self.at) if self.at is not None else None
        return d

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Fact":
        at = d.get("at")
        return Fact(
            subject=str(d["subject"]),
            predicate=str(d["predicate"]),
            object=str(d.get("object", "")),
            at=tuple(int(x) for x in at) if at is not None else None,
            source=str(d.get("source", "")),
        )


@dataclass
class Entity:
    id: str
    kind: str
    name: str
    attrs: dict[str, Any] = field(default_factory=dict)


@dataclass
class Contradiction:
    kind: str
    message: str
    fact: Fact
    conflicts_with: list[Fact] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "message": self.message,
            "fact": self.fact.to_dict(),
            "conflicts_with": [f.to_dict() for f in self.conflicts_with],
        }


@dataclass
class Schema:
    """Правила світу. Розширюються під проєкт; типові — для оповідей і проєктів."""

    # Одне значення в кожен момент (де зараз, ким працює, стан).
    functional: set[str] = field(default_factory=lambda: {"located_at", "status", "role", "leads", "age_group"})
    # Одне значення назавжди (народження, вид, справжнє ім'я).
    immutable: set[str] = field(default_factory=lambda: {"born", "species", "true_name", "birthplace"})
    # Події, що завершують існування сутності.
    terminal: set[str] = field(default_factory=lambda: {"dies", "destroyed", "deleted"})
    # Події, що повертають сутність після завершення.
    revival: set[str] = field(default_factory=lambda: {"revived", "restored", "resurrected"})
    # Що можна казати про сутність і після кінця (пам'ять, спадок).
    posthumous: set[str] = field(default_factory=lambda: {
        "mentioned", "remembered", "buried_at", "legacy", "mourned_by", "dies", "destroyed", "deleted",
        "revived", "restored", "resurrected", "setup", "payoff",
    })
    # Дії, що вимагають живого/наявного ОБ'ЄКТА.
    interactive: set[str] = field(default_factory=lambda: {"talks_to", "meets", "fights", "helps", "kisses", "calls", "works_with"})
    # Симетричні зв'язки.
    symmetric: set[str] = field(default_factory=lambda: {"sibling_of", "married_to", "friend_of", "enemy_of", "works_with"})
    # Взаємно обернені зв'язки.
    inverse: dict[str, str] = field(default_factory=lambda: {"parent_of": "child_of", "child_of": "parent_of"})
    # Сутність не може бути в цьому зв'язку сама з собою.
    irreflexive: set[str] = field(default_factory=lambda: {
        "sibling_of", "married_to", "parent_of", "child_of", "talks_to", "fights", "kisses",
    })
    # Сутність не може мати двох таких «на одне місце» (двоє біологічних батьків — ок,
    # але «єдина дитина» не має сиблінгів).
    exclusive: dict[str, str] = field(default_factory=lambda: {"only_child": "sibling_of"})
    # Інші дозволені зв'язки; решту словника складають множини вище.
    extra: set[str] = field(default_factory=lambda: {
        "owns", "member_of", "knows", "loves", "fears", "lives_with", "rival_of", "mentor_of", "serves",
    })
    # Зв'язки, об'єкт яких — теж сутність канону (а не значення).
    entity_objects: set[str] = field(default_factory=lambda: {
        "knows", "loves", "fears", "lives_with", "rival_of", "mentor_of", "serves", "remembered", "mourned_by",
    })

    def vocabulary(self) -> set[str]:
        return (self.functional | self.immutable | self.terminal | self.revival | self.posthumous
                | self.interactive | self.symmetric | set(self.inverse) | self.irreflexive
                | set(self.exclusive) | set(self.exclusive.values()) | self.extra)

    def needs_entity_object(self, predicate: str) -> bool:
        return (predicate in self.interactive or predicate in self.symmetric or predicate in self.inverse
                or predicate in self.entity_objects or predicate in self.exclusive.values())


def _before(a: Stamp | None, b: Stamp | None) -> bool:
    return a is not None and b is not None and a < b


def _at_or_after(a: Stamp | None, b: Stamp | None) -> bool:
    return a is not None and b is not None and a >= b


def _fmt(at: Stamp | None) -> str:
    return "поза часом" if at is None else "·".join(str(x) for x in at)


# Відмінкові закінчення українських імен: «Міра» → «Міру», «Мірою»; «Тарас» →
# «Тараса», «Тарасові». Основа + лише ці закінчення, тож «Олег» не стає «Олею»,
# а «Мірошник» — «Мірою».
_ENDINGS = sorted({
    "", "а", "я", "у", "ю", "і", "ї", "е", "є", "о", "и", "ь", "й",
    "ой", "ою", "ею", "єю", "ові", "еві", "єві", "ом", "ем", "єм", "ів", "ій", "ої", "ей",
    "ам", "ям", "ами", "ями", "ах", "ях", "ин", "ина", "ину", "иної", "ині",
}, key=len, reverse=True)
_SOFT_TAIL = "аяоеєиіїйуюь"


def _stem(word: str) -> str:
    w = word.lower()
    while len(w) > 2 and w[-1] in _SOFT_TAIL:
        w = w[:-1]
    return w


def _name_pattern(name: str) -> re.Pattern[str] | None:
    words = [w for w in re.findall(r"[\w'’]+", name) if w]
    if not words:
        return None
    ends = "|".join(re.escape(e) for e in _ENDINGS)
    body = r"\s+".join(re.escape(_stem(w)) + f"(?:{ends})" for w in words)
    return re.compile(rf"(?<![\w'’]){body}(?![\w'’])", re.IGNORECASE)


class Canon:
    """Сховище правди з індексами: перевірка факту торкається лише фактів про
    причетні сутності (O(факти сутності)), а не всього канону — інакше серіал
    на 20 сезонів з тисячами персонажів третього плану перевіряється хвилинами.
    """

    def __init__(self, schema: Schema | None = None) -> None:
        self.schema = schema or Schema()
        self.entities: dict[str, Entity] = {}
        self._facts: list[Fact] = []
        self._name_pats: dict[str, tuple[str, str, re.Pattern[str] | None]] = {}
        self._reindex()

    # ── індекси ───────────────────────────────────────────────────────────
    @property
    def facts(self) -> list[Fact]:
        if len(self._facts) != self._indexed:  # хтось дописав у список напряму
            self._reindex()
        return self._facts

    @facts.setter
    def facts(self, value: Iterable[Fact]) -> None:
        self._facts = list(value)
        self._reindex()

    def _reindex(self) -> None:
        self._by_subject: dict[str, list[Fact]] = {}
        self._by_object: dict[str, list[Fact]] = {}
        self._by_predicate: dict[str, list[Fact]] = {}
        self._present: Counter[Fact] = Counter()
        self._indexed = 0
        for f in self._facts:
            self._index(f)

    def _index(self, f: Fact) -> None:
        self._by_subject.setdefault(f.subject, []).append(f)
        self._by_object.setdefault(f.object, []).append(f)
        self._by_predicate.setdefault(f.predicate, []).append(f)
        self._present[f] += 1
        self._indexed += 1

    def _sync(self) -> None:
        if len(self._facts) != self._indexed:
            self._reindex()

    def _append(self, f: Fact) -> None:
        self._sync()
        self._facts.append(f)
        self._index(f)

    def _truncate(self, n: int) -> None:
        """Відкотити до перших `n` фактів — і список, і всі індекси.

        Додане після `n` лежить у кінці кожного індексного списку в тому ж
        порядку, тож знімаємо з хвостів, не перебудовуючи весь канон."""
        self._sync()
        while len(self._facts) > n:
            f = self._facts.pop()
            for idx, key in ((self._by_subject, f.subject), (self._by_object, f.object), (self._by_predicate, f.predicate)):
                lst = idx[key]
                lst.pop()
                if not lst:
                    del idx[key]
            self._present[f] -= 1
            if self._present[f] <= 0:
                del self._present[f]
            self._indexed -= 1

    def _has(self, f: Fact) -> bool:
        self._sync()
        return self._present.get(f, 0) > 0

    # ── сутності ──────────────────────────────────────────────────────────
    def entity(self, id: str, kind: str, name: str, **attrs: Any) -> Entity:
        e = self.entities.get(id)
        if e is None:
            e = Entity(id=id, kind=kind, name=name, attrs=dict(attrs))
            self.entities[id] = e
        else:
            e.attrs.update(attrs)
        return e

    def name(self, id: str) -> str:
        e = self.entities.get(id)
        return e.name if e else id

    # ── правда в момент часу ─────────────────────────────────────────────
    def _about(self, subject: str) -> list[Fact]:
        self._sync()
        return self._by_subject.get(subject, [])

    def _towards(self, obj: str) -> list[Fact]:
        self._sync()
        return self._by_object.get(obj, [])

    def _with(self, predicate: str) -> list[Fact]:
        self._sync()
        return self._by_predicate.get(predicate, [])

    def _alive_at(self, id: str, at: Stamp | None) -> tuple[bool, Fact | None]:
        """Чи існує сутність у момент `at`; якщо ні — факт, що її завершив."""
        if at is None:
            return True, None
        end: Fact | None = None
        for f in sorted((f for f in self._about(id) if f.at is not None), key=lambda f: f.at):
            if f.at > at:
                break
            if f.predicate in self.schema.terminal:
                end = f
            elif f.predicate in self.schema.revival:
                end = None
        return end is None, end

    def _born_at(self, id: str) -> Fact | None:
        for f in self._about(id):
            if f.predicate == "born" and f.at is not None:
                return f
        return None

    def at(self, subject: str, when: Stamp | None) -> dict[str, list[str]]:
        """Що правда про сутність у момент `when` (останнє значення для функційних)."""
        out: dict[str, list[str]] = {}
        latest: dict[str, tuple[Stamp, str]] = {}
        for f in self._about(subject):
            if when is not None and f.at is not None and f.at > when:
                continue
            if f.predicate in self.schema.functional and f.at is not None:
                prev = latest.get(f.predicate)
                if prev is None or f.at >= prev[0]:
                    latest[f.predicate] = (f.at, f.object)
            else:
                out.setdefault(f.predicate, [])
                if f.object not in out[f.predicate]:
                    out[f.predicate].append(f.object)
        for p, (_, v) in latest.items():
            out[p] = [v]
        return out

    # ── перевірка ────────────────────────────────────────────────────────
    def check(self, fact: Fact) -> list[Contradiction]:
        s = self.schema
        out: list[Contradiction] = []
        subj, obj = self.name(fact.subject), self.name(fact.object)

        # Словник і сутності: інакше «20 фактів узгоджено» може означати 20
        # фактів про ніщо (28.09 наживо: «setup plot_thread_1 stolen_archive»).
        if fact.predicate not in s.vocabulary():
            return [Contradiction(
                "unknown_predicate",
                f"Зв'язку «{fact.predicate}» у каноні нема. Можна: {', '.join(sorted(s.vocabulary()))}.",
                fact,
            )]
        if fact.subject not in self.entities:
            out.append(Contradiction("unknown_entity", f"Сутності «{fact.subject}» у каноні нема — спершу заведи її.", fact))
        if s.needs_entity_object(fact.predicate) and fact.object not in self.entities:
            out.append(Contradiction("unknown_entity", f"«{fact.predicate}» потребує сутності, а «{fact.object}» у каноні нема.", fact))
        if out:
            return out

        if fact.predicate in s.irreflexive and fact.subject == fact.object:
            out.append(Contradiction("self_relation", f"{subj} не може бути «{fact.predicate}» сам із собою.", fact))

        if fact.predicate in s.immutable:
            prior = [f for f in self._about(fact.subject) if f.predicate == fact.predicate and (f.object != fact.object or f.at != fact.at)]
            if prior:
                out.append(Contradiction(
                    "immutable_changed",
                    f"«{fact.predicate}» у {subj} незмінне: вже «{prior[0].object}» ({_fmt(prior[0].at)}), а тепер «{fact.object}».",
                    fact, prior,
                ))

        if fact.predicate in s.functional and fact.at is not None:
            same = [f for f in self._about(fact.subject) if f.predicate == fact.predicate and f.at == fact.at and f.object != fact.object]
            if same:
                out.append(Contradiction(
                    "two_values_at_once",
                    f"{subj} в один момент ({_fmt(fact.at)}) і «{same[0].object}», і «{fact.object}» ({fact.predicate}).",
                    fact, same,
                ))

        if fact.predicate not in s.posthumous:
            alive, end = self._alive_at(fact.subject, fact.at)
            if not alive and end is not None:
                out.append(Contradiction(
                    "acts_after_end",
                    f"{subj} вже не існує з {_fmt(end.at)} ({end.predicate}), а тут у {_fmt(fact.at)}: «{fact.predicate}».",
                    fact, [end],
                ))
            born = self._born_at(fact.subject)
            if born is not None and _before(fact.at, born.at) and fact.predicate != "born":
                out.append(Contradiction(
                    "acts_before_start",
                    f"{subj} ще не існує в {_fmt(fact.at)} (з'являється в {_fmt(born.at)}).",
                    fact, [born],
                ))

        if fact.predicate in s.interactive and fact.object in self.entities:
            alive, end = self._alive_at(fact.object, fact.at)
            if not alive and end is not None:
                out.append(Contradiction(
                    "partner_gone",
                    f"{subj} не може «{fact.predicate}» з {obj}: {obj} вже нема з {_fmt(end.at)} ({end.predicate}).",
                    fact, [end],
                ))

        for flag, rel in s.exclusive.items():
            if fact.predicate == rel:
                for who in (fact.subject, fact.object):
                    flagged = [f for f in self._about(who) if f.predicate == flag]
                    if flagged:
                        out.append(Contradiction(
                            "exclusive",
                            f"{self.name(who)} — «{flag}», тож не може мати «{rel}».",
                            fact, flagged,
                        ))
            if fact.predicate == flag:
                rels = [f for f in self._about(fact.subject) if f.predicate == rel]
                rels += [f for f in self._towards(fact.subject) if f.predicate == rel and f.subject != fact.subject]
                if rels:
                    out.append(Contradiction(
                        "exclusive",
                        f"{subj} вже має «{rel}», тож не може бути «{flag}».",
                        fact, rels,
                    ))

        if fact.predicate in s.terminal:
            later = [
                f for f in self._about(fact.subject)
                if f.predicate not in s.posthumous and _at_or_after(f.at, fact.at) and f.at != fact.at
                and not self._revived_between(fact.subject, fact.at, f.at)
            ]
            partners = [
                f for f in self._towards(fact.subject)
                if f.predicate in s.interactive and _at_or_after(f.at, fact.at) and f.at != fact.at
                and not self._revived_between(fact.subject, fact.at, f.at)
            ]
            if later or partners:
                first = sorted(later + partners, key=lambda f: f.at or ())[0]
                out.append(Contradiction(
                    "end_before_later_acts",
                    f"{subj} не може «{fact.predicate}» в {_fmt(fact.at)}: у каноні ще діє пізніше — {_fmt(first.at)} «{first.predicate}».",
                    fact, later + partners,
                ))
        return out

    def _revived_between(self, id: str, a: Stamp | None, b: Stamp | None) -> bool:
        return any(
            f.predicate in self.schema.revival and f.at is not None and a is not None and b is not None and a < f.at <= b
            for f in self._about(id)
        )

    def _mirrors(self, fact: Fact) -> list[Fact]:
        s = self.schema
        if fact.predicate in s.symmetric:
            return [Fact(fact.object, fact.predicate, fact.subject, fact.at, fact.source)]
        if fact.predicate in s.inverse:
            return [Fact(fact.object, s.inverse[fact.predicate], fact.subject, fact.at, fact.source)]
        return []

    def add(self, fact: Fact) -> list[Contradiction]:
        """Додати факт, якщо він сумісний з усім каноном. Інакше — нічого не змінити."""
        problems = self.check(fact)
        if problems:
            return problems
        for m in self._mirrors(fact):
            if not self._has(m):
                problems += [c for c in self.check(m) if c.kind != "self_relation"]
        if problems:
            return problems
        if not self._has(fact):
            self._append(fact)
        for m in self._mirrors(fact):
            if not self._has(m):
                self._append(m)
        return []

    def add_all(self, facts: Iterable[Fact]) -> list[Contradiction]:
        """Додати пакет атомарно: або всі, або жодного (з переліком проблем)."""
        self._sync()
        mark = len(self._facts)
        problems: list[Contradiction] = []
        for f in facts:
            problems += self.add(f)
        if problems:
            self._truncate(mark)
        return problems

    # ── нитки оповіді ────────────────────────────────────────────────────
    def open_threads(self, until: Stamp | None = None) -> list[Fact]:
        """Закладене («setup»), що досі не отримало розв'язки («payoff»).

        Для серіалу — рушниці, що мають вистрілити: деталь першого сезону,
        яку двадцятий повинен підхопити, — бачить автор, не пам'ять моделі.
        """
        paid = {f.object for f in self._with("payoff") if until is None or f.at is None or f.at <= until}
        return [
            f for f in self._with("setup")
            if f.object not in paid and (until is None or f.at is None or f.at <= until)
        ]

    def relevant(self, text: str, when: Stamp | None = None, limit: int = 40) -> list[str]:
        """Кого з канону показати моделі для цього тексту (сцени, задачі).

        Спершу — названі в тексті (за id або ім'ям, без огляду на регістр і
        відмінок), у порядку появи. Далі — їхні сусіди на один крок через
        факти, ближчі в часі до `when` першими (спершу найсвіжіші до `when`,
        потім найближчі після, позачасові — в кінці). Далі — власники
        відкритих ниток на момент `when`. Не більше `limit`.
        """
        if limit <= 0:
            return []
        low = text.lower()
        hits: list[tuple[int, str]] = []
        for id, e in self.entities.items():
            # Дешевий відсів підрядком: регулярки — лише для кандидатів.
            pos: list[int] = []
            lid = id.lower()
            if lid in low:
                pos += [m.start() for m in re.finditer(rf"(?<![\w-]){re.escape(lid)}(?![\w-])", low)]
            pat = self._name_pat(e, low)
            if pat is not None:
                m = pat.search(text)
                if m:
                    pos.append(m.start())
            if pos:
                hits.append((min(pos), id))
        out = [id for _, id in sorted(hits)]
        seen = set(out)

        before: dict[str, Stamp] = {}
        after: dict[str, Stamp] = {}
        timeless: list[str] = []
        for id in out:
            for f in list(self._about(id)) + list(self._towards(id)):
                other = f.object if f.subject == id else f.subject
                if other in seen or other not in self.entities:
                    continue
                if f.at is None:
                    if other not in timeless:
                        timeless.append(other)
                elif when is None or f.at <= when:
                    if other not in before or f.at > before[other]:
                        before[other] = f.at
                elif other not in after or f.at < after[other]:
                    after[other] = f.at
        ranked = sorted(before, key=lambda o: before[o], reverse=True)
        ranked += [o for o in sorted(after, key=lambda o: after[o]) if o not in before]
        ranked += [o for o in timeless if o not in before and o not in after]
        for o in ranked:
            if o not in seen:
                out.append(o)
                seen.add(o)

        for f in self.open_threads(when):
            for who in (f.subject, f.object):
                if who in self.entities and who not in seen:
                    out.append(who)
                    seen.add(who)
        return out[:limit]

    def _name_pat(self, e: Entity, low: str) -> re.Pattern[str] | None:
        """Шаблон імені — лише якщо основа першого слова взагалі є в тексті
        (компіляція тисяч шаблонів на кожен виклик коштувала б пів секунди)."""
        hit = self._name_pats.get(e.id)
        if hit is None or hit[0] != e.name:  # ім'я могли змінити — шаблон за ним
            words = re.findall(r"[\w'’]+", e.name)
            hit = (e.name, _stem(words[0]) if words else "", None)
            self._name_pats[e.id] = hit
        name, stem, pat = hit
        if not stem or stem not in low:
            return None
        if pat is None:
            pat = _name_pattern(name)
            self._name_pats[e.id] = (name, stem, pat)
        return pat

    def brief(self, ids: Iterable[str], when: Stamp | None) -> str:
        """Стисла довідка для моделі: лише ті сутності, яких стосується робота."""
        lines: list[str] = []
        for id in ids:
            e = self.entities.get(id)
            if e is None:
                continue
            alive, end = self._alive_at(id, when)
            state = "" if alive else f" [нема з {_fmt(end.at)}: {end.predicate}]"
            truths = "; ".join(f"{p}: {', '.join(v)}" for p, v in sorted(self.at(id, when).items()))
            lines.append(f"- {e.name} ({e.kind}, id={id}){state}: {truths}")
        return "\n".join(lines)

    # ── збереження ───────────────────────────────────────────────────────
    def to_dict(self) -> dict[str, Any]:
        return {
            "entities": [asdict(e) for e in self.entities.values()],
            "facts": [f.to_dict() for f in self.facts],
        }

    @staticmethod
    def from_dict(d: dict[str, Any], schema: Schema | None = None) -> "Canon":
        c = Canon(schema)
        for e in d.get("entities", []):
            c.entities[e["id"]] = Entity(**e)
        c.facts = [Fact.from_dict(f) for f in d.get("facts", [])]  # сеттер будує індекси
        return c

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.to_dict(), ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(path)

    @staticmethod
    def load(path: Path, schema: Schema | None = None) -> "Canon":
        if not path.exists():
            return Canon(schema)
        return Canon.from_dict(json.loads(path.read_text(encoding="utf-8")), schema)
