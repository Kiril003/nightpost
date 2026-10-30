package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 6 September 2026: `:app` died with
 * `Failed to flatten XML for resource 'peer_card_unverified_why' with error:
 * Invalid unicode escape sequence`. The message names a unicode escape; the
 * actual cause was one apostrophe in the English word `person's`, which
 * Android requires written as `\'`.
 *
 * The part worth a gate is not the typo. **All eight string firewalls in this
 * package were green on that commit**, because every one of them reads the XML
 * as TEXT — key sets, format arguments, Cyrillic, Russian, English-in-Ukrainian.
 * Resources are flattened through `aapt2` only in `:app`. So a branch that
 * cannot build reported eight passing guards, and the only reason it was caught
 * is that the gate happened to include `:app`.
 *
 * That is the shape of the defect this file exists to close: **the choice of
 * module decides which classes of fault are reachable at all**, and eight
 * guards were giving a confidence they had no basis to give. A string gate that
 * cannot see what `aapt2` rejects is a gate over half the problem.
 *
 * WHY ONLY THE APOSTROPHE. Measured on this tree the same day: 20 strings carry
 * an unescaped double quote and `:app` builds green — `"постав таймер на "` is
 * Android's own quoting idiom for preserving whitespace, and a bare `"` inside
 * an unwrapped string is accepted. Guarding it would redden 20 healthy strings
 * and teach the next person to switch the gate off. The apostrophe is the one
 * that actually refuses to flatten.
 *
 * GATE LAW: raw file I/O over other modules; run with `--rerun-tasks`.
 */
class Aapt2EscapeFirewallTest {

    /** `<string>` and `<item>` (array/plural members) — not `<string-array>` itself. */
    private val entry = Regex(
        """<(string|item)(?![-\w])[^>]*>(.*?)</\1>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** An apostrophe with no backslash in front of it. */
    private val looseApostrophe = Regex("""(?<!\\)'""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun resourceFiles(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { module ->
                File(module, "src/main/res").listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("values") }
                    .flatMap { it.listFiles().orEmpty().filter { f -> f.extension == "xml" } }
            }
            .sortedBy { it.path }

    @Test
    fun `no string carries an apostrophe that aapt2 will refuse to flatten`() {
        val root = repoRoot()
        val offenders = resourceFiles(root).flatMap { f ->
            entry.findAll(f.readText()).mapNotNull { m ->
                val value = m.groupValues[2].trim()
                // Wrapped in double quotes: Android's whitespace idiom, apostrophes inside are safe.
                val wrapped = value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")
                if (!wrapped && looseApostrophe.containsMatchIn(value)) {
                    "${f.relativeTo(root).path}: ${value.take(70)}"
                } else {
                    null
                }
            }
        }

        assertEquals(
            "an unescaped apostrophe stops aapt2 from flattening the resource, so the\n" +
                "app does not build — and every text-level string gate stays green while it:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\nwrite it as \\' , or wrap the whole string in double quotes.",
            emptyList<String>(),
            offenders,
        )
    }
}
