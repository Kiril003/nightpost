"""Сторінка закладу має ОДИН словник блоків — на сайті (/build) і на сервері.

Власник закладу складає сторінку у «Білдері» на сайті, а сервер (`venues.py`)
приймає маніфест `{v: 1, blocks: [...]}` лише із закритого словника. Розбіжність
мовчазна з обох боків: TypeScript не бачить Python, а Python не бачить
TypeScript. Сайт, що шле блок, якого сервер не знає, отримує 422 на кнопці
«Опублікувати»; сервер, що навчився новому блоку, якого сайт не вміє скласти, —
мертва частина контракту.

Сторож міряє три речі:

  1. словник сайту (`src/lib/venueBlocks.ts`, `BLOCK_FIELDS`) — це контракт
     плану build 18 (§2.2: title, about, address+point, hours, menu) плюс
     гаманець закладу з рішення власника 07.10 («якщо на сторінці закладу є
     гаманець, організатор може обрати "платити закладу"»). Фото (у плані зі
     знаком питання) не ввійшло: CSP сайту й WebView чужих картинок не пускає;
  2. словник сервера, коли `venues.py` вже злито (гілка S1), — той самий набір
     типів, і, якщо сервер оголошує поля, ті самі поля в кожного типу;
  3. перевірка маніфесту на сайті справді відкидає те, що контракт забороняє,
     і пропускає те, що дозволяє (виконуємо сам TS через Node, без перекладу).
"""
import importlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

SERVER = Path(__file__).resolve().parents[1]
SITE = SERVER.parent
BLOCKS_TS = SITE / "src/lib/venueBlocks.ts"
VENUES_PY = SERVER / "venues.py"

# Контракт плану (build18-scene-plan.md §2.2) + гаманець закладу (рішення власника).
CONTRACT: dict[str, set[str]] = {
    "title": {"text"},
    "about": {"text"},
    "address": {"text", "lat", "lon"},
    "hours": {"rows"},
    "menu": {"title", "token", "items"},
    "event": {"id", "title", "starts", "ends", "note", "price", "token"},
    "wallet": {"address"},
}
PAGE_VERSION = 1


def _site_source() -> str:
    assert BLOCKS_TS.exists(), "немає src/lib/venueBlocks.ts — у сайту немає словника блоків"
    return BLOCKS_TS.read_text(encoding="utf-8")


def _site_vocabulary() -> dict[str, set[str]]:
    text = _site_source()
    m = re.search(r"export const BLOCK_FIELDS = \{(.*?)\n\}", text, re.S)
    assert m, "у venueBlocks.ts немає `export const BLOCK_FIELDS = { … }`"
    out: dict[str, set[str]] = {}
    for name, fields in re.findall(r"^\s+([a-z_]+): \[([^\]]*)\]", m.group(1), re.M):
        out[name] = set(re.findall(r"'([a-z_]+)'", fields))
    assert out, "BLOCK_FIELDS порожній"
    return out


def _site_version() -> int:
    m = re.search(r"export const PAGE_VERSION = (\d+);", _site_source())
    assert m, "у venueBlocks.ts немає `export const PAGE_VERSION = …;`"
    return int(m.group(1))


def _server_vocabulary() -> tuple[set[str], dict[str, set[str]] | None]:
    """Типи блоків, які сервер приймає, і — якщо він їх оголошує — поля кожного.

    Назву константи в `venues.py` не вгадуємо: шукаємо модульне значення, що
    містить і `title`, і `menu` — це і є словник. Словник-мапа дає поля,
    множина чи кортеж — лише типи.
    """
    if str(SERVER) not in sys.path:
        sys.path.insert(0, str(SERVER))
    venues = importlib.import_module("venues")
    for name in dir(venues):
        if name.startswith("__"):
            continue
        value = getattr(venues, name)
        if isinstance(value, dict) and {"title", "menu"} <= set(value):
            fields: dict[str, set[str]] = {}
            for kind, spec in value.items():
                if isinstance(spec, dict):
                    fields[kind] = {k for k in spec if isinstance(k, str)}
                elif isinstance(spec, (set, frozenset, tuple, list)) and all(isinstance(s, str) for s in spec):
                    fields[kind] = set(spec)
                elif isinstance(spec, (tuple, list)):
                    # (обовʼязкові, необовʼязкові, скільки разів) — поля це обʼєднання множин.
                    parts = [p for p in spec if isinstance(p, (set, frozenset))]
                    if parts and all(isinstance(f, str) for part in parts for f in part):
                        fields[kind] = set().union(*parts)
            return set(value), (fields if len(fields) == len(value) else None)
        if isinstance(value, (set, frozenset, tuple, list)) and all(isinstance(s, str) for s in value):
            if {"title", "menu"} <= set(value):
                return set(value), None
    raise AssertionError("у venues.py не знайшлося словника блоків (значення з 'title' і 'menu')")


def test_словник_сайту_це_контракт_плану():
    assert _site_vocabulary() == CONTRACT
    assert _site_version() == PAGE_VERSION


def test_словник_сервера_збігається_зі_словником_сайту():
    if not VENUES_PY.exists():
        pytest.skip("venues.py ще не злито (S1, cloud/b18-venues): порівнювати нема з чим")
    types, fields = _server_vocabulary()
    site = _site_vocabulary()
    assert types == set(site), (
        f"сервер приймає {sorted(types)}, сайт складає {sorted(site)}: "
        f"лише сервер {sorted(types - set(site))}, лише сайт {sorted(set(site) - types)}"
    )
    if fields is not None:
        for kind in sorted(types):
            assert fields[kind] == site[kind], f"блок {kind}: сервер {sorted(fields[kind])}, сайт {sorted(site[kind])}"


# ── Перевірка маніфесту на сайті: виконуємо сам venueBlocks.ts ───────────────

def _page(*blocks: dict) -> dict:
    return {"v": PAGE_VERSION, "blocks": list(blocks)}


TITLE = {"type": "title", "text": "Cafe X"}
ADDRESS = {"type": "address", "text": "Stodolní 12, Ostrava", "lat": 49.8347, "lon": 18.282}
HOURS = {"type": "hours", "rows": [{"day": "mon", "open": "08:00", "close": "22:00"}]}
MENU = {
    "type": "menu",
    "token": "USDC",
    "items": [
        {"id": "borsch", "name": "Борщ", "price": "4.5"},
        {"id": "pierogi", "name": "Вареники", "price": "6"},
        {"id": "tea", "name": "Чай", "price": "1.25"},
        {"id": "cake", "name": "Торт", "price": "3.75"},
    ],
}

EVENT = {"type": "event", "id": "jazz", "title": "Джаз", "starts": "2026-10-20T19:00"}

CASES: list[tuple[str, dict, bool]] = [
    ("подія без ціни", _page(TITLE, EVENT), True),
    ("подія з кінцем, приміткою й ціною", _page(TITLE, {**EVENT, "ends": "2026-10-20T22:00", "note": "Вхід з двору",
                                                        "price": "5", "token": "USDC"}), True),
    ("кінець події до початку", _page(TITLE, {**EVENT, "ends": "2026-10-20T18:00"}), False),
    ("час події без T", _page(TITLE, {**EVENT, "starts": "2026-10-20 19:00"}), False),
    ("31 листопада", _page(TITLE, {**EVENT, "starts": "2026-11-31T19:00"}), False),
    ("ціна події без монети", _page(TITLE, {**EVENT, "price": "5"}), False),
    ("монета події без ціни", _page(TITLE, {**EVENT, "token": "USDC"}), False),
    ("дві події з одним кодом", _page(TITLE, EVENT, {**EVENT, "title": "Ще"}), False),
    ("двадцять подій", _page(TITLE, *[{**EVENT, "id": f"e{k}"} for k in range(20)]), True),
    ("двадцять одна подія", _page(TITLE, *[{**EVENT, "id": f"e{k}"} for k in range(21)]), False),
    ("кафе з чотирма стравами", _page(TITLE, {"type": "about", "text": "Затишно"}, ADDRESS, HOURS, MENU), True),
    ("лише назва", _page(TITLE), True),
    ("невідомий блок", _page(TITLE, {"type": "script", "text": "x"}), False),
    ("фото поки не блок", _page(TITLE, {"type": "photo", "url": "https://x.example/a.jpg", "alt": ""}), False),
    ("зайве поле в блоці", _page({"type": "title", "text": "Cafe X", "html": "<b>"}), False),
    ("зайве поле в страві", _page(TITLE, {**MENU, "items": [{**MENU["items"][0], "img": "x"}]}), False),
    ("примітка до страви", _page(TITLE, {**MENU, "items": [{**MENU["items"][0], "note": "еспресо"}]}), True),
    ("меню з назвою", _page(TITLE, {**MENU, "title": "Кухня"}), True),
    ("без назви", _page(ADDRESS), False),
    ("дві назви", _page(TITLE, TITLE), False),
    ("інша версія", {"v": 2, "blocks": [TITLE]}, False),
    ("ціна числом, не рядком", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": 1.25}]}), False),
    ("ціна з комою", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": "1,25"}]}), False),
    ("нульова ціна", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": "0"}]}), False),
    ("сім знаків USDC", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": "1.0000001"}]}), False),
    ("дев'ять знаків SOL", _page(TITLE, {"type": "menu", "token": "SOL",
                                         "items": [{"id": "a", "name": "Чай", "price": "0.000000001"}]}), True),
    ("невідома монета", _page(TITLE, {**MENU, "token": "EUR"}), False),
    ("два меню однією монетою", _page(TITLE, MENU, {"type": "menu", "title": "Напої", "token": "USDC",
                                                     "items": [{"id": "kava", "name": "Кава", "price": "2"}]}), True),
    ("два меню різними монетами", _page(TITLE, MENU, {"type": "menu", "token": "SOL",
                                                       "items": [{"id": "pyvo", "name": "Пиво", "price": "0.01"}]}), False),
    ("одна страва двічі в двох меню", _page(TITLE, MENU, {"type": "menu", "token": "USDC",
                                                           "items": [MENU["items"][0]]}), False),
    ("порожнє меню", _page(TITLE, {**MENU, "items": []}), False),
    ("дві страви з одним id", _page(TITLE, {**MENU, "items": [MENU["items"][0], MENU["items"][0]]}), False),
    ("широта поза світом", _page(TITLE, {**ADDRESS, "lat": 91}), False),
    ("день словом", _page(TITLE, {"type": "hours", "rows": [{"day": "понеділок", "open": "08:00", "close": "22:00"}]}), False),
    ("година 25:00", _page(TITLE, {"type": "hours", "rows": [{"day": "mon", "open": "25:00", "close": "22:00"}]}), False),
    ("до першої ночі", _page(TITLE, {"type": "hours", "rows": [{"day": "sat", "open": "10:00", "close": "01:00"}]}), True),
    ("гаманець", _page(TITLE, {"type": "wallet", "address": "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"}), True),
    ("гаманець не base58", _page(TITLE, {"type": "wallet", "address": "0OIl" * 10}), False),
    # 44 знаки base58, але 33 байти: довжини рядка мало, сервер розгортає ключ.
    ("гаманець на 33 байти", _page(TITLE, {"type": "wallet", "address": "z" * 44}), False),
    ("ціна з нулями попереду", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": "007"}]}), False),
    ("ціна 0.50", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "Чай", "price": "0.50"}]}), True),
    ("код страви з великими й _", _page(TITLE, {**MENU, "items": [{"id": "Tea_1", "name": "Чай", "price": "1"}]}), True),
    ("код страви кирилицею", _page(TITLE, {**MENU, "items": [{"id": "чай", "name": "Чай", "price": "1"}]}), False),
    ("табуляція в назві", _page({"type": "title", "text": "Cafe\tX"}), False),
    ("новий рядок у «про заклад»", _page(TITLE, {"type": "about", "text": "Кава.\nОбіди."}), True),
    ("цілодобово", _page(TITLE, {"type": "hours", "rows": [{"day": "fri", "open": "00:00", "close": "00:00"}]}), True),
    # Невидимі й керівні знаки — те, що приходить разом із текстом, вставленим із
    # веб-сторінки чи Word. Сервер (`_text`: категорії Cc і Cf) їх не приймає.
    ("мʼякий перенос у назві", _page({"type": "title", "text": "Cafe\u00adX"}), False),
    ("пробіл нульової ширини в адресі", _page(TITLE, {**ADDRESS, "text": "Stodolní\u200b 12, Ostrava"}), False),
    ("LRM у назві страви", _page(TITLE, {**MENU, "items": [{"id": "a", "name": "\u200eЧай", "price": "1"}]}), False),
    ("керівний C1 U+0085 у назві", _page({"type": "title", "text": "Cafe\u0085X"}), False),
    ("BOM у «про заклад»", _page(TITLE, {"type": "about", "text": "Кава\ufeff обіди"}), False),
    ("керівний знак у примітці", _page(TITLE, {**MENU, "items": [{**MENU["items"][0], "note": "еспресо\u0007"}]}), False),
    ("примітка з самих пробілів", _page(TITLE, {**MENU, "items": [{**MENU["items"][0], "note": "   "}]}), False),
    ("примітка довша за 140", _page(TITLE, {**MENU, "items": [{**MENU["items"][0], "note": "к" * 141}]}), False),
    # Межа страв — на всю сторінку (200), не лише на розділ.
    ("три розділи по сто страв", _page(TITLE, *[
        {"type": "menu", "token": "USDC", "items": [{"id": f"d{m}-{k}", "name": "Чай", "price": "1"} for k in range(100)]}
        for m in range(3)
    ]), False),
    ("два розділи по сто страв", _page(TITLE, *[
        {"type": "menu", "token": "USDC", "items": [{"id": f"d{m}-{k}", "name": "Чай", "price": "1"} for k in range(100)]}
        for m in range(2)
    ]), True),
    # 32 КБ маніфесту — межа сервера (`too_big`), міряна так, як її міряє Python.
    ("сторінка понад 32 КБ", _page(TITLE, {"type": "menu", "token": "USDC", "items": [
        {"id": f"d{k}", "name": "Б" * 80, "price": "1", "note": "п" * 140} for k in range(100)
    ]}), False),
    # Довжину сервер рахує в знаках (код-пойнтах), не в UTF-16: 80 емодзі — це 80.
    ("80 емодзі в назві", _page({"type": "title", "text": "\U0001F355" * 80}), True),
]


def _site_verdicts() -> dict[str, bool]:
    node = shutil.which("node")
    if node is None:
        pytest.skip("немає node: перевірку сайту виконати нічим")
    script = (
        "const m = await import(process.argv[1]);"
        "const cases = JSON.parse(process.argv[2]);"
        "const out = {};"
        "for (const [name, page] of cases) out[name] = m.validatePage(page).ok;"
        "console.log(JSON.stringify(out));"
    )
    payload = json.dumps([[name, page] for name, page, _ in CASES], ensure_ascii=False)
    run = subprocess.run(
        [node, "--input-type=module", "-e", script, BLOCKS_TS.as_uri(), payload],
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout.strip().splitlines()[-1])


def test_опублікувати_без_адреси_не_можна():
    """Без точки заклад не знайдеться «поруч»; чернетку без адреси скласти можна."""
    node = shutil.which("node")
    if node is None:
        pytest.skip("немає node")
    script = (
        "const m = await import(process.argv[1]);"
        "const p = JSON.parse(process.argv[2]);"
        "console.log(JSON.stringify([m.validatePage(p).ok, m.validatePage(p, {publish: true})]));"
    )
    run = subprocess.run([node, "--input-type=module", "-e", script, BLOCKS_TS.as_uri(), json.dumps(_page(TITLE, MENU))],
                         capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    draft_ok, publish = json.loads(run.stdout.strip().splitlines()[-1])
    assert draft_ok is True
    assert publish["ok"] is False and publish["problem"] == "needs_address"


def test_перевірка_сайту_тримає_контракт():
    _site_source()
    got = _site_verdicts()
    wrong = {name: got.get(name) for name, _, ok in CASES if got.get(name) is not ok}
    assert not wrong, f"сайт помиляється у вердиктах: {wrong}"



# Де сайт НАВМИСНО суворіший за сервер: сервер прийме сторінку без назви (назва
# закладу живе й окремо, з `POST /venues`), а редактор без назви не зберігає —
# картка зустрічі в чаті підписує заклад саме нею.
SITE_STRICTER = {"без назви"}


def test_сервер_і_сайт_дають_однакові_вердикти():
    """Той самий набір сторінок — крізь `venues.validate_page` сервера. Інакше
    редактор пропустить те, на що сервер відповість 422, або навпаки — не дасть
    зберегти сторінку, яку сервер прийняв би."""
    if not VENUES_PY.exists():
        pytest.skip("venues.py ще не злито (S1, cloud/b18-venues): порівнювати нема з чим")
    if str(SERVER) not in sys.path:
        sys.path.insert(0, str(SERVER))
    venues = importlib.import_module("venues")
    validate = getattr(venues, "validate_page", None)
    refused = getattr(venues, "Refused", Exception)
    assert callable(validate), "у venues.py немає validate_page"

    def server_ok(page: dict) -> bool:
        try:
            validate(json.loads(json.dumps(page)))
            return True
        except refused:
            return False

    wrong = {}
    for name, page, site_ok in CASES:
        want = True if name in SITE_STRICTER else site_ok
        if server_ok(page) is not want:
            wrong[name] = {"server": not want, "site": site_ok}
    assert not wrong, f"сервер і сайт розходяться: {wrong}"

# ── Досяжність: «Білдер» живе за адресою /build і справді ходить у сервер ────

APP_TSX = SITE / "src/App.tsx"
BUILD_TSX = SITE / "src/pages/Build.tsx"
API_TS = SITE / "src/lib/venuesApi.ts"


def test_білдер_досяжний_з_адреси_build():
    """`/build` — редактор v2; стара форма закладу живе на `/build/place`."""
    app = APP_TSX.read_text(encoding="utf-8")
    assert re.search(r"\{ path: '/build', element: bare\(<BuildEditor />\) \}", app), "/build не відкриває редактор"
    assert re.search(r"'/build/place': \(\) => import\('\./pages/Build'\)", app), "App.tsx не вантажить pages/Build для /build/place"
    assert re.search(r"\{ path: '/build/place', element: page\('/build/place'\) \}", app), "у маршрутах немає /build/place"

    assert BUILD_TSX.exists(), "немає src/pages/Build.tsx"
    page = BUILD_TSX.read_text(encoding="utf-8")
    page_and_parts = page + "".join(
        p.read_text(encoding="utf-8") for p in sorted((SITE / "src/components/builder").glob("*.ts*"))
    )
    for fn in ("createVenue", "savePage", "publishVenue", "getVenue"):
        assert re.search(r"\b%s\(" % fn, page_and_parts), f"Білдер не викликає {fn}"
    assert "validatePage(" in page_and_parts, "Білдер шле маніфест, не перевіривши його"

    api = API_TS.read_text(encoding="utf-8")
    # Шляхи контракту S1 (план §2.2): створення, сторінка, читання.
    assert "'/venues'" in api
    assert re.search(r"`/venues/\$\{[^}]+\}/page`", api)
    assert re.search(r"`/venues/\$\{[^}]+\}`", api)


def test_браузер_пускають_кожним_методом_білдера():
    """Сайт (phantom-os.dev) і сервер (phantom-license.fly.dev) — різні
    походження, тож кожен запит Білдера з тілом іде через CORS-preflight. Метод,
    якого немає в `allow_methods`, браузер зріже ще до сервера: «Опублікувати»
    мовчки мертве, а в логах сервера — нічого. Звіряємо методи, якими справді
    ходить `venuesApi.ts`, зі списком у main.py."""
    if not VENUES_PY.exists():
        pytest.skip("venues.py ще не злито (S1): дверей закладів на сервері ще немає")
    api = API_TS.read_text(encoding="utf-8")
    used = set(re.findall(r"method: '([A-Z]+)'", api)) | {"GET"}
    main = (SERVER / "main.py").read_text(encoding="utf-8")
    m = re.search(r"CORSMiddleware,.*?allow_methods=\[([^\]]*)\]", main, re.S)
    assert m, "у main.py немає CORSMiddleware з allow_methods"
    allowed = set(re.findall(r'"([A-Z]+)"', m.group(1)))
    missing = used - allowed
    assert not missing, f"Білдер ходить методами {sorted(missing)}, а CORS у main.py їх не пускає"


# ── Редактор: чернетка і стан сторінки, виконані самим TS ─────────────────────
#
# `draft.ts` і `editorState.ts` імпортують сусідів без розширень (так їх збирає
# Vite), тож Node сам їх не завантажить: збираємо одним esbuild із залежностей
# сайту і виконуємо зібране.

DRAFT_TS = SITE / "src/components/builder/draft.ts"
EDITOR_STATE_TS = SITE / "src/components/builder/editorState.ts"
VENUE_EDITOR_TSX = SITE / "src/components/builder/VenueEditor.tsx"
BUILD_COPY_TS = SITE / "src/i18n/build.ts"


def _run_ts(entry: Path, body: str):
    node = shutil.which("node")
    if node is None:
        pytest.skip("немає node")
    esbuild = SITE / "node_modules/.bin/esbuild"
    if not esbuild.exists():
        pytest.skip("немає node_modules сайту (npm ci): esbuild зібрати нічим")
    assert entry.exists(), f"немає {entry.relative_to(SITE)}"
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "m.mjs"
        b = subprocess.run(
            [str(esbuild), str(entry), "--bundle", "--format=esm", "--platform=node", "--log-level=error", f"--outfile={out}"],
            capture_output=True, text=True, timeout=60,
        )
        assert b.returncode == 0, b.stderr
        script = f"const m = await import({json.dumps(out.as_uri())});\n{body}"
        run = subprocess.run([node, "--input-type=module", "-e", script], capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout.strip().splitlines()[-1])


DRAFT_CASES = """
const d = (over) => ({ ...m.emptyDraft('Cafe X'), ...over });
const pick = (b) => ({ problem: b.problem, fields: b.fields, blocks: b.page.blocks });
console.log(JSON.stringify({
  addressNoPoint: pick(m.pageFromDraft(d({ address: 'Stodolní 12' }))),
  pointNoAddress: pick(m.pageFromDraft(d({ point: '49.83, 18.28' }))),
  garbagePoint: pick(m.pageFromDraft(d({ address: 'Stodolní 12', point: 'біля мосту' }))),
  both: pick(m.pageFromDraft(d({ address: 'Stodolní 12', point: '49.83, 18.28' }))),
  neither: pick(m.pageFromDraft(d({}))),
  pasted: pick(m.pageFromDraft(d({
    title: 'Ca\\u00adfe X',
    about: 'Кава.\\r\\nОбіди\\u200b.',
    menus: [{ key: 'm', title: '', items: [{ key: 'i', id: '', name: '\\u200eЧай', price: '1', note: 'з\\tлимоном' }] }],
  }))),
  event: pick(m.pageFromDraft(d({
    events: [{ key: 'e', id: '', title: 'Джаз', starts: '2026-10-20T19:00', ends: '', note: '', price: '5' }],
  }))),
  eventBackwards: pick(m.pageFromDraft(d({
    events: [{ key: 'e', id: '', title: 'Джаз', starts: '2026-10-20T19:00', ends: '2026-10-20T18:00', note: '', price: '' }],
  }))),
}));
"""


def test_адреса_без_точки_і_точка_без_адреси_кажуть_правду():
    """Адреса без точки — «додайте точку», а не «поза мапою світу»; точка без
    адреси — помилка біля адреси, а не мовчки викинута точка."""
    got = _run_ts(DRAFT_TS, DRAFT_CASES)
    assert got["addressNoPoint"]["problem"] == "missing_point"
    assert got["addressNoPoint"]["fields"] == {"point": "missing"}
    assert got["pointNoAddress"]["problem"] == "missing_address"
    assert got["pointNoAddress"]["fields"] == {"address": "missing"}
    assert got["garbagePoint"]["problem"] == "bad_point"
    assert got["garbagePoint"]["fields"] == {"point": "point"}
    assert got["both"]["problem"] is None
    assert any(b["type"] == "address" for b in got["both"]["blocks"])
    assert got["neither"]["problem"] is None and got["neither"]["fields"] == {}


def test_вставлений_текст_чиститься_від_невидимих_знаків():
    """Мʼякий перенос, LRM, пробіл нульової ширини приходять із вставленим
    текстом; сервер їх не прийме, тож редактор прибирає їх сам."""
    got = _run_ts(DRAFT_TS, DRAFT_CASES)["pasted"]
    assert got["problem"] is None, got
    by = {b["type"]: b for b in got["blocks"]}
    assert by["title"]["text"] == "Cafe X"
    assert by["about"]["text"] == "Кава.\nОбіди."
    assert by["menu"]["items"][0]["name"] == "Чай"
    assert by["menu"]["items"][0]["note"] == "з лимоном"


def test_чернетка_редактора_складає_подію_у_формі_сервера():
    got = _run_ts(DRAFT_TS, DRAFT_CASES)
    ev = [b for b in got["event"]["blocks"] if b["type"] == "event"]
    assert got["event"]["problem"] is None, got["event"]
    assert ev == [{"type": "event", "id": ev[0]["id"], "title": "Джаз", "starts": "2026-10-20T19:00",
                   "price": "5", "token": "USDC"}]
    assert ev[0]["id"]
    assert got["eventBackwards"]["fields"] == {"eventEnds:e": "time"}


def test_повідомлення_про_точку_й_адресу_є_обома_мовами():
    copy = BUILD_COPY_TS.read_text(encoding="utf-8")
    assert copy.count("missing_point:") == 2 and copy.count("missing_address:") == 2
    assert "Додайте точку на мапі" in copy
    editor = VENUE_EDITOR_TSX.read_text(encoding="utf-8")
    assert "fields.point === 'missing'" in editor, "поле точки не розрізняє «порожньо» і «не координати»"
    assert "fields.address" in editor, "поле адреси не показує помилки"


VENUE = {"id": "v1", "name": "Cafe X", "published": True, "rev": 3, "rating": None,
         "page": {"v": 1, "blocks": [TITLE, ADDRESS, MENU]}}

EDITOR_CASES = """
const venue = %s;
const fresh = m.loaded(m.initialEditor(), venue, null);
const edited = m.edited(fresh, { ...fresh.draft, menus: [...fresh.draft.menus,
  { key: 'n', title: 'Напої', items: [{ key: 'k', id: '', name: 'Кава', price: 'abc', note: '' }] }] });
// Повторне завантаження того самого закладу (напр. після зміни мови) — правок не чіпає.
const reloaded = m.loaded(edited, { ...venue, rev: 4 }, null);
const unpub = m.requestFor('unpublish', edited.draft, venue);
const afterUnpub = m.saved(edited, { ...venue, published: false, rev: 4 }, 'unpublish');
const afterSave = m.saved(edited, { ...venue, rev: 4 }, 'save');
// Сесія скінчилась посеред правок: чернетку відкладено у вкладці й повернуто після входу.
const store = new Map();
const storage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
m.stashDraft(storage, 'v1', edited.draft);
const back = m.loaded(m.initialEditor(), venue, m.takeStash(storage, 'v1'));
const other = m.takeStash(storage, 'v2');
const once = m.takeStash(storage, 'v1');
const broken = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); }, removeItem: () => {} };
m.stashDraft(broken, 'v1', edited.draft);
console.log(JSON.stringify({
  reloadedMenus: reloaded.draft.menus.length, reloadedDirty: reloaded.dirty, reloadedRev: reloaded.venue.rev,
  unpub, afterUnpubMenus: afterUnpub.draft.menus.length, afterUnpubDirty: afterUnpub.dirty,
  afterUnpubPublished: afterUnpub.venue.published,
  afterSaveDirty: afterSave.dirty,
  backMenus: back.draft.menus.length, backDirty: back.dirty, backRestored: back.restored,
  other, once, brokenTake: m.takeStash(broken, 'v1'),
}));
""" % json.dumps(VENUE, ensure_ascii=False)


def test_редактор_не_губить_незбережених_правок():
    got = _run_ts(EDITOR_STATE_TS, EDITOR_CASES)
    # 1. Повторне завантаження закладу не перезаписує брудну чернетку.
    assert got["reloadedMenus"] == 2 and got["reloadedDirty"] is True and got["reloadedRev"] == 4
    # 4. «Зняти з публікації» шле останню ЗБЕРЕЖЕНУ сторінку, а не чернетку з «abc» у ціні.
    assert got["unpub"] == {"page": VENUE["page"], "published": False}
    assert got["afterUnpubMenus"] == 2 and got["afterUnpubDirty"] is True
    assert got["afterUnpubPublished"] is False
    assert got["afterSaveDirty"] is False
    # 6. Вихід посеред правок: чернетка повертається після входу — лише своєму закладу і раз.
    assert got["backMenus"] == 2 and got["backDirty"] is True and got["backRestored"] is True
    assert got["other"] is None and got["once"] is None
    assert got["brokenTake"] is None


def test_редактор_досяжний_зі_сторінки_build():
    """Стан редактора — не бібліотека збоку: ним живе `Editor` у Build.tsx, а
    завантаження закладу не перезапускається від зміни мови."""
    page = BUILD_TSX.read_text(encoding="utf-8")
    for fn in ("loaded(", "edited(", "saved(", "requestFor(", "stashDraft(", "takeStash("):
        assert fn in page, f"Build.tsx не викликає {fn}"
    m = re.search(r"getVenue\(id\)[\s\S]*?\}, \[([^\]]*)\]\);", page)
    assert m, "у Build.tsx немає ефекту з getVenue(id)"
    assert m.group(1).strip() == "id", f"завантаження закладу залежить від [{m.group(1)}], а не лише від id"
