package local.phantom.companion.core.design.component

import java.io.File
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The back pill was copied byte-for-byte into eight settings screens and every
 * copy was ~29dp tall — 15sp of caption between 7dp of padding, against a 48dp
 * floor. `TouchTargetFirewallTest` never saw one of them: it resolves `.size()`
 * on a clickable chain, and this control has no `.size()` at all. Eight
 * sub-floor back buttons therefore sat inside a green gate for as long as they
 * existed.
 *
 * This file covers the shape that scan cannot: a `.clickable` whose only
 * vertical extent comes from `.padding`. It reads sources as raw file I/O, so
 * — same GATE LAW as the other firewalls — run it with `--rerun-tasks` or a
 * stale green will hide a count move.
 */
class BackPillFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun ktFiles(relPath: String): List<File> {
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

    private val clickableCall = Regex("""\.(clickable|combinedClickable)\s*[({]""")
    private val backClickable = Regex("""\.clickable\(\s*onClick\s*=\s*onBack\s*\)""")
    private val paddingVertical =
        Regex("""\.padding\([^)]*vertical\s*=\s*([0-9]+(?:\.[0-9]+)?)\.dp""")
    private val paddingUniform = Regex("""\.padding\(\s*([0-9]+(?:\.[0-9]+)?)\.dp\s*\)""")

    // Anything that gives the node a height of its own. A chain carrying one of
    // these is out of scope here — either it is already tall enough, or it is
    // TouchTargetFirewallTest's `.size()` shape, not this one.
    private val ownsHeight = Regex(
        """\.(size|sizeIn|height|heightIn|requiredHeight|defaultMinSize|fillMaxSize|""" +
            """fillMaxHeight|minimumInteractiveComponentSize)\b""",
    )

    /**
     * A modifier chain as written here: one call per line. Unlike
     * `TouchTargetFirewallTest`'s window this also walks FORWARD, because in
     * every one of the eight copies `.padding` came AFTER `.clickable` — a
     * backward-only walk reports zero on the exact defect this file exists for.
     */
    private fun chainWindow(lines: List<String>, at: Int): String {
        val parts = ArrayDeque<String>()
        parts.addLast(lines[at])
        var i = at - 1
        while (i >= 0) {
            val t = lines[i].trim()
            when {
                t.startsWith(".") -> {
                    parts.addFirst(lines[i]); i--
                }
                t == "Modifier" || t.endsWith("Modifier") ||
                    Regex("""modifier\s*=\s*Modifier\b""").containsMatchIn(t) -> {
                    parts.addFirst(lines[i]); i = -1
                }
                else -> i = -1
            }
        }
        var j = at + 1
        while (j < lines.size && lines[j].trim().startsWith(".")) {
            parts.addLast(lines[j]); j++
        }
        return parts.joinToString("\n")
    }

    /**
     * 20dp stands in for the tallest line box these pills wrap — `PhantomBody`
     * at 17sp draws ~20dp. Using the tallest means a flagged control is under
     * the floor for EVERY caller, never merely for the small ones: this errs
     * towards silence, like the other firewalls.
     */
    private fun paddedOffenders(relPath: String): List<String> {
        val out = mutableListOf<String>()
        for (file in ktFiles(relPath)) {
            val lines = file.readText().split("\n")
            for ((i, line) in lines.withIndex()) {
                if (!clickableCall.containsMatchIn(line)) continue
                val window = chainWindow(lines, i)
                if (ownsHeight.containsMatchIn(window)) continue
                val m = paddingVertical.find(window) ?: paddingUniform.find(window) ?: continue
                val pad = m.groupValues[1].toDouble()
                if (pad * 2 + GLYPH_LINE < PhantomTouchFloor.value) {
                    out.add("${file.relativeTo(repoRoot()).path}:${i + 1}  vertical=${pad}dp")
                }
            }
        }
        return out.sorted()
    }

    @Test
    fun `the floor is never argued below 48dp`() {
        assertTrue(
            "PhantomTouchFloor is ${PhantomTouchFloor.value}dp. 48 is the floor a gloved, " +
                "cold, shaking hand in a moving vehicle needs; every value below it was " +
                "someone's local judgement, and four separate files had already made four " +
                "different ones (44, 44, 40, 26).",
            PhantomTouchFloor.value >= 48f,
        )
    }

    @Test
    fun `no settings screen hand-rolls its own back control`() {
        val found = ktFiles(SETTINGS)
            .filter { backClickable.containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()

        assertEquals(
            "these settings screens still build their own back affordance instead of " +
                "PhantomBackPill.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  The one named below was being edited by other agents during the " +
                "2026-08-15 wave and were left alone deliberately. Migrating one means " +
                "deleting its name from this list — that is the ratchet, not a nuisance.",
            listOf("DiagnosticsScreen.kt"),
            found,
        )
    }

    @Test
    fun `core-design ships no clickable whose only height is padding`() {
        val found = paddedOffenders("core-design/src/main")

        assertEquals(
            "clickable targets in core-design whose entire vertical extent is padding, " +
                "measuring under ${PhantomTouchFloor.value}dp: ${found.size}.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  Grow the tap area — an outer Box at PhantomTouchFloor, or " +
                "heightIn(min = PhantomTouchFloor) — never the drawn glyph.",
            0,
            found.size,
        )
    }

    private companion object {
        const val SETTINGS = "app/src/main/java/local/phantom/companion/ui/settings"
        const val GLYPH_LINE = 20.0
    }
}
