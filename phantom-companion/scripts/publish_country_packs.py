r"""Місця й вулиці країни — від печі до телефона.

    # 1. на оракулі, де лежать спечені TSV (~/places-europe/out/<iso2>/):
    python publish_country_packs.py stage \
        --bake ~/places-europe/out --out ~/road-mesh-bake/serve \
        --app ~/repo-phantom --min-app-build 13

    # 2. там, де лежить ПРИВАТНИЙ ключ (ніколи не на печі) — дія власника:
    python sign_world_packs.py --key keys/packs.pem <дерево>

    # 3. звідки видно і байти, і .env з ключами R2:
    python publish_country_packs.py upload --serve <дерево>

Три кроки з тієї ж причини, що й у `publish_world_packs.py`: три секрети
живуть на трьох машинах, і скрипт, який робив би все разом, вимагав би звезти
їх в одне місце. Звірити дерево між кроками:

    python publish_country_packs.py verify --serve <дерево>

ЯК ПІДПИСАТИ, НЕ ВЕЗУЧИ 163 МБ І НЕ ВЕЗУЧИ КЛЮЧ. Підписуються лише документи —
каталог і 24 манiфести, разом 44 КБ; блоби лишаються на печі, їх тримає sha256
всередині манiфеста. Тобто rsync лише `manifest.json` і `forge-catalog.json`
на машину з ключем, підпис там, rsync назад — і крок 3 там, де байти й R2.

ПАСТКА ДРУГОГО КРОКУ, знайдена 17.09. `sign_world_packs.py` існує у ДВОХ тілах
під одним іменем. Копія стовбура (`platform-site/server/`) підписує
`world-catalog.json` і кожен `*/manifest.json` — і МОВЧКИ проходить повз
`forge-catalog.json`, бо її `documents()` знає лише перше ім'я. Копія, яка знає
обидва, лежить тільки на гілці `wt-forge-12sep` і в стовбур не зведена. Підпис
копією стовбура — це 24 підписані манiфести й каталог БЕЗ підпису: `upload`
відмовить і назве файл, а телефон без каталогу не бачить жодного рядка. Звіряти:

    python sign_world_packs.py --key keys/packs.pem <дерево> --check

КАТАЛОГ СПІЛЬНИЙ, І СУСІД ПИШЕ ЙОГО ЦІЛКОМ. `forge_packs.py stage` перезаписує
`forge-catalog.json` одним документом зі своїх рядків — наступний випік кузні
зітре звідти всі 24 країни. Симетрії немає: `merge_catalog` нижче чужі рядки
пропускає наскрізь, а той бік про ці не знає. Лікується повторним `stage`, але
знати про це треба ДО того, як у полі зникне пів каталогу.

ЧИМ ЦЕЙ ПАК ВІДРІЗНЯЄТЬСЯ ВІД СВІТОВОГО. Світовий пакет — один блоб на
`<база>/<id>/<id>.bin`. Пак КРАЇНИ — підписаний манiфест
`<база>/<ISO2>/manifest.json` і кілька файлів під ним, бо корпус місць мусить
приїхати разом із рамкою, домом і ліцензією: інакше телефон отримав би
півмільйона точок, яких нема кому приписати, і не знав би навіть, на яку
країну їх вішати.

РАМКА Й ДІМ НЕ НАБИРАЮТЬСЯ ТУТ РУКАМИ. Рамка — з `WorldTable.kt` телефона
(межа OSM `admin_level=2`, знята через Nominatim), дім — зі списку столиць у
`PlaceGazetteerTest.kt`. Обидва вже стереже збірка: тест звіряє, що столиця
лежить усередині рамки, і саме він колись викрив польську ґміну, видану
Nominatim за Білорусь. Другий список, набраний тут, розійшовся б мовчки.

ЧОМУ GZIP І ЧОМУ З ОБНУЛЕНИМ ЧАСОМ. Прогнано наскрізь 17.09 на всьому
європейському випіку: 24 країни, 563 МБ сирим TSV → 163,4 МБ у роздачі за 37 с
(Німеччина — 51 + 59 МБ → 17,1 + 16,6 МБ). Для людини в роумінгу це різниця між
«качати» й «не качати». Час у заголовку gzip обнулено навмисно: без цього
кожен повторний прогін давав би інші байти, інший sha256, нову версію — і гнав
би ті самі 34 МБ на кожен телефон ще раз. Заміряно: другий прогін по Німеччині
дав ті самі sha256 і лишив версію 1. Версія росте рівно зі ЗМІНОЮ ВМІСТУ.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

FORGE_CATALOG = "forge-catalog.json"
MANIFEST = "manifest.json"
KIND = "COUNTRY_PLACES"
CHUNK = 4 * 1024 * 1024

#: Що піч кладе в `<bake>/<iso2>/` → як це зветься в манiфесті пака.
#: `settlements.tsv` тут є на випадок, коли піч його додасть; станом на 16.09
#: європейський випік несе лише перші два, і пак чесно оголошує два.
CORPORA = {
    "places.tsv": ("placesAsset", "місця"),
    "streets.tsv": ("streetsAsset", "вулиці"),
    "settlements.tsv": ("settlementsAsset", "населені пункти"),
}

ATTRIBUTION = "© OpenStreetMap contributors"


# ── таблиці телефона ────────────────────────────────────────────────────

WORLD_TABLE_KT = "core-geo/src/main/java/local/phantom/companion/core/geo/place/WorldTable.kt"
CAPITALS_KT = "core-geo/src/test/java/local/phantom/companion/core/geo/place/PlaceGazetteerTest.kt"

#: `C|w-at|Австрія|Austria||N|E|S|W|км²|osm`
_ROW = re.compile(
    r"^C\|w-([a-z]{2})\|([^|]+)\|[^|]*\|\|"
    r"(-?\d+\.?\d*)\|(-?\d+\.?\d*)\|(-?\d+\.?\d*)\|(-?\d+\.?\d*)\|",
    re.MULTILINE,
)

#: `"w-at" to Capital("Відень", 48.2084, 16.3725),`
_CAPITAL = re.compile(r'"w-([a-z]{2})"\s+to\s+Capital\("[^"]*",\s*(-?\d+\.?\d*),\s*(-?\d+\.?\d*)\)')


class Country:
    def __init__(self, iso2: str, label_ua: str, box: tuple[float, float, float, float]):
        self.iso2 = iso2
        self.label_ua = label_ua
        self.max_lat, self.max_lon, self.min_lat, self.min_lon = box
        self.home: tuple[float, float] | None = None


def read_countries(app: Path) -> dict[str, Country]:
    """Рамки й столиці з дерева телефона. Бракує файла — стоп, не здогад."""
    table = app / WORLD_TABLE_KT
    capitals = app / CAPITALS_KT
    for path in (table, capitals):
        if not path.is_file():
            raise SystemExit(f"немає {path} — вкажіть --app на дерево phantom-companion")

    found: dict[str, Country] = {}
    for m in _ROW.finditer(table.read_text(encoding="utf-8")):
        iso2 = m.group(1).upper()
        found[iso2] = Country(iso2, m.group(2), tuple(float(m.group(i)) for i in (3, 4, 5, 6)))

    for m in _CAPITAL.finditer(capitals.read_text(encoding="utf-8")):
        iso2 = m.group(1).upper()
        if iso2 in found:
            found[iso2].home = (float(m.group(2)), float(m.group(3)))
    return found


# ── дерево роздачі ──────────────────────────────────────────────────────


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while block := handle.read(CHUNK):
            digest.update(block)
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
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def squeeze(source: Path, target: Path) -> None:
    """gzip з ОБНУЛЕНИМ mtime — див. заголовок файла. `gzip.GzipFile(mtime=0)`
    прибирає єдине поле, через яке однакові дані давали б різні байти."""
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as src, target.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as out:
            shutil.copyfileobj(src, out, CHUNK)


def baked_at(source: Path) -> str:
    stamp = datetime.fromtimestamp(source.stat().st_mtime, tz=timezone.utc)
    return stamp.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def stage(bake: Path, out: Path, app: Path, only: set[str] | None, min_app_build: int) -> int:
    known = read_countries(app)
    rows, bumped, kept, skipped = [], 0, 0, []

    for country_dir in sorted(p for p in bake.iterdir() if p.is_dir()):
        iso2 = country_dir.name.upper()
        if only and iso2 not in only:
            continue
        present = {name: country_dir / name for name in CORPORA if (country_dir / name).is_file()}
        if not present:
            continue
        country = known.get(iso2)
        # Країна без рамки або без столиці НЕ їде з вигаданою рамкою: пак, чий
        # дім лежить поза межами, телефон однаково відхилить (structuralFault),
        # тільки дізнається про це людина посеред поля.
        if country is None or country.home is None:
            skipped.append(f"{iso2}: немає рамки або столиці в таблицях телефона")
            continue

        pack_dir = out / iso2
        old = previous(pack_dir / MANIFEST)
        old_hashes = {f["name"]: f["sha256"] for f in old.get("files", [])}

        files, assets, sources, total = [], {}, [], 0
        for name, source in sorted(present.items()):
            field, human = CORPORA[name]
            wire_name = name + ".gz"
            squeeze(source, pack_dir / wire_name)
            blob = pack_dir / wire_name
            files.append(
                {"name": wire_name, "sha256": sha256_of(blob), "bytes": blob.stat().st_size}
            )
            assets[field] = wire_name
            # Ліцензія на КОЖЕН асет окремо: `PackLicenseAudit` на телефоні
            # відмовляє паку, чиє джерело не назване або не несе атрибуції, і
            # робить це ДО встановлення.
            sources.append(
                {
                    "id": f"{iso2.lower()}-{name.removesuffix('.tsv')}",
                    "title": f"{country.label_ua}: {human}",
                    "source": "OpenStreetMap",
                    "license": "ODBL",
                    "role": "OVERLAY",
                    "attribution": ATTRIBUTION,
                    "asset": wire_name,
                }
            )
            total += blob.stat().st_size

        changed = {f["name"]: f["sha256"] for f in files} != old_hashes
        version = int(old.get("version", 0)) + 1 if changed else int(old.get("version", 1))
        bumped, kept = (bumped + 1, kept) if changed else (bumped, kept + 1)

        write_json(
            pack_dir / MANIFEST,
            {
                "id": iso2,
                "title": country.label_ua,
                "version": version,
                "minAppBuild": min_app_build,
                "bounds": {
                    "minLat": country.min_lat, "minLon": country.min_lon,
                    "maxLat": country.max_lat, "maxLon": country.max_lon,
                },
                "home": {"lat": country.home[0], "lon": country.home[1], "zoom": 12.0},
                **assets,
                "sources": sources,
                "files": files,
            },
        )
        rows.append(
            {
                "id": iso2,
                "kind": KIND,
                "country": iso2,
                "labelUa": f"{country.label_ua} — місця й вулиці",
                "bytes": total,
                "bakedAt": baked_at(sorted(present.values())[0]),
                "minAppBuild": min_app_build,
            }
        )

    if not rows:
        print(f"у {bake} не знайдено жодної країни з корпусом")
        for line in skipped:
            print(f"  ✗ {line}")
        return 65

    merge_catalog(out, rows)

    total = sum(r["bytes"] for r in rows)
    print(f"\n{len(rows)} країн, {total / 1_000_000:.0f} МБ разом → {out}")
    print(f"версію піднято: {bumped}, лишилось як було: {kept}")
    for line in skipped:
        print(f"  ✗ {line}")
    print(
        "\nПІДПИСУ ЩЕ НЕМА — телефон таке дерево відхилить цілком.\n"
        f"  python sign_world_packs.py --key keys/packs.pem {out}\n"
        f"  python sign_world_packs.py --key keys/packs.pem {out} --check\n"
        f"Другий рядок обов'язковий: у переліку підписаного мусить бути й "
        f"{FORGE_CATALOG} — копія підписувача зі стовбура його пропускає."
    )
    return 0


def merge_catalog(out: Path, rows: list[dict]) -> None:
    """Каталог кузні СПІЛЬНИЙ із `forge_packs.py`.

    Писати його цілком означало б стирати чужі 49 рядків при кожному прогоні —
    а дізнався б про це телефон, у якого зник геокодер. Міняємо лише свої
    рядки; чужі проходять наскрізь, і `sig` викидається, бо документ змінився
    й старий підпис до нього більше не належить.
    """
    path = out / FORGE_CATALOG
    existing = [r for r in previous(path).get("packs", []) if r.get("kind") != KIND]
    merged = sorted(existing + rows, key=lambda r: (r.get("kind", ""), r.get("id", "")))
    write_json(path, {"packs": merged})


# ── звірка ──────────────────────────────────────────────────────────────


def verify(serve: Path, only: set[str] | None) -> int:
    """Що каталог оголосив — те й лежить у дереві.

    Між `stage` і `upload` дерево проходить через ЧУЖІ руки: підписувач
    переписує документи, rsync возить їх між машинами, сусідній `forge_packs.py`
    пише той самий каталог. Кожен крок лишає дерево на вигляд цілим, а телефон
    побачить різницю вже байтами з R2 — у полі. Тут той самий рахунок, що
    робить телефон, лише до викладення.
    """
    catalog = previous(serve / FORGE_CATALOG)
    rows = [r for r in catalog.get("packs", []) if r.get("kind") == KIND]
    if only:
        rows = [r for r in rows if r.get("id") in only]
    if not rows:
        print(f"у {serve / FORGE_CATALOG} немає жодного пака країни")
        return 65

    faults, total = [], 0
    for row in sorted(rows, key=lambda r: r["id"]):
        iso2 = row["id"]
        m = previous(serve / iso2 / MANIFEST)
        if not m:
            faults.append(f"{iso2}: немає {MANIFEST}")
            continue
        size = 0
        for spec in m.get("files", []):
            blob = serve / iso2 / spec["name"]
            if not blob.is_file():
                faults.append(f"{iso2}/{spec['name']}: немає файла")
                continue
            got = blob.stat().st_size
            if got != spec["bytes"]:
                faults.append(f"{iso2}/{spec['name']}: {got} байт, оголошено {spec['bytes']}")
            elif sha256_of(blob) != spec["sha256"]:
                faults.append(f"{iso2}/{spec['name']}: розмір той, sha256 інший — файл підмінено")
            size += got
        named = {m[k] for k in ("placesAsset", "streetsAsset", "settlementsAsset", "poiAsset") if m.get(k)}
        on_wire = {f["name"] for f in m.get("files", [])}
        b, h = m.get("bounds", {}), m.get("home", {})
        home_inside = (b.get("minLat", 1) <= h.get("lat", 0) <= b.get("maxLat", -1)
                       and b.get("minLon", 1) <= h.get("lon", 0) <= b.get("maxLon", -1))
        for bad, note in (
            (size != row["bytes"], f"каталог оголосив {row['bytes']} байт, манiфест несе {size}"),
            (named != on_wire, f"асети {sorted(named)} ≠ файли {sorted(on_wire)}"),
            ({s["asset"] for s in m.get("sources", []) if s.get("asset")} != on_wire,
             "не кожен асет має ліцензію — PackLicenseAudit відмовить"),
            (not home_inside, "дім лежить за рамкою — телефон відмовить structuralFault"),
            (m.get("id") != iso2, f"манiфест називає себе «{m.get('id')}»"),
        ):
            if bad:
                faults.append(f"{iso2}: {note}")
        total += size
        print(f"  {iso2} v{m.get('version')} {size / 1_000_000:6.1f} МБ  {len(m.get('files', []))} файли"
              f"  {'підписано' if m.get('sig') else 'БЕЗ ПІДПИСУ'}")

    print(f"\n{len(rows)} країн, {total / 1_000_000:.1f} МБ")
    print(f"каталог: {'підписано' if catalog.get('sig') else 'БЕЗ ПІДПИСУ — телефон не побачить жодного рядка'}")
    for line in faults:
        print(f"  ✗ {line}")
    return 70 if faults else 0


# ── R2 ──────────────────────────────────────────────────────────────────


def upload(serve: Path, prefix: str, only: set[str] | None, force: bool, dry: bool) -> int:
    catalog_path = serve / FORGE_CATALOG
    if not catalog_path.is_file():
        print(f"немає {catalog_path} — спершу stage")
        return 66
    rows = [r for r in previous(catalog_path).get("packs", []) if r.get("kind") == KIND]
    if only:
        rows = [r for r in rows if r.get("id") in only]
    if not rows:
        print("у каталозі немає жодного пака країни для викладення")
        return 65

    documents = [catalog_path] + [serve / r["id"] / MANIFEST for r in rows]
    if bad := [p for p in documents if not previous(p).get("sig")]:
        print("БЕЗ ПІДПИСУ — нічого не викладено:")
        for path in bad:
            print(f"  {path.relative_to(serve)}")
        print(f"\n  python sign_world_packs.py --key keys/packs.pem {serve}")
        return 1

    try:
        import boto3  # noqa: PLC0415
        from dotenv import load_dotenv  # noqa: PLC0415
    except ModuleNotFoundError as e:
        print(f"для викладення бракує залежності: {e.name}\n  pip install boto3 python-dotenv")
        return 69

    import os  # noqa: PLC0415

    load_dotenv(Path(__file__).resolve().parent / ".env")
    need = ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET")
    if missing := [k for k in need if not os.environ.get(k)]:
        print("R2 не налаштовано: бракує " + ", ".join(missing))
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

    def remote_size(key: str) -> int | None:
        try:
            return int(client.head_object(Bucket=bucket, Key=key)["ContentLength"])
        except Exception:
            return None

    def put(source: Path, key: str, content_type: str) -> None:
        size = source.stat().st_size
        if not force and remote_size(key) == size:
            print(f"  = {key} ({size / 1_000_000:.0f} МБ, уже там)")
            return
        print(f"  → {key} ({size / 1_000_000:.0f} МБ, {content_type})")
        if not dry:
            client.upload_file(str(source), bucket, key, ExtraArgs={"ContentType": content_type})

    # Порядок той самий, що й у світових пакетів, і з тієї ж причини: каталог
    # ОСТАННІМ, бо він — єдине, що телефон читає без запрошення, а каталог,
    # який називає пак без байтів у бакеті, це 404 посеред поля.
    print(f"{len(rows)} пакiв країн → {bucket}/{head or '(корінь)'}")
    for row in rows:
        iso2 = row["id"]
        pack_dir = serve / iso2
        manifest = previous(pack_dir / MANIFEST)
        for spec in manifest.get("files", []):
            blob = pack_dir / spec["name"]
            if not blob.is_file():
                print(f"  ✗ {iso2}: немає {spec['name']}")
                return 66
            put(blob, f"{head}{iso2}/{spec['name']}", "application/gzip")
        put(pack_dir / MANIFEST, f"{head}{iso2}/{MANIFEST}", "application/json")
    put(catalog_path, f"{head}{FORGE_CATALOG}", "application/json")

    print("\nПробний прогін — нічого не викладено." if dry else "\nВикладено.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    st = sub.add_parser("stage", help="скласти дерево роздачі зі спечених TSV")
    st.add_argument("--bake", type=Path, required=True, help="каталог печі (~/places-europe/out)")
    st.add_argument("--out", type=Path, required=True, help="дерево роздачі")
    st.add_argument("--app", type=Path, required=True, help="дерево phantom-companion")
    st.add_argument("--only", default="", help="через кому: лише ці ISO2")
    st.add_argument("--min-app-build", type=int, required=True)

    up = sub.add_parser("upload", help="викласти підписане дерево в R2")
    up.add_argument("--serve", type=Path, required=True)
    up.add_argument("--prefix", default="")
    up.add_argument("--only", default="")
    up.add_argument("--force", action="store_true")
    up.add_argument("--dry-run", action="store_true")

    ve = sub.add_parser("verify", help="звірити дерево з його ж каталогом")
    ve.add_argument("--serve", type=Path, required=True)
    ve.add_argument("--only", default="")

    args = parser.parse_args()
    only = {p.strip().upper() for p in args.only.split(",") if p.strip()} or None

    if args.command == "stage":
        if args.min_app_build <= 0:
            print("--min-app-build мусить бути додатним номером збірки")
            return 64
        bake = args.bake.expanduser().resolve()
        if not bake.is_dir():
            print(f"немає каталогу печі: {bake}")
            return 66
        return stage(
            bake,
            args.out.expanduser().resolve(),
            args.app.expanduser().resolve(),
            only,
            args.min_app_build,
        )

    if args.command == "verify":
        return verify(args.serve.expanduser().resolve(), only)

    return upload(
        args.serve.expanduser().resolve(), args.prefix, only, args.force, args.dry_run
    )


if __name__ == "__main__":
    raise SystemExit(main())
