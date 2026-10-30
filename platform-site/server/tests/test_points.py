"""Бали v0: журнал лише дописується, бали дає сервер за доведену дію, ніколи — клієнт.

Рішення власника 07.10: зустріч, що відбулась, — 10; перша підтверджена мережею
оплачена частка — 5; запрошений друг, що приєднався, — 20 (P2, маршруту ще немає).
Бали приватні: бачить лише той, чий ключ. Жодного маршруту виплати, купівлі чи
переказу балів немає — це перевіряє перепис нижче.

Кожна заява підписана справжнім ключем Ed25519 тим самим кодом, що звіряє правду
й вкладення (`attachments.is_fresh_nonce` + `directory.verify`). Мережа Solana
підмінена на шві `points.fetch_transaction` відповідями тієї форми, яку віддає
`getTransaction` з `jsonParsed`.
"""
import base64
import secrets
import sys
import time
import uuid
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import attachments  # noqa: E402
import license_crypto as lc  # noqa: E402
import points  # noqa: E402
from solana_pay import ALPHABET  # noqa: E402

VENUE = "cafe-x"
VENUE_CELL = "881e2a1a2bfffff"
OTHER_CELL = "881e2a1a3bfffff"
HOUR_MS = 3_600_000
VENUES = {VENUE: VENUE_CELL}
BILL = "6f1c2a9e-3b7d-4c55-9a0e-1d2f3a4b5c6d"
MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def now_ms() -> int:
    return int(time.time() * 1000)


class Device:
    def __init__(self):
        self.priv = Ed25519PrivateKey.generate()
        self.raw = self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        self.pub = base64.b64encode(self.raw).decode()

    def proof(self, sentence, pub=None):
        pub = pub or self.pub
        nonce = secrets.token_hex(16)
        issued = now_ms()
        sig = base64.b64encode(self.priv.sign(sentence(pub, nonce, issued))).decode()
        return {"pub_key": pub, "nonce": nonce, "issued_ms": issued, "signature": sig}

    def checkin_body(self, meetup, at=None, venue=VENUE, cell=VENUE_CELL, pub=None):
        at = now_ms() if at is None else at
        body = self.proof(lambda p, n, i: points.checkin_message(p, n, i, meetup, venue, cell, at), pub)
        return {**body, "meetup_id": meetup, "venue_id": venue, "cell": cell, "meetup_at": at}

    def settled_body(self, signature, wallet, ref=None, cluster="devnet", signer=None, address=None):
        """Заява про оплату: підписують і пристрій, і сам гаманець (`signer`, типово —
        той самий гаманець). Без підпису гаманця адресу не прив'язати."""
        ref = BILL if ref is None else ref
        address = address or wallet.address
        sentence = {}

        def make(p, n, i):
            sentence["bytes"] = points.settled_message(p, n, i, cluster, signature, address, ref)
            return sentence["bytes"]

        body = self.proof(make)
        return {**body, "cluster": cluster, "tx": signature, "address": address, "ref": ref,
                "wallet_signature": (signer or wallet).sign(sentence["bytes"])}

    def badge_body(self, badge):
        body = self.proof(lambda p, n, i: points.badge_message(p, n, i, badge))
        return {**body, "badge": badge}

    def me_body(self):
        return self.proof(lambda p, n, i: points.me_message(p, n, i))


class Wallet:
    """Гаманець Solana — це ключ Ed25519; адреса — його 32 байти в base58."""

    def __init__(self):
        self.priv = Ed25519PrivateKey.generate()
        self.address = b58(self.priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    def sign(self, message: bytes) -> str:
        return b58(self.priv.sign(message))


def tx_signature() -> str:
    return b58(secrets.token_bytes(64))


LICENCE = Ed25519PrivateKey.generate()
LICENCE_HEX = LICENCE.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw).hex()


def badge_for(wallet: str, sgt=True, expires_in=86_400, key=LICENCE, kind="seeker_badge"):
    now = int(time.time())
    wallet = getattr(wallet, "address", wallet)
    doc = {"v": 1, "kind": kind, "wallet": wallet, "identity": "peer:olena", "sgt": sgt,
           "issued_at": now, "expires": now + expires_in}
    return lc.sign_certificate(doc, key)


_DEFAULT = object()


def spl_transfer(payer, *, signer=True, err=None, block_time=_DEFAULT, drop=7_500_000, fee_payer=None,
                 memo=BILL):
    """Відповідь `getTransaction` (jsonParsed) на переказ USDC devnet від `payer` з
    позначкою рахунку в Memo (N3 кладе `bill.id` у Memo переказу)."""
    payer = getattr(payer, "address", payer)
    fee_payer = getattr(fee_payer, "address", fee_payer) or payer
    keys = [{"pubkey": fee_payer, "signer": True, "writable": True, "source": "transaction"}]
    if fee_payer != payer:
        keys.append({"pubkey": payer, "signer": signer, "writable": False, "source": "transaction"})
    mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    before, after = 20_000_000, 20_000_000 - drop
    return {
        "blockTime": int(time.time()) - 30 if block_time is _DEFAULT else block_time,
        "slot": 1,
        "meta": {
            "err": err, "fee": 5000,
            "preBalances": [1_000_000_000] * len(keys), "postBalances": [1_000_000_000 - 5000] + [1_000_000_000] * (len(keys) - 1),
            "preTokenBalances": [{"accountIndex": 5, "mint": mint, "owner": payer, "uiTokenAmount": {"amount": str(before), "decimals": 6}}],
            "postTokenBalances": [{"accountIndex": 5, "mint": mint, "owner": payer, "uiTokenAmount": {"amount": str(after), "decimals": 6}}],
        },
        "transaction": {"message": {"accountKeys": keys, "instructions": [
            {"program": "spl-token", "programId": "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
             "parsed": {"type": "transferChecked"}},
            *([{"program": "spl-memo", "programId": MEMO_PROGRAM, "parsed": memo}] if memo is not None else []),
        ]}, "signatures": ["x"]},
    }


@pytest.fixture()
def chain(monkeypatch):
    """signature → відповідь getTransaction (None — ще не підтверджено)."""
    seen: dict[str, dict | None] = {}
    calls: list[tuple[str, str]] = []

    def fake(rpc: str, signature: str):
        calls.append((rpc, signature))
        return seen.get(signature)

    monkeypatch.setattr(points, "fetch_transaction", fake)
    seen["calls"] = calls  # type: ignore[assignment]
    return seen


@pytest.fixture()
def client(tmp_path, chain):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    attachments.reset_for_tests()
    points.reset_for_tests()
    points.init(tmp_path / "volume" / "points.db", venue_cell=VENUES.get, licence_public_hex=LICENCE_HEX)
    app = FastAPI()
    app.include_router(points.router)
    return TestClient(app)


def me(client, dev):
    r = client.post("/points/me", json=dev.me_body())
    assert r.status_code == 200, r.text
    return r.json()


# ── зустріч, що відбулась ────────────────────────────────────────────────────

def test_meetup_needs_two_distinct_keys_in_the_venue_cell(client):
    olena, petro = Device(), Device()
    meetup = str(uuid.uuid4())
    at = now_ms() - 10 * 60_000

    first = client.post("/points/checkin", json=olena.checkin_body(meetup, at))
    assert first.status_code == 200, first.text
    assert first.json()["awarded"] == []
    assert me(client, olena)["total"] == 0

    second = client.post("/points/checkin", json=petro.checkin_body(meetup, at))
    assert second.status_code == 200, second.text
    assert [e["kind"] for e in second.json()["awarded"]] == ["meetup.happened"]

    for dev in (olena, petro):
        mine = me(client, dev)
        assert mine["total"] == 10
        assert [(e["kind"], e["ref"], e["points"]) for e in mine["events"]] == [("meetup.happened", meetup, 10)]


def test_one_key_is_never_two_people(client):
    """Та сама людина двічі — і тим самим ключем в іншій абетці base64 — лишається однією."""
    olena = Device()
    meetup = str(uuid.uuid4())
    at = now_ms()
    assert client.post("/points/checkin", json=olena.checkin_body(meetup, at)).status_code == 200
    again = client.post("/points/checkin", json=olena.checkin_body(meetup, at))
    assert again.status_code == 200 and again.json()["awarded"] == []
    urlsafe = base64.urlsafe_b64encode(olena.raw).decode().rstrip("=")
    other_spelling = client.post("/points/checkin", json=olena.checkin_body(meetup, at, pub=urlsafe))
    assert other_spelling.status_code == 200 and other_spelling.json()["awarded"] == []
    assert me(client, olena)["total"] == 0


def test_meetup_is_never_awarded_to_one_key_twice(client):
    olena, petro, ira = Device(), Device(), Device()
    meetup = str(uuid.uuid4())
    at = now_ms()
    for dev in (olena, petro):
        client.post("/points/checkin", json=dev.checkin_body(meetup, at))
    late = client.post("/points/checkin", json=ira.checkin_body(meetup, at))
    assert [e["kind"] for e in late.json()["awarded"]] == ["meetup.happened"]
    again = client.post("/points/checkin", json=olena.checkin_body(meetup, at))
    assert again.json()["awarded"] == []
    assert me(client, olena)["total"] == 10
    assert me(client, ira)["total"] == 10


def test_replayed_checkin_gives_nothing(client):
    olena, petro = Device(), Device()
    meetup = str(uuid.uuid4())
    at = now_ms()
    body = olena.checkin_body(meetup, at)
    assert client.post("/points/checkin", json=body).status_code == 200
    client.post("/points/checkin", json=petro.checkin_body(meetup, at))
    replay = client.post("/points/checkin", json=body)
    assert replay.status_code == 401
    assert me(client, olena)["total"] == 10


def test_checkin_outside_the_cell_or_the_window_is_refused(client):
    olena, petro = Device(), Device()
    meetup = str(uuid.uuid4())

    wrong_cell = client.post("/points/checkin", json=olena.checkin_body(meetup, now_ms(), cell=OTHER_CELL))
    assert wrong_cell.status_code == 422 and wrong_cell.json()["refused"] == "not_at_venue"
    assert wrong_cell.json()["message_uk"] and wrong_cell.json()["message_en"]

    stale = client.post("/points/checkin", json=olena.checkin_body(meetup, now_ms() - 3 * HOUR_MS))
    assert stale.status_code == 422 and stale.json()["refused"] == "outside_window"
    early = client.post("/points/checkin", json=olena.checkin_body(meetup, now_ms() + 3 * HOUR_MS))
    assert early.status_code == 422 and early.json()["refused"] == "outside_window"

    unknown = client.post("/points/checkin", json=olena.checkin_body(meetup, now_ms(), venue="nowhere"))
    assert unknown.status_code == 422 and unknown.json()["refused"] == "venue_unknown"

    # Двоє, що не згодні, о котрій зустріч, — не одна зустріч.
    at = now_ms()
    client.post("/points/checkin", json=olena.checkin_body(meetup, at))
    other_time = client.post("/points/checkin", json=petro.checkin_body(meetup, at - HOUR_MS))
    assert other_time.status_code == 200 and other_time.json()["awarded"] == []
    assert me(client, olena)["total"] == 0 and me(client, petro)["total"] == 0


def test_half_wiring_is_refused_at_startup_not_at_every_checkin(tmp_path):
    """`points.init(path)` без комірок закладів і ключа ліцензій лишав би живими
    лише двері: кожне «Я на місці» й кожен знак — 503 на справжньому сервері.
    Таке підключення мусить падати при старті, а не мовчки."""
    points.reset_for_tests()
    with pytest.raises(TypeError):
        points.init(tmp_path / "p.db")
    with pytest.raises(ValueError):
        points.init(tmp_path / "p.db", venue_cell=None, licence_public_hex=LICENCE_HEX)
    with pytest.raises(ValueError):
        points.init(tmp_path / "p.db", venue_cell=VENUES.get, licence_public_hex="")
    points.reset_for_tests()


def test_routes_before_init_are_an_honest_refusal(tmp_path, chain):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    attachments.reset_for_tests()
    points.reset_for_tests()
    app = FastAPI()
    app.include_router(points.router)
    r = TestClient(app).post("/points/checkin", json=Device().checkin_body(str(uuid.uuid4())))
    assert r.status_code == 503


def test_meetup_points_are_capped_per_key_per_day(client):
    """Комірка закладу публічна, id і час зустрічі обирає клієнт: сервер не може
    довести присутність. Тож один ключ, що щоразу бере в пару свіжий ключ, не
    збирає балів без межі — не більше MEETUPS_PER_DAY зустрічей за добу."""
    farmer = Device()
    for _ in range(5):
        meetup, at = str(uuid.uuid4()), now_ms()
        client.post("/points/checkin", json=farmer.checkin_body(meetup, at))
        client.post("/points/checkin", json=Device().checkin_body(meetup, at))
    assert points.MEETUPS_PER_DAY == 2
    assert me(client, farmer)["total"] == 10 * points.MEETUPS_PER_DAY
    # Друг, з яким зустрілись уперше, своє отримує, навіть коли в пари межа вичерпана.
    friend, meetup, at = Device(), str(uuid.uuid4()), now_ms()
    client.post("/points/checkin", json=farmer.checkin_body(meetup, at))
    r = client.post("/points/checkin", json=friend.checkin_body(meetup, at))
    assert [e["kind"] for e in r.json()["awarded"]] == ["meetup.happened"]
    assert me(client, farmer)["total"] == 10 * points.MEETUPS_PER_DAY


def test_bad_signature_is_401(client):
    olena, mallory = Device(), Device()
    body = olena.checkin_body(str(uuid.uuid4()))
    body["signature"] = mallory.checkin_body(body["meetup_id"])["signature"]
    assert client.post("/points/checkin", json=body).status_code == 401
    forged_me = olena.me_body()
    forged_me["pub_key"] = mallory.pub
    assert client.post("/points/me", json=forged_me).status_code == 401


# ── перша оплачена частка ────────────────────────────────────────────────────

def test_first_paid_share_is_awarded_once(client, chain):
    olena, wallet = Device(), Wallet()
    sig1, sig2 = tx_signature(), tx_signature()
    chain[sig1] = spl_transfer(wallet.address)
    chain[sig2] = spl_transfer(wallet.address)

    r = client.post("/points/settled", json=olena.settled_body(sig1, wallet))
    assert r.status_code == 200, r.text
    assert [(e["kind"], e["points"]) for e in r.json()["awarded"]] == [("payment.first", 5)]
    again = client.post("/points/settled", json=olena.settled_body(sig2, wallet))
    assert again.status_code == 200 and again.json()["awarded"] == []
    assert me(client, olena)["total"] == 5
    assert chain["calls"][0][0] == "https://api.devnet.solana.com"


def test_unconfirmed_signature_is_pending_not_points(client, chain):
    olena, wallet = Device(), Wallet()
    sig = tx_signature()  # getTransaction(commitment=confirmed) → null
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 202 and r.json()["status"] == "pending"
    assert me(client, olena)["total"] == 0
    # Пізніше мережа підтвердила — та сама заява вже рахується.
    chain[sig] = spl_transfer(wallet.address)
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 200 and r.json()["awarded"]


def test_solana_rpc_down_is_a_503_with_a_reason_not_a_bare_503(client, monkeypatch):
    """Мережа не відповіла — це не «двері не підключено». Голий 503 телефон читає як
    «сервер не ввімкнено»; тут двері є, тож відмова несе причину."""
    def down(rpc, signature):
        raise TimeoutError("rpc down")

    monkeypatch.setattr(points, "fetch_transaction", down)
    olena, wallet = Device(), Wallet()
    r = client.post("/points/settled", json=olena.settled_body(tx_signature(), wallet))
    assert r.status_code == 503
    body = r.json()
    assert body["refused"] == "network_unavailable"
    assert body["message_uk"] and body["message_en"]
    assert me(client, olena)["total"] == 0


@pytest.mark.parametrize("case", ["failed", "foreign_signer", "not_a_signer", "nothing_left", "old"])
def test_payment_that_is_not_yours_or_not_a_payment_is_refused(client, chain, case):
    olena, wallet, stranger = Device(), Wallet(), Wallet()
    sig = tx_signature()
    chain[sig] = {
        "failed": lambda: spl_transfer(wallet.address, err={"InstructionError": [0, "Custom"]}),
        "foreign_signer": lambda: spl_transfer(stranger.address),
        "not_a_signer": lambda: spl_transfer(wallet.address, signer=False, fee_payer=stranger.address),
        "nothing_left": lambda: spl_transfer(wallet.address, drop=0),
        "old": lambda: spl_transfer(wallet.address, block_time=int(time.time()) - 30 * 86_400),
    }[case]()
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 422, r.text
    assert r.json()["refused"] == {
        "failed": "tx_failed", "foreign_signer": "not_your_payment", "not_a_signer": "not_your_payment",
        "nothing_left": "not_a_payment", "old": "too_old",
    }[case]
    assert me(client, olena)["total"] == 0


def test_someone_elses_payment_cannot_be_claimed_and_cannot_lock_its_owner_out(client, chain):
    """Будь-хто бачить транзакції в мережі. Без підпису самого гаманця заява про
    чужу оплату не дає ні балів, ні прив'язки — і справжня власниця потім і
    отримує свої бали, і записує свій знак Seeker."""
    attacker, attackers_wallet, olena, wallet = Device(), Wallet(), Device(), Wallet()
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet)
    squat = client.post("/points/settled", json=attacker.settled_body(sig, wallet, signer=attackers_wallet))
    assert squat.status_code == 401, squat.text
    assert me(client, attacker)["total"] == 0
    assert chain["calls"] == [], "підробка відмовлена до мережі"

    own = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert own.status_code == 200 and own.json()["awarded"], own.text
    badge = client.post("/points/badge", json=olena.badge_body(badge_for(wallet)))
    assert badge.status_code == 200, badge.text
    assert me(client, olena)["rewards_eligible"] is True


def test_only_a_transfer_carrying_this_bill_counts(client, chain):
    """Будь-який вихідний переказ — ще не частка рахунку. Рахується лише той, що
    несе в Memo id рахунку із заяви (N3 кладе `bill.id` у Memo)."""
    olena, wallet = Device(), Wallet()
    for memo in (None, "6f1c2a9e-0000-4c55-9a0e-1d2f3a4b5c6d", "coffee"):
        sig = tx_signature()
        chain[sig] = spl_transfer(wallet, memo=memo)
        r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
        assert r.status_code == 422 and r.json()["refused"] == "not_this_bill", (memo, r.text)
        assert r.json()["message_uk"] and r.json()["message_en"]
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet, memo=BILL.upper())
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 200 and r.json()["awarded"], r.text


def test_mainnet_claims_are_refused_devnet_only(client, chain):
    olena, wallet = Device(), Wallet()
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet)
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet, cluster="mainnet"))
    assert r.status_code == 422, r.text
    assert chain["calls"] == []
    assert me(client, olena)["total"] == 0


def test_confirmed_without_block_time_is_pending_not_too_old(client, chain):
    """`getTransaction` може віддати підтверджену транзакцію з `blockTime: null`.
    Це «спитай пізніше», а не «старша за тиждень»."""
    olena, wallet = Device(), Wallet()
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet, block_time=None)
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 202 and r.json()["status"] == "pending", r.text
    chain[sig] = spl_transfer(wallet)
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 200 and r.json()["awarded"], r.text


def test_sponsored_fee_still_counts_when_the_wallet_signed(client, chain):
    """Комісію може платити спонсор (sponsor.py), але частку підписує сам гаманець."""
    olena, wallet, sponsor = Device(), Wallet(), Wallet()
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet.address, fee_payer=sponsor.address)
    r = client.post("/points/settled", json=olena.settled_body(sig, wallet))
    assert r.status_code == 200 and r.json()["awarded"]


def test_one_transaction_and_one_wallet_serve_one_person(client, chain):
    olena, petro, wallet = Device(), Device(), Wallet()
    sig = tx_signature()
    chain[sig] = spl_transfer(wallet.address)
    assert client.post("/points/settled", json=olena.settled_body(sig, wallet)).status_code == 200
    same_tx = client.post("/points/settled", json=petro.settled_body(sig, wallet))
    assert same_tx.status_code == 422 and same_tx.json()["refused"] == "tx_used"
    sig2 = tx_signature()
    chain[sig2] = spl_transfer(wallet.address)
    same_wallet = client.post("/points/settled", json=petro.settled_body(sig2, wallet))
    assert same_wallet.status_code == 422 and same_wallet.json()["refused"] == "wallet_taken"
    assert me(client, petro)["total"] == 0
    # Відома відмова не витрачає бюджету RPC: до мережі ходили лише раз.
    assert len(chain["calls"]) == 1


# ── знак Seeker: право на майбутні нагороди, лише записане ──────────────────

def test_rewards_eligible_needs_a_real_badge_for_a_wallet_you_paid_from(client, chain):
    olena, wallet = Device(), Wallet()
    assert me(client, olena)["rewards_eligible"] is False

    unlinked = client.post("/points/badge", json=olena.badge_body(badge_for(wallet)))
    assert unlinked.status_code == 422 and unlinked.json()["refused"] == "wallet_not_linked"

    sig = tx_signature()
    chain[sig] = spl_transfer(wallet.address)
    client.post("/points/settled", json=olena.settled_body(sig, wallet))

    forged = client.post("/points/badge", json=olena.badge_body(badge_for(wallet.address, key=Ed25519PrivateKey.generate())))
    assert forged.status_code == 422 and forged.json()["refused"] == "badge_invalid"
    expired = client.post("/points/badge", json=olena.badge_body(badge_for(wallet.address, expires_in=-1)))
    assert expired.status_code == 422 and expired.json()["refused"] == "badge_invalid"
    staker_only = client.post("/points/badge", json=olena.badge_body(badge_for(wallet.address, sgt=False)))
    assert staker_only.status_code == 422 and staker_only.json()["refused"] == "badge_invalid"
    assert me(client, olena)["rewards_eligible"] is False

    ok = client.post("/points/badge", json=olena.badge_body(badge_for(wallet)))
    assert ok.status_code == 200, ok.text
    mine = me(client, olena)
    assert mine["rewards_eligible"] is True
    assert mine["total"] == 5, "знак сам балів не дає"


# ── межі: бали не приходять від клієнта, нічого не купується й не виводиться ─

def test_no_endpoint_accepts_a_points_amount_from_the_client(client):
    olena = Device()
    meetup = str(uuid.uuid4())
    for path, body in (
        ("/points/checkin", olena.checkin_body(meetup)),
        ("/points/me", olena.me_body()),
    ):
        smuggled = {**body, "points": 1000}
        r = client.post(path, json=smuggled)
        assert r.status_code == 422, (path, r.text)
    for route in points.router.routes:
        model = route.body_field.type_ if getattr(route, "body_field", None) else None
        fields = set(model.model_fields) if model else set()
        assert not fields & {"points", "amount", "value", "total", "score", "balance"}, route.path


FORBIDDEN = ("pay", "buy", "purchase", "withdraw", "redeem", "payout", "cashout", "transfer", "send",
             "shop", "gift", "sell", "spend", "convert")


def test_census_points_routes_are_exactly_these_four():
    seen = {(route.path, tuple(sorted(route.methods))) for route in points.router.routes}
    assert seen == {
        ("/points/checkin", ("POST",)),
        ("/points/settled", ("POST",)),
        ("/points/badge", ("POST",)),
        ("/points/me", ("POST",)),
    }
    for path, _ in seen:
        assert not any(word in path.lower() for word in FORBIDDEN), path


def test_census_on_the_real_app_once_wired(tmp_path_factory, monkeypatch):
    """Вхід — `main.app`. Підключення роутера — коміт S1 (`main.py` його); доки його
    немає, тест чесно пропускається, а не зеленіє порожнім."""
    lic = tmp_path_factory.mktemp("licence")
    vol = tmp_path_factory.mktemp("volume")
    monkeypatch.setenv("LICENSE_DB", str(lic / "t.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(lic / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(lic / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(vol / "relay.db"))
    monkeypatch.setenv("TRUTH_DB", str(vol / "truth.db"))
    monkeypatch.setenv("POINTS_DB", str(vol / "points.db"))
    monkeypatch.setenv("VENUE_DB", str(vol / "venues.db"))
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer", "attachments", "truth_store"):
        sys.modules.pop(mod, None)
    import main

    mounted = {r.path for r in main.app.routes if getattr(r, "path", "").startswith("/points")}
    if not mounted:
        pytest.skip("points.router ще не підключено в main.py (коміт підключення — S1)")
    assert mounted == {"/points/checkin", "/points/settled", "/points/badge", "/points/me"}
    for route in main.app.routes:
        path = getattr(route, "path", "").lower()
        if "point" in path or "бал" in path:
            assert not any(word in path for word in FORBIDDEN), path

    # Змонтовано — ще не досяжно. Справжні підписані заяви крізь `main.app` мусять
    # дійти до звірки закладу й знака, а не впертись у 503 «не підключено».
    from fastapi.testclient import TestClient

    http = TestClient(main.app)
    olena, wallet = Device(), Wallet()
    checkin = http.post("/points/checkin", json=olena.checkin_body(str(uuid.uuid4()), venue="no-such-venue"))
    assert checkin.status_code == 422, checkin.text
    assert checkin.json()["refused"] == "venue_unknown"
    real_badge = badge_for(wallet, key=main.signing.private)
    badge = http.post("/points/badge", json=olena.badge_body(real_badge))
    assert badge.status_code == 422, badge.text
    assert badge.json()["refused"] == "wallet_not_linked", "знак підписано справжнім ключем ліцензій main"
    assert http.post("/points/me", json=olena.me_body()).status_code == 200


# ── приватність і сховище ───────────────────────────────────────────────────

def test_points_are_private_to_their_key(client):
    olena, petro, stranger = Device(), Device(), Device()
    meetup = str(uuid.uuid4())
    at = now_ms()
    for dev in (olena, petro):
        client.post("/points/checkin", json=dev.checkin_body(meetup, at))
    mine = me(client, stranger)
    assert mine == {"total": 0, "events": [], "rewards_eligible": False, "private": True}
    # Відповідь на відмітку не каже, ХТО ще відмітився, — лише скільки бракує.
    r = client.post("/points/checkin", json=Device().checkin_body(str(uuid.uuid4()), at))
    assert set(r.json()) == {"checked_in", "awarded", "waiting_for"}
    assert r.json()["waiting_for"] == 1


def test_journal_is_append_only_and_checkins_never_touch_the_disk(client, tmp_path):
    import sqlite3

    olena, petro = Device(), Device()
    meetup = str(uuid.uuid4())
    at = now_ms() - HOUR_MS
    for dev in (olena, petro):
        client.post("/points/checkin", json=dev.checkin_body(meetup, at))
    db = tmp_path / "volume" / "points.db"
    with sqlite3.connect(db) as conn:
        with pytest.raises(sqlite3.DatabaseError):
            conn.execute("UPDATE events SET points = 1000")
        with pytest.raises(sqlite3.DatabaseError):
            conn.execute("DELETE FROM events")
        # Відмітка — це місце людини в часі. Вона живе лише в пам'яті й лише на
        # вікно зустрічі: том знімається у знімки, і там її бути не має.
        columns = {
            col for (table,) in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
            for col in (r[1] for r in conn.execute(f"PRAGMA table_info({table})"))
        }
        assert not columns & {"cell", "lat", "lon", "meetup_at"}
        assert VENUE_CELL.encode() not in db.read_bytes()
    assert points.checkins().size() == 2
    points.checkins().sweep(now_ms=at + points.WINDOW_MS + 1)
    assert points.checkins().size() == 0
    assert me(client, olena)["total"] == 10


def test_ledger_lives_on_the_durable_volume_by_default(tmp_path):
    """Журнал балів — не сховок листів: викладення не сміє його стерти, тож без
    POINTS_DB він лягає поруч із базою ліцензій, на том зі знімками."""
    lic = tmp_path / "data" / "licenses.db"
    assert points.resolve_db_path("", lic) == (tmp_path / "data" / "points.db").resolve()
    assert points.resolve_db_path(str(tmp_path / "x" / "p.db"), lic) == (tmp_path / "x" / "p.db").resolve()
    with pytest.raises(RuntimeError):
        points.resolve_db_path(str(lic), lic)


def test_owner_values():
    assert points.POINTS == {"meetup.happened": 10, "payment.first": 5, "invite.joined": 20}


# ── спільний вектор речень (телефон звіряє той самий файл) ────────────────────

def test_вектор_речень_балів():
    """`points_sentence_vectors.json` — для `PointsClient` на телефоні: ті самі
    речення, той самий підпис Ed25519, той самий звіряльник, що для правди.
    Розійдеться одне слово — кожна заява з телефона стане 401."""
    import json

    import directory

    data = json.loads((Path(__file__).parent / "vectors" / "points_sentence_vectors.json").read_text(encoding="utf-8"))
    priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(data["seed_hex"]))
    pub = base64.b64encode(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    assert {c["kind"] for c in data["cases"]} == {"checkin", "settled", "me"}
    for c in data["cases"]:
        assert c["pub_key"] == pub
        if c["kind"] == "checkin":
            sentence = points.checkin_message(pub, c["nonce"], c["issued_ms"], c["meetup_id"], c["venue_id"],
                                              c["cell"], c["meetup_at"])
        elif c["kind"] == "settled":
            sentence = points.settled_message(pub, c["nonce"], c["issued_ms"], c["cluster"], c["tx"], c["address"],
                                              c["ref"])
            # Друга заява: те саме речення підписує й сам гаманець (його ключ — `wallet_seed_hex`).
            wallet = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(data["wallet_seed_hex"]))
            assert c["address"] == b58(wallet.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
            assert base64.b64encode(wallet.sign(sentence)).decode() == c["wallet_signature"]
            assert points._wallet_signed(wallet.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw), sentence,
                                         c["wallet_signature"])
        else:
            sentence = points.me_message(pub, c["nonce"], c["issued_ms"])
        assert sentence == c["sentence"].encode()
        assert base64.b64encode(priv.sign(sentence)).decode() == c["signature"]
        assert directory.verify(pub, sentence, c["signature"])
