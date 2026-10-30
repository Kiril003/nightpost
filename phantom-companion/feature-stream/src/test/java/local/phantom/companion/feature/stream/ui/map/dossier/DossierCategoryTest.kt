package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 — «LIBRARY» більше не має де взятися.
 *
 * Саме цей рядок оператор бачив на пристрої: коли Overpass не встигав,
 * категорією ставав сирий англійський хінт із тапу. Тест тримає обидва
 * шляхи — теги і хінти — на одній українській відповіді.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class DossierCategoryTest {

    @Before
    fun setUp() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `бібліотека за тегом - Бібліотека`() {
        val category = dossierCategoryOf(mapOf("amenity" to "library"))
        assertEquals(DossierCategory.LIBRARY, category)
        assertEquals("Бібліотека", category.labelUk)
    }

    @Test
    fun `бібліотека лише за хінтом тапу - теж Бібліотека`() {
        // Рівно той стан, у якому картка друкувала «LIBRARY».
        val category = dossierCategoryOf(
            tags = emptyMap(),
            categoryHint = "amenity",
            subcategoryHint = "library",
        )
        assertEquals(DossierCategory.LIBRARY, category)
        assertEquals("Бібліотека", category.labelUk)
    }

    @Test
    fun `клас базового стилю мапи теж читається`() {
        // Базовий стиль віддає не «amenity=library», а class=«library».
        assertEquals(
            DossierCategory.LIBRARY,
            dossierCategoryOf(tags = emptyMap(), categoryHint = "library"),
        )
    }

    @Test
    fun `хінти нормалізуються за регістром і роздільником`() {
        assertEquals(
            DossierCategory.TRANSPORT,
            dossierCategoryOf(tags = emptyMap(), subcategoryHint = "Bus Stop"),
        )
    }

    @Test
    fun `теги важать більше за хінти`() {
        assertEquals(
            DossierCategory.PHARMACY,
            dossierCategoryOf(mapOf("amenity" to "pharmacy"), subcategoryHint = "library"),
        )
    }

    @Test
    fun `невідома крамниця все одно Магазин`() {
        assertEquals(DossierCategory.SHOP, dossierCategoryOf(mapOf("shop" to "vacuum_cleaner")))
    }

    @Test
    fun `нічого не відомо - це Місце, а не other`() {
        val category = dossierCategoryOf(emptyMap())
        assertEquals(DossierCategory.PLACE, category)
        assertEquals("Місце", category.labelUk)
        assertTrue(category.usesAppAccent)
    }

    @Test
    fun `акценти брифу закріплені`() {
        assertEquals(0xFF3B82F6, DossierCategory.FUEL.accentArgb)
        assertEquals(0xFF22C55E, DossierCategory.PHARMACY.accentArgb)
        assertEquals(0xFF8B5CF6, DossierCategory.LIBRARY.accentArgb)
        assertEquals(0xFFF59E0B, DossierCategory.FOOD.accentArgb)
        assertEquals(0xFF14B8A6, DossierCategory.TRANSPORT.accentArgb)
    }

    @Test
    fun `кожна категорія крім дефолтної має власний акцент`() {
        DossierCategory.values()
            .filterNot { it.usesAppAccent }
            .forEach { assertNotEquals("${it.name} без акценту", 0L, it.accentArgb) }
    }

    @Test
    fun `жодна назва категорії не англійська`() {
        DossierCategory.values().forEach { category ->
            assertTrue(
                "${category.name} має нелатинську назву",
                category.labelUk.any { it in 'А'..'я' || it == 'і' || it == 'ї' || it == 'є' },
            )
        }
    }

    // ── Конкретний вид місця ────────────────────────────────────────────────

    @Test
    fun `вид місця українською з тегів`() {
        assertEquals("бібліотека", kindLabelUk(mapOf("amenity" to "library")))
        assertEquals("зупинка автобуса", kindLabelUk(mapOf("highway" to "bus_stop")))
        assertEquals("магазин · пекарня", kindLabelUk(mapOf("shop" to "bakery")))
    }

    @Test
    fun `невідомий англійський токен ніколи не витікає в UI`() {
        // Був би «vacuum_cleaner»; має бути назва категорії.
        assertEquals("магазин", kindLabelUk(mapOf("shop" to "vacuum_cleaner")))
        assertEquals(
            "бібліотека",
            kindLabelUk(emptyMap(), categoryHint = "amenity", subcategoryHint = "library"),
        )
    }

    @Test
    fun `коли невідомо взагалі нічого - рядка виду просто нема`() {
        assertNull(kindLabelUk(emptyMap()))
    }
}
