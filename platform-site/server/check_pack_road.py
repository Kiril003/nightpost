"""Пройти дорогу пакета так, як її проходить телефон, і сказати, де вона рветься.

    python check_pack_road.py http://127.0.0.1:8091 --key <64 hex>
    python check_pack_road.py https://packs.example --key <64 hex> --pack road-mesh-md

Це не «чи відповідає сервер». Це той самий порядок кроків, що й у
`core-net/.../PackFetcher.kt` та `WorldPackCatalogClient.kt`, з тими самими
причинами відмови:

    каталог → ПІДПИС → манiфест → ПІДПИС → id збігається з адресою
            → Range → обрив → докачка → sha256 над ЦІЛИМ файлом

Навіщо власний прохід, коли є телефон. Телефон каже «пакет не поїхав» одним
рядком на екрані, і на 5 ГБ через мобільну мережу ця відповідь коштує години.
Тут кожен крок названий окремо, тож видно САМЕ ТОЙ, що впав.

Чому обрив тут навмисний. Докачку в клієнті написано, але доведено її лише на
nginx, а роздавати збираємось із R2 — і різниця між ними видна рівно в одному
місці: чи віддасть хост 206 і `Content-Range` на другий запит. Хост, який
Range ІГНОРУЄ, відповідає 200 і цілим тілом; `PackFetcher` це переживе
(перепише з нуля), але докачки в нього не буде — обрив на 4-му гігабайті
Німеччини почне все спочатку. Мовчазна втрата, яку без цієї проби не видно.

Ключ звірки — той самий `phantom.packKey`, що йде у збірку телефона. Без
нього перевіряти нічого: `--key` обов'язковий саме тому, що «пропустив ключ»
і «підпис зійшовся» не мають виглядати однаково.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from license_crypto import verify_certificate  # noqa: E402  — після sys.path

CATALOG = "world-catalog.json"
MANIFEST = "manifest.json"
CHUNK = 1 << 20
TIMEOUT = 30

OK, BAD = "  ✓", "  ✗"


class Broken(Exception):
    """Дорога рветься тут. Текст — те, що варто показати людині."""


def get(url: str, headers: dict[str, str] | None = None):
    request = urllib.request.Request(url, headers=headers or {})
    try:
        return urllib.request.urlopen(request, timeout=TIMEOUT)
    except urllib.error.HTTPError as e:
        raise Broken(f"HTTP {e.code} на {url}") from e
    except Exception as e:  # мережа, DNS, TLS
        raise Broken(f"{type(e).__name__}: {e} — {url}") from e


def signed_json(url: str, key: str, what: str) -> dict:
    """Забрати документ і ЗВІРИТИ ПІДПИС — у тому ж порядку, що й телефон:
    жоден байт документа не вважається правдою, поки підпис не зійшовся."""
    with get(url) as response:
        raw = response.read().decode("utf-8")
    try:
        document = json.loads(raw)
    except ValueError as e:
        raise Broken(f"{what}: не читається як JSON ({e})") from e
    if not isinstance(document, dict):
        raise Broken(f"{what}: не JSON-об'єкт")
    if "sig" not in document:
        raise Broken(
            f"{what}: БЕЗ ПІДПИСУ. Телефон відхилить — «дзеркало могло підмінити дороги». "
            "Дерево не проходило sign_world_packs.py."
        )
    if not verify_certificate(document, key):
        raise Broken(
            f"{what}: підпис Є, але НЕ СХОДИТЬСЯ з цим ключем. "
            "Або дерево підписане іншою парою, або в збірці стоїть не той phantom.packKey."
        )
    return document


def probe_range(url: str, total: int) -> str:
    """Чи вміє хост віддати шматок. Один байт трафіку, а не гігабайт."""
    with get(url, {"Range": "bytes=0-0"}) as response:
        code = response.status
        content_range = response.headers.get("Content-Range")
        response.read(1)
    if code != 206 or not content_range:
        raise Broken(
            f"хост НЕ ПІДТРИМУЄ Range (віддав HTTP {code}, Content-Range: {content_range!r}). "
            "Пакет доїде лише з однієї спроби: будь-який обрив почне качати з нуля."
        )
    declared = content_range.rsplit("/", 1)[-1]
    if declared.isdigit() and int(declared) != total:
        raise Broken(
            f"манiфест обіцяє {total} байт, хост віддає {declared} — це різні файли"
        )
    return content_range


def download_with_break(url: str, target: Path, total: int, at: float) -> str:
    """Качаємо, рвемо на [at] від файла, докачуємо Range — і рахуємо sha256 над
    ЦІЛИМ файлом, як `PackFetcher.receive`: наявний хвіст входить у хеш, інакше
    звірялося б лише докачане, і підміна першої половини пройшла б непоміченою.
    """
    cut = max(1, int(total * at))
    digest = hashlib.sha256()
    written = 0

    with target.open("wb") as out:
        with get(url) as response:
            while written < cut:
                block = response.read(min(CHUNK, cut - written))
                if not block:
                    break
                out.write(block)
                digest.update(block)
                written += len(block)
    print(f"{OK} обрив зроблено навмисно на {written} з {total} байт")

    if written >= total:
        raise Broken("файл доїхав до обриву — проба докачки нічого не довела")

    with get(url, {"Range": f"bytes={written}-"}) as response:
        if response.status != 206:
            raise Broken(
                f"докачка: хост відповів HTTP {response.status} замість 206 — "
                "Range проігноровано, докачки немає"
            )
        with target.open("ab") as out:
            while True:
                block = response.read(CHUNK)
                if not block:
                    break
                out.write(block)
                digest.update(block)
                written += len(block)

    if written != total:
        raise Broken(f"доїхало {written} байт замість {total}")
    return digest.hexdigest()


def walk(base: str, key: str, wanted: str | None, at: float, work: Path, probe_only: bool) -> int:
    base = base.rstrip("/")

    print(f"\nбаза: {base}")
    print(f"ключ: {key[:16]}… ({len(key)} символів)")
    if len(key) != 64:
        print(f"{BAD} ключ мусить бути 64 шістнадцяткові символи — це {len(key)}")
        return 1

    print("\n1. каталог")
    catalog = signed_json(f"{base}/{CATALOG}", key, "каталог")
    packs = catalog.get("packs", [])
    print(f"{OK} підпис сходиться, {len(packs)} пакетів")

    usable = [p for p in packs if p.get("id") and p.get("bytes", 0) > 0]
    if not usable:
        raise Broken("каталог підписаний, але в ньому немає жодного придатного рядка")

    if wanted:
        row = next((p for p in usable if p["id"] == wanted), None)
        if row is None:
            raise Broken(f"у каталозі немає «{wanted}»; є: {', '.join(p['id'] for p in usable)}")
    else:
        # Найдешевший — щоб проба дороги не коштувала 5 ГБ без потреби.
        row = min(usable, key=lambda p: p["bytes"])
    pack_id = row["id"]
    print(f"{OK} беремо «{pack_id}» — {row['bytes'] / 1_000_000:.0f} МБ, {row.get('labelUa', '?')}")

    print("\n2. манiфест")
    manifest = signed_json(f"{base}/{pack_id}/{MANIFEST}", key, f"манiфест {pack_id}")
    print(f"{OK} підпис сходиться")

    if manifest.get("id") != pack_id:
        raise Broken(
            f"за адресою «{pack_id}» лежить пак «{manifest.get('id')}» — збитий хост, а не пак"
        )
    total, want_hash = int(manifest.get("bytes", 0)), manifest.get("sha256", "")
    if total <= 0 or len(want_hash) != 64:
        raise Broken("манiфест без розміру або суми — рахувати доставлене нічим")
    if total != row["bytes"]:
        raise Broken(f"каталог обіцяє {row['bytes']} байт, манiфест — {total}")
    print(f"{OK} id збігається з адресою, {total} байт, sha256 {want_hash[:16]}…")

    print("\n3. байти")
    blob = f"{base}/{pack_id}/{pack_id}.bin"
    print(f"{OK} Range: {probe_range(blob, total)}")

    if probe_only:
        print("\n--probe-only: байтів не качали, sha256 не доведено.")
        return 0

    work.mkdir(parents=True, exist_ok=True)
    target = work / f"{pack_id}.bin"
    got = download_with_break(blob, target, total, at)

    print("\n4. сума")
    if got != want_hash:
        raise Broken(f"sha256 {got} замість {want_hash} — доїхало не те")
    print(f"{OK} sha256 сходиться з підписаним манiфестом")

    print(
        f"\nДОРОГА ЦІЛА: «{pack_id}», {total / 1_000_000:.0f} МБ, "
        f"з навмисним обривом і докачкою, підпис звірено ключем збірки."
    )
    print(f"файл: {target}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("base", help="база, під якою лежить <id>/manifest.json")
    parser.add_argument("--key", required=True, help="phantom.packKey — 64 hex, Ed25519 публічний")
    parser.add_argument("--pack", default=None, help="який пакет (типово найдешевший у каталозі)")
    parser.add_argument("--break-at", type=float, default=0.4, help="частка файла до обриву")
    parser.add_argument("--work", type=Path, default=None, help="куди качати")
    parser.add_argument("--probe-only", action="store_true", help="без качання байтів")
    args = parser.parse_args()

    work = args.work or Path(tempfile.gettempdir()) / "phantom-pack-road"
    try:
        return walk(args.base, args.key.strip().lower(), args.pack, args.break_at, work, args.probe_only)
    except Broken as e:
        print(f"\n{BAD} {e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
