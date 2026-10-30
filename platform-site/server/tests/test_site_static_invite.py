"""Сторінка запрошення /i/ і assetlinks.json: статичні файли, що їдуть у dist/.

Текст «поділитись» у месенджері несе посилання https://phantom-os.dev/i/#<PH2>.
Без цих файлів сайт на /i/ віддає головну (SPA-підміна), а на
/.well-known/assetlinks.json — HTML зі статусом 200 (переміряно 08.10:
`content-type: text/html`). Android читає саме цей JSON, щоб відкривати
https-посилання одразу в застосунку; HTML замість JSON — і перевірка падає
мовчки, посилання відкриває браузер, людина бачить головну без запрошення.

Сторож міряє пʼять речей:
  1. файли лежать у public/ і після збірки — у dist/ байт у байт;
  2. сторінка живе під CSP сайту (script-src 'self', style-src 'self'): жодного
     inline-скрипта, inline-стилю чи on*-обробника, усі ресурси — власні;
  3. код із частини після # не йде в мережу: у скрипті немає fetch/XHR/beacon,
     жодної чужої адреси, а сторінку заборонено індексувати;
  4. assetlinks.json називає обидва пакети, відбитки — 32 байти через
     двокрапку, і вони збігаються з ключами, які СПРАВДІ підписують APK
     (де ключ чи APK є поруч);
  5. сама дорога: Netlify і nginx віддають справжній файл раніше за підміну
     на index.html — інакше /i/ і assetlinks існували б лише в git.
"""
import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

SITE_DIR = Path(__file__).resolve().parents[2]
PUBLIC = SITE_DIR / "public"
DIST = SITE_DIR / "dist"
NETLIFY = SITE_DIR / "netlify.toml"
NGINX_SITE = SITE_DIR / "scripts/phantom-os-site.conf"

PAGE = "i/index.html"
STYLE = "i/invite.css"
SCRIPT = "i/invite.js"
ASSETLINKS = ".well-known/assetlinks.json"
SHIPPED = (PAGE, STYLE, SCRIPT, ASSETLINKS)

PACKAGES = {"local.phantom.companion", "local.phantom.companion.debug"}
RELATION = "delegate_permission/common.handle_all_urls"
FINGERPRINT = re.compile(r"^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$")

#: Ключ, під яким перемикач мови сайту зберігає вибір (src/i18n/index.tsx).
LANG_KEY = "phantom-lang"
#: Правило власника: не більше сорока слів у блоці.
MAX_WORDS = 40

#: Те, чим Gradle СПРАВДІ підписує debug (app/build.gradle.kts:381) —
#: НЕ ~/.android/debug.keystore, той ключ не йде в жоден APK.
DEBUG_KEYSTORE = SITE_DIR.parent / "phantom-companion/app/debug.keystore"
DEBUG_KEYSTORE_PASSWORD = "phantom"


def _read(rel: str) -> str:
    return (PUBLIC / rel).read_text(encoding="utf-8")


# 1. Файли існують


@pytest.mark.parametrize("rel", SHIPPED)
def test_файл_лежить_у_public(rel):
    assert (PUBLIC / rel).is_file(), f"public/{rel} немає — на /{rel} сайт віддасть головну"


# 2. Під CSP сайту


def test_сторінка_запрошення_не_має_inline_скриптів_і_стилів():
    html = _read(PAGE)
    inline = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>", html)
    assert not inline, f"inline-скрипт під script-src 'self' мовчки не виконається: {inline}"
    assert "<style" not in html, "блок <style> під style-src 'self' мовчки не застосується"
    assert not re.search(r"\sstyle\s*=", html), "атрибут style= під style-src 'self' мовчки не застосується"
    assert not re.search(r"\son[a-z]+\s*=", html, re.I), "on*-обробник — це inline-скрипт"


def test_усі_ресурси_сторінки_власні():
    html = _read(PAGE)
    refs = re.findall(r'<(?:script|link)\b[^>]*?\b(?:src|href)="([^"]+)"', html)
    foreign = [r for r in refs if re.match(r"^(?:[a-z][a-z0-9+.-]*:)?//", r, re.I)]
    assert not foreign, f"чужі ресурси CSP зріже: {foreign}"
    assert f"/{SCRIPT}" in refs, "сторінка не підключає власний скрипт"
    assert f"/{STYLE}" in refs, "сторінка не підключає власний стиль"
    assert "<form" not in html and "<iframe" not in html


# 3. Код не йде в мережу


def test_код_із_адреси_читається_лише_на_пристрої():
    js = _read(SCRIPT)
    assert "location.hash" in js, "скрипт має читати код із частини після #"
    assert "PH2" in js, "скрипт має впізнавати код PH2 — інакше кнопка «відкрити» веде в нікуди"
    for bad in ("fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "EventSource", "new Image", "document.cookie"):
        assert bad not in js, f"{bad}: код запрошення не має жодної дороги в мережу"
    # Частину після # складає чужий пристрій: у DOM вона потрапляє лише текстом.
    assert "innerHTML" not in js and "insertAdjacentHTML" not in js
    foreign = [u for u in re.findall(r"https?://[^\s'\"`]+", js) if not u.startswith("https://phantom-os.dev")]
    assert not foreign, f"чужі адреси в скрипті запрошення: {foreign}"


def test_сторінку_запрошення_заборонено_індексувати():
    html = _read(PAGE)
    assert re.search(r'<meta\s+name="robots"\s+content="[^"]*noindex', html), (
        "особисте посилання не має потрапляти в пошук"
    )


def test_сторінка_шанує_вибір_мови_сайту():
    assert re.search(r'<html\b[^>]*\blang="en"', _read(PAGE)), "без скрипта — мова сайту за замовчуванням, англійська"
    assert LANG_KEY in _read(SCRIPT), f"скрипт має читати {LANG_KEY!r} — той самий ключ, що й перемикач сайту"


def test_кожний_блок_тексту_не_довший_за_сорок_слів():
    html = _read(PAGE)
    too_long = []
    for m in re.finditer(r"<(h1|h2|p|li|summary|noscript)\b[^>]*>(.*?)</\1>", html, re.S):
        words = re.sub(r"<[^>]+>", " ", m.group(2)).split()
        if len(words) > MAX_WORDS:
            too_long.append((m.group(1), len(words)))
    assert not too_long, too_long


# 4. assetlinks.json


def _assetlinks() -> list[dict]:
    data = json.loads(_read(ASSETLINKS))
    assert isinstance(data, list) and data, "assetlinks.json — непорожній список записів"
    return data


def _fingerprints(package: str) -> list[str]:
    for entry in _assetlinks():
        if entry["target"]["package_name"] == package:
            return entry["target"]["sha256_cert_fingerprints"]
    raise AssertionError(f"у assetlinks.json немає пакета {package}")


def test_assetlinks_називає_обидва_пакети():
    data = _assetlinks()
    assert {e["target"]["package_name"] for e in data} == PACKAGES
    for entry in data:
        assert entry["relation"] == [RELATION], entry
        assert entry["target"]["namespace"] == "android_app", entry


def test_відбитки_це_32_байти_через_двокрапку():
    for package in sorted(PACKAGES):
        prints = _fingerprints(package)
        assert prints, f"{package}: без відбитка Android не повірить жодному APK"
        for p in prints:
            assert FINGERPRINT.match(p), f"{package}: {p!r} — не 32 байти hex через двокрапку у верхньому регістрі"


def _debug_keystore_fingerprint() -> str:
    out = subprocess.run(
        ["keytool", "-list", "-v", "-keystore", str(DEBUG_KEYSTORE), "-storepass", DEBUG_KEYSTORE_PASSWORD],
        capture_output=True, text=True, check=True,
    ).stdout
    m = re.search(r"SHA256:\s*((?:[0-9A-F]{2}:){31}[0-9A-F]{2})", out)
    assert m, "keytool не надрукував SHA256"
    return m.group(1)


# У хмарі (CLAUDE_CODE_REMOTE=true) cloud-setup.sh народжує СВІЙ app/debug.keystore
# (навмисно інший ключ, scripts/cloud-setup.sh: «щоб APK з хмари не став оновленням
# поверх ноутбучної debug-збірки») — він жодного телефона не підписує й звіряти з
# ним — міряти не те.
@pytest.mark.skipif(
    os.environ.get("CLAUDE_CODE_REMOTE") == "true" or not DEBUG_KEYSTORE.is_file() or shutil.which("keytool") is None,
    reason="debug.keystore, що підписує телефони, або keytool поруч немає — звіряти нема з чим",
)
def test_відбиток_debug_це_ключ_що_підписує_debug_apk():
    assert _debug_keystore_fingerprint() in _fingerprints("local.phantom.companion.debug")


def _apksigner() -> Path | None:
    roots = [Path(p) for p in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")) if p]
    roots.append(Path.home() / "Android/Sdk")
    for root in roots:
        found = sorted((root / "build-tools").glob("*/apksigner"))
        if found:
            return found[-1]
    return None


def _release_apk() -> Path | None:
    found = sorted(
        Path.home().glob("phantom_ai/*/phantom-companion/app/build/outputs/apk/field/release/app-field-*-release.apk"),
        key=lambda p: p.stat().st_mtime,
    )
    return found[-1] if found else None


RELEASE_APK = _release_apk()
APKSIGNER = _apksigner()


@pytest.mark.skipif(RELEASE_APK is None or APKSIGNER is None, reason="релізного APK або apksigner поруч немає — звіряти нема з чим")
def test_відбиток_релізу_це_ключ_що_підписує_опублікований_apk():
    out = subprocess.run(
        [str(APKSIGNER), "verify", "--print-certs", str(RELEASE_APK)], capture_output=True, text=True, check=True
    ).stdout
    m = re.search(r"SHA-256 digest:\s*([0-9a-f]{64})", out)
    assert m, out
    digest = m.group(1).upper()
    colons = ":".join(digest[i : i + 2] for i in range(0, 64, 2))
    assert colons in _fingerprints("local.phantom.companion"), f"{RELEASE_APK.name} підписано {colons}"


# 5. Після збірки й дорога до файлу


@pytest.mark.skipif(not DIST.is_dir(), reason="dist/ немає — спершу npm run build")
@pytest.mark.parametrize("rel", SHIPPED)
def test_після_збірки_файл_у_dist_байт_у_байт(rel):
    assert (DIST / rel).is_file(), f"dist/{rel} немає — Vite не переніс public/{rel}"
    assert (DIST / rel).read_bytes() == (PUBLIC / rel).read_bytes()


def test_netlify_віддає_справжній_файл_раніше_за_підміну():
    toml = NETLIFY.read_text(encoding="utf-8")
    block = re.search(r'\[\[redirects\]\]\s*\n\s*from = "/\*"(.*?)(?=\n\[\[|\Z)', toml, re.S)
    assert block, "у netlify.toml немає SPA-підміни /* → /index.html"
    assert "force" not in block.group(1), "force = true підмінить і /i/, і assetlinks.json на головну"


def test_nginx_віддає_справжній_файл_раніше_за_підміну():
    conf = NGINX_SITE.read_text(encoding="utf-8")
    m = re.search(r"location / \{(.*?)\}", conf, re.S)
    assert m, "у nginx немає location /"
    assert re.search(r"try_files\s+\$uri\s+\$uri/\s+/index\.html", m.group(1)), (
        "nginx має спершу шукати файл і теку ($uri/ дає /i/ → /i/index.html), а лише потім підміняти"
    )


# 6. Поведінка в живому JS: «Відкрити» без фолбеку і ПК-конверт
#
# Файли вище перевіряють ТЕКСТ invite.js (regex). Тут його СПРАВДІ виконує
# Node у мінімальному DOM-заглушці — інакше тест, що просто шукає рядок
# "intent://" чи "phantom-debug", зелений і коли код насправді нічого не
# робить. Два білди — дві схеми (release "phantom", debug "phantom-debug",
# build.gradle.kts:430); на апараті лише з однією з них тап у чужу схему
# не відкриває нічого і нікуди не падає. "Відкрити" мусить пробувати обидві
# й лише тоді йти на /download.

NODE = shutil.which("node")

_HARNESS_IDS = [
    "open", "get", "code", "pc", "pc-code", "pc-copy", "copy", "card",
    "invite", "empty", "legacy", "invite-title", "invite-sub",
    "code-summary", "code-hint", "pc-title", "pc-sub", "pc-code-summary",
    "pc-code-hint", "empty-title", "empty-sub", "get-empty",
    "legacy-title", "legacy-sub", "get-legacy", "privacy", "title",
]

#: Мінімальний DOM: без справжнього браузера, лише те, що invite.js чіпає.
_HARNESS_TEMPLATE = r"""
'use strict';
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const hash = process.argv[3] || '';
const mode = process.argv[4] || '';

const ids = __IDS__;
const elements = {};
function makeEl(id) {
  return {
    id, hidden: true, textContent: '', _attrs: {}, _handlers: {},
    setAttribute(k, v) { this._attrs[k] = v; },
    getAttribute(k) { return k in this._attrs ? this._attrs[k] : null; },
    addEventListener(ev, fn) { (this._handlers[ev] = this._handlers[ev] || []).push(fn); },
    removeEventListener() {},
  };
}
ids.forEach((id) => { elements[id] = makeEl(id); });
elements['get']._attrs.href = '/download';
elements['get-empty']._attrs.href = '/download';
elements['get-legacy']._attrs.href = '/download';

const docHandlers = {};
let visibilityState = 'visible';
// Node 21+ уже має свої читань-лише global.navigator/location/window
// (fetch API); перевизначаємо через defineProperty, бо просте `global.x = …`
// на них кидає TypeError.
Object.defineProperty(global, 'document', {
  configurable: true,
  value: {
    getElementById(id) { return elements[id]; },
    addEventListener(ev, fn) { (docHandlers[ev] = docHandlers[ev] || []).push(fn); },
    removeEventListener(ev, fn) {
      if (docHandlers[ev]) docHandlers[ev] = docHandlers[ev].filter((h) => h !== fn);
    },
    documentElement: {},
    title: '',
    get visibilityState() { return visibilityState; },
  },
});

const timers = [];
global.setTimeout = (fn) => { timers.push(fn); return timers.length; };
global.localStorage = { getItem() { return null; } };
Object.defineProperty(global, 'navigator', {
  configurable: true, value: { languages: ['en'], language: 'en' },
});

const hrefLog = [];
const loc = {
  hash, origin: 'https://phantom-os.dev', _href: '',
  get href() { return this._href; },
  set href(v) { hrefLog.push(v); this._href = v; },
};
Object.defineProperty(global, 'location', { configurable: true, value: loc });
Object.defineProperty(global, 'window', { configurable: true, value: { addEventListener() {} } });
global.atob = atob;

eval(src);

function fireVisibilityHidden() {
  visibilityState = 'hidden';
  (docHandlers['visibilitychange'] || []).forEach((h) => h());
}
function drainAllTimers() {
  while (timers.length) { const fn = timers.shift(); fn(); }
}

if (mode === 'click-open-no-app') {
  (elements.open._handlers.click || []).forEach((h) => h({ preventDefault() {} }));
  drainAllTimers();
} else if (mode === 'click-open-app-opens-after-first-scheme') {
  (elements.open._handlers.click || []).forEach((h) => h({ preventDefault() {} }));
  fireVisibilityHidden();
  drainAllTimers();
}

console.log(JSON.stringify({
  hidden: Object.fromEntries(['invite', 'pc', 'empty', 'legacy'].map((id) => [id, elements[id].hidden])),
  pcCode: elements['pc-code'].textContent,
  hrefLog,
}));
"""


def _run_invite(hash_value: str, mode: str = "") -> dict:
    harness = _HARNESS_TEMPLATE.replace("__IDS__", json.dumps(_HARNESS_IDS))
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fh:
        fh.write(harness)
        harness_path = fh.name
    try:
        out = subprocess.run(
            [NODE, harness_path, str(PUBLIC / SCRIPT), hash_value, mode],
            capture_output=True, text=True, check=True,
        )
    finally:
        os.unlink(harness_path)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def _pc_envelope_code() -> str:
    # Дзеркало looksLikePcEnvelope/PeerInvite: base64url без двокрапок, перші
    # декодовані байти — "PHI". Вміст після них не має значення.
    raw = b"PHI" + bytes(range(13))
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


PC_CODE = _pc_envelope_code()
PH2_CODE = "PH2:abcDEF123_-:1a2b3c"


@pytest.mark.skipif(NODE is None, reason="node поруч немає — живу поведінку invite.js не перевірити")
def test_пк_конверт_отримує_власний_екран_а_не_порожній():
    result = _run_invite("#" + PC_CODE)
    assert result["hidden"] == {"invite": True, "pc": False, "empty": True, "legacy": True}, result
    assert result["pcCode"] == PC_CODE


@pytest.mark.skipif(NODE is None, reason="node поруч немає — живу поведінку invite.js не перевірити")
def test_ph2_і_сміття_досі_не_плутаються_з_пк_конвертом():
    assert _run_invite("#" + PH2_CODE)["hidden"]["invite"] is False
    assert _run_invite("#not-a-code-at-all")["hidden"]["empty"] is False


@pytest.mark.skipif(NODE is None, reason="node поруч немає — живу поведінку invite.js не перевірити")
def test_кнопка_відкрити_пробує_обидві_схеми_і_лише_тоді_йде_на_завантаження():
    result = _run_invite("#" + PH2_CODE, "click-open-no-app")
    assert result["hrefLog"] == [
        "phantom://invite/" + PH2_CODE,
        "phantom-debug://invite/" + PH2_CODE,
        "/download",
    ], result


@pytest.mark.skipif(NODE is None, reason="node поруч немає — живу поведінку invite.js не перевірити")
def test_кнопка_відкрити_зупиняється_щойно_застосунок_відповів():
    result = _run_invite("#" + PH2_CODE, "click-open-app-opens-after-first-scheme")
    # Застосунок «відкрився» (visibilitychange → hidden) між першою і другою
    # спробою: другу схему й /download чіпати не можна — інакше тап по вже
    # відкритому застосунку все одно зіштовхує в /download.
    assert result["hrefLog"] == ["phantom://invite/" + PH2_CODE], result
