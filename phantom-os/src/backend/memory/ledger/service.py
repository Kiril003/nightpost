"""MemoryLedger — запис, читання, консолідація й синхронізація пам'яті.

Правила запису (див. `apply_candidate`):
  * низька певність або здогад (`inferred`) → `pending`, ніколи не мовчазний факт;
  * той самий зміст удруге → підтвердження (last_confirmed, певність ↑);
  * нове значення в однозначному слоті (lives_in, name…) → старому закривається
    valid_to, обидва лишаються; але здогад не перебиває сказане власником —
    він стає `pending` поруч;
  * зміст під надгробком («неправда») не записується ніколи.
"""
from __future__ import annotations

import asyncio
import logging
import math
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .extract import LLM, Candidate, Extraction, extract
from .ground import DONT_KNOW, Grounding, ground
from .index import VectorIndex, cosine
from .model import (
    OWNER,
    PROFILE_PREDICATES,
    REASONS,
    SINGLE_VALUED,
    SOURCE_RANK,
    MemoryItem,
    Provenance,
    new_id,
    normalize,
    now_ms,
)
from .store import LedgerStore, get_store

logger = logging.getLogger(__name__)

PENDING_BELOW = 0.6           # певність, нижче якої кандидат лише чекає підтвердження
BUDGET_TOKENS = 1500          # фіксований бюджет пам'яті в системному промпті
PROFILE_SHARE = 0.4           # частка бюджету під закріплений профіль
SEMANTIC_TOP_K = 8
SEMANTIC_MIN = 0.12          # поріг доречності для старих епізодів
RECENT_EPISODES = 5
CONSOLIDATE_EVERY = 20        # нових записів між консолідаціями
CONSOLIDATE_MAX_AGE_MS = 24 * 3600 * 1000
EPISODE_FADE_AFTER_MS = 30 * 24 * 3600 * 1000
DAY_MS = 24 * 3600 * 1000


# Звідки запис має право прийти (правило запису):
#   owner_chat      — розмова власника з його PHANTOM («запамʼятай…», виправлення);
#   owner_statement — власник сам сказав про себе (чат PHANTOM, екран «Памʼять»);
#   mission         — підсумок місії, яку власник доручив;
#   peer_message    — чуже приватне повідомлення: ЛИШЕ з явним «запамʼятай це» власника.
ORIGINS = ("owner_chat", "owner_statement", "mission", "peer_message")


class WriteRefused(ValueError):
    """Запис заборонено правилом походження (чуже повідомлення без явного «запамʼятай»)."""


def write_allowed(origin: str, explicit: bool) -> bool:
    if origin not in ORIGINS:
        return False
    return origin != "peer_message" or explicit


def exclusive(predicate: str) -> bool:
    return predicate in SINGLE_VALUED or predicate.startswith("favorite_")


def estimate_tokens(text: str) -> int:
    """Консервативно: кирилиця в токенізаторах ≈ 3 символи на токен."""
    return math.ceil(len(text) / 3) + 1


@dataclass(slots=True)
class IngestReport:
    added: list[str] = field(default_factory=list)
    pending: list[str] = field(default_factory=list)
    confirmed: list[str] = field(default_factory=list)
    superseded: list[str] = field(default_factory=list)
    retracted: list[str] = field(default_factory=list)
    blocked: int = 0
    consolidated: bool = False


@dataclass(slots=True)
class MemoryContext:
    text: str
    item_ids: list[str]
    tokens: int


class MemoryLedger:
    def __init__(self, store: LedgerStore | None = None, llm: LLM | None = None, *, device: str = "pc") -> None:
        self._store = store
        self._llm = llm
        self.device = device
        self.index = VectorIndex()
        self._locks: dict[str, asyncio.Lock] = {}

    @property
    def store(self) -> LedgerStore:
        return self._store or get_store()

    @property
    def llm(self) -> LLM:
        if self._llm is not None:
            return self._llm
        from .extract import provider_llm

        return provider_llm

    def _lock(self, user_id: str) -> asyncio.Lock:
        lock = self._locks.get(user_id)
        if lock is None:
            lock = self._locks[user_id] = asyncio.Lock()
        return lock

    # ══ запис ═══════════════════════════════════════════════════════════
    async def ingest_turn(
        self,
        user_id: str,
        user_text: str,
        *,
        assistant_text: str = "",
        conversation_id: str | None = None,
        message_id: str | None = None,
        device: str | None = None,
        consolidate: bool = True,
        origin: str = "owner_chat",
        explicit: bool = False,
    ) -> IngestReport:
        if not write_allowed(origin, explicit):
            raise WriteRefused(origin)
        ex: Extraction = await extract(self.llm, user_text, assistant_text)
        async with self._lock(user_id):
            report = self.apply_extraction(
                user_id, ex, conversation_id=conversation_id, message_id=message_id, device=device or self.device
            )
            if consolidate and self.consolidation_due(user_id):
                await self.consolidate(user_id)
                report.consolidated = True
        return report

    def apply_extraction(
        self,
        user_id: str,
        ex: Extraction,
        *,
        conversation_id: str | None = None,
        message_id: str | None = None,
        device: str = "pc",
    ) -> IngestReport:
        report = IngestReport()
        for r in ex.retractions:
            report.retracted += self.retract(user_id, r, device=device)
        for c in ex.items:
            prov = Provenance(
                source=c.source, conversation_id=conversation_id, message_id=message_id, ts=now_ms(), device=device
            )
            outcome, item_id = self.apply_candidate(user_id, c, prov)
            if outcome == "blocked":
                report.blocked += 1
            elif outcome == "confirmed":
                report.confirmed.append(item_id)
            elif outcome == "pending":
                report.pending.append(item_id)
            elif outcome == "superseded":
                report.superseded.append(item_id)
                report.added.append(item_id)
            else:
                report.added.append(item_id)
        new = len(report.added) + len(report.pending)
        if new:
            self.store.meta_set(user_id, "new_since_consolidation",
                                self.store.meta_int(user_id, "new_since_consolidation") + new)
        return report

    def apply_candidate(self, user_id: str, c: Candidate, prov: Provenance) -> tuple[str, str]:
        st = self.store
        text = c.text or " ".join(p for p in (c.predicate.replace("_", " "), c.value) if p)
        if st.is_tombstoned(user_id, text=text, subject=c.subject, predicate=c.predicate, value=c.value):
            return "blocked", ""
        status = "active"
        if c.source == "inferred" or c.confidence < PENDING_BELOW:
            status = "pending"
        now = now_ms()

        # той самий зміст — підтвердження, а не дубль
        same = st.live_by_triple(user_id, c.subject, c.predicate, c.value) if c.value else st.live_by_text(user_id, text)
        if same:
            it = same[0]
            if SOURCE_RANK[c.source] > SOURCE_RANK[it.source]:
                it.provenance.source = c.source
            it.confidence = max(it.confidence, c.confidence)
            it.last_confirmed = now
            if it.status == "pending" and status == "active":
                it.status = "active"
            st.save(it)
            return "confirmed", it.id

        item = MemoryItem(
            user_id=user_id, kind=c.kind, subject=c.subject, predicate=c.predicate, value=c.value,
            text=text, provenance=prov, confidence=c.confidence, status=status, valid_from=now,
            last_confirmed=now, sensitivity=c.sensitivity,
        )
        exclusive = c.predicate in SINGLE_VALUED or c.predicate.startswith("favorite_")
        if exclusive and c.value:
            rivals = [r for r in st.live_by_slot(user_id, c.subject, c.predicate) if r.status == "active"]
            stronger = [r for r in rivals if SOURCE_RANK[r.source] > SOURCE_RANK[c.source]]
            if stronger:
                # сказане власником перемагає здогад: новий лише чекає підтвердження
                item.status = "pending"
                st.save(item)
                return "pending", item.id
            if item.status == "active" and rivals:
                st.save(item)
                for r in rivals:
                    r.valid_to = now
                    r.status = "superseded"
                    r.superseded_by = item.id
                    st.save(r)
                return "superseded", item.id
        st.save(item)
        return ("pending" if item.status == "pending" else "added"), item.id

    def retract(self, user_id: str, r: Candidate, *, device: str = "pc") -> list[str]:
        """«Неправда, …» у розмові: надгробок на все, що збігається, і на сам зміст."""
        st = self.store
        hits: dict[str, MemoryItem] = {}
        if r.value:
            for it in st.live_by_triple(user_id, r.subject, r.predicate, r.value):
                hits[it.id] = it
        if not hits and r.text:
            for it in st.live_by_text(user_id, r.text):
                hits[it.id] = it
        if not hits:
            # нечіткий збіг: той самий слот і значення входить у текст, або близький текст
            q = normalize(r.text or r.value)
            for it in st.live_by_slot(user_id, r.subject, r.predicate):
                if r.value and normalize(r.value) in normalize(it.display_text() + " " + it.value):
                    hits[it.id] = it
            if not hits and q:
                for score, it in self.index.rank(q, st.list(user_id)):
                    if score >= 0.75:
                        hits[it.id] = it
        gone = [it.id for it in hits.values() if self.forget(user_id, it.id, device=device)]
        # окремий надгробок на сам зміст — щоб той же витяг не повернувся
        marker = MemoryItem(
            user_id=user_id, kind="fact", subject=r.subject, predicate=r.predicate, value=r.value,
            text=r.text, provenance=Provenance(source="owner_said", ts=now_ms(), device=device),
            confidence=1.0, status="active",
        )
        if not st.is_tombstoned(user_id, text=r.text, subject=r.subject, predicate=r.predicate, value=r.value):
            marker.status = "tombstone"
            marker.valid_to = now_ms()
            st.save(marker)
        return gone

    # ══ дії власника (UI / API) ═════════════════════════════════════════
    def forget(self, user_id: str, item_id: str, *, device: str = "pc", why: str = "false") -> bool:
        """Надгробок. why="false" — «неправда» (зміст заблоковано назавжди);
        why="forget" — «забудь» (зміст стерто без сліду, сказане знову запамʼятається)."""
        it = self.store.get(user_id, item_id)
        if it is None or it.tombstone:
            return False
        it.status = "tombstone"
        it.reason = why if why in REASONS else "false"
        it.valid_to = it.valid_to or now_ms()
        it.provenance.device = device
        self.store.save(it)
        return True

    def remember_text(
        self,
        user_id: str,
        text: str,
        *,
        origin: str,
        explicit: bool = False,
        kind: str = "fact",
        subject: str = OWNER,
        predicate: str = "about",
        value: str = "",
        conversation_id: str | None = None,
        device: str | None = None,
    ) -> tuple[str, str]:
        """Прямий запис без витягу моделлю: «запамʼятай це» на повідомленні, підсумок місії.

        Сказане власником (будь-яке походження з явним «запамʼятай») — `owner_said`;
        підсумок місії без явного прохання — `heard` (переказ, не слова власника)."""
        if not write_allowed(origin, explicit):
            raise WriteRefused(origin)
        text = (text or "").strip()[:1200]
        if not text:
            raise ValueError("empty")
        source = "heard" if origin == "mission" and not explicit else "owner_said"
        c = Candidate(kind=kind, subject=subject, predicate=predicate, value=value, text=text,
                      source=source, confidence=1.0 if source == "owner_said" else 0.8)
        prov = Provenance(source=source, conversation_id=conversation_id, ts=now_ms(), device=device or self.device)
        return self.apply_candidate(user_id, c, prov)

    def confirm(self, user_id: str, item_id: str) -> MemoryItem | None:
        it = self.store.get(user_id, item_id)
        if it is None or not it.is_live:
            return None
        now = now_ms()
        # підтверджене значення однозначного слоту закриває суперників
        if it.predicate in SINGLE_VALUED and it.value:
            for r in self.store.live_by_slot(user_id, it.subject, it.predicate):
                if r.id != it.id and r.status == "active":
                    r.valid_to, r.status, r.superseded_by = now, "superseded", it.id
                    self.store.save(r)
        it.status = "active"
        it.provenance.source = "owner_said"
        it.confidence = 1.0
        it.last_confirmed = now
        it.legacy = False
        return self.store.save(it)

    def edit(self, user_id: str, item_id: str, text: str) -> MemoryItem | None:
        """Власник виправив запис: старий зміст — неправда, новий — сказане власником."""
        old = self.store.get(user_id, item_id)
        text = (text or "").strip()
        if old is None or not old.is_live or not text:
            return None
        now = now_ms()
        new = MemoryItem(
            user_id=user_id, kind=old.kind, subject=old.subject, predicate=old.predicate, value="",
            text=text[:1200], provenance=Provenance(source="owner_said", ts=now, device=self.device),
            confidence=1.0, status="active", valid_from=now, last_confirmed=now, sensitivity=old.sensitivity,
        )
        self.store.save(new)
        old.superseded_by = new.id
        old.status = "tombstone"
        old.valid_to = now
        self.store.save(old)
        return new

    def known(self, user_id: str) -> list[MemoryItem]:
        items = [i for i in self.store.list(user_id) if i.valid_to is None]
        return sorted(items, key=lambda i: (i.status != "active", -i.last_confirmed))

    # ══ читання ═════════════════════════════════════════════════════════
    def context_for_prompt(self, user_id: str, query: str, *, budget_tokens: int = BUDGET_TOKENS) -> MemoryContext:
        # «secret» (власник виключив із промптів) у розмову не йде ніколи
        live = [i for i in self.store.list(user_id, statuses=("active",))
                if i.valid_to is None and i.sensitivity != "secret"]
        header = "[ПАМʼЯТЬ PHANTOM — записи про власника; кожен має мітку [m:id]]"
        rule = (
            "ПРАВИЛО ПАМʼЯТІ: про власника, його людей, місця й події кажи лише те, що є в записах вище. "
            "Якщо в памʼяті немає опори для відповіді — прямо скажи, що не знаєш (або що він тобі цього не казав), "
            "і НЕ вигадуй та не вгадуй. Записи можуть бути застарілими — якщо власник каже інакше, вір йому."
        )
        if not live:
            body = "(про власника в памʼяті поки нічого немає)"
            text = f"{header}\n{body}\n{rule}"
            return MemoryContext(text=text, item_ids=[], tokens=estimate_tokens(text))

        used = estimate_tokens(header) + estimate_tokens(rule) + 12
        chosen: list[str] = []
        sections: list[tuple[str, list[str]]] = []

        def take(items: list[MemoryItem], limit: int, fmt) -> list[str]:
            nonlocal used
            lines: list[str] = []
            for it in items:
                if it.id in chosen:
                    continue
                line = fmt(it)
                cost = estimate_tokens(line)
                if used + cost > limit:
                    continue
                used += cost
                chosen.append(it.id)
                lines.append(line)
            return lines

        def fmt_fact(it: MemoryItem) -> str:
            flag = " (здогад)" if it.source == "inferred" else ""
            return f"- [m:{it.short_id}] {it.display_text()}{flag}"

        def fmt_episode(it: MemoryItem) -> str:
            day = datetime.fromtimestamp(it.valid_from / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
            return f"- [m:{it.short_id}] ({day}) {it.display_text()}"

        # 1) закріплений профіль
        profile = [i for i in live if i.subject == OWNER and i.predicate in PROFILE_PREDICATES
                   and i.kind in ("fact", "person", "place") and i.source == "owner_said"]
        lines = take(profile, int(budget_tokens * PROFILE_SHARE), fmt_fact)
        if lines:
            sections.append(("Профіль:", lines))

        # 2) семантичні top-k
        rest = [i for i in live if i.kind != "episode" and i.id not in chosen]
        ranked = [it for _score, it in self.index.rank(query, rest)[:SEMANTIC_TOP_K]]
        lines = take(ranked, budget_tokens - 150, fmt_fact)
        if lines:
            sections.append(("Доречне до питання:", lines))

        # 3) недавні епізоди (і доречні старі)
        eps = [i for i in live if i.kind == "episode"]
        eps_rel = [it for s, it in self.index.rank(query, eps) if s >= SEMANTIC_MIN][:3]
        eps_recent = sorted(eps, key=lambda i: -i.valid_from)[:RECENT_EPISODES]
        lines = take(eps_rel + eps_recent, budget_tokens, fmt_episode)
        if lines:
            sections.append(("Події:", lines))

        if not sections:
            sections.append(("", ["(доречних записів немає)"]))
        body = "\n".join((f"{title}\n" if title else "") + "\n".join(ls) for title, ls in sections)
        text = f"{header}\n{body}\n{rule}"
        return MemoryContext(text=text, item_ids=chosen, tokens=estimate_tokens(text))

    def resolve_short_id(self, user_id: str, short: str) -> MemoryItem | None:
        for it in self.store.list(user_id, statuses=None):
            if it.short_id == short:
                return it
        return None

    # ══ консолідація ════════════════════════════════════════════════════
    def consolidation_due(self, user_id: str, now: int | None = None) -> bool:
        """Після N нових записів — одразу; інакше раз на добу (перша перевірка лише засікає час)."""
        now = now or now_ms()
        if self.store.meta_int(user_id, "new_since_consolidation") >= CONSOLIDATE_EVERY:
            return True
        last = self.store.meta_int(user_id, "last_consolidated")
        if last == 0:
            self.store.meta_set(user_id, "last_consolidated", now)
            return False
        return now - last >= CONSOLIDATE_MAX_AGE_MS

    async def consolidate(self, user_id: str, *, now: int | None = None, summarize: bool = True) -> dict[str, int]:
        now = now or now_ms()
        st = self.store
        merged = 0
        live = [i for i in st.list(user_id) if i.valid_to is None]

        # 1) дублікати: той самий зміст у тому ж слоті, або майже той самий текст
        cands = [i for i in live if i.kind != "episode"]
        parent = {i.id: i.id for i in cands}

        def root(x: str) -> str:
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        for n, a in enumerate(cands):
            for b in cands[n + 1:]:
                if (a.subject, a.predicate) != (b.subject, b.predicate):
                    continue
                va, vb = normalize(a.value), normalize(b.value)
                if va and vb:
                    same = va == vb
                else:
                    same = cosine(self.index.vector(a), self.index.vector(b)) >= 0.9
                if same:
                    parent[root(b.id)] = root(a.id)
        groups: dict[str, list[MemoryItem]] = {}
        for it in cands:
            groups.setdefault(root(it.id), []).append(it)
        for items in groups.values():
            if len(items) < 2:
                continue
            items.sort(key=lambda i: (-SOURCE_RANK[i.source], i.status != "active", -i.last_confirmed))
            keep = items[0]
            for dup in items[1:]:
                keep.confidence = max(keep.confidence, dup.confidence)
                keep.last_confirmed = max(keep.last_confirmed, dup.last_confirmed)
                keep.valid_from = min(keep.valid_from, dup.valid_from)
                dup.status, dup.valid_to, dup.superseded_by = "merged", now, keep.id
                st.save(dup)
                merged += 1
            st.save(keep)

        # 2) старі епізоди згасають у місячний підсумок
        faded = 0
        old_eps = [i for i in live if i.kind == "episode" and now - i.valid_from >= EPISODE_FADE_AFTER_MS
                   and not i.legacy and i.status == "active"]
        by_month: dict[str, list[MemoryItem]] = {}
        for ep in old_eps:
            month = datetime.fromtimestamp(ep.valid_from / 1000, tz=timezone.utc).strftime("%Y-%m")
            by_month.setdefault(month, []).append(ep)
        for month, eps in by_month.items():
            if len(eps) < 2:
                continue
            eps.sort(key=lambda e: e.valid_from)
            summary_text = await self._summarize(month, eps) if summarize else ""
            if not summary_text:
                summary_text = f"Підсумок {month}: " + "; ".join(e.display_text().rstrip(".") for e in eps)
            summary = MemoryItem(
                user_id=user_id, kind="episode", subject=OWNER, predicate="summary", value=month,
                text=summary_text[:900], provenance=Provenance(source="inferred", ts=now, device=self.device),
                confidence=min(e.confidence for e in eps), status="active",
                valid_from=eps[0].valid_from, last_confirmed=max(e.last_confirmed for e in eps),
            )
            # підсумок лише з переказаного власником — не здогад, а стиснення
            if all(e.source == "owner_said" for e in eps):
                summary.provenance.source = "owner_said"
            st.save(summary)
            for e in eps:
                e.status, e.valid_to, e.superseded_by = "faded", now, summary.id
                st.save(e)
                faded += 1

        st.meta_set(user_id, "new_since_consolidation", 0)
        st.meta_set(user_id, "last_consolidated", now)
        return {"merged": merged, "faded": faded}

    async def _summarize(self, month: str, eps: list[MemoryItem]) -> str:
        listing = "\n".join(f"- {e.display_text()}" for e in eps)
        try:
            out = await self.llm(
                "Стисни ці події з життя власника в 1–2 речення українською, звертаючись на «ти». "
                "Нічого не додавай від себе. Поверни лише текст.",
                f"Події за {month}:\n{listing}",
            )
        except Exception:
            return ""
        out = (out or "").strip()
        return "" if out.startswith("{") else out

    # ══ синхронізація ═══════════════════════════════════════════════════
    def changes(self, user_id: str, since: int, limit: int = 500) -> dict[str, Any]:
        items = self.store.changes_since(user_id, since, limit + 1)
        more = len(items) > limit
        items = items[:limit]
        out = []
        for it in items:
            ch: dict[str, Any] = {
                "op": "tombstone" if it.tombstone else "upsert",
                "id": it.id,
                "lamport": it.lamport,
                "ts": it.updated_ts,
                "device": it.provenance.device,
            }
            if it.tombstone:
                ch["why"] = it.reason or "false"
            else:
                ch["item"] = it.to_wire()
            out.append(ch)
        cursor = items[-1].seq if items else max(int(since), 0)
        return {"cursor": cursor, "more": more, "changes": out}

    def apply_changes(self, user_id: str, changes: list[dict[str, Any]], *, device: str) -> dict[str, int]:
        applied = ignored = 0
        for ch in changes:
            if not isinstance(ch, dict) or ch.get("op") not in ("upsert", "tombstone") or not ch.get("id"):
                ignored += 1
                continue
            lamport = int(ch.get("lamport") or 0)
            self.store.observe_lamport(user_id, lamport)  # годинник бачить і відхилені зміни
            src_dev = str(ch.get("device") or device)[:64]
            local = self.store.get(user_id, str(ch["id"]))
            if local is not None and local.tombstone:
                ignored += 1  # надгробок липкий: його не воскрешає жоден апсерт
                continue
            if ch["op"] == "tombstone":
                if local is None:
                    it = MemoryItem.from_wire(user_id, {**(ch.get("item") or {}), "id": ch["id"]})
                else:
                    it = local
                it.status = "tombstone"
                it.reason = ch.get("why") if ch.get("why") in REASONS else "false"
                it.valid_to = it.valid_to or now_ms()
                it.lamport = max(lamport, (local.lamport + 1) if local else 0)
                it.provenance.device = src_dev
                self.store.save(it, keep_lamport=True)
                applied += 1
                continue
            data = ch.get("item")
            if not isinstance(data, dict):
                ignored += 1
                continue
            if local is not None and (local.lamport, local.provenance.device) >= (lamport, src_dev):
                ignored += 1  # LWW: локальна версія новіша
                continue
            it = MemoryItem.from_wire(user_id, {**data, "id": ch["id"]})
            if it.tombstone:
                it.status = "tombstone"
            elif self.store.is_tombstoned(user_id, text=it.display_text(), subject=it.subject,
                                          predicate=it.predicate, value=it.value):
                ignored += 1
                continue
            it.lamport = lamport
            it.provenance.device = src_dev
            if local is not None:
                it.legacy = local.legacy
            self.store.save(it, keep_lamport=True)
            applied += 1
            self.reconcile_slot(user_id, it)
        return {"applied": applied, "ignored": ignored, "cursor": self.store.cursor(user_id)}

    def reconcile_slot(self, user_id: str, it: MemoryItem) -> list[str]:
        """Після чужої зміни: в однозначному слоті лишається одне живе значення.

        Обидва вузли рахують те саме з тих самих даних, тож сходяться:
          * переможець — сильніше джерело (owner_said > heard > inferred), далі
            новіша дійсність (`valid_from`), далі більший id;
          * старіший за переможця — `superseded`, valid_to = valid_from переможця;
          * новіший, але слабший за сказане власником — `pending` поруч (правило 3).
        Повертає id змінених записів (локальні зміни — їдуть назад у журнал)."""
        if it.tombstone or it.status != "active" or not it.value or not exclusive(it.predicate):
            return []
        rivals = [r for r in self.store.live_by_slot(user_id, it.subject, it.predicate) if r.status == "active"]
        if len(rivals) < 2:
            return []
        winner = max(rivals, key=lambda r: (SOURCE_RANK[r.source], r.valid_from, r.id))
        changed: list[str] = []
        for r in rivals:
            if r.id == winner.id:
                continue
            if r.valid_from > winner.valid_from:
                r.status = "pending"
            else:
                r.status, r.valid_to, r.superseded_by = "superseded", winner.valid_from, winner.id
            r.provenance.device = self.device
            self.store.save(r)
            changed.append(r.id)
        return changed

    # ══ опора відповіді ═════════════════════════════════════════════════
    def prompt_items(self, user_id: str) -> list[MemoryItem]:
        return [i for i in self.store.list(user_id, statuses=("active",))
                if i.valid_to is None and i.sensitivity != "secret"]

    def grounding(self, user_id: str, query: str) -> Grounding:
        return ground(self.index, query, self.prompt_items(user_id))

    def ungrounded_answer(self, user_id: str, query: str) -> str | None:
        """«Не знаю» без моделі, коли питання про власника, а опори в памʼяті немає."""
        try:
            return DONT_KNOW if self.grounding(user_id, query).ungrounded else None
        except Exception:  # памʼять не відповіла — слово моделі з правилом у промпті
            return None

    def citations(self, user_id: str, answer: str) -> list[dict[str, Any]]:
        """Мітки [m:xxxxxxxx] у відповіді → записи, на які вона спирається."""
        import re as _re

        shorts = list(dict.fromkeys(_re.findall(r"\[m:([0-9a-f]{8})\]", answer or "")))
        if not shorts:
            return []
        by_short = {i.short_id: i for i in self.store.list(user_id, statuses=None)}
        out = []
        for s_ in shorts:
            it = by_short.get(s_)
            if it is not None and not it.tombstone:
                out.append({"id": it.id, "short": s_, "text": it.display_text(), "source": it.source,
                            "device": it.provenance.device})
        return out

    def export(self, user_id: str) -> dict[str, Any]:
        """Усе живе, що вузол памʼятає, — у формі дроту (SYNC.md)."""
        items = [i.to_wire() for i in self.store.list(user_id, statuses=("active", "pending", "superseded"))]
        return {"format": "phantom-memory/1", "exported_at": now_ms(), "count": len(items), "items": items}


memory_ledger = MemoryLedger()
