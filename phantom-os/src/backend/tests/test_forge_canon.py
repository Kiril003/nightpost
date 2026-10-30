"""Канон Кузні: суперечності ловить код, а не пам'ять моделі.

Сценарій власника: серіал на двадцять сезонів, де навіть персонаж третього
плану з першого сезону і його зв'язки мають сходитись у двадцятому.
"""
from __future__ import annotations

from agent.forge.canon import Canon, Fact


def _series() -> Canon:
    c = Canon()
    c.entity("mira", "person", "Міра")
    c.entity("taras", "person", "Тарас")
    c.entity("olya", "person", "Оля")
    c.entity("baker", "person", "Пекар Іван")  # третій план, сезон 1
    assert c.add_all([
        Fact("baker", "born", "1970", (0,)),
        Fact("baker", "located_at", "Поділ", (1, 2, 4)),
        Fact("baker", "setup", "ключ-від-підвалу", (1, 2, 4), source="S1E2 сц.4"),
        Fact("taras", "sibling_of", "olya", (1, 1)),
        Fact("mira", "only_child", "yes", (1, 1)),
        Fact("baker", "dies", "пожежа", (1, 9, 2), source="S1E9"),
    ]) == []
    return c


def test_minor_character_dead_in_s1_cannot_talk_in_s20():
    c = _series()
    problems = c.add(Fact("baker", "talks_to", "mira", (20, 3, 1), source="S20E3 чернетка"))
    assert [p.kind for p in problems] == ["acts_after_end"]
    assert "Пекар Іван" in problems[0].message and "1·9·2" in problems[0].message
    assert problems[0].conflicts_with[0].source == "S1E9"


def test_nobody_can_talk_to_the_dead_either():
    c = _series()
    problems = c.add(Fact("mira", "talks_to", "baker", (20, 1, 1)))
    assert "partner_gone" in [p.kind for p in problems]


def test_but_the_dead_can_be_remembered_and_their_setup_paid_off():
    c = _series()
    assert c.add(Fact("mira", "remembered", "baker", (20, 1, 1))) == []
    assert c.add(Fact("baker", "mentioned", "ключ", (20, 1, 2))) == []
    assert [f.object for f in c.open_threads()] == ["ключ-від-підвалу"]
    assert c.add(Fact("mira", "payoff", "ключ-від-підвалу", (20, 10, 5))) == []
    assert c.open_threads() == []


def test_only_child_cannot_gain_a_sibling_in_season_seven():
    c = _series()
    problems = c.add(Fact("mira", "sibling_of", "taras", (7, 4)))
    assert "exclusive" in [p.kind for p in problems]
    assert not any(f.subject == "mira" and f.predicate == "sibling_of" for f in c.facts)


def test_relations_mirror_and_revival_is_respected():
    c = _series()
    assert Fact("olya", "sibling_of", "taras", (1, 1)) in c.facts
    assert c.add(Fact("baker", "resurrected", "ритуал", (15, 1))) == []
    assert c.add(Fact("baker", "talks_to", "mira", (20, 3, 1))) == []


def test_death_cannot_be_inserted_before_later_appearances():
    c = Canon()
    c.entity("x", "person", "Ікс")
    assert c.add(Fact("x", "located_at", "Львів", (5, 1))) == []
    problems = c.add(Fact("x", "dies", "хвороба", (3, 2)))
    assert [p.kind for p in problems] == ["end_before_later_acts"]


def test_immutable_and_one_place_at_a_time():
    c = _series()
    assert "immutable_changed" in [p.kind for p in c.add(Fact("baker", "born", "1980", (0,)))]
    c.entity("olya", "person", "Оля")
    assert c.add(Fact("olya", "located_at", "Київ", (2, 1, 1))) == []
    assert "two_values_at_once" in [p.kind for p in c.add(Fact("olya", "located_at", "Одеса", (2, 1, 1)))]
    assert c.add(Fact("olya", "located_at", "Одеса", (2, 1, 2))) == []
    assert c.at("olya", (2, 1, 5))["located_at"] == ["Одеса"]


def test_batch_is_atomic_and_canon_survives_disk(tmp_path):
    c = _series()
    before = len(c.facts)
    assert c.add_all([Fact("olya", "located_at", "Київ", (3, 1)), Fact("baker", "fights", "olya", (3, 1))])
    assert len(c.facts) == before
    c.save(tmp_path / "canon.json")
    again = Canon.load(tmp_path / "canon.json")
    assert again.add(Fact("baker", "talks_to", "mira", (20, 3, 1)))
    assert "[нема з 1·9·2: dies]" in again.brief(["baker"], (20, 1))


def test_facts_about_nothing_are_not_consistency():
    """28.09 наживо модель дала «setup plot_thread_1 stolen_archive» без жодної
    сутності — і слабкий канон назвав це узгодженим."""
    c = Canon()
    assert [p.kind for p in c.add(Fact("setup", "plot_thread_1", "stolen_archive"))] == ["unknown_predicate"]
    assert [p.kind for p in c.add(Fact("andriy", "located_at", "podil", (1, 1, 1)))] == ["unknown_entity"]
    c.entity("andriy", "person", "Андрій")
    assert [p.kind for p in c.add(Fact("andriy", "sibling_of", "olena", (1, 1)))] == ["unknown_entity"]
    c.entity("olena", "person", "Олена")
    c.entity("archive", "thread", "Вкрадений архів")
    assert c.add_all([Fact("andriy", "sibling_of", "olena", (1, 1)), Fact("andriy", "setup", "archive", (1, 2), "S1E2")]) == []
    assert [f.object for f in c.open_threads()] == ["archive"]


# ── масштаб двадцяти сезонів ───────────────────────────────────────────────
import time  # noqa: E402

_PLACES = ["Поділ", "Оболонь", "Печерськ", "Троєщина", "Виноградар", "Лук'янівка", "Позняки"]


def _big_series(n_people: int = 2000) -> tuple[Canon, list[Fact]]:
    """Детермінований світ: ~2000 людей, ~60 000 фактів на 20 сезонів.

    Вантажиться через from_dict (як із диска), бо саме так живе канон
    серіалу між сесіями; повертає ще й 500 нових сумісних фактів сезону 20.
    """
    entities = [{"id": f"p{i}", "kind": "person", "name": f"Персонаж{i}", "attrs": {}} for i in range(n_people)]
    facts: list[Fact] = []
    for i in range(n_people):
        pid = f"p{i}"
        facts.append(Fact(pid, "born", str(1950 + i % 50), (0,)))
        for s in range(1, 21):
            facts.append(Fact(pid, "located_at", _PLACES[(i + s) % len(_PLACES)], (s, 1, 1), f"S{s}E1"))
        for k in range(1, 4):
            other = f"p{(i + 7 * k) % n_people}"
            facts.append(Fact(pid, "knows", other, (k, 2, 1)))
            facts.append(Fact(pid, "talks_to", other, (k * 5, 3, 1)))
        facts.append(Fact(pid, "mentioned", "у хроніці", (10, 4, 1)))
        facts.append(Fact(pid, "owns", f"річ{i}", (1, 1)))
        if i % 2 == 0:
            j = f"p{(i + 1) % n_people}"
            facts.append(Fact(pid, "friend_of", j, (2, 1)))
            facts.append(Fact(j, "friend_of", pid, (2, 1)))
    new: list[Fact] = []
    for n in range(500):
        a, b = f"p{(n * 13) % n_people}", f"p{(n * 13 + 5) % n_people}"
        kind = n % 5
        if kind == 0:
            new.append(Fact(a, "located_at", _PLACES[n % len(_PLACES)], (20, 5, n)))
        elif kind == 1:
            new.append(Fact(a, "talks_to", b, (20, 6, n)))
        elif kind == 2:
            new.append(Fact(a, "works_with", b, (20, 7, n)))  # симетричне → дзеркало
        elif kind == 3:
            new.append(Fact(a, "mentioned", "у розмові", (20, 8, n)))
        else:
            new.append(Fact(a, "status", "втомлений", (20, 9, n)))
    c = Canon.from_dict({"entities": entities, "facts": [f.to_dict() for f in facts]})
    return c, new


def test_scale_twenty_seasons_500_adds_are_fast():
    """До індексів кожен add() сканував усі 60 000 фактів кілька разів
    (≈ десятки секунд на 500 додавань); з індексами — частки секунди."""
    c, new = _big_series()
    assert len(c.facts) >= 60_000
    t0 = time.perf_counter()
    for f in new:
        assert c.add(f) == [], f
    elapsed = time.perf_counter() - t0
    assert elapsed < 3.0, f"500 add() на 60k фактів зайняли {elapsed:.2f} с"
    # Правила на масштабі працюють так само: мертвий не говорить.
    assert c.add(Fact("p3", "dies", "шторм", (20, 10, 1))) == []
    assert [p.kind for p in c.add(Fact("p3", "talks_to", "p4", (20, 11, 1)))] == ["acts_after_end"]
    assert "partner_gone" in [p.kind for p in c.add(Fact("p4", "talks_to", "p3", (20, 11, 2)))]
    # Смерть не вставити перед пізнішою появою — навіть якщо пізніше лише дзеркало.
    assert [p.kind for p in c.add(Fact("p10", "dies", "шторм", (19, 1, 1)))] == ["end_before_later_acts"]


def test_indexes_follow_rollback_of_a_failed_batch():
    c = _series()
    before = c.to_dict()
    # Перший факт сам по собі валідний і встигає потрапити в індекси,
    # другий валить пакет — відкат мусить прибрати перший і з індексів.
    problems = c.add_all([
        Fact("taras", "located_at", "Київ", (5, 1)),
        Fact("olya", "works_with", "taras", (5, 1)),
        Fact("baker", "fights", "olya", (5, 1)),
    ])
    assert [p.kind for p in problems] == ["acts_after_end"]
    assert c.to_dict() == before
    assert c.at("taras", (6, 1)) == {"sibling_of": ["olya"]}
    assert "works_with" not in c.at("olya", (6, 1))
    # Якби індекс пам'ятав відкочене «taras у Києві в 5·1», інше місце в той
    # самий момент дало б хибне «two_values_at_once»; якби пам'ятав дзеркало
    # works_with — повтор пакета не додав би дзеркала.
    assert c.add(Fact("taras", "located_at", "Львів", (5, 1))) == []
    assert c.add(Fact("olya", "works_with", "taras", (5, 1))) == []
    assert Fact("taras", "works_with", "olya", (5, 1)) in c.facts
    # Відкат «тільки дитина» теж: сиблінг, доданий і відкочений, не блокує.
    c.entity("lesya", "person", "Леся")
    c.entity("ivan2", "person", "Іван")
    assert c.add_all([Fact("lesya", "sibling_of", "ivan2", (1, 1)), Fact("nobody", "knows", "lesya", (1, 1))])
    assert c.add(Fact("lesya", "only_child", "yes", (1, 1))) == []


def test_indexes_survive_disk_and_direct_facts_assignment(tmp_path):
    c = _series()
    c.save(tmp_path / "c.json")
    again = Canon.load(tmp_path / "c.json")
    assert "exclusive" in [p.kind for p in again.add(Fact("mira", "sibling_of", "olya", (3, 1)))]
    assert "exclusive" in [p.kind for p in again.add(Fact("taras", "only_child", "yes", (3, 1)))]
    # Хтось підмінив список фактів цілком — індекси мусять іти за ним.
    again.facts = [f for f in again.facts if f.predicate != "dies"]
    assert again.add(Fact("baker", "talks_to", "mira", (20, 3, 1))) == []


# ── що показати моделі: relevant() ─────────────────────────────────────────
def _series_linked() -> Canon:
    c = _series()
    c.entity("kyiv_fire", "event", "Пожежа на Подолі")
    assert c.add_all([
        Fact("mira", "knows", "baker", (1, 3, 1), source="S1E3"),
        Fact("baker", "remembered", "mira", (1, 9, 5)),
    ]) == []
    return c


def test_relevant_pulls_the_dead_s1_baker_into_a_s20_scene_about_mira():
    c = _series_linked()
    scene = "Сезон 20. Міра спускається до підвалу й згадує запах хліба."
    ids = c.relevant(scene, when=(20, 3, 1))
    assert ids[0] == "mira"
    assert "baker" in ids  # сусід через «knows», хоч і мертвий з 1·9·2
    assert "taras" not in ids and "olya" not in ids and "kyiv_fire" not in ids
    brief = c.brief(ids, (20, 3, 1))
    assert "[нема з 1·9·2: dies]" in brief and "ключ-від-підвалу" in brief


def test_relevant_names_are_case_insensitive_and_inflected():
    c = _series_linked()
    assert c.relevant("з МІРОЮ нічого не сталося", when=(20, 1))[0] == "mira"
    assert c.relevant("розмова про Тараса", when=(20, 1))[0] == "taras"
    assert c.relevant("taras і olya", when=(20, 1))[:2] == ["taras", "olya"]
    # «Олег» не є відмінком «Олі», «Мірошник» — не «Міра».
    c2 = Canon()
    c2.entity("olya", "person", "Оля")
    c2.entity("mira", "person", "Міра")
    assert c2.relevant("Олег і Мірошник", when=None) == []


def test_relevant_brings_open_threads_even_without_mentions_and_respects_limit():
    c = _series_linked()
    # Нитка «ключ-від-підвалу» відкрита — її власник іде в довідку.
    assert c.relevant("Порожня сцена без імен.", when=(20, 1)) == ["baker"]
    assert c.add(Fact("mira", "payoff", "ключ-від-підвалу", (20, 2))) == []
    assert c.relevant("Порожня сцена без імен.", when=(20, 5)) == []
    # До розв'язки (у часі) нитка ще відкрита.
    assert c.relevant("Порожня сцена без імен.", when=(19, 1)) == ["baker"]
    assert len(c.relevant("Міра, Тарас, Оля", when=(20, 1), limit=2)) == 2


def test_relevant_prefers_neighbours_nearest_in_time():
    c = Canon()
    for pid, name in [("hero", "Герой"), ("old", "Старий"), ("new", "Новий"), ("far", "Майбутній")]:
        c.entity(pid, "person", name)
    assert c.add_all([
        Fact("hero", "knows", "old", (1, 1)),
        Fact("hero", "knows", "new", (19, 1)),
        Fact("hero", "knows", "far", (25, 1)),
    ]) == []
    assert c.relevant("Герой", when=(20, 1)) == ["hero", "new", "old", "far"]
    assert c.relevant("Герой", when=(20, 1), limit=2) == ["hero", "new"]


def test_relevant_at_scale_finds_the_tertiary_character_quickly():
    c, _ = _big_series()
    c.entity("mira", "person", "Міра")
    c.entity("baker", "person", "Пекар Іван")
    assert c.add_all([
        Fact("baker", "born", "1970", (0,)),
        Fact("baker", "setup", "ключ-від-підвалу", (1, 2, 4)),
        Fact("mira", "knows", "baker", (1, 3, 1)),
        Fact("baker", "dies", "пожежа", (1, 9, 2)),
    ]) == []
    t0 = time.perf_counter()
    ids = c.relevant("Сезон 20: Міра біля пекарні.", when=(20, 3, 1))
    assert time.perf_counter() - t0 < 1.0
    assert ids == ["mira", "baker"]

