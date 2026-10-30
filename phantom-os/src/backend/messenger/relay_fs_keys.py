"""Ключі конверта v5 на ПК — і коли їх СТИРАТИ (docs/crypto/RELAY_FS.md §2–§4, §10).

Дзеркало `core-net/.../peer/RelayFsKeys.kt` (ті самі строки, межі й правила),
плюс `RelayFsMail`: рішення «v5 чи чесний відкат на v3» для листа телефону.

Мій бік: підписаний SPK обертається щотижня, старий живе ще 14 діб і
стирається; OPK — окрема пачка на кожен телефон, приватний OPK стирається,
щойно лист із ним відчинився. Листи лише під SPK — пам'ять використаних EK.

Відчинений лист пам'ятається ЛИШЕ як «(spk, EK) → id листа»: копія, яку
сховок віддасть удруге (квитанція не лягла, спалення не пройшло), уже не
відчиниться — ключа немає, — але її треба впізнати, підтвердити й спалити.
Інакше автор назавжди лишився б без «доставлено», а копія лежала б до строку.

Стан — у `Slot`; на вузлі це файл, зашифрований ключем, виведеним із
X25519 вузла (як prekey-набір месенджера). Кожна зміна — на диск до
повернення: OPK, стертий лише в пам'яті, після перезапуску воскрес би.
"""
from __future__ import annotations

import base64
import json
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Protocol, Union

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from node import peer_letter as pl
from node import relay_fs as fs

__all__ = [
    "STATE_FILE",
    "FileSlot",
    "MemorySlot",
    "RelayFsKeys",
    "Sealed",
    "Fallback",
    "Opened",
    "Replay",
    "seal_letter",
    "open_letter",
]

DAY_MS = 24 * 3600 * 1000
SPK_ROTATE_MS = 7 * DAY_MS
SPK_RETENTION_MS = 14 * DAY_MS
SPK_MAX_AGE_MS = 10 * DAY_MS
OPK_BATCH = 16
OPK_LOW = 4
MAX_OPKS_PER_PEER = fs.MAX_OPKS_PER_PEER
#: Файл стану v5 поруч з ідентичністю вузла. У резервну копію НЕ йде
#: (tools/backup_service.py, RELAY_FS.md §11): копія, що пам'ятає вже стерті
#: OPK, зробила б стирання марним.
STATE_FILE = "messenger_relay_fs.bin"
#: Скільки відчинених листів пам'ятаємо для повторної квитанції. Сховок
#: тримає копію 7 діб, скринька — до 32 листів на контакт.
MAX_LANDED = 4096


class Slot(Protocol):
    def read(self) -> Optional[str]: ...
    def write(self, value: str) -> None: ...


class MemorySlot:
    """Для тестів і для кур'єра без теки даних."""

    def __init__(self, value: Optional[str] = None) -> None:
        self.value = value

    def read(self) -> Optional[str]:
        return self.value

    def write(self, value: str) -> None:
        self.value = value


_MAGIC = b"PHFK1"
_AT_REST_INFO = b"PHANTOM OS/relay-fs-at-rest-v1"


class FileSlot:
    """Файл під AES-GCM; ключ — HKDF від X25519 вузла, окремою міткою."""

    def __init__(self, path: Path, identity_dh_private: X25519PrivateKey) -> None:
        self._path = path
        self._key = HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_AT_REST_INFO).derive(
            identity_dh_private.private_bytes_raw()
        )

    def read(self) -> Optional[str]:
        if not self._path.exists():
            return None
        blob = self._path.read_bytes()
        if not blob.startswith(_MAGIC):
            return None
        nonce, body = blob[len(_MAGIC) : len(_MAGIC) + 12], blob[len(_MAGIC) + 12 :]
        try:
            return AESGCM(self._key).decrypt(nonce, body, _MAGIC).decode()
        except (InvalidTag, ValueError):
            return None

    def write(self, value: str) -> None:
        nonce = os.urandom(12)
        sealed = AESGCM(self._key).encrypt(nonce, value.encode(), _MAGIC)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(self._path.suffix + ".tmp")
        tmp.write_bytes(_MAGIC + nonce + sealed)
        tmp.chmod(0o600)
        os.replace(tmp, self._path)


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def _unb64(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4))


def _raw(priv: X25519PrivateKey) -> bytes:
    return priv.private_bytes_raw()


@dataclass(frozen=True)
class Choice:
    spk_id: int
    spk_pub: bytes
    opk_id: Optional[int]
    opk_pub: Optional[bytes]


class RelayFsKeys:
    """Стан цілком у слоті; методи — ті самі, що в RelayFsKeys.kt."""

    def __init__(
        self,
        slot: Slot,
        clock: Callable[[], int] = lambda: int(time.time() * 1000),
        keypair: Callable[[], X25519PrivateKey] = X25519PrivateKey.generate,
    ) -> None:
        self._slot = slot
        self._clock = clock
        self._keypair = keypair
        self._lock = threading.RLock()
        self._state = self._load()

    def _load(self) -> dict:
        empty = {"spks": [], "opks": [], "next_spk": 1, "next_opk": 1, "seen_ek": {}, "landed": {}, "theirs": {}, "sent_spk": {}}
        raw = self._slot.read()
        if not raw:
            return empty
        try:
            data = json.loads(raw)
        except ValueError:
            return empty
        return {**empty, **data} if isinstance(data, dict) else empty

    def _save(self) -> None:
        self._slot.write(json.dumps(self._state, separators=(",", ":"), sort_keys=True))

    # ── мій бік ─────────────────────────────────────────────────────────────

    def current_spk(self, sign: Callable[[bytes], bytes]) -> dict:
        """Поточний SPK; обертає й стирає за розкладом. `sign` — Ed25519 вузла."""
        now = self._clock()
        with self._lock:
            s = self._state
            live = [k for k in s["spks"] if not k["retired"] or now - k["retired"] < SPK_RETENTION_MS]
            current = next((k for k in live if not k["retired"]), None)
            changed = len(live) != len(s["spks"])
            if current is None or now - current["created"] >= SPK_ROTATE_MS:
                priv = self._keypair()
                pub = priv.public_key().public_bytes_raw()
                spk_id = s["next_spk"]
                for k in live:
                    if not k["retired"]:
                        k["retired"] = now
                current = {
                    "id": spk_id,
                    "priv": _b64(_raw(priv)),
                    "pub": _b64(pub),
                    "sig": _b64(sign(fs.signed_prekey_payload(spk_id, pub))),
                    "created": now,
                    "retired": 0,
                }
                live.append(current)
                s["next_spk"] = spk_id + 1
                changed = True
            s["spks"] = live
            if changed:
                self._save()
            return dict(current)

    def bundle_for(self, peer: str, sign: Callable[[bytes], bytes]) -> Optional[fs.Bundle]:
        """Пачка для `peer`, якщо потрібна: нового SPK він не бачив або OPK < OPK_LOW."""
        spk = self.current_spk(sign)
        now = self._clock()
        with self._lock:
            s = self._state
            mine = [o for o in s["opks"] if o["peer"] == peer]
            spk_new = s["sent_spk"].get(peer) != spk["id"]
            if not spk_new and len(mine) >= OPK_LOW:
                return None
            issued: list[dict] = []
            if len(mine) < OPK_LOW:
                for _ in range(OPK_BATCH):
                    priv = self._keypair()
                    issued.append({
                        "id": s["next_opk"],
                        "priv": _b64(_raw(priv)),
                        "pub": _b64(priv.public_key().public_bytes_raw()),
                        "peer": peer,
                        "issued": now,
                    })
                    s["next_opk"] += 1
            kept = (mine + issued)[-MAX_OPKS_PER_PEER:]
            s["opks"] = [o for o in s["opks"] if o["peer"] != peer] + kept
            s["sent_spk"][peer] = spk["id"]
            self._save()
        return fs.Bundle(
            spk_id=spk["id"],
            spk_pub=_unb64(spk["pub"]),
            spk_sig=_unb64(spk["sig"]),
            opks=tuple((o["id"], _unb64(o["pub"])) for o in issued),
            gen=spk["id"],
        )

    def spk_private(self, spk_id: int) -> Optional[X25519PrivateKey]:
        now = self._clock()
        with self._lock:
            for k in self._state["spks"]:
                if k["id"] == spk_id and (not k["retired"] or now - k["retired"] < SPK_RETENTION_MS):
                    return X25519PrivateKey.from_private_bytes(_unb64(k["priv"]))
        return None

    def opk_private(self, peer: str, opk_id: int) -> Optional[X25519PrivateKey]:
        """Лише OPK, виданий САМЕ цьому контакту."""
        with self._lock:
            for o in self._state["opks"]:
                if o["id"] == opk_id and o["peer"] == peer:
                    return X25519PrivateKey.from_private_bytes(_unb64(o["priv"]))
        return None

    @staticmethod
    def _ek_key(spk_id: int, ek: bytes) -> str:
        return f"{spk_id}:{ek.hex()}"

    def consume(self, peer: str, spk_id: int, ek: bytes, opk_id: Optional[int], letter_id: str) -> bool:
        """Лист відчинився — стерти, що мусить зникнути. False — повтор."""
        now = self._clock()
        key = self._ek_key(spk_id, ek)
        with self._lock:
            s = self._state
            s["landed"] = {k: v for k, v in s["landed"].items() if v["until"] > now}
            if opk_id is not None:
                before = len(s["opks"])
                s["opks"] = [o for o in s["opks"] if not (o["id"] == opk_id and o["peer"] == peer)]
                if len(s["opks"]) == before:
                    return False
            else:
                s["seen_ek"] = {k: v for k, v in s["seen_ek"].items() if v > now}
                if key in s["seen_ek"]:
                    return False
                s["seen_ek"][key] = now + SPK_ROTATE_MS + SPK_RETENTION_MS
            s["landed"][key] = {"peer": peer, "id": letter_id, "until": now + SPK_ROTATE_MS + SPK_RETENTION_MS}
            if len(s["landed"]) > MAX_LANDED:
                oldest = sorted(s["landed"].items(), key=lambda kv: kv[1]["until"])
                s["landed"] = dict(oldest[-MAX_LANDED:])
            self._save()
        return True

    def landed_id(self, peer: str, frame: str) -> Optional[str]:
        """Id листа, який ЦЯ копія вже принесла (повтор від сховка), або None."""
        parsed = fs.parse(frame)
        if parsed is None or parsed["from"] != peer:
            return None
        with self._lock:
            hit = self._state["landed"].get(self._ek_key(parsed["spk"], parsed["ek"]))
        if hit is None or hit["peer"] != peer or hit["until"] <= self._clock():
            return None
        return hit["id"]

    # ── їхній бік ───────────────────────────────────────────────────────────

    def absorb(self, peer: str, bundle: fs.Bundle) -> None:
        """Пачка телефона (підпис уже перевірено `relay_fs.decode_bundle`)."""
        now = self._clock()
        with self._lock:
            old = self._state["theirs"].get(peer) or {}
            # Лічильник SPK пішов назад або той самий id з іншим ключем — у
            # контакта новий стан (переустановка, відновлення без файла v5).
            # Старі OPK у нього стерто, а їхні id видаються наново: черга з
            # нуля, інакше листи йшли б на ключі, яких більше немає.
            if old and (bundle.spk_id < old["spk_id"] or (bundle.spk_id == old["spk_id"] and _b64(bundle.spk_pub) != old["spk_pub"])):
                old = {}
            queue: list[list] = list(old.get("opks") or [])
            have = {i for i, _ in queue}
            for i, p in bundle.opks:
                if i not in have:
                    queue.append([i, _b64(p)])
                    have.add(i)
            self._state["theirs"][peer] = {
                "spk_id": bundle.spk_id,
                "spk_pub": _b64(bundle.spk_pub),
                "received": now,
                "opks": queue[-MAX_OPKS_PER_PEER:],
                "gen": bundle.gen,
            }
            self._save()

    def next_for_send(self, peer: str) -> Optional[Choice]:
        """OPK, поки є (і він тут же викреслюється), далі лише SPK. None — немає або застаріло."""
        now = self._clock()
        with self._lock:
            t = self._state["theirs"].get(peer)
            if t is None or now - t["received"] > SPK_MAX_AGE_MS:
                return None
            opk = t["opks"][0] if t["opks"] else None
            if opk is not None:
                t["opks"] = t["opks"][1:]
                self._save()
            return Choice(t["spk_id"], _unb64(t["spk_pub"]), opk[0] if opk else None, _unb64(opk[1]) if opk else None)

    def opks_left_for(self, peer: str) -> int:
        with self._lock:
            t = self._state["theirs"].get(peer)
            return len(t["opks"]) if t else 0

    def opks_issued_to(self, peer: str) -> int:
        with self._lock:
            return sum(1 for o in self._state["opks"] if o["peer"] == peer)

    def knows(self, peer: str) -> bool:
        with self._lock:
            return peer in self._state["theirs"]


# ── лист: v5, коли можна, і чесний відкат, коли ні ─────────────────────────


@dataclass(frozen=True)
class Sealed:
    frame: str
    with_opk: bool


@dataclass(frozen=True)
class Fallback:
    why: str


@dataclass(frozen=True)
class Opened:
    letter: pl.PeerLetter
    with_opk: bool


@dataclass(frozen=True)
class Replay:
    """Ця копія вже приносила лист `letter_id`: підтвердити й спалити, не приймати вдруге."""

    letter_id: str


def seal_letter(
    letter: pl.PeerLetter,
    me: fs.Identity,
    my_dh_private: X25519PrivateKey,
    them: fs.Identity,
    keys: RelayFsKeys,
    *,
    enabled: bool,
    ephemeral: Optional[X25519PrivateKey] = None,
) -> Union[Sealed, Fallback]:
    """RelayFsMail.seal: прапорець вимкнено чи пачки немає — названий відкат."""
    if not enabled:
        return Fallback("v5 вимкнено")
    choice = keys.next_for_send(letter.to_id)
    if choice is None:
        return Fallback("телефон ще не прислав ключів або вони застаріли")
    opk = (choice.opk_id, choice.opk_pub) if choice.opk_id is not None and choice.opk_pub is not None else None
    try:
        frame = fs.seal_letter(
            letter.to_json().encode("utf-8"), letter.from_id, letter.to_id, me, my_dh_private, them,
            (choice.spk_id, choice.spk_pub), opk, ephemeral,
        )
    except ValueError:
        return Fallback("лист не запечатався")
    if len(frame.encode("utf-8")) > pl.MAX_FRAME_BYTES:
        return Fallback("лист завеликий")
    return Sealed(frame, opk is not None)


def open_letter(
    raw: str,
    me: fs.Identity,
    my_dh_private: X25519PrivateKey,
    sender_id: str,
    sender: fs.Identity,
    keys: RelayFsKeys,
) -> Union[Opened, Replay, None]:
    """RelayFsMail.open. Ключі стираються лише ПІСЛЯ тегу і збігу заяв; повтор — `Replay`."""
    parsed = fs.parse(raw)
    if parsed is None or parsed["from"] != sender_id:
        return None
    got = fs.open_letter(raw, me, my_dh_private, sender, keys.spk_private, lambda i: keys.opk_private(sender_id, i))
    if got is None:
        landed = keys.landed_id(sender_id, raw)
        return Replay(landed) if landed else None
    plain, opk_id, ek = got
    letter = pl.PeerLetter.from_json(plain.decode("utf-8", errors="replace"))
    if letter is None or letter.from_id != parsed["from"] or letter.to_id != parsed["to"] or not letter.id:
        return None
    if not keys.consume(sender_id, parsed["spk"], ek, opk_id, letter.id):
        landed = keys.landed_id(sender_id, raw)
        return Replay(landed) if landed else None
    return Opened(letter, opk_id is not None)
