package local.phantom.companion.feature.stream.ui.map.icons

import java.io.File
import local.phantom.companion.core.nav.OsmSpatialUniverse
import local.phantom.companion.feature.stream.ui.map.MapDiscovery
import local.phantom.companion.feature.stream.ui.map.dossier.DossierCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одна рука на весь продукт, а не тільки на землю.
 *
 * Пін уже носив [PhantomGlyph], але полиця пошуку, сітка «поруч» і картка
 * досьє малювали ті самі категорії власними наборами — Material Outlined і
 * системними емодзі. Оператор тиснув одну аптеку і приземлявся на іншу.
 * Ці перевірки не дають жодній з тих рук відрости назад.
 */
class CatalogGlyphTest {

    private fun record(glyph: PhantomGlyph) = RecordingGlyphPen().also { glyph.drawOn(it) }

    @Test
    fun `every tactical catalog id carries its own family sign`() {
        val missing = OsmSpatialUniverse.catalog
            .filterNot { it.id in spatialGlyphKeys }
            .map { "${it.id} (${it.labelUk})" }

        assertEquals("айді каталогу без знака — полиця намалює ромб", emptyList<String>(), missing)
    }

    /** Таблиця, що пережила свій каталог, — це наступний ромб на екрані. */
    @Test
    fun `the catalog table carries no id the catalog dropped`() {
        val live = OsmSpatialUniverse.catalog.map { it.id }.toSet()
        assertEquals("зайвий айді в таблиці знаків", emptySet<String>(), spatialGlyphKeys - live)
    }

    @Test
    fun `every catalog row resolves to a glyph that actually draws`() {
        OsmSpatialUniverse.catalog.forEach { cat ->
            assertTrue("«${cat.labelUk}» без знака", record(cat.glyph()).marks > 0)
        }
        MapDiscovery.all.forEach { cat ->
            assertTrue("«${cat.label}» без знака", record(cat.glyph).marks > 0)
        }
        DossierCategory.entries.forEach { cat ->
            assertTrue("«${cat.labelUk}» без знака", record(cat.glyph()).marks > 0)
        }
    }

    /**
     * Хвіст каталогу пошуку не має права осісти в ромбі: «просто місце» — це
     * відповідь для невідомого тега з Overpass, а не для категорії, яку ми
     * самі написали і самі показуємо оператору.
     */
    @Test
    fun `no hand-written catalog row falls through to the plain diamond`() {
        val fellThrough = MapDiscovery.all
            .filter { it.glyph == PhantomGlyph.PLACE }
            .map { it.label }
        assertEquals("категорія каталогу лишилась без власного знака", emptyList<String>(), fellThrough)

        assertEquals(
            "айді каталогу провалився в ромб",
            emptyList<String>(),
            OsmSpatialUniverse.catalog.filter { it.glyph() == PhantomGlyph.PLACE }.map { it.id },
        )
    }

    /**
     * Емодзі малює шрифт системи, тож на кожній прошивці воно інше, і
     * пофарбувати його акцентом не можна. Храповик стоїть на файлах, де
     * категорія — увесь їхній зміст.
     *
     * `MapToolsCapsule`, `MapSearchSheet` і `PlaceDossierSheet` сюди свідомо
     * НЕ входять: там лишились піктограми стану («евакуація йде», «скопійовано»)
     * — інший словник, не знак місця. Категорійний шлях у них тримає не текст,
     * а тип: `BentoPoiCard` бере [PhantomGlyph], а не рядок, тож емодзі туди
     * більше не компілюється.
     */
    @Test
    fun `the taxonomy surfaces spell no emoji at all`() {
        val guarded = listOf(
            "MapDiscoveryCatalog.kt",
            "dossier/DossierInside.kt",
            "dossier/DossierCategoryVisuals.kt",
        )
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        assertTrue("не знайдено кореня репозиторію", dir != null)
        val root = File(dir, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")

        val offenders = guarded.flatMap { rel ->
            val file = File(root, rel)
            assertTrue("зник файл під охороною: $rel", file.isFile)
            file.readLines().withIndex()
                .filter { (_, line) -> line.codePoints().anyMatch(::isPictograph) }
                .map { (i, line) -> "$rel:${i + 1} ${line.trim()}" }
        }

        assertEquals("емодзі повернулось у хром мапи", emptyList<String>(), offenders)
    }

    /**
     * Тільки справжні піктограми, і по КОДОВИХ ТОЧКАХ, а не по `Char`: емодзі
     * поза BMP приїжджає сурогатною парою, тож посимвольна перевірка бачила б
     * 0xD83D і мовчала.
     *
     * Кирилиця, «·», тире, лапки-ялинки, стрілка «→» і рамка «──» — друкарня,
     * а не піктограми; ловити їх означало б зробити перевірку невиконуваною у
     * файлах, які цілком українською.
     */
    private fun isPictograph(code: Int): Boolean =
        code in 0x1F000..0x1FAFF ||
            code in 0x2600..0x27BF ||
            code in 0x2B00..0x2BFF ||
            code == 0x24C2 ||
            code == 0xFE0F
}
