package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * СТОРОЖ AI СТУДІЇ — тепер сторож її ВІДСУТНОСТІ.
 *
 * 05.09 виміряно: за дверима «AI Студія» в нижній навігації телефона не було
 * жодного виклику моделі — `sendMessage` це `setTimeout(600)` і три шаблони
 * за регулярним виразом, «Круглий стіл» — чотири готові репліки з літеральним
 * `confidenceScore`. Рішення власника 05.09 (веб `fbfee428`, злито 12.09 у
 * `wave/web-12sep` 44de1d7c): студію з телефонної збірки прибрано цілком —
 * `AISynthesisModal` і `aiSynthesisStore` підмінюються порожніми модулями
 * (`PHONE_SWAPS`, vite.config.ts), тож чанк студії не народжується.
 *
 * До 12.09 цей тест тримав СТЕЛЮ вигаданого (modelUsed ≤4, confidenceScore ≤7…)
 * і сам казав: «двері прибрали — тест зайвий». Двері прибрано; тепер сторож
 * доводить нуль — і доводить, що шукав: бандл непорожній, головний чанк на місці.
 */
class AiStudioInferenceTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val bundle get() = File(repoRoot(), "app/src/main/assets/webmsgr")

    private fun scripts(): List<File> =
        bundle.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".js") && it.length() < 20L * 1024 * 1024 }
            .toList()

    private fun count(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()

    @Test
    fun `студії й її вигаданих вимірів у бандлі телефона немає`() {
        val files = scripts()
        assertTrue("бандл у ${bundle.path} порожній або відсутній: ${files.size} .js", files.size > 1)
        assertTrue(
            "головного чанка MessengerLayout-*.js немає — це не збірка месенджера",
            files.any { it.name.startsWith("MessengerLayout-") },
        )

        val markers = listOf(
            "AI Студія", "startRoundTableDebate", "modelUsed", "isLocalInference",
            "confidenceScore", "thoughtSteps", "ragSources",
            "AVAILABLE_MODELS", "INITIAL_MEMORY_STATE",
        )
        val present = files.flatMap { f ->
            val text = f.readText()
            markers.mapNotNull { m -> count(text, m).takeIf { it > 0 }?.let { "${f.name}: $m ×$it" } }
        }
        assertEquals(
            "у бандлі телефона знову є студія або її вигадані виміри (двері прибрано 05.09, " +
                "модуль підмінюється у phone-збірці — хтось повернув їх іншими дверима):\n    " +
                present.joinToString("\n    "),
            emptyList<String>(),
            present,
        )
    }
}
