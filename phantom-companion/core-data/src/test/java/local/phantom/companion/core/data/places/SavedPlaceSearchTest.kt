package local.phantom.companion.core.data.places

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Брама пошуку міток — ОДНА на сховище й на екран.
 *
 * Довго їх було дві: сховище шукало за іменем, адресою і категорією, а екран
 * пошуку мапи мав власне, вужче правило (`name.contains`). Тому «додому» не
 * знаходило дім, названий «Хата», хоча сховище вміло його знайти — і саме це
 * вміння лишалось невикликаним.
 */
class SavedPlaceSearchTest {

    private fun place(
        label: String,
        category: String = SavedPlaceCategory.CUSTOM,
        address: String? = null,
    ) = SavedPlaceEntity(
        id = label,
        label = label,
        category = category,
        lat = 50.45,
        lon = 30.52,
        address = address,
        note = null,
        createdAt = 0L,
        lastUsedMs = 0L,
    )

    @Test
    fun `імʼя знаходиться підрядком`() {
        assertTrue(SavedPlaceCategory.matchesQuery("обол", place("Хата на Оболоні")))
    }

    @Test
    fun `адреса знаходиться підрядком`() {
        assertTrue(
            SavedPlaceCategory.matchesQuery(
                "хрещатик",
                place("Штаб", address = "вул. Хрещатик, 1"),
            ),
        )
    }

    @Test
    fun `«додому» знаходить дім, названий не «домом»`() {
        // Те, заради чого правило й існує: категорія — ознака, імʼя — ні.
        val home = place("Хата", category = SavedPlaceCategory.HOME)

        assertTrue(SavedPlaceCategory.matchesQuery("додому", home))
        assertTrue(SavedPlaceCategory.matchesQuery("дім", home))
    }

    @Test
    fun `«домофон» домом не є`() {
        // Категорія збігається лише по ЦІЛОМУ слову, інакше синонімний
        // словник перехопив би світовий запит.
        assertFalse(
            SavedPlaceCategory.matchesQuery("домофон", place("Хата", SavedPlaceCategory.HOME)),
        )
    }

    @Test
    fun `порожній запит не знаходить нічого`() {
        assertFalse(SavedPlaceCategory.matchesQuery("   ", place("Хата", SavedPlaceCategory.HOME)))
    }

    @Test
    fun `обидві форми брами відповідають однаково`() {
        // Мапа тримає мітку як PoiEntity і мусить питати ТЕ САМЕ правило;
        // розходження тут і є той дефект, що ховається роками.
        val entity = place("Хата", SavedPlaceCategory.HOME, address = "вул. Хрещатик, 1")
        listOf("хата", "хрещатик", "додому", "домофон", "", "штаб").forEach { q ->
            assertTrue(
                "форми брами розійшлись на запиті «$q»",
                SavedPlaceCategory.matchesQuery(q, entity) ==
                    SavedPlaceCategory.matchesQuery(q, entity.label, entity.address, entity.category),
            )
        }
    }
}
