"""Мова відповіді розуму — мова інтерфейсу (аудит 28.10 №4): українець отримав англійське
«Not recognized…». Телефон шле мову заголовком X-Nightpost-Lang (uk|en|cs + хвиля 1: es pt-BR de fr pl tr it id), сервер
дописує правило в кінець systemInstruction; будь-що інше — ніби заголовка немає."""
import json

import pytest

from test_brain import body, w  # noqa: F401  (w — фікстура)


def ask(w, doc=None, lang=None):
    headers = {"fly-client-ip": "203.0.113.7", "content-type": "application/json",
               "x-nightpost-device": "dEv1ce_Id-0123456789abcd"}
    if lang is not None:
        headers["x-nightpost-lang"] = lang
    return w.client.post("/api/brain/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse",
                         content=json.dumps(doc if doc is not None else body()).encode(), headers=headers)


def rule(sent: dict) -> str:
    return sent["systemInstruction"]["parts"][-1]["text"]


@pytest.mark.parametrize("lang,word", [
    ("uk", "Ukrainian"), ("en", "English"), ("cs", "Czech"), ("UK", "Ukrainian"),
    ("es", "Spanish"), ("pt-BR", "Brazilian Portuguese"), ("pt", "Brazilian Portuguese"), ("de", "German"),
    ("fr", "French"), ("pl", "Polish"), ("tr", "Turkish"), ("it", "Italian"), ("id", "Indonesian"), ("in", "Indonesian"),
])
def test_the_ui_language_becomes_the_last_system_rule(w, lang, word):
    assert ask(w, lang=lang).status_code == 200
    sent = w.net.sent()
    assert sent["systemInstruction"]["parts"][0]["text"] == "Answer in English."
    assert rule(sent).startswith("LANGUAGE:")
    assert word in rule(sent)


def test_a_request_without_system_instruction_gets_one(w):
    doc = body()
    doc.pop("systemInstruction")
    ask(w, doc, lang="uk")
    assert "Ukrainian" in rule(w.net.sent())


def test_the_snake_case_system_instruction_is_extended_not_duplicated(w):
    doc = body()
    doc["system_instruction"] = doc.pop("systemInstruction")
    ask(w, doc, lang="cs")
    sent = w.net.sent()
    assert "systemInstruction" not in sent
    assert "Czech" in sent["system_instruction"]["parts"][-1]["text"]


@pytest.mark.parametrize("lang", [None, "", "xx", "uk-UA; drop", "ru", "x" * 40])
def test_no_or_unknown_language_leaves_the_body_untouched(w, lang):
    doc = body()
    ask(w, doc, lang=lang)
    assert w.net.sent()["systemInstruction"] == doc["systemInstruction"]


def test_a_lang_field_in_the_body_is_read_and_never_reaches_google(w):
    ask(w, body(lang="uk"))
    sent = w.net.sent()
    assert "lang" not in sent
    assert "Ukrainian" in rule(sent)
