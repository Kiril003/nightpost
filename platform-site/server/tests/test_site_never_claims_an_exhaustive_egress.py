"""Сайт не сміє казати «більше нічого» про вихідний трафік застосунку.

У поширених питаннях стояло: «З пристрою назовні йдуть лише активація ліцензії
та завантаження пакетів даних — більше нічого». Це було неправдою за власним
визначенням сайту: пакетами даних він називає мапи, місця, рельєф і тривоги, а
назовні йшли ще й моделі голосу з huggingface.co, слуху з alphacephei.com,
погода, геокодування, тайли й три стрічки тривог. Гірше: та сама сторінка в
виносці про третіх осіб чесно все це перелічує — тобто сайт спростовував себе
сам, за два екрани.

Обіцянка виду «ЛИШЕ X, більше нічого» — найдорожчий рід тверджень: одного
контрприкладу досить, щоб знецінити решту сторінки. Тому вона заборонена, а
заборона спирається не на смак, а на ВИМІР: у дереві телефона живе перепис
вихідних хостів (`OutboundHostAllowlistTest.kt`), і поки в ньому більше жменьки
мережевих адрес, жодна «лише…» на сайті не може бути правдивою.

Перевірка спершу міряє передумову, потім тримає копірайт — той самий порядок,
що й у стороже мапи без мережі.
"""
import re
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
SITE_DIR = SERVER_DIR.parent
COPY = [SITE_DIR / "src/i18n/commerce.ts", SITE_DIR / "src/i18n/legal.ts", SITE_DIR / "src/i18n/home.ts"]

#: Конструкції «лише … більше нічого» про вихідний трафік, обома мовами.
EXHAUSTIVE = (
    "більше нічого",
    "The only traffic leaving the device",
    "nothing else leaves",
    "більше нікуди",
)

#: Позначка з перепису телефона: рядок є, мережевого виклику немає.
NOT_NETWORK = "НЕ МЕРЕЖА"


def _allowlist() -> tuple[Path, dict[str, str]] | tuple[None, None]:
    """Перепис вихідних хостів із дерева телефона — хост -> умова.

    Файл шукається за ІМЕНЕМ у всіх сусідніх деревах телефона, а не за однією
    прибитою дорогою: перепис народився у хвилі `wave/privacy-truth-05sep` і
    переїде у ствол при зведенні. Сторож, який знає лише одну дорогу, мовчки
    зникне рівно в день зведення.
    """
    for tree in sorted(SITE_DIR.parent.glob("*phantom-companion*")) + sorted(
        SITE_DIR.parent.glob("*privacy-truth*")
    ):
        for path in tree.glob("app/src/test/**/OutboundHostAllowlistTest.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            block = text.split("allowedHosts", 1)[-1]
            hosts: dict[str, str] = {}
            for m in re.finditer(r'"([a-z0-9][a-z0-9.-]*\.[a-z]{2,})"\s+to\s*\n?\s*"([^"]{0,40})', block):
                hosts[m.group(1)] = m.group(2)
            if hosts:
                return path, hosts
    return None, None


def test_перепис_хостів_телефона_знайдено_і_він_не_куций():
    """Передумова заборони. Немає перепису — немає й підстави забороняти."""
    path, hosts = _allowlist()
    if path is None:
        pytest.skip("перепису вихідних хостів немає в жодному дереві телефона")

    live = {h: why for h, why in hosts.items() if not why.startswith(NOT_NETWORK)}
    assert len(live) > 5, (
        f"у {path.name} лише {len(live)} мережевих хостів ({sorted(live)}). "
        "Якщо це правда, заборону нижче треба переглянути СВІДОМО, а не обійти"
    )


def test_сайт_не_обіцяє_вичерпного_переліку_вихідного_трафіку():
    """Той бік, яким копірайт відстає від застосунку.

    Одного контрприкладу досить: рецензент читає «лише активація і пакети», а
    потім бачить у виносці тієї ж сторінки погоду, тривоги й huggingface.co.
    Після цього він не вірить жодному рядку, і має рацію.
    """
    guilty: list[str] = []
    for path in COPY:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for phrase in EXHAUSTIVE:
            if phrase in text:
                line = text[: text.index(phrase)].count("\n") + 1
                guilty.append(f"{path.name}:{line} — «{phrase}»")

    assert not guilty, (
        "сайт обіцяє вичерпний перелік вихідного трафіку:\n  "
        + "\n  ".join(guilty)
        + "\nЗастосунок ходить на десятки хостів (див. OutboundHostAllowlistTest). "
        "Назвіть категорії й пошліться на перепис — або зніміть слово «лише»"
    )
