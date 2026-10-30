package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ратчет кадрового бюджету мапи.
 *
 * Мапа гріла телефон двома різними способами, і обидва поверталися: безкінечні
 * анімації, що будять головний потік, поки скомпоновані, і покадрові цикли, що
 * малюють GPU-кадр, коли на екрані нічого не змінилось. Другого не ловив жоден
 * тест — Filament-рендерер призм малював щовсинку, і це помітили лише читанням.
 *
 * Тут не «має бути нуль», а «не більше, ніж є»: росте — падає.
 */
class MapFrameBudgetTest {

    /**
     * Кожне джерело мусить лишатись за умовою («поки триває тривога», «поки
     * вантажиться»), а не жити просто тому, що екран відкрито. Зростання
     * означає нову вічну анімацію — знайди її й привʼяжи до стану.
     *
     * Було три. Скелетон досьє пішов — пульсуючий прямокутник удавав роботу,
     * тепер слот словом каже, що саме шукає. Крапка в «Небі» пішла — статус
     * там уже кричить словом ТРИВОГА/ТИША на всю ширину. Лишилась одна:
     * крапка в стрічці тривоги, і та компонується лише під `underAlert`.
     */
    private val infiniteAnimationCeiling = 1

    /**
     * Скільки покадрових циклів має мапа. Кожен ОБОВʼЯЗКОВО має ворота, які
     * пропускають кадр лише на справжню зміну; це перевіряється нижче окремо.
     *
     * Було два — обидва Filament-рендерери (Кришталеве місто й призми
     * транспорту). Жоден з них ніколи не вмикався в продукті, і обидва пішли
     * разом із мертвим рендер-шляхом. Стеля нуль: наступний покадровий цикл на
     * мапі має спершу довести, що він потрібен.
     */
    private val frameLoopCeiling = 0

    @Test
    fun `вічних анімацій на мапі не побільшало`() {
        val found = mapFiles().sumOf { file ->
            Regex("rememberInfiniteTransition\\(").findAll(file.readText()).count()
        }
        assertEquals(
            "Джерел безкінечної анімації на мапі: $found (стеля $infiniteAnimationCeiling). " +
                "Кожне будить головний потік 60 разів на секунду, поки скомпоноване.",
            infiniteAnimationCeiling,
            found,
        )
    }

    @Test
    fun `кожен покадровий цикл мапи має ворота на справжню зміну`() {
        val loops = mapFiles().filter { "Choreographer.FrameCallback" in it.readText() }
        assertEquals(
            "Покадрових циклів: ${loops.map { it.name }}",
            frameLoopCeiling,
            loops.size,
        )
        loops.forEach { file ->
            val body = file.readText()
            val gated = "movedVisibly" in body
            assertTrue(
                "${file.name} малює щовсинку без воріт. Запаркована камера над " +
                    "нерухомою картинкою не винна GPU нічого — саме це пекло SoC.",
                gated,
            )
        }
    }

    /**
     * ATLAS-0b — той самий бюджет, розширений на кадровий цикл камери.
     *
     * `withFrameNanos` переозброюється щовсинку (до 120 Гц). Цикл дота й
     * камери має РІВНО ОДИН дім і мусить сам себе гальмувати: ~30 fps поки
     * щось рухається, ~6 fps над запаркованою мапою. Без цього повертається
     * рівно та сама термалка — 42 °C шкіри над нерухомою картинкою.
     */
    @Test
    fun `кадровий цикл камери один і сам себе гальмує`() {
        val loops = mapFiles().filter { "withFrameNanos" in it.readText() }.map { it.name }.sorted()
        assertEquals(
            "файлів із покадровим циклом: $loops",
            listOf("MapCameraLoop.kt"),
            loops,
        )
        val camera = mapFiles().single { it.name == "MapCameraLoop.kt" }.readText()
        assertTrue(
            "цикл камери малює щовсинку без гальмування: запаркована камера " +
                "над нерухомою картинкою не винна GPU нічого — саме це пекло SoC",
            "FLUX_IDLE_COAST_FRAMES" in camera && "FLUX_IDLE_FRAME_MS" in camera,
        )
    }

    private fun mapFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = File(
            requireNonNull(dir),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map",
        )
        assertTrue("каталог мапи не знайдено: $root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun requireNonNull(dir: File?): File =
        dir ?: error("корінь репозиторію не знайдено від ${File("").absolutePath}")
}
