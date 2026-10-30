package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The chat menu offered «Vault» in Latin between «Зараз» and «новий простір»,
 * while the island called the same place «СХОВИЩЕ» — one section, two names,
 * one of them in the wrong language.
 *
 * Every i18n gate here reads composables, and this literal was a field on a
 * `domain/` data class, so none of them could see it. A `label`/`title`/`name`
 * carrying display text belongs in strings.xml no matter which layer holds it.
 *
 * GATE LAW: raw file I/O over other modules; run with `--rerun-tasks`.
 */
class DomainLabelFirewallTest {

    private val roots = listOf(
        "feature-stream/src/main", "feature-onboarding/src/main",
        "feature-precision/src/main", "feature-ambient/src/main",
        "core-design/src/main", "app/src/main",
    )

    private val displayLiteral = Regex(
        """\b(label|title|caption|placeholder|displayName)\s*=\s*"([^"$\\]{2,})"""",
    )

    /** Not display text: routing keys, tags, test ids, format skeletons. */
    private val notCopy = Regex("""^[\w./:@#%${'$'}+*-]+$""")

    /**
     * The ground on the day this gate landed: SurfaceId's 17 tray entries,
     * three SystemEventParser titles and one PresentAggregator title. They are
     * real debt — a domain enum holding Ukrainian copy has no English mirror
     * and never will until the copy moves — but they predate the gate, so the
     * ratchet pins them rather than blocking every commit behind a 42-string
     * extraction. Only the owner raises this. 02.10 the tray and its parsers
     * went with the stream: the ground is zero.
     */
    private val ceiling = 0

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    @Test
    fun `no domain type hardcodes a label the operator reads`() {
        val root = repoRoot()
        val offenders = roots.map { File(root, it) }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("/domain/") || it.path.contains("/model/") }
                .flatMap { f ->
                    f.readText().lineSequence().withIndex().mapNotNull { (i, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                        val m = displayLiteral.find(line) ?: return@mapNotNull null
                        val text = m.groupValues[2]
                        if (notCopy.matches(text)) null
                        else "${f.name}:${i + 1}  ${trimmed.take(90)}"
                    }
                }.toList()
        }

        assertEquals(
            "domain copy is ${offenders.size}, ceiling says $ceiling.\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n  ↑ rose: move it to strings.xml (+ values) and hold a " +
                "@StringRes id, the way ThreadDescriptor.labelRes does.\n" +
                "  ↓ fell: good. Set ceiling = ${offenders.size} to keep the ground.",
            ceiling,
            offenders.size,
        )
    }
}
