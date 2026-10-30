"""Сторінка /delete-account: маршрут, свіже підтвердження, скасування та правові тексти."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SITE = ROOT / "platform-site"
MAIN_PY = SITE / "server/main.py"
APP_TSX = SITE / "src/App.tsx"
API_TS = SITE / "src/lib/account-api.ts"
SIGNED_OUT_TSX = SITE / "src/components/account/SignedOut.tsx"
PAGE_TSX = SITE / "src/pages/DeleteAccount.tsx"
COPY_TS = SITE / "src/i18n/deleteAccount.ts"
LEGAL_TS = SITE / "src/i18n/legal.ts"
SITEMAP_XML = SITE / "public/sitemap.xml"
PHONE_ROW_KT = (
    ROOT
    / "phantom-companion/app/src/main/java/local/phantom/companion/ui/settings/DeleteAccountRow.kt"
)


def _function_body(src: str, name: str) -> str:
    match = re.search(rf"\bfunction\s+{re.escape(name)}\s*\(", src)
    assert match, f"у DeleteAccount.tsx немає function {name}"
    idx = match.end() - 1
    paren_depth = 0
    while idx < len(src):
        ch = src[idx]
        if ch == "(":
            paren_depth += 1
        elif ch == ")":
            paren_depth -= 1
            if paren_depth == 0:
                idx += 1
                break
        idx += 1
    brace_start = src.find("{", idx)
    assert brace_start != -1, f"у function {name} немає тіла"
    depth = 0
    for pos in range(brace_start, len(src)):
        if src[pos] == "{":
            depth += 1
        elif src[pos] == "}":
            depth -= 1
            if depth == 0:
                return src[brace_start : pos + 1]
    raise AssertionError(f"незамкнене тіло function {name}")


def _lang_dict(src: str, lang: str) -> dict[str, str]:
    match = re.search(rf"\b{lang}\s*:\s*\{{(.*?)\n\s*\}}", src, re.S)
    assert match, f"у deleteAccount.ts немає блоку {lang}"
    block = match.group(1)
    pairs = re.findall(r"([A-Za-z0-9_]+)\s*:\s*'([^']*)'", block)
    assert pairs, f"блок {lang} порожній"
    return dict(pairs)


def test_phone_url_is_a_route():
    kt = PHONE_ROW_KT.read_text(encoding="utf-8")
    m = re.search(r'const val URL = "https://phantom-os\.dev(/[a-z-]+)"', kt)
    assert m, "DeleteAccountRow.kt не оголошує URL на phantom-os.dev"
    route_path = m.group(1)

    app_src = APP_TSX.read_text(encoding="utf-8")
    loaders_match = re.search(r"const LOADERS:\s*Record<string,\s*Loader>\s*=\s*\{(.*?)\};", app_src, re.S)
    assert loaders_match, "у App.tsx немає LOADERS"
    assert re.search(rf"'{re.escape(route_path)}'\s*:\s*\(\)\s*=>\s*import\('\./pages/DeleteAccount'\)", loaders_match.group(1))

    route_match = re.search(
        rf"\{{\s*path:\s*'{re.escape(route_path)}'\s*,\s*element:\s*page\('{re.escape(route_path)}'\)\s*\}}",
        app_src,
    )
    wildcard_match = re.search(r"\{\s*path:\s*'\*'", app_src)
    assert route_match, f"App.tsx не має маршруту {route_path}"
    assert wildcard_match, "App.tsx не має маршруту '*'"
    assert route_match.start() < wildcard_match.start(), f"{route_path} мусить стояти перед '*'"


def test_erase_needs_a_fresh_erase_approval():
    api_src = API_TS.read_text(encoding="utf-8")
    assert re.search(
        r"export const startEraseQr\s*=\s*\(\)\s*=>\s*post<QrStartResponse>\('/api/auth/qr/start',\s*\{\s*purpose:\s*'erase'\s*\}\)",
        api_src,
    ), "account-api.ts мусить оголошувати startEraseQr з { purpose: 'erase' }"
    assert re.search(
        r"export const eraseMe\s*=\s*\(\)\s*=>\s*post<\{\s*pending:\s*boolean;\s*due_at:\s*number\s*\}>\('/api/me/erase'\)",
        api_src,
    ), "account-api.ts мусить оголошувати eraseMe для /api/me/erase"

    signed_out = SIGNED_OUT_TSX.read_text(encoding="utf-8")
    assert re.search(r"export function EmailDoor\b", signed_out), "SignedOut.tsx мусить експортувати EmailDoor"

    page_src = PAGE_TSX.read_text(encoding="utf-8")
    assert re.search(
        r"createQrAuthController\(\s*\{[^}]*\bstart:\s*startEraseQr\b",
        page_src,
        re.S,
    ), "DeleteAccount.tsx мусить передавати start: startEraseQr у createQrAuthController"

    confirm_body = _function_body(page_src, "ConfirmStep")
    assert "eraseMe(" in confirm_body, "ConfirmStep мусить викликати eraseMe()"
    assert page_src.count("eraseMe(") == confirm_body.count("eraseMe(") == 1, (
        "eraseMe() має викликатися лише всередині ConfirmStep"
    )


def test_no_phone_branch_cannot_erase():
    page_src = PAGE_TSX.read_text(encoding="utf-8")
    no_phone_body = _function_body(page_src, "NoPhone")
    assert "/api/me/threads" in no_phone_body, "NoPhone мусить відкривати звернення через /api/me/threads"
    for forbidden in ("eraseMe", "/api/erase", "/api/me/erase"):
        assert forbidden not in no_phone_body, f"NoPhone не має містити {forbidden}"


def test_pending_step_cancels_without_erasing():
    api_src = API_TS.read_text(encoding="utf-8")
    assert re.search(
        r"export const cancelErase\s*=\s*\(\)\s*=>\s*post<\{\s*cancelled:\s*boolean\s*\}>\('/api/me/erase/cancel'\)",
        api_src,
    ), "account-api.ts мусить оголошувати cancelErase для /api/me/erase/cancel"

    page_src = PAGE_TSX.read_text(encoding="utf-8")
    pending_body = _function_body(page_src, "PendingStep")
    assert "cancelErase(" in pending_body, "PendingStep мусить викликати cancelErase()"
    assert page_src.count("cancelErase(") == pending_body.count("cancelErase(") == 1, (
        "cancelErase() має викликатися лише всередині PendingStep"
    )
    for forbidden in ("eraseMe", "/api/erase", "/api/me/erase"):
        assert forbidden not in pending_body, f"PendingStep не має містити {forbidden}"


def test_copy_is_complete():
    copy_src = COPY_TS.read_text(encoding="utf-8")
    en = _lang_dict(copy_src, "en")
    uk = _lang_dict(copy_src, "uk")
    assert set(en.keys()) == set(uk.keys()), f"різні ключі перекладу: {set(en) ^ set(uk)}"

    for lang, mapping in (("en", en), ("uk", uk)):
        for key, value in mapping.items():
            assert value.strip(), f"порожній рядок {lang}.{key}"
            assert "phantom" not in value.lower(), f"{lang}.{key} згадує Phantom: {value!r}"
            assert "circles" not in value.lower(), f"{lang}.{key} згадує Circles: {value!r}"

    expected_en = {
        "Delete account",
        "Confirm on your phone",
        "Email account",
        "No phone",
        "Removed after 7 days",
        "Only from the app: place ratings, Seeker rewards",
        "Not available for this account",
        "Account disabled",
        "Send",
        "Deletion scheduled",
        "Cancel deletion",
        "Account restored",
        "Nothing to cancel",
    }
    expected_uk = {
        "Видалити обліковий запис",
        "Підтвердіть на телефоні",
        "Обліковий запис з поштою",
        "Немає телефона",
        "Видаляється через 7 днів",
        "Лише із застосунку: оцінки місць, нагороди Seeker",
        "Недоступно для цього облікового запису",
        "Обліковий запис вимкнено",
        "Надіслати",
        "Видалення заплановано",
        "Скасувати видалення",
        "Обліковий запис відновлено",
        "Нічого скасовувати",
    }
    assert expected_en <= set(en.values()), f"бракує англійських підписів: {expected_en - set(en.values())}"
    assert expected_uk <= set(uk.values()), f"бракує українських підписів: {expected_uk - set(uk.values())}"


def test_published():
    sitemap = SITEMAP_XML.read_text(encoding="utf-8")
    assert "<loc>https://phantom-os.dev/delete-account</loc>" in sitemap

    legal = LEGAL_TS.read_text(encoding="utf-8")
    uk_start = legal.index("const uk: LegalCopy =")
    en_start = legal.index("const en: LegalCopy =")
    uk_block = legal[uk_start:en_start]
    en_block = legal[en_start:]

    assert uk_block.count("https://phantom-os.dev/delete-account") >= 3
    assert en_block.count("https://phantom-os.dev/delete-account") >= 3
    assert "Delete everything" in en_block


def test_server_has_the_routes():
    main_src = MAIN_PY.read_text(encoding="utf-8")
    assert '@app.post("/api/me/erase")' in main_src
    assert '@app.post("/api/me/erase/cancel")' in main_src
    assert re.search(r"class QrStartRequest\(BaseModel\):\s+purpose:", main_src)
    assert re.search(r'@app\.post\("/api/auth/qr/start"\)\s*def auth_qr_start\([^)]*body:\s*QrStartRequest', main_src)
