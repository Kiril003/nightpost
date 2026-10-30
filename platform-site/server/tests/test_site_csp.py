"""Сайт віддається з Content-Security-Policy, і вона не розходиться з кодом.

Токен сесії кабінету лежить у localStorage: будь-який чужий скрипт на сторінці
прочитав би його одним рядком. Захист від цього — CSP без 'unsafe-inline' і
'unsafe-eval' для скриптів. Але CSP ламається тихо: забудь хост у connect-src —
і кабінет перестає входити без жодної помилки на сервері; забудь `data:` у
font-src — і зникають шрифти. Тому сторож міряє обидва боки:

  1. обидва способи розгортання (Netlify і nginx) несуть ОДНАКОВУ політику;
  2. скриптам заборонено inline і eval, а код сайту не має конструкцій, яким
     це знадобилося б (dangerouslySetInnerHTML, eval, new Function);
  3. ліцензійний сервер, до якого сайт ходить за замовчуванням, дозволений у
     connect-src — інакше кнопка завантаження й вхід у кабінет мовчки мертві.
"""
import re
from pathlib import Path

SITE_DIR = Path(__file__).resolve().parents[2]
NETLIFY = SITE_DIR / "netlify.toml"
NGINX = SITE_DIR / "scripts/phantom-os-headers.conf"
META = SITE_DIR / "src/lib/meta.ts"
SRC = SITE_DIR / "src"


def _netlify_csp() -> str:
    m = re.search(r'Content-Security-Policy\s*=\s*"([^"]+)"', NETLIFY.read_text(encoding="utf-8"))
    assert m, "у netlify.toml немає Content-Security-Policy"
    return m.group(1)


def _nginx_csp() -> str:
    m = re.search(r'add_header Content-Security-Policy "([^"]+)"', NGINX.read_text(encoding="utf-8"))
    assert m, "у nginx-заголовках немає Content-Security-Policy"
    return m.group(1)


def _directives(csp: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for part in csp.split(";"):
        words = part.split()
        if words:
            out[words[0]] = words[1:]
    return out


def test_netlify_і_nginx_віддають_одну_й_ту_саму_політику():
    assert _directives(_netlify_csp()) == _directives(_nginx_csp())


def test_скриптам_заборонено_inline_і_eval():
    d = _directives(_netlify_csp())
    assert d.get("script-src") == ["'self'"], d.get("script-src")
    assert d.get("object-src") == ["'none'"]
    assert d.get("frame-ancestors") == ["'none'"]
    assert d.get("base-uri") == ["'self'"]
    for key in ("script-src", "default-src", "style-src"):
        for bad in ("'unsafe-inline'", "'unsafe-eval'", "*"):
            assert bad not in d.get(key, []), f"{key} дозволяє {bad}"


def test_код_сайту_не_потребує_inline_скриптів():
    guilty = []
    for path in sorted(SRC.rglob("*.ts*")):
        text = path.read_text(encoding="utf-8")
        for bad in ("dangerouslySetInnerHTML", "eval(", "new Function(", "innerHTML ="):
            if bad in text:
                guilty.append(f"{path.relative_to(SITE_DIR)}: {bad}")
    assert not guilty, guilty
    html = (SITE_DIR / "index.html").read_text(encoding="utf-8")
    inline = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>", html)
    assert not inline, f"index.html має inline-скрипт: {inline}"


def test_ліцензійний_сервер_дозволений_у_connect_src():
    m = re.search(r"VITE_LICENSE_SERVER \?\? '([^']+)'", META.read_text(encoding="utf-8"))
    assert m, "не знайшли адресу ліцензійного сервера в meta.ts — сторож міряє не те"
    server = m.group(1).rstrip("/")
    assert server in _directives(_netlify_csp()).get("connect-src", []), (
        f"{server} немає в connect-src — сайт не зможе спитати про збірки й увійти в кабінет"
    )


def test_адреса_сервера_на_oracle_дозволена_в_обох_розгортаннях():
    for name, csp in (("netlify.toml", _netlify_csp()), ("phantom-os-headers.conf", _nginx_csp())):
        assert "https://api.phantom-os.dev" in _directives(csp).get("connect-src", []), (
            f"{name}: без api.phantom-os.dev у connect-src кабінет мовчки мертвий після переїзду"
        )
