"""Спонсор комісій: що він підписує і чого не підпише ніколи.

Кожен тест перевіряє не лише код відповіді, а й ПРИЧИНУ: 403 з чужої причини — це
інша дірка, яка просто випадково закрилась. Мережа підмінена; транзакції складає
власний мінімальний компілятор повідомлень тут, незалежний від розбору в sponsor.py.
"""
import base64
import hashlib
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage", "solana_pay", "sponsor")
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
SYSTEM = "11111111111111111111111111111111"
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
USDC_DEV = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
USDC_MAIN = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
EURC = "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"
BLOCKHASH = bytes([0xAB]) * 32


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def unb58(text: str) -> bytes:
    n = 0
    for c in text:
        n = n * 58 + ALPHABET.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


class Key:
    def __init__(self):
        self.sk = Ed25519PrivateKey.generate()
        self.pub = self.sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        self.address = b58(self.pub)

    def secret_json(self) -> str:
        seed = self.sk.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
        return json.dumps(list(seed + self.pub))

    def sign(self, data: bytes) -> bytes:
        return self.sk.sign(data)


def rand_addr() -> str:
    return Key().address


def compile_message(payer: str, ixs, blockhash: bytes = BLOCKHASH, version0: bool = False) -> bytes:
    """ixs: [(program, [(key, signer, writable)], data)]. Порядок ключів — як вимагає формат."""
    metas: dict[str, list[bool]] = {payer: [True, True]}
    for program, accounts, _ in ixs:
        for key, signer, writable in accounts:
            m = metas.setdefault(key, [False, False])
            m[0] |= signer
            m[1] |= writable
        metas.setdefault(program, [False, False])
    rest = [k for k in metas if k != payer]
    ordered = [payer] + [k for k in rest if metas[k] == [True, True]] + [k for k in rest if metas[k] == [True, False]] \
        + [k for k in rest if metas[k] == [False, True]] + [k for k in rest if metas[k] == [False, False]]
    index = {k: i for i, k in enumerate(ordered)}
    out = bytearray()
    if version0:
        out.append(0x80)
    out += bytes([
        sum(metas[k][0] for k in ordered),
        sum(metas[k][0] and not metas[k][1] for k in ordered),
        sum(not metas[k][0] and not metas[k][1] for k in ordered),
    ])
    out.append(len(ordered))
    for k in ordered:
        out += unb58(k)
    out += blockhash
    out.append(len(ixs))
    for program, accounts, data in ixs:
        out.append(index[program])
        out.append(len(accounts))
        out += bytes(index[k] for k, _, _ in accounts)
        out.append(len(data))
        out += data
    if version0:
        out.append(0)
    return bytes(out)


def transfer_checked(source, mint, dest, authority, amount=2_500_000, program=TOKEN):
    return (program, [(source, False, True), (mint, False, False), (dest, False, True), (authority, True, False)],
            bytes([12]) + amount.to_bytes(8, "little") + bytes([6]))


def create_ata(payer, ata, owner, mint=USDC_DEV):
    return (ATA, [(payer, True, True), (ata, False, True), (owner, False, False), (mint, False, False),
                  (SYSTEM, False, False), (TOKEN, False, False)], bytes([1]))


def system_transfer(src, dst, lamports=1_000_000):
    return (SYSTEM, [(src, True, True), (dst, False, True)], (2).to_bytes(4, "little") + lamports.to_bytes(8, "little"))


def advance_nonce(nonce, authority):
    return (SYSTEM, [(nonce, False, True), (RECENT_BLOCKHASHES, False, False), (authority, True, False)], (4).to_bytes(4, "little"))


def memo(text="PHANTOM"):
    return (MEMO, [], text.encode())


class World:
    def __init__(self, client, server, sponsor_key, net):
        self.client, self.server, self.sponsor, self.net = client, server, sponsor_key, net
        self.user = Key()
        self.source = rand_addr()
        self.recipient = rand_addr()
        self.dest = rand_addr()

    def honest(self, with_ata=True):
        ixs = ([create_ata(self.sponsor.address, self.dest, self.recipient)] if with_ata else []) + [
            transfer_checked(self.source, USDC_DEV, self.dest, self.user.address), memo()]
        return compile_message(self.sponsor.address, ixs)

    def body(self, message: bytes, signer: Key | None = None, identity_key: Key | None = None) -> dict:
        signer = signer or self.user
        identity_key = identity_key or self.user
        n = message[1] if message[0] & 0x80 else message[0]
        slots = [bytes(64)] * n
        if n >= 2:
            slots[1] = signer.sign(message)
        raw = bytes([n]) + b"".join(slots) + message
        id_sig = identity_key.sign(b"phantom-sponsor:v1:" + hashlib.sha256(message).digest())
        return {"tx_base64": base64.b64encode(raw).decode(), "identity": self.user.address, "identity_sig": b58(id_sig)}

    def sign(self, message: bytes, **kw):
        return self.client.post("/api/solana/sponsor/sign", json=self.body(message, **kw))


def _boot(tmp_path, monkeypatch, cluster="devnet", quota=None):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "sponsor.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    key = Key()
    monkeypatch.setenv("SOLANA_SPONSOR_CLUSTER", cluster)
    monkeypatch.setenv("SOLANA_SPONSOR_KEY", key.secret_json())
    if quota is not None:
        monkeypatch.setenv("SOLANA_SPONSOR_DAILY_QUOTA", str(quota))
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import sponsor
    from fastapi.testclient import TestClient

    net = {"sent": [], "existing": set(), "fresh": True}

    def send_raw(rpc, raw):
        net["sent"].append(raw)
        return b58(raw[1:65])

    sponsor.send_raw = send_raw
    sponsor.account_exists = lambda rpc, k: k in net["existing"]
    sponsor.blockhash_valid = lambda rpc, bh: net["fresh"]
    server._buckets.clear()
    return World(TestClient(server.app), server, key, net)


@pytest.fixture()
def w(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def reason(res):
    return res.json().get("detail")


def test_door_names_the_fee_payer(w):
    res = w.client.get("/api/solana/sponsor")
    assert res.status_code == 200
    assert res.json() == {"cluster": "devnet", "fee_payer": w.sponsor.address}


def test_honest_usdc_transfer_with_ata_is_cosigned_and_sent(w):
    message = w.honest()
    res = w.sign(message)
    assert res.status_code == 200, res.text
    assert len(w.net["sent"]) == 1
    raw = w.net["sent"][0]
    assert raw[0] == 2 and raw[129:] == message
    Ed25519PublicKey.from_public_bytes(w.sponsor.pub).verify(raw[1:65], message)
    Ed25519PublicKey.from_public_bytes(w.user.pub).verify(raw[65:129], message)
    assert res.json() == {"signature": b58(raw[1:65]), "cluster": "devnet"}
    assert w.server.store.count("events", "kind = ?", ("sponsor_ata",)) == 1


def test_honest_eurc_transfer_with_ata_is_cosigned_and_sent(w):
    message = compile_message(w.sponsor.address, [
        create_ata(w.sponsor.address, w.dest, w.recipient, mint=EURC),
        transfer_checked(w.source, EURC, w.dest, w.user.address), memo()])
    res = w.sign(message)
    assert res.status_code == 200, res.text
    assert w.net["sent"][0][129:] == message


def test_mint_outside_the_cluster_allowlist_is_refused(w):
    for mint in (rand_addr(), USDC_MAIN):
        res = w.sign(compile_message(w.sponsor.address, [transfer_checked(w.source, mint, w.dest, w.user.address)]))
        assert res.status_code == 403
        assert reason(res) == "mint_not_allowed"
        res = w.sign(compile_message(w.sponsor.address, [
            create_ata(w.sponsor.address, w.dest, w.recipient, mint=mint),
            transfer_checked(w.source, mint, w.dest, w.user.address)]))
        assert res.status_code == 403
        assert reason(res) == "ata_instruction_not_allowed"
    assert w.net["sent"] == []


def test_system_transfer_from_sponsor_is_refused(w):
    message = compile_message(w.sponsor.address, [
        transfer_checked(w.source, USDC_DEV, w.dest, w.user.address),
        system_transfer(w.sponsor.address, w.user.address),
    ])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "system_program"
    assert not w.net["sent"]


def test_sponsor_as_owner_of_a_token_transfer_is_refused(w):
    sponsor_ata = rand_addr()
    message = compile_message(w.sponsor.address, [
        transfer_checked(w.source, USDC_DEV, w.dest, w.user.address),
        transfer_checked(sponsor_ata, USDC_DEV, w.dest, w.sponsor.address),
    ])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "sponsor_misused"


def test_close_account_is_refused(w):
    # Чотири рахунки (власник-мультипідпис) — щоб відмову давав саме тег інструкції, а не їхня кількість.
    close = (TOKEN, [(w.dest, False, True), (w.recipient, False, True), (w.user.address, True, False), (w.user.address, True, False)], bytes([9]))
    message = compile_message(w.sponsor.address, [transfer_checked(w.source, USDC_DEV, w.dest, w.user.address), close])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "token_instruction_not_allowed"


def test_set_authority_is_refused(w):
    set_auth = (TOKEN, [(w.source, False, True), (w.user.address, True, False)], bytes([6, 2, 1]) + unb58(rand_addr()))
    message = compile_message(w.sponsor.address, [transfer_checked(w.source, USDC_DEV, w.dest, w.user.address), set_auth])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "token_instruction_not_allowed"


def test_second_sponsored_ata_for_the_same_identity_is_refused(w):
    assert w.sign(w.honest()).status_code == 200
    w.dest, w.recipient = rand_addr(), rand_addr()
    res = w.sign(w.honest())
    assert res.status_code == 403
    assert reason(res) == "ata_already_sponsored"
    assert len(w.net["sent"]) == 1


def test_idempotent_create_of_an_existing_account_costs_nothing_and_passes(w):
    assert w.sign(w.honest()).status_code == 200
    w.net["existing"].add(w.dest)
    res = w.sign(w.honest())
    assert res.status_code == 200, res.text
    assert w.server.store.count("events", "kind = ?", ("sponsor_ata",)) == 1


def test_ata_paid_by_sponsor_with_no_transfer_into_it_is_refused(w):
    other = rand_addr()
    message = compile_message(w.sponsor.address, [
        create_ata(w.sponsor.address, other, rand_addr()),
        transfer_checked(w.source, USDC_DEV, w.dest, w.user.address),
    ])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "ata_without_transfer"


def test_daily_quota_per_identity(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, quota=2)
    for _ in range(2):
        assert w.sign(w.honest(with_ata=False)).status_code == 200
    res = w.sign(w.honest(with_ata=False))
    assert res.status_code == 429
    assert reason(res) == "sponsor_quota"
    assert len(w.net["sent"]) == 2


def test_mainnet_is_closed_until_the_owner_says_yes(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, cluster="mainnet")
    res = w.client.get("/api/solana/sponsor")
    assert res.status_code == 503
    assert "not approved" in reason(res)
    assert w.sign(w.honest()).status_code == 503
    assert not w.net["sent"]


def test_unconfigured_sponsor_is_a_closed_door(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("SOLANA_SPONSOR_KEY", "")
    assert w.client.get("/api/solana/sponsor").status_code == 503


def test_durable_nonce_is_refused(w):
    message = compile_message(w.sponsor.address, [
        advance_nonce(rand_addr(), w.user.address),
        transfer_checked(w.source, USDC_DEV, w.dest, w.user.address),
    ])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "durable_nonce"


def test_forged_identity_signature_is_refused(w):
    res = w.sign(w.honest(), identity_key=Key())
    assert res.status_code == 403
    assert reason(res) == "bad_identity_sig"


def test_transfer_signed_by_someone_else_than_identity_is_refused(w):
    res = w.sign(w.honest(), signer=Key())
    assert res.status_code == 403
    assert reason(res) == "identity_did_not_sign"


def test_priority_fee_above_the_ceiling_is_refused(w):
    price = (COMPUTE_BUDGET, [], bytes([3]) + (10_000_000).to_bytes(8, "little"))
    message = compile_message(w.sponsor.address, [price, transfer_checked(w.source, USDC_DEV, w.dest, w.user.address)])
    res = w.sign(message)
    assert res.status_code == 403
    assert reason(res) == "compute_price"


def test_stale_blockhash_is_refused(w):
    w.net["fresh"] = False
    res = w.sign(w.honest())
    assert res.status_code == 403
    assert reason(res) == "stale_blockhash"


def test_fee_payer_other_than_sponsor_is_refused(w):
    message = compile_message(w.user.address, [transfer_checked(w.source, USDC_DEV, w.dest, w.user.address)])
    raw = bytes([1]) + w.user.sign(message) + message
    id_sig = w.user.sign(b"phantom-sponsor:v1:" + hashlib.sha256(message).digest())
    res = w.client.post("/api/solana/sponsor/sign", json={
        "tx_base64": base64.b64encode(raw).decode(), "identity": w.user.address, "identity_sig": b58(id_sig)})
    assert res.status_code == 403
    assert reason(res) == "fee_payer_not_sponsor"


def test_versioned_message_is_parsed_but_lookup_tables_are_not(w):
    import sponsor

    v0 = compile_message(w.sponsor.address, [transfer_checked(w.source, USDC_DEV, w.dest, w.user.address)], version0=True)
    assert w.sign(v0).status_code == 200
    with_lookup = v0[:-1] + bytes([1]) + unb58(rand_addr()) + bytes([1, 0, 0])
    with pytest.raises(sponsor.BadTx, match="lookup_tables"):
        sponsor.parse_tx(bytes([2]) + bytes(128) + with_lookup)
