"""K1 Builder AI: description + price list → a page manifest v2 draft. Nothing invented, model chosen by profile."""
import base64
import io
import json
import sys
import zipfile
from pathlib import Path

import httpx
import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR / "tests"))

from test_venues import _create, _login, env  # noqa: F401

LANA = "Манікюрна студія Лана. Працюємо щодня 10-20, троє майстрів: Оля, Іра і Катя. Депозит 20% за запис. Телефон +420 777 123 456."


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def _xlsx(rows):
    strings, index = [], {}
    def sid(s):
        if s not in index:
            index[s] = len(strings)
            strings.append(s)
        return index[s]
    cols = "ABCDEFGH"
    sheet = ['<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for r, row in enumerate(rows, 1):
        sheet.append(f'<row r="{r}">')
        for c, v in enumerate(row):
            ref = f"{cols[c]}{r}"
            if isinstance(v, (int, float)):
                sheet.append(f'<c r="{ref}"><v>{v}</v></c>')
            else:
                sheet.append(f'<c r="{ref}" t="s"><v>{sid(v)}</v></c>')
        sheet.append("</row>")
    sheet.append("</sheetData></worksheet>")
    sst = ['<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">']
    sst += [f"<si><t>{s}</t></si>" for s in strings] + ["</sst>"]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("xl/worksheets/sheet1.xml", "".join(sheet))
        z.writestr("xl/sharedStrings.xml", "".join(sst))
    return buf.getvalue()


class Model:
    """Upstream stub: records what the server asked and answers with `facts` as the model's JSON."""

    def __init__(self, facts=None, status=200):
        self.facts, self.status, self.seen = facts or {}, status, []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        if self.status != 200:
            return httpx.Response(self.status, json={"error": {"message": "no"}})
        text = json.dumps(self.facts, ensure_ascii=False)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": text}]}}]})


@pytest.fixture()
def ai(env, monkeypatch):  # noqa: F811
    import brain

    for name in ("VERTEX_PROJECT", "VERTEX_LOCATION", "GOOGLE_SA_JSON", "GOOGLE_APPLICATION_CREDENTIALS", "BRAIN_PROFILES"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    model = Model()
    monkeypatch.setattr(brain, "transport", httpx.MockTransport(model))
    head = _login(env, "lana@studio.cz")
    return {**env, "model": model, "head": head}


def _ask(ai, **body):
    return ai["client"].post("/venues/ai-draft", headers=ai["head"], json={"kind": "book", "lang": "uk", **body})


def _blocks(page, kind):
    return [b for b in page["blocks"] if b["kind"] == kind]


def test_a_draft_needs_an_account(ai):
    assert ai["client"].post("/venues/ai-draft", json={"kind": "book", "text": LANA}).status_code == 401
    assert ai["model"].seen == []


def test_the_price_list_becomes_services_and_a_foreign_currency_stays_empty(ai):
    ai["model"].facts = {}
    csv = "Послуга;Ціна;Тривалість\nМанікюр класичний;20 EUR;60 хв\nПокриття гель-лак;25 EUR;90\nДизайн одного нігтя;50 грн;15\n"
    res = _ask(ai, text=LANA, table={"name": "прайс.csv", "b64": _b64(csv.encode())})
    assert res.status_code == 200, res.text
    out = res.json()
    [svc] = _blocks(out["page"], "cat.services")
    assert svc["data"]["token"] == "EURC"
    items = svc["data"]["items"]
    assert [(i["name"], i["price"], i["duration"]) for i in items] == [
        ("Манікюр класичний", "20", 60), ("Покриття гель-лак", "25", 90), ("Дизайн одного нігтя", "", 15)]
    assert out["table"] == {"rows": 3, "unpriced": 1, "currencies": ["UAH"]}
    assert items[2]["was"] == "50 грн" and "was" not in items[0]


def test_an_xlsx_price_list_with_stock_fills_a_shop(ai):
    raw = _xlsx([["Товар", "Ціна", "Залишок"], ["Свічка соєва", "12 USDC", 7], ["Набір пензлів", "30 USDC", 0]])
    res = _ask(ai, kind="shop", text="Магазин Лана", table={"name": "stock.xlsx", "b64": _b64(raw)})
    assert res.status_code == 200, res.text
    [cat] = _blocks(res.json()["page"], "cat.products")
    assert cat["data"]["token"] == "USDC"
    assert [(i["name"], i["price"], i["stock"]) for i in cat["data"]["items"]] == [("Свічка соєва", "12", 7), ("Набір пензлів", "30", 0)]


def test_the_model_is_picked_by_profile_and_what_it_invents_is_dropped(ai):
    ai["model"].facts = {
        "name": "Студія Лана",
        "address": "Kyiv, Podil 12",
        "phone": "+420 777 123 456",
        "hours": [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"], "open": "10:00", "close": "20:00"}],
        "staff": ["Оля", "Іра", "Катя", "Марина"],
        "staff_count": 3,
        "deposit_percent": 20,
        "cancel_hours": 24,
    }
    res = _ask(ai, text=LANA)
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["assistant"] == "read"
    page = out["page"]
    assert _blocks(page, "id.name")[0]["data"]["name"] == "Студія Лана"
    assert _blocks(page, "id.about")[0]["data"]["text"] == LANA
    assert _blocks(page, "loc.address")[0]["data"] == {"text": ""}
    assert _blocks(page, "hrs.week")[0]["data"]["rows"] == [
        {"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"], "hours": {"open": "10:00", "close": "20:00"}}]
    assert [s["name"] for s in _blocks(page, "book.staff")[0]["data"]["items"]] == ["Оля", "Іра", "Катя"]
    policy = _blocks(page, "book.policy")[0]["data"]
    assert policy.get("deposit") == 20 and policy["cancel"] == 0
    assert _blocks(page, "loc.contact")[0]["data"] == {"phone": "+420777123456"}
    assert "Kyiv" not in json.dumps(page, ensure_ascii=False)

    [call] = ai["model"].seen
    assert ":generateContent" in str(call.url)
    import brain
    assert f"/models/{brain._profile_map()['act']}:" in str(call.url)
    sent = json.loads(call.content)
    assert LANA in json.dumps(sent, ensure_ascii=False)
    assert sent["generationConfig"]["responseMimeType"] == "application/json"


def test_staff_count_without_names_leaves_empty_rows_and_hours_without_days_leave_days_empty(ai):
    ai["model"].facts = {"name": "Лана", "staff_count": 3, "staff": [],
                         "hours": [{"days": ["mon", "tue"], "open": "10:00", "close": "20:00"}]}
    res = _ask(ai, text="Студія Лана, працюємо 10-20, троє майстрів")
    page = res.json()["page"]
    assert [s["name"] for s in _blocks(page, "book.staff")[0]["data"]["items"]] == ["", "", ""]
    assert _blocks(page, "hrs.week")[0]["data"]["rows"] == [{"days": [], "hours": {"open": "10:00", "close": "20:00"}}]


def test_without_a_mind_the_draft_is_still_made_from_what_the_user_gave(ai, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    csv = "name,price\nHaircut,15 USDC\n"
    res = _ask(ai, text=LANA, table={"name": "p.csv", "b64": _b64(csv.encode())})
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["assistant"] == "off"
    assert ai["model"].seen == []
    assert _blocks(out["page"], "id.name")[0]["data"]["name"] == ""
    assert _blocks(out["page"], "cat.services")[0]["data"]["items"][0]["name"] == "Haircut"


def test_a_failed_model_call_is_said_not_hidden(ai):
    ai["model"].status = 500
    out = _ask(ai, text=LANA).json()
    assert out["assistant"] == "failed"
    assert _blocks(out["page"], "id.name")[0]["data"]["name"] == ""


def test_photos_named_in_the_table_are_linked_and_the_rest_are_counted(ai):
    csv = "Послуга,Ціна,Фото\nМанікюр,20 EURC,nails-1.jpg\nПедикюр,30 EURC,missing.jpg\n"
    res = _ask(ai, text=LANA, table={"name": "p.csv", "b64": _b64(csv.encode())}, photos=["nails-1.jpg", "room.jpg"])
    out = res.json()
    items = _blocks(out["page"], "cat.services")[0]["data"]["items"]
    assert items[0]["photo"] == "nails-1.jpg" and "photo" not in items[1]
    assert out["photos"] == {"given": 2, "linked": 1}


def test_the_draft_saves_as_a_venue_draft(ai):
    ai["model"].facts = {"name": "Студія Лана", "staff_count": 3}
    page = _ask(ai, text=LANA).json()["page"]
    clean = json.loads(json.dumps(page))
    for b in clean["blocks"]:
        for it in b["data"].get("items", []):
            it.pop("photo", None)
            it.pop("was", None)
    vid = _create(ai, ai["head"], "Студія Лана")
    res = ai["client"].put(f"/venues/{vid}/draft", headers=ai["head"], json=clean)
    assert res.status_code == 200, res.text


@pytest.mark.parametrize("table", [
    {"name": "x.pdf", "b64": _b64(b"%PDF-1.4")},
    {"name": "x.csv", "b64": "not base64!!"},
    {"name": "x.xlsx", "b64": _b64(b"PK not a zip")},
])
def test_a_table_that_cannot_be_read_is_refused(ai, table):
    res = _ask(ai, text=LANA, table=table)
    assert res.status_code == 422 and res.json()["refused"] == "bad_table"


def test_kinds_outside_the_list_and_empty_input_are_refused(ai):
    assert _ask(ai, kind="casino", text=LANA).status_code == 422
    no = _ask(ai, text="   ").json()
    assert no["refused"] == "no_input" and "«" not in no["message_uk"] and no["message_en"]
