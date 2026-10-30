"""Дорога від печі до сховища: спечене → дерево роздачі → R2.

    # 1. на оракулі, де лежать байти: зібрати дерево й порахувати суми
    python publish_world_packs.py stage --bake ~/road-mesh-bake

    # 2. там, де лежить ПРИВАТНИЙ ключ (ніколи не на печі):
    python sign_world_packs.py --key keys/packs.pem <дерево>

    # 3. звідки видно і байти, і .env з ключами R2:
    python publish_world_packs.py upload --serve <дерево>

Три кроки, а не один, бо три різні секрети живуть на трьох різних машинах:
байти — на печі, приватний ключ підпису — у власника, ключі R2 — у сервера.
Скрипт, який робив би все разом, вимагав би звезти їх в одне місце.

Чому дерево доводиться складати скриптом, а не рукою. Станом на 07.09 дерево
`serve/` на оракулі існує й має правильну форму, але його НЕ БУДУЄ ніщо: ні
`bake.sh`, ні `mesh_bake.py`, ні будь-що інше в дереві печі не згадує
`world-catalog.json`. Воно зроблене руками один раз. Отже перепікання країни
не оновлює ні розміру, ні суми, ні версії — і телефон, який уже має пакет,
нової дороги не побачить НІКОЛИ, бо `WorldPackGate.installedVersion` порівнює
саме версію. Тиха вада, яку видно лише на другому випуску.

ВЕРСІЯ — не лічильник запусків. Вона зростає рівно тоді, коли змінилась
sha256, і не зростає інакше: перезапуск цього скрипта над тими самими
байтами мусить бути порожньою дією, інакше кожен прогін гнав би 24 ГБ
чужого трафіку на телефони.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

CATALOG = "world-catalog.json"
MANIFEST = "manifest.json"

#: ISO-3166-1 alpha-2 → (частка імені спеченого файла, українська назва).
#:
#: Назви НЕ перекладені тут: це `name:uk` самого OSM, узятий із згенерованої
#: `core-geo/.../place/WorldTable.kt` телефона. Два списки країн, набрані
#: руками в двох деревах, розійшлися б мовчки й на одну літеру —
#: `tests/test_world_packs_table_matches_the_app.py` звіряє КОЖЕН рядок із
#: таблицею телефона, коли дерево телефона поруч.
#:
#: Слаг — єдине, чого нема більше ніде: піч називає файли за Geofabrik
#: (`czech-republic`, не `cz`), і ця відповідність жила досі лише всередині
#: руками зробленого дерева `serve/`.
COUNTRIES: dict[str, tuple[str, str]] = {
    "AT": ("austria", "Австрія"),
    "BE": ("belgium", "Бельгія"),
    "BG": ("bulgaria", "Болгарія"),
    "CZ": ("czech-republic", "Чехія"),
    "DE": ("germany", "Німеччина"),
    "EE": ("estonia", "Естонія"),
    "FI": ("finland", "Фінляндія"),
    "FR": ("france", "Франція"),
    "HR": ("croatia", "Хорватія"),
    "HU": ("hungary", "Угорщина"),
    "IT": ("italy", "Італія"),
    "LT": ("lithuania", "Литва"),
    "LV": ("latvia", "Латвія"),
    "MD": ("moldova", "Молдова"),
    "NL": ("netherlands", "Нідерланди"),
    "NO": ("norway", "Норвегія"),
    "PL": ("poland", "Польща"),
    "RO": ("romania", "Румунія"),
    "RS": ("serbia", "Сербія"),
    "SI": ("slovenia", "Словенія"),
    "SK": ("slovakia", "Словаччина"),
    "UA": ("ukraine", "Україна"),
}

#: `kind` → (префікс id, звідки брати спечене). Рядок `kind` мусить збігатися
#: з іменем константи `WorldPackKind` у Kotlin ЛІТЕРА В ЛІТЕРУ: enum
#: серіалізується власним іменем, і «Basemap» замість «BASEMAP» дало б
#: зіпсований каталог, а не невідомий рядок.
KINDS = {
    "ROAD_MESH": ("road-mesh", lambda bake, slug: bake / f"phantom_road_mesh.{slug}.db"),
    "BASEMAP": ("basemap", lambda bake, slug: bake / "basemap" / f"{slug}.mbtiles"),
}

CHUNK = 4 * 1024 * 1024


class Pack:
    """Один пакет: чим він є в каталозі й яким файлом лежить на печі."""

    def __init__(self, kind: str, iso2: str, source: Path):
        slug, label = COUNTRIES[iso2]
        self.kind = kind
        self.country = iso2
        self.label_ua = label
        self.slug = slug
        self.source = source
        self.id = f"{KINDS[kind][0]}-{iso2.lower()}"

    #: Ім'я файла в бакеті. НЕ довільне: телефон будує адресу сам —
    #: `WorldPackGate.fileName` віддає `"$packId.bin"`, і `PackFetcher` качає
    #: `<база>/<id>/<id>.bin`. Розширення джерела (.db / .mbtiles) на дорозі
    #: не виживає, і не мусить: що це за файл, вирішує `kind` у каталозі.
    @property
    def file_name(self) -> str:
        return f"{self.id}.bin"


def discover(bake: Path, only: set[str] | None) -> list[Pack]:
    """Що з таблиці справді спечене. Відсутнє джерело — не помилка: країни
    печуться хвилями, і половина таблиці на будь-який момент ще попереду."""
    found = []
    for kind, (_, locate) in KINDS.items():
        for iso2 in COUNTRIES:
            pack = Pack(kind, iso2, locate(bake, COUNTRIES[iso2][0]))
            if only and pack.id not in only:
                continue
            if pack.source.is_file():
                found.append(pack)
    return sorted(found, key=lambda p: p.id)


def sha256_of(path: Path, note: str = "") -> str:
    digest = hashlib.sha256()
    read = 0
    total = path.stat().st_size
    with path.open("rb") as handle:
        while True:
            block = handle.read(CHUNK)
            if not block:
                break
            digest.update(block)
            read += len(block)
            if note and total:
                print(f"\r  {note}: {read * 100 // total}%", end="", flush=True)
    if note:
        print(f"\r  {note}: {total / 1_000_000:.0f} МБ", flush=True)
    return digest.hexdigest()


def previous(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
        return loaded if isinstance(loaded, dict) else {}
    except (OSError, ValueError):
        return {}


def write_json(path: Path, document: dict) -> None:
    """Формат файла й підпис незалежні: підпис рахується над канонічними
    байтами (`license_crypto.canonical_payload`), тож відступи тут нікого не
    ламають — вони лише для людини, яка відкриє файл руками."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def baked_at(source: Path) -> str:
    stamp = datetime.fromtimestamp(source.stat().st_mtime, tz=timezone.utc)
    return stamp.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def stage(bake: Path, out: Path, only: set[str] | None, link: bool) -> int:
    packs = discover(bake, only)
    if not packs:
        print(f"у {bake} не знайдено жодного спеченого пакета з таблиці")
        return 65

    rows = []
    bumped, kept = 0, 0
    for pack in packs:
        pack_dir = out / pack.id
        manifest_path = pack_dir / MANIFEST
        old = previous(manifest_path)
        size = pack.source.stat().st_size

        # Суму рахуємо ЗАВЖДИ, навіть коли розмір не змінився: перепікання
        # країни з тим самим фільтром дає файл того ж розміру з іншим вмістом
        # частіше, ніж здається, і саме на цьому «оптимізація за розміром»
        # мовчки лишила б телефони зі старими дорогами.
        digest = sha256_of(pack.source, note=pack.id)

        if old.get("sha256") == digest:
            version = int(old.get("version", 1))
            kept += 1
        else:
            version = int(old.get("version", 0)) + 1
            bumped += 1

        write_json(
            manifest_path,
            {
                "id": pack.id,
                "version": version,
                "bytes": size,
                "sha256": digest,
                "minAppBuild": 0,
            },
        )

        target = pack_dir / pack.file_name
        if link:
            if target.is_symlink() or target.exists():
                target.unlink()
            target.symlink_to(pack.source.resolve())

        rows.append(
            {
                "id": pack.id,
                "kind": pack.kind,
                "country": pack.country,
                "labelUa": pack.label_ua,
                "bytes": size,
                "bakedAt": baked_at(pack.source),
                "minAppBuild": 0,
            }
        )

    write_json(out / CATALOG, {"packs": rows})

    total = sum(r["bytes"] for r in rows)
    print(f"\n{len(rows)} пакетів, {total / 1_000_000_000:.1f} ГБ разом → {out}")
    print(f"версію піднято: {bumped}, лишилось як було: {kept}")
    print(
        "\nПІДПИСУ ЩЕ НЕМА — телефон таке дерево відхилить цілком.\n"
        f"  python sign_world_packs.py --key keys/packs.pem {out}"
    )
    return 0


# ── R2 ──────────────────────────────────────────────────────────────────


def unsigned(paths: list[Path]) -> list[Path]:
    """Заслін, заради якого цей крок окремий: непідписане дерево в бакеті —
    це 24 ГБ, які телефон однаково не візьме, і рахунок за вихідний трафік
    за них. Перевіряємо наявність поля, а не підпис: чи сходиться він із
    ключем ЗБІРКИ, звідси не видно ніколи — це робота `check_pack_road.py`."""
    bad = []
    for path in paths:
        document = previous(path)
        if not isinstance(document.get("sig"), str) or not document["sig"]:
            bad.append(path)
    return bad


def upload(serve: Path, prefix: str, only: set[str] | None, force: bool, dry: bool) -> int:
    # Порядок навмисний: спершу все, для чого досить стандартної бібліотеки.
    # Заслін підпису — найдешевша й найважливіша відмова тут, і вона не сміє
    # залежати від того, чи стоїть boto3: машина без нього мусить УМІТИ
    # сказати «дерево не підписане», а не впасти на імпорті раніше перевірки.
    catalog_path = serve / CATALOG
    if not catalog_path.is_file():
        print(f"немає {catalog_path} — спершу stage")
        return 66
    rows = previous(catalog_path).get("packs", [])
    if only:
        rows = [r for r in rows if r.get("id") in only]
    if not rows:
        print("у каталозі немає жодного пакета для викладення")
        return 65

    manifests = [serve / r["id"] / MANIFEST for r in rows]
    if bad := unsigned([catalog_path, *manifests]):
        print("БЕЗ ПІДПИСУ — нічого не викладено:")
        for path in bad:
            print(f"  {path.relative_to(serve)}")
        print(f"\n  python sign_world_packs.py --key keys/packs.pem {serve}")
        return 1

    try:
        import boto3  # noqa: PLC0415 — потрібен лише цьому кроку
        from dotenv import load_dotenv  # noqa: PLC0415
    except ModuleNotFoundError as e:
        # У requirements.txt їх немає й станом на 07.09 не було: `upload_to_r2.py`
        # імпортує обидва так само й на свіжому клоні падає так само.
        print(f"для викладення бракує залежності: {e.name}\n  pip install boto3 python-dotenv")
        return 69

    import storage  # noqa: PLC0415

    load_dotenv(Path(__file__).resolve().parent / ".env")

    if not storage.configured():
        print("R2 не налаштовано: бракує R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / "
              "R2_SECRET_ACCESS_KEY / R2_BUCKET у server/.env")
        return 78

    bucket = os.environ["R2_BUCKET"]
    head = prefix.strip("/") + "/" if prefix.strip("/") else ""
    client = boto3.client(
        "s3",
        endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )

    def put(source: Path, key: str, content_type: str) -> None:
        size = source.stat().st_size
        if not force and remote_size(client, bucket, key) == size:
            print(f"  = {key} ({size / 1_000_000:.0f} МБ, уже там)")
            return
        print(f"  → {key} ({size / 1_000_000:.0f} МБ, {content_type})")
        if dry:
            return
        # `upload_file` сам ріже на частини понад 8 МБ, тож 5-гігабайтна
        # Німеччина не впирається в стелю одиничного PUT.
        client.upload_file(
            str(source), bucket, key, ExtraArgs={"ContentType": content_type}
        )

    # Порядок несе весь сенс. Каталог ОСТАННІМ: він — єдине, що телефон читає
    # без запрошення, і каталог, який називає пакет, чиїх байтів у бакеті ще
    # немає, це обіцянка, за якою людина поїде в 404 посеред поля.
    print(f"{len(rows)} пакетів → {bucket}/{head or '(корінь)'}")
    for row in rows:
        pack_id = row["id"]
        pack_dir = serve / pack_id
        blob = pack_dir / f"{pack_id}.bin"
        if not blob.exists():
            print(f"  ✗ {pack_id}: немає {blob.name}")
            return 66
        put(blob.resolve(), f"{head}{pack_id}/{pack_id}.bin", "application/octet-stream")
        put(pack_dir / MANIFEST, f"{head}{pack_id}/{MANIFEST}", "application/json")
    put(catalog_path, f"{head}{CATALOG}", "application/json")

    print("\nПробний прогін — нічого не викладено." if dry else "\nВикладено.")
    return 0


def remote_size(client, bucket: str, key: str) -> int | None:
    try:
        return int(client.head_object(Bucket=bucket, Key=key)["ContentLength"])
    except Exception:
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    st = sub.add_parser("stage", help="скласти дерево роздачі зі спеченого")
    st.add_argument("--bake", type=Path, required=True, help="каталог печі")
    st.add_argument("--out", type=Path, default=None, help="дерево роздачі (типово <bake>/serve)")
    st.add_argument("--only", default="", help="через кому: лише ці id")
    st.add_argument("--copy", action="store_true", help="не робити symlink на джерело")

    up = sub.add_parser("upload", help="викласти підписане дерево в R2")
    up.add_argument("--serve", type=Path, required=True, help="дерево роздачі")
    up.add_argument("--prefix", default="", help="префікс ключів у бакеті")
    up.add_argument("--only", default="", help="через кому: лише ці id")
    up.add_argument("--force", action="store_true", help="класти навіть те, що вже там")
    up.add_argument("--dry-run", action="store_true", help="показати й нічого не класти")

    args = parser.parse_args()
    only = {piece.strip() for piece in args.only.split(",") if piece.strip()} or None

    if args.command == "stage":
        bake = args.bake.expanduser().resolve()
        if not bake.is_dir():
            print(f"немає каталогу печі: {bake}")
            return 66
        return stage(bake, (args.out or bake / "serve").expanduser().resolve(), only, not args.copy)

    return upload(args.serve.expanduser().resolve(), args.prefix, only, args.force, args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
