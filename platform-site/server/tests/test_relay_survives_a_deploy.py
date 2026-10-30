"""Сховок мусить пережити викладення — і сказати, коли не пережив.

Дві обіцянки, які до 07.09 трималися на слові, а не механічно:

1. `RELAY_DB` лежав у `/tmp`, тобто в шарі образу. Кожен `fly deploy`
   витирав усі ще не забрані конверти. Телефон-відправник цього не бачив і
   не торкався листа до `heldUntilMs` — до СЕМИ ДІБ тиші на лист, який
   людина бачить як «У СХОВКУ».

   Окремий том без знімків Fly не дозволяє (один том на машину), а єдиний
   том знімається — на ньому ключ підпису. Тож конверти живуть на диску
   машини, і викладення їх стирає; обіцянку тримає пункт 2.

2. Навіть на томі база може зникнути (перестворення тому, зміна регіону,
   ручне витирання). Тоді сервер мусить уміти сказати «мій сховок почався о
   T» — і телефон, чий лист покладено до T, поверне його в чергу негайно,
   а не за тиждень. Це `store_since_ms` у відповіді на забір.

Обидва твердження перевіряються тут, а не читаються оком: перше — з
Dockerfile, fly.toml і скрипта викладення, друге — прогоном сервера.
"""
from __future__ import annotations

import base64
import os
import re
import sys
import time
import tomllib
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
SITE_DIR = SERVER_DIR.parent
sys.path.insert(0, str(SERVER_DIR))

DOCKERFILE = SERVER_DIR / "Dockerfile"
FLY_TOML = SERVER_DIR / "fly.toml"
DEPLOY = SITE_DIR / "scripts" / "deploy-server.sh"

TAG_A = "A" * 43


def envelope(size=4124, fill=b"\x01"):
    return base64.b64encode(fill * size).decode()


def tags64(*real):
    out = list(real)
    while len(out) < 64:
        out.append(base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="))
    return out


def _docker_env(name: str) -> str:
    """Значення ENV з Dockerfile, з урахуванням перенесення рядка `\\`."""
    text = DOCKERFILE.read_text(encoding="utf-8")
    text = re.sub(r"\\\s*\n\s*", " ", text)
    found = re.findall(rf"^\s*ENV\b.*?\b{name}=(\S+)", text, flags=re.MULTILINE)
    assert found, f"{name} не оголошено в Dockerfile"
    return found[-1]


def _mounts() -> list[dict]:
    fly = tomllib.loads(FLY_TOML.read_text(encoding="utf-8"))
    mounts = fly.get("mounts", [])
    return mounts if isinstance(mounts, list) else [mounts]


def _mount_for(path: str) -> dict | None:
    """Точка монтування, всередині якої лежить [path]. Найдовша перемагає."""
    best = None
    for mount in _mounts():
        dest = str(mount.get("destination", "")).rstrip("/")
        if not dest:
            continue
        if path == dest or path.startswith(dest + "/"):
            if best is None or len(dest) > len(str(best["destination"]).rstrip("/")):
                best = mount
    return best


def test_fly_дає_один_том_на_машину():
    """«invalid config.mounts, only 1 volume supported» — так впало викладення
    02.10. Два [[mounts]] у fly.toml — це конфіг, який Fly не прийме."""
    assert len(_mounts()) == 1, f"у fly.toml {len(_mounts())} томи, Fly приймає один"


def test_конверти_не_лежать_на_томі_зі_знімками():
    """Єдиний том знімається (на ньому єдиний ключ підпису), а знімок
    конверта живе довше за обіцяні сім днів. Тож сховок — поза томом."""
    for name in ("RELAY_DB", "TRUTH_DB"):
        path = _docker_env(name)
        assert _mount_for(path) is None, (
            f"{name}={path} лежить на томі «{_mount_for(path)['source']}», який "
            "знімається у знімки: конверт пережив би свої сім днів"
        )


def test_том_ключа_підпису_лишається_зі_знімками():
    """Ключ підпису існує лише на цьому томі. Вимкнені знімки = одна поломка
    диска до недійсності всіх виданих сертифікатів."""
    mount = _mount_for(_docker_env("LICENSE_SIGNING_KEY"))
    assert mount is not None, "ключ підпису мусить лежати на томі"
    assert mount.get("scheduled_snapshots") is not False


# ---------------------------------------------------------------------------
# Друга половина: сервер каже, з якої миті існує його сховок.
# ---------------------------------------------------------------------------


@pytest.fixture()
def relay_env(tmp_path_factory, monkeypatch):
    licence_dir = tmp_path_factory.mktemp("licence")
    relay_dir = tmp_path_factory.mktemp("relay")
    monkeypatch.setenv("LICENSE_DB", str(licence_dir / "test.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(licence_dir / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(licence_dir / "policy.json"))
    monkeypatch.setenv("RELAY_DB", str(relay_dir / "relay.db"))
    for mod in ("main", "db", "relay", "license_crypto", "providers", "mailer"):
        sys.modules.pop(mod, None)
    import main as srv

    yield srv
    srv._buckets.clear()
    srv._miss.clear()
    srv._mail.clear()
    srv._relay_buckets.clear()


@pytest.fixture()
def client(relay_env):
    from fastapi.testclient import TestClient

    return TestClient(relay_env.app)


def test_забір_каже_з_якої_миті_існує_цей_сховок(client):
    """Без цього числа «твого листа тут немає» не відрізнити від «сховок
    щойно народився заново» — а це різниця між тижнем тиші й хвилиною."""
    before = time.time() * 1000
    got = client.post("/relay/fetch", json={"tags": tags64(TAG_A)})
    assert got.status_code == 200
    since = got.json().get("store_since_ms")
    assert isinstance(since, int), (
        "відповідь на забір не називає, з якої миті існує сховок — телефон "
        "не має як дізнатись, що його лист стерло викладення"
    )
    assert 0 < since <= before + 5_000


def test_вкидання_каже_час_сервера_а_не_телефона(client):
    """`held_until` рядком уже був; телефон його не читав і рахував строк за
    СВОЇМ годинником. Порівнювати той строк із часом сервера не можна."""
    got = client.post("/relay/drop", json={"tag": TAG_A, "blob": envelope()})
    assert got.status_code == 200
    held = got.json().get("held_until_ms")
    assert isinstance(held, int), "розписка не несе строку числом"
    now = time.time() * 1000
    assert now < held <= now + 8 * 86_400_000


def test_мить_народження_переживає_перезапуск_процесу(relay_env, tmp_path):
    """Число мусить лежати В БАЗІ. Час старту процесу зробив би з кожного
    перезапуску «сховок стерто» — і всі листи пішли б по другому колу."""
    import relay as relay_mod

    path = tmp_path / "same.db"
    first = relay_mod.RelayStore(path)
    reopened = relay_mod.RelayStore(path)
    assert reopened.since_ms == first.since_ms, (
        "перевідкриття того самого файла дало іншу мить народження — "
        "кожен перезапуск сервера виглядав би для телефонів як витирання"
    )

    fresh = relay_mod.RelayStore(tmp_path / "other.db")
    assert fresh.since_ms >= first.since_ms
