"""«Намір» v0 — мій ПК як агент і шлюз для телефона без інтернету.

Біблія месенджера, розділ 07 «Обхідний інтернет», §2.2 і §8. Телефон шле
крихітне запечатане прохання будь-якою щілиною; ПК виконує його лише для
МОЇХ спарених телефонів, лише з білого списку, і відповідає під розмір смуги,
якою відповідь поїде назад.
"""
from __future__ import annotations

from typing import Optional

from sqlalchemy import select

from .executor import IntentExecutor, OwnPhone

__all__ = ["IntentExecutor", "OwnPhone", "get_executor", "own_phones"]

_executor: Optional[IntentExecutor] = None


async def own_phones() -> list[OwnPhone]:
    """Телефони, спарені з цим ПК як «мій пристрій». Контакт із запрошення PH2
    (чужа людина) сюди не потрапляє ніколи: `mine` ставить лише спарювання."""
    from api.routes_messenger import _keys
    from db.database import AsyncSessionLocal
    from db.models import MessengerContact, MessengerPhonePeer
    from messenger.phone_peers import pair_for

    keys = _keys()
    async with AsyncSessionLocal() as db:
        peers = (
            await db.execute(
                select(MessengerPhonePeer)
                .join(MessengerContact, MessengerContact.id == MessengerPhonePeer.contact_id)
                .where(MessengerContact.mine.is_(True))
            )
        ).scalars().all()
    out: list[OwnPhone] = []
    for peer in peers:
        pair = pair_for(keys, peer)
        if pair is None:
            continue
        out.append(
            OwnPhone(
                phone_id=pair.peer_id,
                pc_id=pair.my_id,
                pair_key=pair.pair_key,
                owner_user_id=pair.owner_user_id,
            )
        )
    return out


def get_executor() -> IntentExecutor:
    global _executor
    if _executor is None:
        from .ports import LivePorts
        from .relay_lane import CourierRelayLane

        _executor = IntentExecutor(own_phones, LivePorts(), relay=CourierRelayLane())
    return _executor
