package local.phantom.companion.core.design.privacy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The onboarding screen tells the operator «Твої дані не залишають пристрій».
 * A system-wide log buffer is off the device by any reasonable reading of
 * that: any app holding READ_LOGS, anyone with adb, and every crash-reporting
 * SDK in the process can read it.
 *
 * On 2026-07-27 `finalizeAssistant` logged `raw=${finalText.take(2000)}` on
 * every single reply, the tool dispatcher logged 300 characters of arguments
 * (which carry position, contact names and vault keys), and the error path
 * logged 2000 characters of a rejected request body. None were debug-gated,
 * and release keeps `Log.d` unless proguard is told otherwise.
 *
 * This forbids the shape rather than the intent: a log line may carry a
 * length, an id, a status or a class name, never a slice of a payload. If a
 * turn cannot be debugged from those, add a field to the structured event —
 * do not print the operator's sentence.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate.
 */
class LogContentFirewallTest {

    private val roots = listOf(
        "app/src/main",
        "core-ai/src/main",
        "core-symbiote/src/main",
        "core-voice/src/main",
        "core-data/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /**
     * A `Log.*` call interpolating a truncated string — `.take(n)` inside the
     * message is the tell, because nothing is truncated unless it was long
     * enough to be content.
     */
    private val logWithSlice = Regex("""Log\.[dviwe]\((?:[^()]|\([^()]*\))*\.take\(""")

    /**
     * `StreamViewModel`'s CLOUD_UNREACHABLE arm is the same exemption for the
     * same reason: at httpCode 0 there is no response at all and `body` holds
     * the throwable's message. Hiding it cost a real diagnosis on 2026-07-27 —
     * the cloud route was failing and the log said only «bodyLen=58».
     *
     * `GeminiCloudRoute` logs the API's own rejection body at warn level on a
     * failure path. It is not operator content, it fires only when a request
     * is refused, and it is what made tonight's HTTP 400 diagnosable at all.
     */
    private val allowed = setOf("GeminiCloudRoute.kt", "StreamViewModel.kt")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun kotlinFilesIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        if (!dir.isDirectory) {
            // ПЕРЕДУМОВА, а не дрібниця: якщо теки немає, обхід поверне порожньо,
            // «порушників» не буде — і сторож стане ЗЕЛЕНИМ ні про що саме тоді,
            // коли пакет перейменували чи перенесли, тобто в мить найімовірнішого
            // дрейфу. Мовчазна порожнеча тут гірша за відсутність сторожа.
            fail("сторож дивиться в порожнє: не існує $relPath")
        }
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Друга передумова: тека може існувати й СПОРОЖНІТИ — вміст переїхав,
        // каталог лишився. Обхід тоді дає нуль файлів, порушників немає, і
        // сторож знову зелений ні про що. Найтихіший випадок із трьох.
        if (files.isEmpty()) fail("тека є, файлів немає: $relPath")
        return files
    }

    @Test
    fun `no log line carries a slice of a payload`() {
        val offenders = roots
            .flatMap { kotlinFilesIn(it) }
            .filter { it.name !in allowed }
            .flatMap { f ->
                f.readText().lineSequence().withIndex()
                    .filter { (_, line) -> logWithSlice.containsMatchIn(line) }
                    .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim().take(110)}" }
            }

        assertTrue(
            "a log line is printing part of a payload:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nLog the length, the id or the status — never the content. The " +
                "operator was promised their data stays on the device.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `release strips debug and verbose logging`() {
        val rules = File(repoRoot(), "app/proguard-rules.pro")
        assertTrue("app/proguard-rules.pro not found", rules.isFile)
        val text = rules.readText().replace(" ", "")
        assertTrue(
            "release keeps android.util.Log.d/v unless proguard is told to drop " +
                "them. Restore the -assumenosideeffects rule.",
            text.contains("-assumenosideeffectsclassandroid.util.Log") &&
                text.contains("staticintd(") &&
                text.contains("staticintv("),
        )
    }
}
