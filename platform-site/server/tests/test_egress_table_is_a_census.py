"""Сторінка безпеки обіцяє: «якщо виклику до нас немає в цій таблиці, код його
не робить». Обіцянка або тримається, або її треба зняти.

Таблиця стояла з двох боків неправдива: телефон ходив ще й у /api/deactivate,
/api/policy, /api/download/link і в три адреси сховка, яких у ній не було, — і
водночас вона мала рядок «офлайн-пакети», хоч PackFetcher ніхто не викликає, а
PACKS_BASE_URL порожній. Обидва боки — той самий дефект: перелік, який ніхто не
звіряє з кодом, розходиться з ним мовчки й саме там, де його читають найуважніше.

Три перевірки, і жодна не вірить копірайту на слово:
  1. таблиця не сміє називати адресу, якої цей сервер не роздає;
  2. обидві мови мусять називати ОДНІ Й ТІ САМІ адреси;
  3. кожна адреса, у яку телефон справді стукає, мусить бути в таблиці.
"""
import re
import sys
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

SITE_DIR = SERVER_DIR.parent
SECURITY_COPY = SITE_DIR / "src/i18n/security.ts"

#: Гілка, з якої збирають те, що ставлять людям.
SHIPPING_BRANCH = "integration/beta"


def _branch(tree: Path) -> str | None:
    """На якій гілці стоїть це дерево. Без git у підпроцесі — читаємо HEAD."""
    dot = tree / ".git"
    if dot.is_file():  # робоче дерево: файл із дорогою до справжнього gitdir
        raw = dot.read_text(encoding="utf-8").partition("gitdir:")[2].strip()
        if not raw:
            return None
        gitdir = Path(raw)
        if not gitdir.is_absolute():
            gitdir = (tree / gitdir).resolve()
    elif dot.is_dir():
        gitdir = dot
    else:
        return None
    try:
        head = (gitdir / "HEAD").read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return head.partition("refs/heads/")[2] or None


def _phone_tree() -> tuple[Path | None, str]:
    """Дерево телефона, яке справді їде людям, — і чому саме воно.

    Сторож дивився в `phantom-companion` намертво. Те дерево стоїть на
    `rebuild/mobile-day1`, а збірку людям збирають з `integration/beta` у
    сусідній теці: перепис, яким тримається найсильніша обіцянка сайту,
    звірявся з деревом, якого ніхто не ставить. Сьогодні обидва дерева дають
    ті самі вісім адрес, тож таблиця не бреше — але розійдись вони завтра,
    сторож мовчав би саме там, де його читають найуважніше.

    Тому дерево шукається ЗА ГІЛКОЮ, а не за назвою теки: перейменують теку —
    знайдемо, переїде ствол — доведеться назвати це тут свідомо.
    """
    siblings = sorted(p for p in SITE_DIR.parent.glob("phantom-companion*") if p.is_dir())
    for tree in siblings:
        if _branch(tree) == SHIPPING_BRANCH:
            return tree, f"{tree.name} ({SHIPPING_BRANCH})"
    for tree in siblings:
        if (tree / "app/src/main").is_dir():
            return tree, f"{tree.name} (гілки {SHIPPING_BRANCH} не знайшли — беремо, що є)"
    return None, "жодного дерева телефона поряд немає"


PHONE, PHONE_WHY = _phone_tree()

#: Адреси, які нас цікавлять: наші, а не чужі хости.
PATH = re.compile(r"/(?:api|relay|truth)/[a-z0-9/_-]+")


def _table_paths() -> dict[str, set[str]]:
    """Адреси, названі в колонці «Виклик», окремо для кожної мови.

    Розбираємо саме `call:` — це та колонка, яку читає рецензент із дампом
    трафіку в руках. Адреса, згадана лише в поясненні, обіцянки не тримає.
    """
    text = SECURITY_COPY.read_text(encoding="utf-8")
    out: dict[str, set[str]] = {}
    for lang in ("en", "uk"):
        start = text.index(f"const {lang}: SecurityCopy")
        end = text.index("egress:", start)
        block = text[end : text.index("footnote:", end)]
        out[lang] = {
            m.group(0).rstrip("/") for line in block.splitlines() if "call:" in line for m in PATH.finditer(line)
        }
    return out


def _helius_routes() -> set[str]:
    source = (SERVER_DIR / "helius_hook.py").read_text(encoding="utf-8")
    return {
        p
        for p in re.findall(r'@routes\.(?:get|post)\("([^"]+)"', source)
        if p != "/api/solana/hook"
    }


def _brain_routes() -> set[str]:
    """Маршрути розуму без ключа (brain.py): `APIRouter(prefix=…)` + `@routes.get/post("…")`.

    Не в main.py, тож `@app.…` їх не бачить. Параметр шляху (`/{model}`) і Gemini-вий
    суфікс дії (`:streamGenerateContent`) таблиця називає повністю, а тут обидва
    зрізаються, як зрізається `{cluster}` у Solana.
    """
    source = (SERVER_DIR / "brain.py").read_text(encoding="utf-8")
    prefix = re.search(r'APIRouter\(prefix="([^"]+)"\)', source)
    assert prefix, "brain.py більше не оголошує APIRouter(prefix=…) — розбирач маршрутів зламався"
    live = (SERVER_DIR / "brain_live.py").read_text(encoding="utf-8")
    return {
        re.sub(r":[A-Za-z]+$", "", re.sub(r"/\{[^}]+\}", "", prefix.group(1) + path))
        for path in re.findall(r'@routes\.(?:get|post)\("([^"]+)"', source)
    } | {prefix.group(1) + path for path in re.findall(r'@router\.post\("([^"]+)"', live)}


def test_таблиця_не_називає_адрес_яких_сервер_не_роздає():
    """Вигадана адреса гірша за відсутню: рецензент піде її шукати, не знайде
    й перестане вірити решті рядків."""
    # Живий канал сховку — websocket, і він теж адреса, у яку стукає телефон.
    # Параметр шляху (`/truth/area/{area}`) таблиця називає без нього.
    routes = {
        re.sub(r"/\{[^}]+\}", "", r)
        for r in re.findall(
            r'@app\.(?:get|post|put|patch|delete|websocket)\("([^"]+)"',
            (SERVER_DIR / "main.py").read_text(encoding="utf-8"),
        )
    }
    assert routes, "не знайшли жодного маршруту — перевірка міряє не те"
    routes |= _brain_routes() | _helius_routes()

    for lang, paths in _table_paths().items():
        assert paths, f"у таблиці {lang} немає жодної адреси — обіцянку нічим тримати"
        invented = paths - routes
        assert not invented, f"таблиця ({lang}) називає адреси, яких сервер не має: {sorted(invented)}"


def test_обидві_мови_називають_однакові_адреси():
    """Рядок, доданий лише в англійську, — це неправда українською, і навпаки.
    Одна таблиця, дві мови; переклад тут не має свободи щодо фактів."""
    paths = _table_paths()
    assert paths["en"] == paths["uk"], (
        f"розбіжність мов: лише en {sorted(paths['en'] - paths['uk'])}, "
        f"лише uk {sorted(paths['uk'] - paths['en'])}"
    )


def _phone_paths() -> set[str]:
    """Адреси на НАШОМУ хості, у які стукає застосунок.

    Дві половини: те, що клеїться до BuildConfig.LICENSE_BASE_URL, і сховок,
    чий baseUrl — той самий LICENSE_BASE_URL (це тут же й доводиться).
    """
    found: set[str] = set()
    # Краудправда ходить на той самий хост через константи TruthWire.
    truth: dict[str, str] = {}
    for wire in PHONE.glob("core-truth/src/main/**/TruthWire.kt"):
        for name, path in re.findall(r'const val ([A-Z]+) = "(/truth/[a-z/]+)"', wire.read_text(encoding="utf-8")):
            truth[name] = path.rstrip("/")
    # Тільки `<модуль>/src/main`: у дереві телефона лежать ще й `build/` з
    # копіями та `.claude/worktrees/` з цілими другими деревами, і обхід усього
    # підряд і повільний, і рахував би ті самі рядки по кілька разів.
    for module in sorted(PHONE.glob("*/src/main")):
        for kt in module.rglob("*.kt"):
            source = kt.read_text(encoding="utf-8", errors="replace")
            found.update(re.findall(r"LICENSE_BASE_URL\}(/[a-z0-9/_-]+)", source))
            if kt.name == "PeerRelayClient.kt":
                # Цифри теж: `/relay/v2/batch` розбирач із [a-z]+ різав до
                # `/relay/v`, і сторож червонів на адресі, якої не існує.
                found.update(re.findall(r"\$baseUrl(/relay/[a-z0-9/]+)", source))
            if kt.name == "PeerRelayLive.kt":
                found.update(re.findall(r'"(/relay/[a-z0-9/]+)"', source))
            for const in re.findall(r"TruthWire\.([A-Z]+)\b", source):
                if const in truth:
                    found.add(truth[const])
    return found


@pytest.mark.skipif(PHONE is None, reason=f"звіряти таблицю нема з чим: {PHONE_WHY}")
def test_кожна_адреса_телефона_є_в_таблиці():
    """Той бік, яким таблиця відстала: код додав адресу, копірайт не дізнався."""
    phone = _phone_paths()
    assert len(phone) >= 5, (
        f"у {PHONE_WHY} знайшли лише {sorted(phone)} — "
        "розбирач адрес зламався, а не телефон замовк"
    )

    for lang, paths in _table_paths().items():
        missing = phone - paths
        assert not missing, (
            f"застосунок ({PHONE_WHY}) ходить туди, чого таблиця ({lang}) не називає: "
            f"{sorted(missing)} — або допишіть рядок, або зніміть обіцянку "
            "«якщо рядка немає, код такого не робить»"
        )


@pytest.mark.skipif(PHONE is None, reason=f"звіряти нема з чим: {PHONE_WHY}")
def test_сховок_живе_на_тому_самому_хості_що_й_ліцензія():
    """Заголовок «один наш сервер» тримається рівно на цьому рядку. Щойно
    сховку дадуть власну адресу, їх стане два, і заголовок збреше."""
    container = (PHONE / "app/src/main/java/local/phantom/companion/data/AppContainer.kt").read_text(
        encoding="utf-8", errors="replace"
    )
    gate = container[container.index("PeerRelayGate.open(") :][:400]
    assert "LICENSE_BASE_URL" in gate, "сховок більше не на хості ліцензії — заголовок таблиці треба міняти"


def test_кожен_маршрут_solana_має_рядок_у_таблиці():
    """Телефон складає адреси Solana з `baseUrl` у core-solana, а не з
    LICENSE_BASE_URL, тож перепис телефона вище їх не бачить. Спонсор і проксі
    RPC так і жили без рядка. Тут — з боку сервера: маршрут /api/solana/* без
    рядка в таблиці червоний, хоч хто б його кликав."""
    solana = {
        re.sub(r"/\{[^}]+\}", "", r)
        for r in re.findall(
            r'@app\.(?:get|post|put|patch|delete)\("(/api/solana/[^"]+)"',
            (SERVER_DIR / "main.py").read_text(encoding="utf-8"),
        )
    }
    assert len(solana) >= 7, f"знайшли лише {sorted(solana)} — розбирач маршрутів зламався"
    for lang, paths in _table_paths().items():
        missing = solana - paths
        assert not missing, f"сервер роздає {sorted(missing)}, а таблиця ({lang}) їх не називає"


def test_кожен_маршрут_brain_має_рядок_у_таблиці():
    """Розум без ключа пускає розмову користувача через наш сервер до Google.
    Сторінка безпеки досі казала: «ваше питання йде до Google під вашим ключем,
    а не через нас». Маршрут /api/brain/* без рядка в таблиці — червоний, хоч хто б
    його кликав: телефон складає адресу з `$root/api/brain`, а не з
    LICENSE_BASE_URL, тож перепис телефона вище його не бачить."""
    brain = _brain_routes()
    assert brain == {"/api/brain/attest", "/api/brain/live-token", "/api/brain/status", "/api/brain/v1beta/models"}, (
        f"маршрути brain.py змінилися: {sorted(brain)} — оновіть і цей сторож, і рядок у таблиці"
    )
    for lang, paths in _table_paths().items():
        missing = brain - paths
        assert not missing, f"сервер роздає {sorted(missing)}, а таблиця ({lang}) їх не називає"
