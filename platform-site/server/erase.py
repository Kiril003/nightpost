"""Видалення даних за ключем або сесією (DESIGN.md §7).

Два ключі й два шляхи:
  * Клейм-ключ (`/api/erase/claim`) — миттєво стирає оцінки закладів і право на
    нагороди Seeker; без очікування, бо ці записи не тримають ні акаунта, ні
    ліцензій.
  * Кореневий ключ (`/api/erase`) або свіжа сесія кабінету (`/api/me/erase`) —
    ставить запис у чергу `pending_erasures` на 7 діб (`GRACE_SECONDS`) і
    вимикає акаунт. Після `due_at` фоновий прибиральник (`sweep`) остаточно
    стирає рядки у всіх базах за переписом `ERASE_MAP`, лишаючи лише знеособлений
    рядок покупки в `licenses` (`email = ''`, `account_id = NULL`).
"""
from __future__ import annotations

import logging
import time
from datetime import datetime, timezone

import accounts as acc
import truth_store

log = logging.getLogger("phantom.erase")

CLAIM_PREFIX = "NP-ERASE-CLAIM-v1"
ROOT_PREFIX = "NP-ERASE-v1"
CANCEL_PREFIX = "NP-ERASE-CANCEL-v1"

GRACE_DAYS = 7
GRACE_SECONDS = GRACE_DAYS * 86400
FRESH_SECONDS = 300
FRESH_VIA = frozenset({"qr_erase", "email"})
CANCEL_VIA = "erase_cancel"
CANCEL_FRESH_VIA = frozenset({"qr_erase", "email", CANCEL_VIA})

#: Строки зберігання неактивних ключів та IP-адрес у журналі подій.
RETENTION = {"inactive_key_s": 365*86400, "event_ip_s": 30*86400}

#: Перепис усіх таблиць, яких торкається видалення (`execute` та `/api/erase/claim`).
ERASE_MAP: dict[tuple[str, str], str] = {
    ("licence", "accounts"): "delete account row by id / pub_key",
    ("licence", "sessions"): "delete sessions of account",
    ("licence", "threads"): "delete support threads of account",
    ("licence", "messages"): "delete messages in account threads",
    ("licence", "qr_challenges"): "delete qr challenges by account_id or pub_key",
    ("licence", "directory_handles"): "delete directory handle by pub_key",
    ("licence", "brain_devices"): "delete brain device by pub_key",
    ("licence", "activations"): "delete activations of account licenses",
    ("licence", "licenses"): "anonymize email='' and account_id=NULL on account licenses",
    ("licence", "events"): "blank ip and detail where detail is exactly the email or pub_key[:16]",
    ("licence", "login_codes"): "delete login codes for account email",
    ("licence", "recoveries"): "delete recovery codes for account email",
    ("support", "support_reports"): "delete reports filed by root key; a report behind a live sanction loses its reporter key instead",
    ("support", "support_tickets"): "delete tickets (and screenshots) of root key",
    ("support", "support_crashes"): "delete crash notes of root key",
    ("support", "support_quota"): "delete daily quota marks of root key",
    ("venues", "venues"): "delete venues owned by account",
    ("venues", "venue_ratings"): "delete ratings by rater claim key or on owned venues",
    ("venues", "venue_claims"): "delete venue claims filed by account or on owned venues",
    ("venues", "venue_visits"): "delete visit marks by claim key (per-venue hash) or on owned venues",
    ("venues", "event_rsvps"): "delete RSVPs by claim key (per-event hash) or on owned venues",
    ("venues", "event_state"): "delete event state on owned venues",
    ("venues", "venue_versions"): "delete published page versions of owned venues",
    ("venues", "venue_media"): "delete image rows of owned venues and their bytes (local file or R2 object)",
    ("venues", "venue_follows"): "delete follows of owned venues and follows made by the account",
    ("venues", "venue_sched_drops"): "delete dropped-schedule notices of owned venues",
    ("venues", "venue_requests"): "delete booking requests and messages sent to owned venues",
    ("venues", "venue_order_lines"): "delete stock and seat counts of owned venues",
    ("venues", "venue_devices"): "delete phone links that read the account's page requests",
    ("venues", "venue_order_lines"): "delete order lines (stock taken) of owned venues",
    ("truth", "truth_voters"): "delete voter row by voter_id(root_key)",
    ("truth", "truth_obs"): "delete observations by voter_id(root_key)",
    ("truth", "truth_impact"): "delete impact rows by voter_id(root_key)",
    ("points", "eligible"): "delete Seeker eligibility by claim key",
}

#: Таблиці, які свідомо не стираються при видаленні особи (разом із ERASE_MAP
#: покривають усі таблиці сервера).
KEPT: dict[tuple[str, str], str] = {
    ("licence", "waitlist"): "pre-launch waitlist, no key link",
    ("licence", "pending_erasures"): "erasure queue row deleted on completion",
    ("points", "events"): "append-only points ledger",
    ("support", "support_sanctions"): "staff decisions on public targets, no reporter key",
    ("points", "wallets"): "anti-sybil wallet link",
    ("points", "spent_tx"): "anti-replay spent transactions",
    ("truth", "truth_meta"): "daily rotating HMAC salt",
    ("truth", "truth_facts"): "keyless cell aggregate",
    ("truth", "truth_views"): "short-lived view tokens",
    ("truth", "truth_moderation"): "keyless moderation notes (7d TTL)",
    ("relay", "relay_blobs"): "anonymous ciphertext (7d TTL)",
    ("relay", "relay_meta"): "relay store creation timestamp",
    ("support", "support_bytes"): "keyless daily screenshot byte total",
}


def claim_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"{CLAIM_PREFIX}|{pub_key}|{nonce}|{issued_ms}".encode("utf-8")


def root_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"{ROOT_PREFIX}|{pub_key}|{nonce}|{issued_ms}".encode("utf-8")


def cancel_message(pub_key: str, nonce: str, issued_ms: int) -> bytes:
    return f"{CANCEL_PREFIX}|{pub_key}|{nonce}|{issued_ms}".encode("utf-8")


def refuse_reason(account, pub_hex: str = "") -> str | None:
    clean_hex = (pub_hex or "").strip().lower()
    if clean_hex and acc.role_for_pubkey(clean_hex) == acc.OWNER:
        return "protected"
    if account is not None:
        keys = account.keys() if hasattr(account, "keys") else ()
        role = (account["role"] or acc.USER) if "role" in keys else acc.USER
        if acc.is_staff(role):
            return "protected"
        email = ((account["email"] or "") if "email" in keys else "").strip().lower()
        if email and acc.role_for(email) == acc.OWNER:
            return "protected"
        acc_pub = ((account["pub_key"] or "") if "pub_key" in keys else "").strip().lower()
        if acc_pub and acc.role_for_pubkey(acc_pub) == acc.OWNER:
            return "protected"
    return None


def _touch(counts: dict[tuple[str, str], int], db_name: str, table: str, n: int) -> None:
    key = (db_name, table)
    if key not in ERASE_MAP:
        raise RuntimeError(f"touched table {key!r} is outside ERASE_MAP")
    counts[key] = counts.get(key, 0) + int(n)


def _erase_key(counts, clean: str, *, store, truth_db) -> None:
    _touch(counts, "licence", "qr_challenges", store.erase_qr_challenges(pub_key=clean))
    _touch(counts, "licence", "directory_handles", store.erase_directory_handle(clean))
    _touch(counts, "licence", "brain_devices", store.erase_brain_device(clean))
    _touch(counts, "licence", "events", store.erase_event_details(clean[:16]))
    if truth_db is not None:
        t_counts = truth_db.erase_voter(truth_store.voter_id(bytes.fromhex(clean)))
        _touch(counts, "truth", "truth_obs", t_counts["truth_obs"])
        _touch(counts, "truth", "truth_impact", t_counts["truth_impact"])
        _touch(counts, "truth", "truth_voters", t_counts["truth_voters"])
    import support_desk

    for table, n in support_desk.erase_subject(clean).items():
        _touch(counts, "support", table, n)


def _erase_account(counts, account, *, store, venues_db) -> None:
    acc_id = account["id"]
    acc_email = account["email"] or ""
    _touch(counts, "licence", "messages", store.erase_messages_of_account(acc_id))
    _touch(counts, "licence", "threads", store.erase_threads_of_account(acc_id))
    _touch(counts, "licence", "sessions", store.erase_sessions_of_account(acc_id))
    _touch(counts, "licence", "qr_challenges", store.erase_qr_challenges(account_id=acc_id))
    _touch(counts, "licence", "activations", store.erase_activations_of_account(acc_id, acc_email))
    _touch(counts, "licence", "licenses", store.erase_anonymize_licenses(acc_id, acc_email))
    _touch(counts, "licence", "login_codes", store.erase_login_codes(acc_email))
    _touch(counts, "licence", "recoveries", store.erase_recoveries(acc_email))
    _touch(counts, "licence", "events", store.erase_event_details(acc_email))
    if venues_db is not None:
        for table, n in venues_db.erase_owner(acc_id).items():
            _touch(counts, "venues", table, n)
        import venue_claims

        _touch(counts, "venues", "venue_claims", venue_claims.erase_owner(acc_id))
    _touch(counts, "licence", "accounts", store.erase_account_row(acc_id))


def _pub_of(account) -> str:
    keys = account.keys() if hasattr(account, "keys") else ()
    pub = ((account["pub_key"] or "") if "pub_key" in keys else "").strip().lower()
    return pub if len(pub) == 64 else ""


def execute(
    subject: str,
    kind: str,
    *,
    store,
    venues_db=None,
    truth_db=None,
    now_s: int | None = None,
) -> dict[tuple[str, str], int]:
    counts: dict[tuple[str, str], int] = {}
    if kind == "root":
        clean = (subject or "").strip().lower()
        if len(clean) != 64:
            raise ValueError("root subject must be 64 hex chars")
        bytes.fromhex(clean)
        account = store.account_by_pubkey(clean)
        reason = refuse_reason(account, clean)
        if reason:
            raise ValueError(reason)
        _erase_key(counts, clean, store=store, truth_db=truth_db)
        if account is not None:
            _erase_account(counts, account, store=store, venues_db=venues_db)
        return counts

    if kind == "account":
        clean_id = (subject or "").strip()
        if not clean_id:
            raise ValueError("account subject is empty")
        account = store.account_by_id(clean_id)
        if account is not None:
            pub = _pub_of(account)
            reason = refuse_reason(account, pub)
            if reason:
                raise ValueError(reason)
            if pub:
                _erase_key(counts, pub, store=store, truth_db=truth_db)
            _erase_account(counts, account, store=store, venues_db=venues_db)
        return counts

    raise ValueError(f"unknown erase kind: {kind!r}")


def sweep(
    now_s: int | None = None,
    *,
    store,
    venues_db=None,
    truth_db=None,
) -> dict[str, int]:
    now_val = int(time.time()) if now_s is None else int(now_s)
    due_rows = store.erase_due(now_val)
    executed = 0
    failed = 0
    for row in due_rows:
        subject = row["subject"]
        kind = row["kind"]
        try:
            execute(
                subject,
                kind,
                store=store,
                venues_db=venues_db,
                truth_db=truth_db,
                now_s=now_val,
            )
            store.erase_done(subject)
            executed += 1
        except Exception:
            log.exception("erase sweep failed for pending row")
            failed += 1
    return {"executed": executed, "failed": failed}


def purge_inactive(
    now_s: int | None = None,
    *,
    store,
    venues_db=None,
    truth_db=None,
) -> dict[str, int]:
    now_val = int(time.time()) if now_s is None else int(now_s)
    cutoff_s = now_val - RETENTION["inactive_key_s"]
    cutoff_iso = datetime.fromtimestamp(cutoff_s, tz=timezone.utc).isoformat()
    cutoff_ms = cutoff_s * 1000

    accounts_purged = 0
    failed = 0
    for row in store.inactive_key_accounts(cutoff_iso):
        pub_hex = (row["pub_key"] or "").strip().lower()
        if not pub_hex or refuse_reason(row, pub_hex):
            continue
        try:
            execute(pub_hex, "root", store=store, venues_db=venues_db, truth_db=truth_db, now_s=now_val)
            store.erase_done(pub_hex)
            accounts_purged += 1
        except Exception:
            log.exception("retention purge failed for an account")
            failed += 1

    brain_purged = 0
    for dev in store.inactive_orphan_brain_devices(cutoff_iso):
        pub_hex = (dev["pub_key"] or "").strip().lower()
        if not pub_hex or refuse_reason(None, pub_hex):
            continue
        brain_purged += store.erase_brain_device(pub_hex)

    voters_purged = 0
    if truth_db is not None:
        owner_vid = None
        if acc.OWNER_PUBKEY and len(acc.OWNER_PUBKEY) == 64:
            try:
                owner_vid = truth_store.voter_id(bytes.fromhex(acc.OWNER_PUBKEY))
            except ValueError:
                owner_vid = None
        for vid in truth_db.inactive_voters(cutoff_ms):
            if owner_vid and vid == owner_vid:
                continue
            truth_db.erase_voter(vid)
            voters_purged += 1

    return {
        "accounts": accounts_purged,
        "brain_devices": brain_purged,
        "truth_voters": voters_purged,
        "failed": failed,
    }


def expire_ips(now_s: int | None = None, store=None) -> int:
    if store is None:
        raise ValueError("store is required")
    now_val = int(time.time()) if now_s is None else int(now_s)
    cutoff = datetime.fromtimestamp(
        now_val - RETENTION["event_ip_s"], tz=timezone.utc
    ).isoformat()
    return store.events_expire_ips(cutoff)
