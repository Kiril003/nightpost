"""Кабінет знає кожне поле, яке сервер віддає про ліцензію й пристрій.

Сервер навчився віддавати `last_seen_at` (остання підписана звірка пристрою),
`provider` (звідки ліцензія) і `revoked_reason` — а кабінет про них не знав,
тож «Останній звʼязок» і «Покупки» не мали з чого малюватись. Розбіжність
мовчазна: TypeScript не бачить JSON сервера, а сервер не бачить TypeScript.

Сторож бере ключі з `_license_view` у main.py (і ключі пристрою в ньому ж) і
вимагає, щоб кожен був оголошений у `License` / `Device` у
src/lib/account-api.ts. І навпаки: машинні причини відкликання та джерела,
які сервер справді пише, мають людську назву в src/i18n/cabinet.ts —
інакше кабінет показав би сире `play_canceled`.
"""
import re
from pathlib import Path

SERVER = Path(__file__).resolve().parents[1]
SITE = SERVER.parent
MAIN = SERVER / "main.py"
API_TS = SITE / "src/lib/account-api.ts"
CABINET_TS = SITE / "src/i18n/cabinet.ts"


def _view_source() -> str:
    text = MAIN.read_text(encoding="utf-8")
    start = text.index("def _license_view(")
    end = text.index("\n\n\n", start)
    return text[start:end]


def _server_keys() -> tuple[set[str], set[str]]:
    src = _view_source()
    dev_block = src[src.index("devices = [") : src.index("for d in store.active_activations")]
    device = set(re.findall(r'"([a-z_]+)":', dev_block))
    lic_block = src[src.index("return {") :]
    licence = set(re.findall(r'^\s+"([a-z_]+)":', lic_block, re.M))
    assert {"fingerprint", "activated_at"} <= device, device
    assert {"id", "tier", "devices"} <= licence, licence
    return device, licence


def _ts_fields(name: str) -> set[str]:
    text = API_TS.read_text(encoding="utf-8")
    m = re.search(r"export interface %s \{(.*?)\n\}" % name, text, re.S)
    assert m, f"у account-api.ts немає interface {name}"
    body = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
    return set(re.findall(r"^\s+([a-z_]+)\??:", body, re.M))


def test_кабінет_оголошує_кожне_поле_пристрою():
    device, _ = _server_keys()
    missing = device - _ts_fields("Device")
    assert not missing, f"сервер віддає про пристрій {sorted(missing)}, а кабінет їх не знає"


def test_кабінет_оголошує_кожне_поле_ліцензії():
    _, licence = _server_keys()
    missing = licence - _ts_fields("License")
    assert not missing, f"сервер віддає про ліцензію {sorted(missing)}, а кабінет їх не знає"


def test_кожне_джерело_й_причина_мають_людську_назву():
    main = MAIN.read_text(encoding="utf-8")
    providers = set(re.findall(r'provider="([a-z]+)"', main))
    providers |= set(re.findall(r'provider="([a-z]+)"', (SERVER / "providers.py").read_text(encoding="utf-8")))
    reasons = set(re.findall(r'store\.revoke\([^,]+,\s*"([a-z_]+)"\)', main))
    reasons |= set(re.findall(r'reason = "([a-z_]+)"', main))
    reasons |= set(re.findall(r'"(play_[a-z]+)" if', main))
    assert {"play", "beta", "manual"} <= providers, providers
    assert "refunded" in reasons, reasons

    copy = CABINET_TS.read_text(encoding="utf-8")
    for lang in ("const uk", "const en"):
        block = copy[copy.index(lang) :]
        block = block[: block.index("\n};")]
        missing_p = [p for p in providers if not re.search(r"\b%s: '" % p, block)]
        missing_r = [r for r in reasons if not re.search(r"\b%s: '" % r, block)]
        assert not missing_p, f"{lang}: джерела без людської назви: {missing_p}"
        assert not missing_r, f"{lang}: причини відкликання без людської назви: {missing_r}"
