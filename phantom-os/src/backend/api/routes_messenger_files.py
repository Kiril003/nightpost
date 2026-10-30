"""`/api/v1/messenger/files/*` — вкладення месенджера.

Файл їде двома дорогами, і це головне, що варто тут розуміти.

Ключ до нього лежить у тілі повідомлення й летить наскрізним кадром разом із
текстом. Байти — окремо, звичайним HTTP, і навмисне без жодного захисту
транспорту: вони вже шифротекст. Вузол-одержувач зберігає вкладення, яке не
може прочитати; розшифрування відбувається в браузері власника.

Тому приймальня блобів публічна — у співрозмовника немає й не може бути
токена цього вузла. Замість токена стоїть інше: блоб беруть лише від того,
кого власник уже має в контактах, і лише в межах розміру.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Optional

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Request,
    Response,
    UploadFile,
    status,
)
from pydantic import BaseModel, ConfigDict
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from config import config
from db.database import get_db
from db.models import (
    MessengerBlob,
    MessengerContact,
    MessengerConversation,
    User,
)
from messenger.blobs import (
    BLOB_LIMIT_BYTES,
    MAX_BLOB_ATTEMPTS,
    BlobRejected,
    blob_path,
    inbound_blob_guard,
    new_blob_id,
    park_blob,
    push_blob,
    read_bytes,
    record,
    request_from_peer,
    store_bytes,
)
from messenger.carrier_relay import (
    _relay_inflight,
    carrier_inbound_guard,
    prepare_relay,
    push_prepared,
    push_transfer,
    request_from_sender,
)
from messenger.carrier_store import (
    CARRIER_CHUNK,
    CHUNK_OVERHEAD,
    CarrierQuota,
    ack_chunk,
    adopt_transfer,
    create_transfer,
    get_chunk,
    put_chunk,
    relay_meta,
    transfer_record,
    update_relay,
    write_relay,
)
from security.device_auth import get_user_or_device_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/messenger/files", tags=["messenger"])

_relay_tasks: set[asyncio.Task] = set()


async def cancel_relay_tasks() -> None:
    tasks = list(_relay_tasks)
    for task in tasks:
        task.cancel()
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    _relay_tasks.difference_update(tasks)
    _relay_inflight.clear()


def _keys():
    from api.routes_messenger import _keys as node_keys

    return node_keys()


class BlobOut(BaseModel):
    blob_id: str
    size: int
    #: Відбиток ШИФРОТЕКСТУ. Браузер звіряє його перед розшифруванням.
    sha256: str
    #: stored | queued | parked | sent | missing — стан перевезення, не вмісту.
    #: parked — байти чекають в хмарі; адресат забере їх сам.
    state: str


def _bytes_on_disk(blob_id: str) -> bool:
    """Чи лежать байти тут. Непридатне імʼя — те саме, що й «немає»."""
    try:
        return blob_path(blob_id).exists()
    except ValueError:
        return False


async def _contact_of(
    session: AsyncSession, owner_user_id: str, peer_node_id: str
) -> Optional[MessengerContact]:
    return (
        await session.execute(
            select(MessengerContact).where(
                MessengerContact.owner_user_id == owner_user_id,
                MessengerContact.peer_node_id == peer_node_id,
            )
        )
    ).scalar_one_or_none()


async def _owned_blob(
    blob_id: str, user: User, session: AsyncSession
) -> MessengerBlob:
    """Рядок блоба, якщо він належить власнику цього вузла.

    Належність доводиться не самим ідентифікатором — інакше він став би
    паролем, — а розмовою або контактом, які вже прив'язані до власника.
    """
    row = await session.get(MessengerBlob, blob_id)
    if row is None:
        raise HTTPException(status_code=404, detail="вкладення не знайдено")

    if row.conversation_id:
        conversation = await session.get(MessengerConversation, row.conversation_id)
        if conversation is not None and conversation.owner_user_id == user.id:
            return row
    if row.peer_node_id:
        contact = await _contact_of(session, user.id, row.peer_node_id)
        if contact is not None:
            return row
    raise HTTPException(status_code=404, detail="вкладення не знайдено")


# ── Власник: покласти, забрати, дізнатись стан ───────────────────────────────


@router.post("/upload", response_model=BlobOut)
async def upload_blob(
    conversation_id: str = Form(...),
    blob: UploadFile = File(...),
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> BlobOut:
    """Приймає ВЖЕ зашифрований браузером файл і везе його співрозмовнику.

    Вузол не бачив ані відкритого файла, ані ключа: браузер шифрує до
    завантаження, а ключ кладе в тіло повідомлення. Тут лише байти.
    """
    conversation = await session.get(MessengerConversation, conversation_id)
    if conversation is None or conversation.owner_user_id != user.id:
        raise HTTPException(status_code=404, detail="розмову не знайдено")

    data = await blob.read()
    if not data:
        raise HTTPException(status_code=400, detail="порожнє вкладення")
    if len(data) > BLOB_LIMIT_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="вкладення завелике",
        )

    blob_id = new_blob_id()
    digest = store_bytes(blob_id, data)

    # Розмова ні з ким (нотатки собі) — везти нікуди, і це не помилка.
    peer_node_id = ""
    address = ""
    if conversation.contact_id:
        contact = await session.get(MessengerContact, conversation.contact_id)
        if contact is not None:
            peer_node_id = contact.peer_node_id
            address = contact.peer_address or ""

    state = "stored"
    attempts = 0
    # Телефон-співрозмовник бере байти шматками PH-FCH тією ж дорогою, що й
    # слова (`messenger/phone_files.py`). Ні прямої адреси ПК, ні хмари в нього
    # немає: штовхати туди шифротекст означало б класти його в хмару даремно.
    from messenger.phone_peers import phone_peer_of

    if peer_node_id and await phone_peer_of(session, conversation.contact_id) is not None:
        peer_node_id = ""
    if peer_node_id:
        # Ретранслятор возить кадри, не файли. Дороги для байтів дві: пряма
        # адреса й хмара. Немає жодної — блоб чесно лягає в чергу і чекає, а
        # не вдає доставленим.
        state = "queued"
        pushed = False
        if address:
            attempts = 1
            pushed = await push_blob(
                address, blob_id, data, from_node_id=_keys().node_id
            )
        if pushed:
            state = "sent"
        elif await park_blob(blob_id, data, peer_node_id):
            # Байти в хмарі: адресат забере їх сам, навіть якщо цей вузол
            # вимкнуть за секунду після надсилання.
            state = "parked"
            attempts = max(attempts, 1)

    row = await record(
        session,
        blob_id,
        direction="out",
        state=state,
        size=len(data),
        sha256=digest,
        conversation_id=conversation_id,
        peer_node_id=peer_node_id or None,
    )
    row.attempts = attempts
    await session.commit()

    return BlobOut(blob_id=blob_id, size=len(data), sha256=digest, state=state)


# Літеральні шляхи оголошені до `/{blob_id}` — інакше він з'їв би їх обидва.


class OutboundRequest(BaseModel):
    blob_id: str
    from_node_id: str
    reply_address: str = ""


@router.post("/inbound")
async def receive_blob(
    request: Request,
    blob_id: str = Form(...),
    from_node_id: str = Form(...),
    blob: UploadFile = File(...),
    session: AsyncSession = Depends(get_db),
) -> dict:
    """Приймає шифротекст вкладення від чужого вузла.

    Свідомо без JWT: у співрозмовника немає токена цього вузла. Але й не для
    всіх підряд — беремо лише від того, кого власник уже вніс у контакти.
    Незнайомець не має права класти байти на чужий диск.
    """
    owner = (await session.execute(select(User.id).order_by(User.id))).scalars().first()
    if owner is None:
        raise HTTPException(status_code=503, detail="вузол ще не має власника")

    contact = await _contact_of(session, owner, from_node_id)
    if contact is None:
        raise HTTPException(
            status_code=403, detail="вкладення приймаємо лише від відомих співрозмовників"
        )

    # Відсікаємо завелике до читання в память: заявлений розмір — привід
    # відмовити одразу, а не після того, як воно вже лягло на диск.
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > BLOB_LIMIT_BYTES + 4096:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="вкладення завелике для цього вузла",
        )

    data = await blob.read()
    try:
        inbound_blob_guard.check(from_node_id, len(data))
    except BlobRejected as exc:
        code = (
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
            if len(data) > BLOB_LIMIT_BYTES
            else status.HTTP_429_TOO_MANY_REQUESTS
        )
        raise HTTPException(status_code=code, detail=str(exc)) from exc

    try:
        digest = store_bytes(blob_id, data)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    conversation = (
        await session.execute(
            select(MessengerConversation).where(
                MessengerConversation.owner_user_id == owner,
                MessengerConversation.contact_id == contact.id,
            )
        )
    ).scalar_one_or_none()

    await record(
        session,
        blob_id,
        direction="in",
        state="stored",
        size=len(data),
        sha256=digest,
        conversation_id=conversation.id if conversation is not None else None,
        peer_node_id=from_node_id,
    )
    await session.commit()
    # Вузол зберіг те, чого не може прочитати: ключ їде в тілі повідомлення.
    return {"stored": True, "size": len(data), "sha256": digest}


@router.post("/outbound-request")
async def resend_blob(
    payload: OutboundRequest,
    request: Request,
    session: AsyncSession = Depends(get_db),
) -> dict:
    """Співрозмовник просить надіслати вкладення ще раз.

    Байти за самим лише ідентифікатором ми не віддаємо — інакше він став би
    паролем. Замість цього звіряємо, що прохач і є той, кому це вкладення
    призначалось, і штовхаємо блоб у ЙОГО приймальню.
    """
    owner = (await session.execute(select(User.id).order_by(User.id))).scalars().first()
    if owner is None:
        raise HTTPException(status_code=503, detail="вузол ще не має власника")

    try:
        inbound_blob_guard.check(payload.from_node_id, 1)
    except BlobRejected as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc

    contact = await _contact_of(session, owner, payload.from_node_id)
    if contact is None:
        raise HTTPException(status_code=403, detail="невідомий співрозмовник")

    row = await session.get(MessengerBlob, payload.blob_id)
    if row is None or row.direction != "out" or row.peer_node_id != payload.from_node_id:
        raise HTTPException(status_code=404, detail="такого вкладення для вас немає")

    data = read_bytes(payload.blob_id)
    if data is None:
        row.state = "missing"
        await session.commit()
        raise HTTPException(status_code=410, detail="байтів вкладення вже немає")

    address = contact.peer_address or payload.reply_address or ""
    if not address:
        raise HTTPException(status_code=409, detail="адреса вашого вузла невідома")

    row.attempts += 1
    pushed = await push_blob(
        address, payload.blob_id, data, from_node_id=_keys().node_id
    )
    if pushed:
        row.state = "sent"
    await session.commit()
    return {"pushed": pushed}


class CarrierCreateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    size: int
    n: int
    tags: list[str]
    conversation_id: Optional[str] = None


class CarrierRecordOut(BaseModel):
    transfer_id: str
    size: int
    n: int
    received: list[int]
    count: int


def _carrier_view(rec: dict) -> CarrierRecordOut:
    received = list(rec.get("received", []))
    return CarrierRecordOut(
        transfer_id=rec["transfer_id"],
        size=rec["size"],
        n=rec["n"],
        received=received,
        count=len(received),
    )


def _owned_transfer(transfer_id: str, user: User) -> dict:
    try:
        rec = transfer_record(transfer_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="передачу не знайдено") from exc
    if rec is None or rec.get("owner_user_id") != user.id:
        raise HTTPException(status_code=404, detail="передачу не знайдено")
    return rec


@router.post("/carrier", response_model=CarrierRecordOut)
async def create_carrier_transfer(
    payload: CarrierCreateIn,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> CarrierRecordOut:
    peer_node_id = ""
    is_phone = False
    if payload.conversation_id is not None:
        conversation = await session.get(
            MessengerConversation, payload.conversation_id
        )
        if conversation is None or conversation.owner_user_id != user.id:
            raise HTTPException(status_code=404, detail="розмову не знайдено")
        if conversation.contact_id:
            contact = await session.get(MessengerContact, conversation.contact_id)
            if contact is not None:
                peer_node_id = contact.peer_node_id
        from messenger.phone_peers import phone_peer_of

        if (
            peer_node_id
            and await phone_peer_of(session, conversation.contact_id) is not None
        ):
            peer_node_id = ""
            is_phone = True

    try:
        rec = create_transfer(user.id, payload.size, payload.n, payload.tags)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if payload.conversation_id is not None:
        write_relay(
            rec["transfer_id"],
            {
                "direction": "out",
                "conversation_id": payload.conversation_id,
                "peer_node_id": (peer_node_id if not is_phone else "") or None,
                "state": "local",
                "attempts": 0,
                "pushed": [],
                "last_attempt_at": None,
            },
        )
    return _carrier_view(rec)


@router.put("/carrier/{transfer_id}/chunks/{i}", response_model=CarrierRecordOut)
async def put_carrier_chunk(
    transfer_id: str,
    i: int,
    request: Request,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> CarrierRecordOut:
    prev_rec = _owned_transfer(transfer_id, user)
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > CARRIER_CHUNK + CHUNK_OVERHEAD:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="шматок завеликий",
        )
    cap = CARRIER_CHUNK + CHUNK_OVERHEAD
    buf = bytearray()
    async for part in request.stream():
        buf.extend(part)
        if len(buf) > cap:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="шматок завеликий",
            )
    data = bytes(buf)
    already_stored = (
        i in prev_rec.get("received", [])
        and get_chunk(transfer_id, i) is not None
    )
    try:
        rec = put_chunk(transfer_id, i, data)
    except CarrierQuota as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={"code": exc.code},
        ) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if (
        not already_stored
        and transfer_id not in _relay_inflight
        and len(rec.get("received", [])) == int(rec.get("n", 0))
    ):
        def _mark_ready_for_relay(m: dict) -> None:
            if (
                m.get("direction") == "out"
                and m.get("state") in ("local", "queued", "missing")
                and m.get("peer_node_id")
                and int(m.get("attempts", 0)) < MAX_BLOB_ATTEMPTS
            ):
                rest = [int(x) for x in m.get("missing", []) if int(x) != i]
                m["missing"] = rest
                m["state"] = "missing" if rest else "queued"

        # Спершу «queued»: якщо ключі чи база впадуть нижче, передачу підбере черга.
        update_relay(transfer_id, _mark_ready_for_relay)
        meta = relay_meta(transfer_id) or {}
        if meta.get("direction") == "out" and meta.get("state") == "queued":
            own_node_id = _keys().node_id
            addr = await prepare_relay(session, transfer_id)
            if addr is not None:
                _spawn_relay(addr, transfer_id, own_node_id)
    return _carrier_view(rec)


def _spawn_relay(addr: str, tid: str, own_node_id: str) -> asyncio.Task:
    # Захоплення знімає finally у push_prepared. Сюди — лише задача, скасована до старту:
    # пізній колбек інакше зняв би нове захоплення тієї ж передачі.
    ran = False

    async def _push() -> None:
        nonlocal ran
        ran = True
        await push_prepared(addr, tid, own_node_id)

    task = asyncio.create_task(_push())
    _relay_tasks.add(task)
    task.add_done_callback(_relay_tasks.discard)
    task.add_done_callback(lambda _t: None if ran else _relay_inflight.discard(tid))
    return task


@router.get("/carrier/{transfer_id}/chunks/{i}")
async def get_carrier_chunk(
    transfer_id: str,
    i: int,
    user: User = Depends(get_user_or_device_user),
) -> Response:
    _owned_transfer(transfer_id, user)
    try:
        data = get_chunk(transfer_id, i)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="шматок не знайдено") from exc
    if data is None:
        raise HTTPException(status_code=404, detail="шматок не знайдено")
    return Response(
        content=data,
        media_type="application/octet-stream",
        headers={"Cache-Control": "no-store"},
    )


@router.delete("/carrier/{transfer_id}/chunks/{i}", response_model=CarrierRecordOut)
async def ack_carrier_chunk(
    transfer_id: str,
    i: int,
    user: User = Depends(get_user_or_device_user),
) -> CarrierRecordOut:
    _owned_transfer(transfer_id, user)
    try:
        rec = ack_chunk(transfer_id, i)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _carrier_view(rec)


@router.get("/carrier/{transfer_id}", response_model=CarrierRecordOut)
async def get_carrier_transfer(
    transfer_id: str,
    user: User = Depends(get_user_or_device_user),
) -> CarrierRecordOut:
    rec = _owned_transfer(transfer_id, user)
    return _carrier_view(rec)


class CarrierInboundMetaIn(BaseModel):
    from_node_id: str
    size: int
    n: int
    tags: list[str]


class CarrierOutboundRequest(BaseModel):
    transfer_id: str
    from_node_id: str
    missing: list[int]
    reply_address: str = ""


@router.post("/carrier-inbound/{transfer_id}")
async def receive_carrier_meta(
    transfer_id: str,
    payload: CarrierInboundMetaIn,
    session: AsyncSession = Depends(get_db),
) -> dict:
    owner = (await session.execute(select(User.id).order_by(User.id))).scalars().first()
    if owner is None:
        raise HTTPException(status_code=503, detail="вузол ще не має власника")

    contact = await _contact_of(session, owner, payload.from_node_id)
    if contact is None:
        raise HTTPException(
            status_code=403,
            detail="приймаємо лише від відомих співрозмовників",
        )

    try:
        carrier_inbound_guard.check(payload.from_node_id, 1)
    except BlobRejected as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc

    if (
        payload.size > 0
        and payload.n > 0
        and payload.size + payload.n * CHUNK_OVERHEAD > config.carrier_user_inflight_bytes
    ):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="передача завелика для цього вузла",
        )

    try:
        existing_relay = relay_meta(transfer_id)
        existing_rec = transfer_record(transfer_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if (
        existing_relay is not None
        and (
            existing_relay.get("direction") == "out"
            or existing_relay.get("peer_node_id") != payload.from_node_id
        )
    ) or (existing_relay is None and existing_rec is not None):
        raise HTTPException(
            status_code=409,
            detail={
                "code": "carrier_transfer_conflict",
                "detail": "передача вже зайнята",
            },
        )

    try:
        adopt_transfer(
            transfer_id,
            owner,
            payload.size,
            payload.n,
            payload.tags,
            quota_key=f"peer:{payload.from_node_id}",
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if existing_relay is None:
        write_relay(
            transfer_id,
            {
                "direction": "in",
                "conversation_id": None,
                "peer_node_id": payload.from_node_id,
                "state": "sent",
                "attempts": 0,
                "pushed": [],
                "last_attempt_at": None,
            },
        )
    return {"accepted": True, "transfer_id": transfer_id}


@router.post("/carrier-inbound/{transfer_id}/chunks/{i}")
async def receive_carrier_chunk(
    transfer_id: str,
    i: int,
    from_node_id: str,
    request: Request,
    session: AsyncSession = Depends(get_db),
) -> dict:
    owner = (await session.execute(select(User.id).order_by(User.id))).scalars().first()
    if owner is None:
        raise HTTPException(status_code=503, detail="вузол ще не має власника")

    contact = await _contact_of(session, owner, from_node_id)
    if contact is None:
        raise HTTPException(
            status_code=403,
            detail="приймаємо лише від відомих співрозмовників",
        )

    cap = CARRIER_CHUNK + CHUNK_OVERHEAD
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > cap:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="шматок завеликий",
        )
    buf = bytearray()
    async for part in request.stream():
        buf.extend(part)
        if len(buf) > cap:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="шматок завеликий",
            )
    data = bytes(buf)

    try:
        carrier_inbound_guard.check(from_node_id, max(len(data), 1))
    except BlobRejected as exc:
        code = (
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
            if len(data) > cap
            else status.HTTP_429_TOO_MANY_REQUESTS
        )
        raise HTTPException(status_code=code, detail=str(exc)) from exc

    try:
        rec = transfer_record(transfer_id)
        meta = relay_meta(transfer_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="передачу не знайдено") from exc

    if (
        rec is None
        or meta is None
        or meta.get("direction") != "in"
        or meta.get("peer_node_id") != from_node_id
    ):
        raise HTTPException(status_code=404, detail="передачу не знайдено")

    try:
        put_chunk(transfer_id, i, data, check_tag=True)
    except CarrierQuota as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={"code": exc.code},
        ) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    def _record_inbound_chunk(m: dict) -> None:
        pushed = set(int(x) for x in m.get("pushed", []))
        pushed.add(i)
        m["pushed"] = sorted(pushed)

    update_relay(transfer_id, _record_inbound_chunk)

    return {"stored": True, "i": i}


@router.post("/carrier-outbound-request")
async def resend_carrier_chunks(
    payload: CarrierOutboundRequest,
    session: AsyncSession = Depends(get_db),
) -> dict:
    owner = (await session.execute(select(User.id).order_by(User.id))).scalars().first()
    if owner is None:
        raise HTTPException(status_code=503, detail="вузол ще не має власника")

    try:
        carrier_inbound_guard.check(payload.from_node_id, 1)
    except BlobRejected as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc

    contact = await _contact_of(session, owner, payload.from_node_id)
    if contact is None:
        raise HTTPException(status_code=403, detail="невідомий співрозмовник")

    try:
        rec = transfer_record(payload.transfer_id)
        meta = relay_meta(payload.transfer_id)
    except ValueError as exc:
        raise HTTPException(
            status_code=404, detail="такої передачі для вас немає"
        ) from exc

    if (
        rec is None
        or meta is None
        or meta.get("direction") != "out"
        or meta.get("peer_node_id") != payload.from_node_id
    ):
        raise HTTPException(
            status_code=404, detail="такої передачі для вас немає"
        )

    n = int(rec["n"])
    if not payload.missing:
        raise HTTPException(status_code=400, detail="список шматків порожній")
    deduped: list[int] = []
    seen: set[int] = set()
    for idx in payload.missing:
        if isinstance(idx, bool) or not isinstance(idx, int) or idx < 0 or idx >= n:
            raise HTTPException(
                status_code=400, detail="індекс шматка поза межами"
            )
        if idx not in seen:
            seen.add(idx)
            deduped.append(idx)

    address = contact.peer_address or payload.reply_address or ""
    if not address:
        raise HTTPException(status_code=409, detail="адреса вашого вузла невідома")

    missing_on_disk = [
        idx for idx in deduped if get_chunk(payload.transfer_id, idx) is None
    ]

    def _prepare_resend(m: dict) -> None:
        m["pushed"] = [
            int(x) for x in m.get("pushed", []) if int(x) not in seen
        ]
        if missing_on_disk:
            missing_set = set(int(x) for x in m.get("missing", []))
            missing_set.update(missing_on_disk)
            m["missing"] = sorted(missing_set)
        if len(missing_on_disk) == len(deduped):
            m["state"] = "missing"
            return
        if m.get("state") == "sent":
            m["state"] = "queued"

    own_node_id = _keys().node_id
    if payload.transfer_id in _relay_inflight:
        raise HTTPException(status_code=409, detail="передача вже йде")
    update_relay(payload.transfer_id, _prepare_resend)
    if len(missing_on_disk) == len(deduped):
        raise HTTPException(status_code=410, detail="байтів передачі вже немає")

    _relay_inflight.add(payload.transfer_id)
    try:
        pushed = await push_transfer(
            address,
            payload.transfer_id,
            from_node_id=own_node_id,
            only=deduped,
        )
    finally:
        _relay_inflight.discard(payload.transfer_id)
    return {"pushed": pushed}


@router.post("/carrier/{transfer_id}/request", response_model=CarrierRecordOut)
async def request_carrier_chunks(
    transfer_id: str,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> CarrierRecordOut:
    rec = _owned_transfer(transfer_id, user)
    meta = relay_meta(transfer_id)
    if meta is None or meta.get("direction") != "in" or not meta.get("peer_node_id"):
        raise HTTPException(
            status_code=404, detail="про цю передачу вузол ще не знає"
        )
    received_set = set(int(x) for x in rec.get("received", []))
    missing = [i for i in range(int(rec["n"])) if i not in received_set]
    if not missing:
        return _carrier_view(rec)

    contact = await _contact_of(session, user.id, meta["peer_node_id"])
    if contact is None:
        raise HTTPException(status_code=404, detail="передачу не знайдено")
    address = contact.peer_address or ""
    if not address:
        raise HTTPException(
            status_code=409, detail="прямої адреси вузла співрозмовника немає"
        )

    await request_from_sender(
        address,
        transfer_id,
        missing,
        from_node_id=_keys().node_id,
        reply_address=config.messenger_public_address,
    )
    refreshed = transfer_record(transfer_id) or rec
    return _carrier_view(refreshed)


@router.get("/{blob_id}/status", response_model=BlobOut)
async def blob_status(
    blob_id: str,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> BlobOut:
    row = await _owned_blob(blob_id, user, session)
    return BlobOut(
        blob_id=row.blob_id, size=row.size, sha256=row.sha256, state=row.state
    )


@router.post("/{blob_id}/request", response_model=BlobOut)
async def ask_again(
    blob_id: str,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> BlobOut:
    """«Запитати ще раз»: спершу хмара, потім вузол відправника.

    Порядок саме такий, бо хмарі не треба бути увімкненою. Прохання до вузла
    відправника працює, лише поки той живий і має нашу адресу; байти, що
    лежать у бакеті, чекають на нас самі.
    """
    from messenger.redelivery import fetch_parked_blobs

    if not _bytes_on_disk(blob_id):
        # Своє й тільки своє: імʼя обʼєкта починається з node_id цього вузла,
        # а ідентифікатор блоба доводиться повідомленням у стрічці власника.
        taken = await fetch_parked_blobs(
            session, _keys(), user.id, only_blob_id=blob_id
        )
        if taken:
            arrived = await session.get(MessengerBlob, blob_id)
            if arrived is not None:
                return BlobOut(
                    blob_id=blob_id,
                    size=arrived.size,
                    sha256=arrived.sha256,
                    state=arrived.state,
                )

    row = await session.get(MessengerBlob, blob_id)
    peer_node_id = row.peer_node_id if row is not None else None
    conversation_id = row.conversation_id if row is not None else None

    # Блоб міг не доїхати ЖОДНОГО разу — тоді рядка про нього тут ще немає,
    # і єдина зачіпка приходить від клієнта: розмова, у якій він стоїть.
    if peer_node_id is None:
        raise HTTPException(status_code=404, detail="про це вкладення вузол ще не знає")

    contact = await _contact_of(session, user.id, peer_node_id)
    if contact is None:
        raise HTTPException(status_code=404, detail="вкладення не знайдено")

    address = contact.peer_address or ""
    if not address:
        raise HTTPException(
            status_code=409, detail="прямої адреси вузла співрозмовника немає"
        )

    ok = await request_from_peer(
        address,
        blob_id,
        from_node_id=_keys().node_id,
        reply_address=config.messenger_public_address,
    )
    refreshed = await session.get(MessengerBlob, blob_id)
    if refreshed is not None:
        await session.refresh(refreshed)
    state = "stored" if ok and refreshed is not None else (
        refreshed.state if refreshed is not None else "queued"
    )
    return BlobOut(
        blob_id=blob_id,
        size=refreshed.size if refreshed else 0,
        sha256=refreshed.sha256 if refreshed else "",
        state=state,
    )


@router.get("/{blob_id}")
async def fetch_blob(
    blob_id: str,
    user: User = Depends(get_user_or_device_user),
    session: AsyncSession = Depends(get_db),
) -> Response:
    """Віддає шифротекст браузеру власника. Розшифрує його браузер, не вузол."""
    row = await _owned_blob(blob_id, user, session)
    data = read_bytes(row.blob_id)
    if data is None:
        raise HTTPException(status_code=404, detail="байтів вкладення немає")
    return Response(
        content=data,
        media_type="application/octet-stream",
        headers={
            # Тип і імʼя навмисне не оголошуємо: вузол їх не знає, вони живуть
            # у тілі повідомлення. Тут — просто непрозорі байти.
            "Cache-Control": "no-store",
            "X-Blob-Sha256": row.sha256,
        },
    )
