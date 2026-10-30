package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ATLAS D4 РАТЧЕТ — камеру пише РІВНО ОДИН файл.
 *
 * До 0b мапа мала ~15 писарів камери навколо одного законного власника, і вони
 * не знали один про одного: кнопки зуму були мертві в єдиному режимі, де водій
 * ними користується; idle-нахил бився з циклом ведення за tilt; вписування
 * листа воювало з поїздкою, яку вписувало. Тепер усі кажуть НАМІР
 * (`CameraIntent`), а `CameraService` вирішує, чий це кадр.
 *
 * Тест читає файли як текст (гасячи коментарі й рядкові літерали — інакше цей
 * KDoc порушив би власне правило) і має allow-list, який може лише коротшати.
 *
 * ЗАКОН ВОРІТ: таке читання невидиме для up-to-date перевірки Gradle —
 * запускати з `--rerun-tasks`, як це робить `scripts/verify.sh`.
 */
class CameraWriterFirewallTest {

    private val forbidden = listOf(
        "animateCamera",
        "easeCamera",
        "moveCamera",
        "cameraPosition =",
        "CameraUpdateFactory.",
    )

    /**
     * Рівно ОДИН файл. `MapAtlasPort` — єдина реалізація `MapPort`, тобто та
     * сама рука, що пише камеру. `RouteFlyover` пішов звідси в ATLAS-1: його
     * леги летять через `MapPort.animateAwait`, скасування те саме.
     *
     * Цей тест — злиття двох ратчетів (був ще слабший `CameraWriteRatchetTest`
     * на вужчому корені й без гасіння літералів). Лишається строгіший.
     */
    private val allowed = setOf("MapAtlasPort.kt")

    /**
     * Порожньо з 04.10: MapWidget (чат-віджет із власним MapView) пішов разом
     * зі старою стрічкою. Множина лишається, щоб виняток, якщо колись буде,
     * був названий рішенням, а не недоглядом.
     */
    private val outOfScope = emptySet<String>()

    @Test
    fun `камеру пише тільки порт`() {
        val offenders = mutableListOf<String>()
        mapFiles().forEach { file ->
            if (file.name in allowed || file.name in outOfScope) return@forEach
            codeLines(file).forEach { (lineNo, line) ->
                forbidden.firstOrNull { it in line }?.let { token ->
                    offenders += "${file.name}:$lineNo — $token"
                }
            }
        }
        assertEquals(
            "прямий запис камери поза CameraService:\n" + offenders.joinToString("\n") +
                "\n\nСкажи намір: ui.camera?.submit(CameraIntent.…). " +
                "Два автори на один кадр — це «зависає і крутить» з польового звіту.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `allow-list не оброс і кожен його файл справді пише камеру`() {
        assertEquals("allow-list подовшав — це ратчет, він тільки коротшає", 1, allowed.size)
        val files = mapFiles().associateBy { it.name }
        allowed.forEach { name ->
            val file = files[name] ?: fail("$name зник — правило вже нічого не боронить") as Nothing
            assertTrue(
                "$name більше не пише камеру: прибери його з allow-list",
                codeLines(file).any { (_, line) -> forbidden.any { it in line } },
            )
        }
    }

    /**
     * ATLAS-2.5 — і ВЕДЕ камеру теж рівно один.
     *
     * Порт боронить, хто пише; це боронить, хто вирішує КУДИ. Під час коустингу
     * намальована точка — вигадана, і камера мусить їхати за нею; другий
     * producer, що читає сирий фікс, повернув би камеру на позицію, якої на
     * екрані немає — рівно той розрив, якого водій не має побачити.
     */
    @Test
    fun `камеру веде один producer`() {
        val producers = mapFiles().filter { file ->
            codeLines(file).any { (_, line) -> "CameraDirector()" in line }
        }.map { it.name }
        assertEquals(
            "producer'ів слідкувальної камери: $producers — має бути лише MapCameraLoop.kt",
            listOf("MapCameraLoop.kt"),
            producers,
        )
    }

    /** Рядки коду: без коментарів і без вмісту рядкових літералів. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        var inBlockComment = false
        return file.readLines().mapIndexedNotNull { idx, raw ->
            val trimmed = raw.trimStart()
            when {
                inBlockComment -> {
                    if ("*/" in raw) inBlockComment = false
                    null
                }
                trimmed.startsWith("/*") -> {
                    if ("*/" !in raw) inBlockComment = true
                    null
                }
                trimmed.startsWith("//") || trimmed.startsWith("*") -> null
                else -> (idx + 1) to raw.substringBefore("//").replace(Regex("\"[^\"]*\""), "\"\"")
            }
        }
    }

    private fun mapFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = File(
            dir ?: fail("корінь репозиторію не знайдено") as Nothing,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui",
        )
        assertTrue("каталог UI не знайдено: $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
