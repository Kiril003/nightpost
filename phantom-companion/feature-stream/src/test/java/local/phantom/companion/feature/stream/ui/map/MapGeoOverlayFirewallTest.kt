package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ATLAS D1 РАТЧЕТ — жодних НОВИХ геопривʼязаних Compose-оверлеїв.
 *
 * Базова мапа — SurfaceView в окремому шарі SurfaceFlinger; усе, що малює
 * Compose поверх неї, защіпається на іншому кадрі й ПЛИВЕ по землі під час
 * пану й зуму. Це перший пункт вердикту рекону, і кожен такий оверлей — його
 * джерело.
 *
 * Правило сформульоване через `@Composable`, а не через саму проєкцію: читати
 * проєкцію для hit-test можна, але з файлу, що нічого не малює (сьогодні такий
 * один — `nav/RenderedRoads.kt`, знімає центр камери для снапа до дороги).
 *
 * Планка тільки опускається: кожна міграція в шари стилю знімає рядок.
 */
class MapGeoOverlayFirewallTest {

    /**
     * Рівно два, і кожен зі своїм tap-шляхом (єдиний диспетчер тапів володіє
     * шарами движка, не hit-test'ом Compose). MapCanvas у списку більше нема —
     * 0b забрав з нього і проєкцію дропнутої точки, і локальний indoor-пробник.
     * `HazardMarkerOverlay` пішов у шар стилю (`EVENT_MARKS`) разом зі своїм
     * `OnCameraMoveListener` — планка опустилась із трьох.
     */
    private val allowed = setOf(
        "IndoorFloorOverlay.kt",
        "TransitVehicleMarkerOverlay.kt",
    )

    private val projectionCalls = listOf("toScreenLocation", "fromScreenLocation")

    @Test
    fun `геопривʼязаних Compose-оверлеїв рівно два`() {
        val found = mapFiles().filter { file ->
            val body = code(file)
            "@Composable" in body && projectionCalls.any { it in body }
        }.map { it.name }.sorted()

        assertEquals(
            "геопривʼязані Compose-оверлеї: $found\n\n" +
                "Новий такий оверлей пливе по землі під пальцем — малюй у шарах " +
                "стилю (MapContribution), не поверх мапи.",
            allowed.sorted(),
            found,
        )
    }

    @Test
    fun `невидимі гілки не ховають четвертий оверлей`() {
        // Без цього перевірка проходила б і на порожньому каталозі.
        val files = mapFiles().associateBy { it.name }
        allowed.forEach { name ->
            assertTrue("$name зник — онови планку, а не тест", name in files)
        }
    }

    /**
     * Читач проєкції ПОЗА Compose лишається законним рівно один. Другий —
     * привід питати, навіщо не-малювальному файлу екранні координати.
     */
    @Test
    fun `проєкцію поза Compose читає один файл`() {
        val readers = mapFiles().filter { file ->
            val body = code(file)
            "@Composable" !in body && projectionCalls.any { it in body }
        }.map { it.name }.sorted()

        assertEquals(
            "не-Compose читачі проєкції: $readers",
            listOf("MapCanvasWiring.kt", "MapTapDispatch.kt", "RenderedRoads.kt"),
            readers,
        )
    }

    /**
     * Код без коментарів — інакше KDoc, який ПОЯСНЮЄ це правило, сам його
     * «порушує»: рядок про «@Composable-файл» у файлі-помічнику зарахував би
     * той файл у геооверлеї.
     */
    private fun code(file: File): String {
        var inBlockComment = false
        return file.readLines().joinToString("\n") { raw ->
            val t = raw.trimStart()
            when {
                inBlockComment -> {
                    if ("*/" in raw) inBlockComment = false
                    ""
                }
                t.startsWith("/*") -> {
                    if ("*/" !in raw) inBlockComment = true
                    ""
                }
                t.startsWith("//") || t.startsWith("*") -> ""
                else -> raw.substringBefore("//")
            }
        }
    }

    private fun mapFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = File(
            dir ?: fail("корінь репозиторію не знайдено") as Nothing,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map",
        )
        assertTrue("каталог мапи не знайдено: $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
