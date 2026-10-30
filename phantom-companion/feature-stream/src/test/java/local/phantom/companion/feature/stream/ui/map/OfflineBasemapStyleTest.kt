package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * МАПА БЕЗ МЕРЕЖІ — остання миля. Коли файл тайлів внесено,
 * `sources.openmaptiles` має тягнути ЙОГО. Мовчазний регрес тут не ламає
 * компіляцію й не міняє картинку в розробника (мережа в нього працює) —
 * жертва лише оператор без мережі, який про це ніколи не дізнається, бо
 * досі ніхто не написав тест, який червоніє.
 *
 * Три речі, кожна з яких сама по собі повертає мапу на мережу: адреса
 * джерела (мусить лишатись `mbtiles:///абсолютний/шлях`, три коси —
 * OFFLINE.md), сам факт наявності джерела для підміни в кожному стилі, і
 * те, що підміна не чіпає нічого іншого (гліфи, шари, решта джерел).
 */
class OfflineBasemapStyleTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева")
    }

    private val styles
        get() = File(repoRoot(), "feature-stream/src/main/assets/styles")
            .listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun sourceUrl(json: String): String? =
        Regex(""""openmaptiles"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1)

    @Test
    fun `substitution produces a three-slash absolute mbtiles url`() {
        val patched = applyOfflineBasemap(
            """{"sources":{"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}}}""",
            "/data/user/0/local.phantom.companion.debug/files/offline-basemap/basemap.mbtiles",
        )
        assertEquals(
            "mbtiles:///data/user/0/local.phantom.companion.debug/files/offline-basemap/basemap.mbtiles",
            sourceUrl(patched),
        )
    }

    @Test
    fun `a relative path is refused, not silently accepted`() {
        val threw = runCatching {
            applyOfflineBasemap("""{"sources":{}}""", "relative/path.mbtiles")
        }.isFailure
        assertTrue("MBTilesFileSource приймає лише абсолютний шлях (OFFLINE.md)", threw)
    }

    @Test
    fun `every shipped style still has an openmaptiles source to patch`() {
        assertTrue("стилів не знайдено", styles.isNotEmpty())
        for (f in styles) {
            assertTrue(
                "${f.name}: sources.openmaptiles.url зник — підміна на mbtiles стане мовчазним нулем",
                sourceUrl(f.readText()) != null,
            )
        }
    }

    @Test
    fun `patching a real style replaces only the tile source url`() {
        for (f in styles) {
            val original = f.readText()
            val patched = applyOfflineBasemap(original, "/sdcard/ukraine.mbtiles")

            assertEquals(
                "${f.name}: mbtiles:// не замінив мережевий TileJSON",
                "mbtiles:///sdcard/ukraine.mbtiles",
                sourceUrl(patched),
            )
            // Решта стилю не змінюється: довжина різниться рівно на різницю
            // довжин старого й нового значення джерела — і жодним символом більше.
            val before = sourceUrl(original)!!
            val after = sourceUrl(patched)!!
            assertEquals(
                "${f.name}: підміна зачепила щось поза url джерела тайлів",
                original.length - before.length,
                patched.length - after.length,
            )
            assertTrue(
                "${f.name}: гліфи мали лишитись у пакунку — див. OfflineGlyphsTest",
                patched.contains(""""glyphs": "asset://fonts/{fontstack}/{range}.pbf""""),
            )
        }
    }

    @Test
    fun `a style with no openmaptiles source is returned untouched`() {
        val foreign = """{"sources":{"raster-tiles":{"type":"raster","tiles":["https://x/{z}/{x}/{y}.png"]}}}"""
        assertEquals(foreign, applyOfflineBasemap(foreign, "/sdcard/ukraine.mbtiles"))
    }
}
