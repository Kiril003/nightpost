"""Слова розмови не летять у геокодер (27.09).

Англійська NER на українському тексті бачила «місця» у звичайних словах, і
кожне йшло в Nominatim: «запам'ятай», «кирило», «котра».
"""
from __future__ import annotations

from memory.geo_extractor import GeoExtractor


class _FakeEnt:
    def __init__(self, text: str) -> None:
        self.text, self.label_, self.start_char, self.end_char = text, "GPE", 0, len(text)


class _EnglishOnUkrainian:
    """Як en_core_web_sm на українському тексті: «місце» — кожне слово з великої."""

    def __call__(self, text: str):
        class Doc:
            ents = [_FakeEnt(w.strip(".,:?")) for w in text.split() if w[:1].isupper()]
        return Doc()


def _extractor() -> GeoExtractor:
    ex = GeoExtractor.__new__(GeoExtractor)
    ex._nlp_en, ex._nlp_uk, ex._spacy_loaded = _EnglishOnUkrainian(), None, True
    return ex


def test_ukrainian_text_without_uk_model_sends_only_real_places():
    ex = _extractor()
    texts = [e.text for e in ex.extract("Запам'ятай: я Кирило, я люблю каву і живу в Києві.", lang="uk")]
    assert "Кирило" not in texts and "Запам'ятай:" not in texts and "Запам'ятай" not in texts
    assert all("Київ" in t or "Києв" in t for t in texts)


def test_question_word_is_not_a_place():
    ex = _extractor()
    assert [e.text for e in ex.extract("Котра зараз година?", lang="uk")] == []
