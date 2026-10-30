"""Разове перенесення старих шарів у ledger: tactical (MemoryFact), strategic
(Chroma), UserFact (Fernet). Позначка `migrated_v1` у meta — вдруге не біжить.

Старі записи з розмов були витягнуті евристикою з власних слів власника:
вони стають `owner_said` з певністю 0.6 і позначкою legacy («старі нотатки»
в UI), щоб власник міг їх переглянути. UserFact — явні дані власника,
певність 1.0.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from .model import MemoryItem, Provenance, normalize, now_ms
from .service import MemoryLedger, memory_ledger
from .store import fp_text

logger = logging.getLogger(__name__)

_FLAG = "migrated_v1"
_EPISODE_CATEGORIES = {"event", "session_synthesis", "emotion"}


def _ms(dt: Any) -> int:
    try:
        return int(dt.timestamp() * 1000)
    except Exception:
        return now_ms()


def _legacy_item(user_id: str, text: str, category: str, ts: int, session_id: str | None) -> MemoryItem:
    return MemoryItem(
        user_id=user_id,
        kind="episode" if category in _EPISODE_CATEGORIES else "fact",
        predicate=normalize(category).replace(" ", "_") or "about",
        text=text.strip()[:1200],
        provenance=Provenance(source="owner_said", conversation_id=session_id, ts=ts, device="pc"),
        confidence=0.6,
        status="active",
        valid_from=ts,
        last_confirmed=ts,
        legacy=True,
    )


def _chroma_docs(user_id: str) -> list[tuple[str, dict]]:
    from memory import strategic_memory as sm

    client = sm._get_client()
    try:
        col = client.get_collection(sm._collection_name(user_id))
    except Exception:
        return []
    got = col.get(include=["documents", "metadatas"])
    docs = got.get("documents") or []
    metas = got.get("metadatas") or [{}] * len(docs)
    return list(zip(docs, metas))


async def migrate_legacy(user_id: str, db: Any | None = None, *, ledger: MemoryLedger | None = None,
                         include_chroma: bool = True) -> int:
    ledger = ledger or memory_ledger
    st = ledger.store
    if st.meta_get(user_id, _FLAG):
        return 0
    seen = {fp_text(i) for i in st.list(user_id, statuses=None)}
    items: list[MemoryItem] = []

    def add(item: MemoryItem) -> None:
        if not normalize(item.display_text()):
            return
        fp = fp_text(item)
        if fp in seen:
            return
        seen.add(fp)
        items.append(item)

    async def _from_db(session: Any) -> None:
        from sqlalchemy import select
        from db.models import MemoryFact, UserFact
        from security.crypto import decrypt_pii

        rows = (await session.execute(
            select(MemoryFact).where(MemoryFact.user_id == user_id, MemoryFact.valid_until.is_(None))
        )).scalars().all()
        for r in rows:
            if getattr(r, "superseded_by", None):
                continue
            add(_legacy_item(user_id, r.content or "", r.category or "fact", _ms(r.created_at), r.source_session_id))
        facts = (await session.execute(select(UserFact).where(UserFact.user_id == user_id))).scalars().all()
        for f in facts:
            try:
                value = decrypt_pii(f.value_encrypted)
            except Exception:
                continue
            label = (f.label or f.category or "").strip()
            ts = _ms(f.updated_at or f.created_at)
            add(MemoryItem(
                user_id=user_id, kind="fact", predicate=normalize(f.category).replace(" ", "_") or "about",
                value=value[:300], text=f"{label}: {value}" if label else value,
                provenance=Provenance(source="owner_said", ts=ts, device="pc"),
                confidence=1.0, status="active", valid_from=ts, last_confirmed=ts, sensitivity="secret" if getattr(f, "exclude_from_prompts", False) else "personal",
            ))

    try:
        if db is not None:
            await _from_db(db)
        else:
            from db.database import get_session

            async with get_session() as session:
                await _from_db(session)
    except Exception as exc:
        logger.warning("ledger migration: SQL layers skipped: %s", exc)
        return 0  # не ставимо позначку — спробуємо наступного разу

    if include_chroma:
        try:
            for doc, meta in await asyncio.to_thread(_chroma_docs, user_id):
                meta = meta or {}
                if meta.get("superseded_by") not in (None, "", "none") or meta.get("is_sealed"):
                    continue
                try:
                    from datetime import datetime
                    ts = _ms(datetime.fromisoformat(str(meta.get("created_at"))))
                except ValueError:
                    ts = now_ms()
                add(_legacy_item(user_id, doc or "", str(meta.get("category") or "fact"), ts,
                                 meta.get("session_id")))
        except Exception as exc:
            logger.info("ledger migration: chroma skipped: %s", exc)

    for it in items:
        st.save(it)
    st.meta_set(user_id, _FLAG, now_ms())
    logger.info("ledger migration: %d items for user %s", len(items), user_id)
    return len(items)
