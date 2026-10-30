"""Таблиця країн роздачі проти таблиці країн телефона.

`publish_world_packs.COUNTRIES` вписує українську назву країни в каталог, який
телефон показує людині. Рівно ті самі назви вже живуть у телефона —
`core-geo/.../place/WorldTable.kt`, згенерована з `name:uk` самого OSM.

Два списки країн, набрані руками в двох деревах, розходяться мовчки і на одну
літеру: у каталозі стало б «Молдавія» там, де в застосунку «Молдова», і
помітила б це людина, а не збірка. Тому тут звіряються ДВА ОГОЛОШЕННЯ ОДНОГО
Й ТОГО САМОГО, а не реалізація сама з собою.

Дерева телефона поруч може не бути (сервер розгортається окремо) — тоді тест
пропускається, а не червоніє на порожньому місці. Так само влаштований
`PHONE_VECTORS` у `test_world_pack_signing.py`.
"""
import re
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from publish_world_packs import COUNTRIES, KINDS  # noqa: E402  — після sys.path

WORLD_TABLES = sorted(
    SERVER_DIR.parents[1].glob(
        "*/core-geo/src/main/java/local/phantom/companion/core/geo/place/WorldTable.kt"
    )
)

#: `C|w-md|Молдова|Moldova||…` — той самий формат, що й `PLACE_TABLE`.
ROW = re.compile(r"^C\|w-([a-z]{2})\|([^|]*)\|", re.M)


def phone_names(path: Path) -> dict[str, str]:
    return {m.group(1).upper(): m.group(2) for m in ROW.finditer(path.read_text(encoding="utf-8"))}


@pytest.mark.skipif(not WORLD_TABLES, reason="дерева телефона поруч немає")
@pytest.mark.parametrize("table", WORLD_TABLES, ids=lambda p: p.parents[7].name)
def test_every_country_label_is_the_phones_own(table: Path):
    names = phone_names(table)
    assert len(names) > 150, f"розібрано лише {len(names)} країн — формат таблиці змінився"

    drift = {
        iso2: (label, names.get(iso2))
        for iso2, (_, label) in COUNTRIES.items()
        if names.get(iso2) != label
    }
    assert not drift, "назви розійшлися з таблицею телефона: " + ", ".join(
        f"{iso2}: роздача={ours!r}, телефон={theirs!r}" for iso2, (ours, theirs) in sorted(drift.items())
    )


def test_pack_ids_are_built_the_way_the_phone_fetches_them():
    """`PackFetcher` качає `<база>/<id>/<id>.bin`, а `WorldPackGate.fileName`
    віддає `"$packId.bin"`. Отже id мусить бути придатним і за шматок адреси,
    і за ім'я файла: пробіл, слеш чи двокрапка тут стали б битою адресою, а
    `PackFetcher.unsafe` відхилив би такий файл уже на телефоні."""
    for iso2 in COUNTRIES:
        for prefix, _ in KINDS.values():
            pack_id = f"{prefix}-{iso2.lower()}"
            assert re.fullmatch(r"[a-z0-9-]+", pack_id), pack_id


def test_kind_names_are_the_kotlin_enum_constants():
    """`WorldPackKind` серіалізується власним іменем константи. «Basemap»
    замість «BASEMAP» дало б телефону зіпсований каталог, а не невідомий рядок:
    kotlinx відмовляє на невідомому значенні enum, і `parseCatalog` віддав би
    null на ВЕСЬ каталог — 27 пакетів зникли б через одну літеру."""
    assert set(KINDS) == {"ROAD_MESH", "BASEMAP"}
