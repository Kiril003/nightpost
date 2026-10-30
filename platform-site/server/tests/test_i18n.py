import re
import string

import pytest
from fastapi.testclient import TestClient

import i18n

BANNED = re.compile(r"\b(light|blackout|darkness|bluetooth|mesh|air raid)\b|світл|блекаут|темрява|блютуз|bluetooth", re.I)


def _fields(text):
    return {f for _, f, _, _ in string.Formatter().parse(text) if f}


def test_uk_en_cs_have_the_same_keys_and_placeholders():
    uk, en, cs = (i18n.CATALOGUES[x] for x in ("uk", "en", "cs"))
    assert set(uk) == set(en) == set(cs)
    for key in uk:
        assert _fields(uk[key]) == _fields(en[key]) == _fields(cs[key]), key
        assert all(uk[key].strip() and en[key].strip() and cs[key].strip() for _ in [0]), key


def test_wave_and_unknown_languages_answer_in_english():
    for lang in i18n.WAVE_LANGS + ("ja", "xx-YY"):
        assert i18n.t("api.no_such_cell", lang) == i18n.t("api.no_such_cell", "en")
    assert i18n.t("api.no_such_cell", "uk") != i18n.t("api.no_such_cell", "en")
    assert i18n.t("api.no_such_cell", "cs") != i18n.t("api.no_such_cell", "en")


def test_placeholders_are_filled_not_concatenated():
    assert "5" in i18n.t("api.exact_tags", "cs", n=5)


@pytest.mark.parametrize("raw,want", [
    ("pt", "pt-BR"), ("pt-PT", "pt-BR"), ("pt_br", "pt-BR"), ("in", "id"), ("en-GB", "en"),
    ("UK-ua", "uk"), ("cs;q=0.8", "cs"), ("*", None), ("", None),
])
def test_tags_are_normalised(raw, want):
    assert i18n.normalise(raw) == want


def test_header_wins_then_accept_language_then_uk():
    assert i18n.from_headers("cs", "en") == "cs"
    assert i18n.from_headers(None, "de-DE,de;q=0.9") == "de"
    assert i18n.from_headers("", "*, fr") == "fr"
    assert i18n.from_headers(None, None) == "uk"


def test_catalogue_copy_has_no_banned_words():
    for lang, cat in i18n.CATALOGUES.items():
        for key, text in cat.items():
            assert not BANNED.search(text), (lang, key)


def _client():
    import main
    return TestClient(main.app)


def test_endpoint_detail_follows_request_language():
    c = _client()
    url = "/truth/cell/not-a-cell"
    h = lambda **kw: {"X-Nightpost-Lang": kw["lang"]} if "lang" in kw else {}
    plain = c.get(url, headers=h())
    assert plain.status_code == 404 and plain.json()["detail"] == i18n.t("api.no_such_cell", "uk")
    assert c.get(url, headers=h(lang="en")).json()["detail"] == "No such cell."
    assert c.get(url, headers=h(lang="cs")).json()["detail"] == i18n.t("api.no_such_cell", "cs")
    assert c.get(url, headers={"Accept-Language": "cs-CZ,cs;q=0.9"}).json()["detail"] == i18n.t("api.no_such_cell", "cs")
    assert c.get(url, headers=h(lang="ja")).json()["detail"] == "No such cell."
