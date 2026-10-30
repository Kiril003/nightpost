package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * With the network down PHANTOM answered, in its own voice and in English,
 * «Unable to resolve host "generativelanguage.googleapis.com"». A Java
 * exception, a vendor hostname and an internal marker, all read by the
 * operator as the assistant speaking.
 *
 * [PhantomError] already draws the line — `message` is operator copy,
 * `cause` is for Diagnostics — but nothing enforced it, and two call sites
 * had quietly pasted a throwable into the sentence.
 *
 * GATE LAW: this reads other modules' sources as raw file I/O, invisible to
 * Gradle's up-to-date check. Run every gate with `--rerun-tasks`.
 */
class RawFailureAsSpeechFirewallTest {

    private val roots = listOf(
        "app/src/main", "core-ai/src/main", "core-data/src/main",
        "core-net/src/main", "core-voice/src/main", "core-symbiote/src/main",
        "feature-stream/src/main", "feature-onboarding/src/main",
        "feature-precision/src/main", "feature-ambient/src/main",
    )

    private val opening = Regex("""(PhantomError\.create\(|errorBus\.emit\(|PhantomError\()""")

    private val leak = Regex(
        """message\s*=\s*[^,\n]*(\.message\b|\.localizedMessage\b|::class\.simpleName|\.stackTraceToString\(\))""",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    @Test
    fun `no operator-facing error message carries the throwable that caused it`() {
        val root = repoRoot()
        val offenders = roots.map { File(root, it) }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { f ->
                    val lines = f.readText().lines()
                    lines.withIndex().filter { opening.containsMatchIn(it.value) }.flatMap { (at, _) ->
                        lines.subList(at, minOf(at + 12, lines.size))
                            .withIndex()
                            .filter { (_, l) ->
                                val t = l.trimStart()
                                !t.startsWith("//") && !t.startsWith("*") && leak.containsMatchIn(l)
                            }
                            .map { (off, l) -> "${f.name}:${at + off + 1}  ${l.trim().take(96)}" }
                    }
                }.toList()
        }.distinct()

        assertEquals(
            "an operator-facing error is quoting the exception:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nPut the throwable in `cause` — Diagnostics reads it — and give " +
                "`message` a string resource that says what the operator lost.",
            emptyList<String>(),
            offenders,
        )
    }
}
