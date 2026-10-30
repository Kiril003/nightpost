"""Booking, shop and Builder-draft refusals speak the person's language, cs included."""
import builder_ai
import i18n
import venue_requests


def _all():
    return {**venue_requests.REFUSALS, **builder_ai.MESSAGES}


def test_every_refusal_is_a_catalogue_key_with_three_languages():
    for reason, entry in _all().items():
        assert entry == f"venues.refusal.{reason}", reason
        uk, en, cs = (i18n.t(entry, lang) for lang in ("uk", "en", "cs"))
        assert uk and en and cs and cs != en, reason


def test_czech_person_gets_czech_refusal():
    body = i18n.refusal_fields(venue_requests.REFUSALS["slot_taken"], "cs")
    assert body["message"] == i18n.t("venues.refusal.slot_taken", "cs") != body["message_en"]


def test_builder_draft_asks_with_formal_you():
    assert i18n.t("venues.refusal.bad_kind", "uk").startswith("Оберіть")
    assert i18n.t("venues.refusal.no_input", "uk").startswith("Опишіть")
