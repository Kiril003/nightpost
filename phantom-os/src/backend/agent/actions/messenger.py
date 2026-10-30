"""messenger.* — месенджер як інструмент симбіота (стовп 3 статуту).

Розум читає, шукає і пише від імені власника тими самими дорогами, що й
вікно месенджера: тіла повідомлень розпечатуються тим самим ключем вузла,
лист іде через той самий `append_message` (шифрування, черга повторів,
телефон чи інший вузол). Нічого не обходить і нічого не вигадує.

Ризик (шкала статуту → `RiskLevel`):
* читання/пошук — ЧИТАННЯ (SAFE);
* лист у розмову, де власник уже писав, — ДІЯ НАЗОВНІ (MEDIUM): вище за
  типову стелю агента, тож ядро питає людину (телефон або вікно);
* ПЕРШИЙ лист людині від імені власника — НЕЗВОРОТНА (HIGH): окреме
  `messenger.send_first`, яке автосхвалення не покриває ніколи.
"""
from __future__ import annotations

import time
import uuid
from typing import Any, ClassVar

from pydantic import Field

from ..schemas import ActionResult, RiskLevel
from .base import Action, ActionContext

_SCAN_LIMIT = 2000


def _ms(t0: float) -> int:
    return int((time.monotonic() - t0) * 1000)


async def _owner(db, user_id: str):
    from db.models import User

    user = await db.get(User, user_id)
    if user is None:
        raise LookupError(f"немає власника {user_id!r}")
    return user


class ChatAmbiguous(LookupError):
    def __init__(self, candidates: list[dict[str, str]]) -> None:
        super().__init__("кілька або жодної розмови з такою назвою — спитай людину, яку саме")
        self.candidates = candidates[:10]


async def _conversation(db, user_id: str, conversation_id: str):
    from sqlalchemy import select

    from db.models import MessengerConversation

    row = (
        await db.execute(
            select(MessengerConversation).where(
                MessengerConversation.id == conversation_id,
                MessengerConversation.owner_user_id == user_id,
            )
        )
    ).scalar_one_or_none()
    if row is not None:
        return row
    needle = conversation_id.strip().casefold()
    rows = (
        await db.execute(
            select(MessengerConversation).where(
                MessengerConversation.owner_user_id == user_id,
            )
        )
    ).scalars().all()
    matches = [
        r
        for r in rows
        if not bool(getattr(r, "is_demo", False))
        and (r.title or "").strip().casefold() == needle
    ]
    if len(matches) == 1:
        return matches[0]
    raise ChatAmbiguous([{"id": r.id, "title": r.title} for r in matches[:10]])


def _own_author_ids(user_id: str) -> set[str]:
    from api.routes_messenger import _keys

    try:
        return {user_id, _keys().node_id}
    except Exception:  # noqa: BLE001 — без ключів вузла «моє» = лише user_id
        return {user_id}


async def owner_has_written(db, user_id: str, conversation_id: str) -> bool:
    """Чи писав власник у цю розмову хоч раз — межа між «відповісти» і
    «написати новій людині»."""
    from sqlalchemy import select

    from db.models import MessengerMessage

    found = (
        await db.execute(
            select(MessengerMessage.id).where(
                MessengerMessage.conversation_id == conversation_id,
                MessengerMessage.author_id.in_(_own_author_ids(user_id)),
            ).limit(1)
        )
    ).first()
    return found is not None


def _message_view(row, mine: set[str]) -> dict[str, Any]:
    from api.routes_messenger import _body_of

    return {
        "id": row.id,
        "seq": row.seq,
        "mine": row.author_id in mine,
        "author": row.author_name,
        "kind": row.kind,
        "text": None if row.deleted_at else _body_of(row) if row.kind == "text" else None,
        "at": row.sent_at.isoformat() if row.sent_at else None,
    }


class MessengerListChats(Action):
    """Розмови власника: хто, скільки непрочитаних, останнє слово."""

    name: ClassVar[str] = "messenger.list_chats"
    risk_level: ClassVar[RiskLevel] = RiskLevel.SAFE
    reversible: ClassVar[bool] = True

    unread_only: bool = Field(default=False, description="Лише розмови з непрочитаним.")
    limit: int = Field(default=30, ge=1, le=200)

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from api.routes_messenger import _conversations_for
        from db.database import get_session

        try:
            async with get_session() as db:
                rows = await _conversations_for(await _owner(db, ctx.user_id), db)
        except LookupError as exc:
            return ActionResult(ok=False, error=str(exc), elapsed_ms=_ms(t0))
        chats = [
            {
                "id": c.id,
                "title": c.title,
                "kind": c.kind,
                "unread": c.unread_count,
                "last": c.last_snippet,
                "last_author": c.last_author,
                "last_at": c.last_at.isoformat() if c.last_at else None,
                "verified": c.contact_verified,
            }
            for c in rows
            if not c.is_demo and (c.unread_count > 0 or not self.unread_only)
        ][: self.limit]
        return ActionResult(ok=True, output={"chats": chats}, elapsed_ms=_ms(t0))


class MessengerRead(Action):
    """Останні повідомлення однієї розмови, розпечатані."""

    name: ClassVar[str] = "messenger.read"
    risk_level: ClassVar[RiskLevel] = RiskLevel.SAFE
    reversible: ClassVar[bool] = True

    conversation_id: str = Field(
        ...,
        min_length=1,
        max_length=64,
        description="id from messenger.list_chats; an exact chat title also works",
    )
    last: int = Field(default=20, ge=1, le=200, description="Скільки останніх повідомлень.")

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from sqlalchemy import select

        from db.database import get_session
        from db.models import MessengerMessage

        try:
            async with get_session() as db:
                conv = await _conversation(db, ctx.user_id, self.conversation_id)
                conv_id = conv.id
                rows = (
                    await db.execute(
                        select(MessengerMessage)
                        .where(MessengerMessage.conversation_id == conv_id)
                        .order_by(MessengerMessage.seq.desc())
                        .limit(self.last)
                    )
                ).scalars().all()
                title = conv.title
        except ChatAmbiguous as exc:
            return ActionResult(
                ok=False,
                error=str(exc),
                error_class="chat_ambiguous",
                output={"candidates": exc.candidates},
                elapsed_ms=_ms(t0),
            )
        except LookupError as exc:
            return ActionResult(ok=False, error=str(exc), elapsed_ms=_ms(t0))
        mine = _own_author_ids(ctx.user_id)
        messages = [_message_view(r, mine) for r in reversed(rows)]
        return ActionResult(
            ok=True,
            output={"conversation_id": conv_id, "title": title, "messages": messages},
            elapsed_ms=_ms(t0),
        )


class MessengerSearch(Action):
    """Пошук слова по всіх розмовах власника (тіла розпечатуються тут, у вузлі)."""

    name: ClassVar[str] = "messenger.search"
    risk_level: ClassVar[RiskLevel] = RiskLevel.SAFE
    reversible: ClassVar[bool] = True

    query: str = Field(..., min_length=2, max_length=200)
    limit: int = Field(default=20, ge=1, le=100)

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from sqlalchemy import select

        from db.database import get_session
        from db.models import MessengerConversation, MessengerMessage

        needle = self.query.casefold().strip()
        mine = _own_author_ids(ctx.user_id)
        hits: list[dict[str, Any]] = []
        scanned = 0
        async with get_session() as db:
            convs = {
                c.id: c.title
                for c in (
                    await db.execute(
                        select(MessengerConversation).where(
                            MessengerConversation.owner_user_id == ctx.user_id
                        )
                    )
                ).scalars()
            }
            if convs:
                rows = (
                    await db.execute(
                        select(MessengerMessage)
                        .where(
                            MessengerMessage.conversation_id.in_(list(convs)),
                            MessengerMessage.kind == "text",
                            MessengerMessage.deleted_at.is_(None),
                        )
                        .order_by(MessengerMessage.sent_at.desc())
                        .limit(_SCAN_LIMIT)
                    )
                ).scalars().all()
                for row in rows:
                    scanned += 1
                    view = _message_view(row, mine)
                    if view["text"] and needle in view["text"].casefold():
                        hits.append({**view, "conversation_id": row.conversation_id,
                                     "title": convs[row.conversation_id]})
                        if len(hits) >= self.limit:
                            break
        return ActionResult(
            ok=True,
            output={"query": self.query, "hits": hits, "scanned": scanned},
            elapsed_ms=_ms(t0),
        )


class _SendBase(Action):
    conversation_id: str = Field(
        ...,
        min_length=1,
        max_length=64,
        description="id from messenger.list_chats; an exact chat title also works",
    )
    text: str = Field(..., min_length=1, max_length=4000, description="Текст листа від імені власника.")

    reversible: ClassVar[bool] = False
    requires_consent: ClassVar[bool] = True
    requires_network: ClassVar[bool] = True
    #: True — лише розмови, де власник уже писав; False — лише нові.
    _replying: ClassVar[bool] = True

    async def execute(self, ctx: ActionContext) -> ActionResult:
        t0 = time.monotonic()
        from api.routes_messenger import MessageIn, append_message
        from db.database import get_session

        try:
            async with get_session() as db:
                owner = await _owner(db, ctx.user_id)
                conv = await _conversation(db, ctx.user_id, self.conversation_id)
                conv_id = conv.id
                written = await owner_has_written(db, ctx.user_id, conv_id)
                if written != self._replying:
                    other = "messenger.send_first" if self._replying else "messenger.send"
                    why = (
                        "власник ще не писав цій людині — перший лист іде лише через "
                        "messenger.send_first з окремим підтвердженням"
                        if self._replying
                        else "власник уже писав у цю розмову — це відповідь, messenger.send"
                    )
                    return ActionResult(
                        ok=False, error=why, error_class="wrong_send_verb",
                        output={"use": other}, elapsed_ms=_ms(t0),
                    )
                out = await append_message(
                    conv_id,
                    MessageIn(
                        client_id=f"agent-{uuid.uuid4()}",
                        author_id=owner.id,
                        author_name=owner.username,
                        kind="text",
                        body=self.text,
                    ),
                    user=owner,
                    session=db,
                )
        except ChatAmbiguous as exc:
            return ActionResult(
                ok=False,
                error=str(exc),
                error_class="chat_ambiguous",
                output={"candidates": exc.candidates},
                elapsed_ms=_ms(t0),
            )
        except LookupError as exc:
            return ActionResult(ok=False, error=str(exc), elapsed_ms=_ms(t0))
        except Exception as exc:  # HTTPException з причиною від маршруту
            detail = getattr(exc, "detail", None) or str(exc)
            return ActionResult(ok=False, error=str(detail), elapsed_ms=_ms(t0))
        return ActionResult(
            ok=True,
            output={"message_id": out.id, "delivery": out.delivery, "conversation_id": conv_id},
            side_effects=[f"messenger: лист у {conv_id[:8]} ({out.delivery})"],
            elapsed_ms=_ms(t0),
        )


class MessengerSend(_SendBase):
    """Відповідь у розмову, де власник уже писав. Ядро питає згоди (MEDIUM)."""

    name: ClassVar[str] = "messenger.send"
    risk_level: ClassVar[RiskLevel] = RiskLevel.MEDIUM
    _replying: ClassVar[bool] = True


class MessengerSendFirst(_SendBase):
    """Перший лист людині від імені власника — незворотне, завжди з окремим «так»."""

    name: ClassVar[str] = "messenger.send_first"
    risk_level: ClassVar[RiskLevel] = RiskLevel.HIGH
    _replying: ClassVar[bool] = False


MESSENGER_ACTIONS = [
    MessengerListChats,
    MessengerRead,
    MessengerSearch,
    MessengerSend,
    MessengerSendFirst,
]

__all__ = ["ChatAmbiguous", "MESSENGER_ACTIONS", "owner_has_written", *[a.__name__ for a in MESSENGER_ACTIONS]]
