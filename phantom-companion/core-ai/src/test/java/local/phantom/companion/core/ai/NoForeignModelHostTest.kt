package local.phantom.companion.core.ai

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жодної чужої адреси на шляху моделей, слуху й голосу.
 *
 * 12.09 «очі-2» довели пʼятьма незалежними доказами, що реліз САМ тягне
 * 143,9 МБ `vosk-model-small-uk-v3-small.zip` з alphacephei.com на холодному
 * старті: адреса в dex, netstats uid = 148,9 МБ, `Content-Length 143 914 407`,
 * `VoskAPI Loading …/files/models/vosk/…` у logcat, і в `assets` моделі немає.
 * У ту саму секунду цей самий клас обіцяв, що «мовчазного відкоту на чуже
 * сховище тут немає». Обіцянка була хибна — і хибною її робив рівно один
 * рядок-літерал у каталозі.
 *
 * Тому каталог більше не носить адрес: у ньому ВІДНОСНІ шляхи, а корінь
 * приходить у `ModelManager(modelsBaseUrl = …)`. Порожній корінь означає
 * «завантажити нема звідки», і це кажеться словом, а не підміняється чужим
 * хостом.
 *
 * `infoUrl` лишається як ПОХОДЖЕННЯ — без схеми, тобто не адреса, за якою
 * код може піти сам; посилання складає інтерфейс, коли людина тисне ℹ.
 */
class NoForeignModelHostTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    /** Шлях моделей: каталог, постачальники, рушії слуху й голосу. */
    private val guardedRoots = listOf(
        "core-ai/src/main/java/local/phantom/companion/core/ai/ModelManager.kt",
        "core-ai/src/main/java/local/phantom/companion/core/ai/HearingProvisioner.kt",
        "core-voice/src/main",
    )

    private val scheme = Regex("""["']https?://""")
    private val lineComment = Regex("//[^\n]*")
    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    private fun ktFiles(rel: String): List<File> {
        val f = File(root, rel)
        assertTrue("сторож дивиться в порожнє: не існує $rel", f.exists())
        return if (f.isFile) listOf(f)
        else f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue("тека є, жодного .kt: $rel", it.isNotEmpty()) }
    }

    @Test
    fun `на шляху моделей немає жодної зашитої адреси`() {
        val offenders = mutableListOf<String>()
        for (rel in guardedRoots) {
            for (f in ktFiles(rel)) {
                val src = blockComment.replace(lineComment.replace(f.readText(), ""), "")
                scheme.findAll(src).forEach { m ->
                    val around = src.substring(m.range.first, minOf(src.length, m.range.first + 70))
                    offenders += "${f.name}: $around"
                }
            }
        }
        assertTrue(
            "зашита адреса на шляху моделей — саме так реліз тихо пішов на alphacephei:\n" +
                offenders.joinToString("\n") + "\n" +
                "Каталог носить ВІДНОСНІ шляхи; корінь приходить у ModelManager(modelsBaseUrl).",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `каталог не згадує відомих чужих сховищ як адрес`() {
        val src = ktFiles(guardedRoots.first()).single().readText()
        for (host in listOf("alphacephei.com/vosk/models/", "://huggingface.co", "://alphacephei.com")) {
            assertTrue(
                "каталог знову несе адресу $host",
                !src.contains("\"$host") && !src.contains("$host\""),
            )
        }
    }
}
