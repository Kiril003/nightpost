package local.phantom.companion.core.design.layout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ten controls were measured below the 48dp touch-target floor on 2026-08-15
 * (`docs/design/app-layout-inventory.md` §5): a 28dp thread-switch menu called
 * "the sole top-chrome affordance" in its own file, a 44dp back button defined
 * as a named constant in two sibling files independently, a 44dp font-size
 * stepper — small, easy to introduce one call site at a time, and exactly the
 * kind of thing nobody notices from a desk. This product is used with a
 * gloved, cold, shaking hand in a moving vehicle; a control that needs a
 * steady fingertip is a control that fails there.
 *
 * All ten were fixed the same way: the drawn glyph/circle/pill keeps its
 * original `.size(N.dp)`, and the *tap* area grows to 48dp either by widening
 * that same box directly (when nothing else — no `.background()`/`.border()`
 * — was drawn at that size, so growing it changes nothing at rest) or by
 * moving `.clickable`/`.combinedClickable` onto a new, larger, otherwise
 * invisible outer `Box` and centring the original box inside it. This test
 * pins that shape: `.clickable`/`.combinedClickable` chained after a
 * `.size(N.dp)` smaller than 48dp, in the scanned modules.
 *
 * ## What this can and cannot prove
 *
 * This is a source scan, not a layout inspector. It resolves two shapes:
 *   1. a literal on the clickable's own modifier chain — `.size(28.dp)...clickable(...)`
 *   2. a named `val`/`private val NAME = N.dp` declared in the SAME file and
 *      referenced as `.size(NAME)` on that chain (how `TOUCH`/`GROUP_TOUCH`
 *      were declared before this fix).
 * A `.size()` built from a cross-file constant, a function parameter, or an
 * expression (`.size(if (x) 28.dp else 48.dp)`) is **not resolved and not
 * flagged** — the scan has no type information and would rather stay silent
 * than guess. `feature-stream`'s own `MIN_TOUCH` (`ui/items/BubbleMetrics.kt:26`,
 * imported by seven bubble files) is exactly this blind spot in shape —
 * still invisible to this test even now that it reads 48dp, because six of
 * those seven files use it on a genuinely clickable row/circle and one
 * (`SystemEventBubble.kt`, a non-clickable status line) was deliberately
 * decoupled onto its own `ROW_MIN_HEIGHT = 44.dp` so a shared constant could
 * not silently inflate a control that was never a tap target. Named here so
 * this scan's silence on that file is never mistaken for coverage.
 * `.height()`/`.heightIn()`/`.defaultMinSize(minHeight = …)` are a different
 * shape (a minimum row height, not an isolated tap circle) and are out of
 * scope by design, not by oversight.
 *
 * ## The ceiling is zero — for now
 *
 * The nine this file's ceiling tracked — `MapSearchSheet.kt:167` and
 * `PlaceDossierSheet.kt:634` (`.clickable` directly on an 18dp `Icon`, no
 * touch box at all), `OfflineTerritoriesPanel.kt:260` (a 30dp **delete**-
 * region control, now 56dp — destructive controls get the higher floor
 * here, not the usual 48), four sites on `PeerChatScreen.kt`'s own
 * `TOUCH = 44.dp`, and `PeopleScreen.kt`'s `TOUCH = 40.dp` — are all fixed
 * and reviewed for neighbour overlap. Ceiling=0 pins that ground: the first
 * `.clickable` chained onto a `.size()` under 48dp anywhere in the scanned
 * roots, old or new, fails this test immediately instead of waiting for
 * someone to notice on a cold, gloved, moving-vehicle tap.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate or a stale green will hide a count move.
 */
class TouchTargetFirewallTest {

    // 2026-08-29 — двадцять пʼять, що наросли з месенджерною хвилею, зняті:
    // шістнадцять коробок нічого не малювали на своєму розмірі, тож просто
    // виросли до 48dp; девʼять малювали заливку або рамку, і в них площа
    // дотику стала 48dp, а намальоване лишилось на місці через внутрішній
    // відступ (.size(48.dp).clickable{}.padding(N.dp).background(...)).
    // Стеля повертається до нуля — туди, куди її ставив попередній прохід і
    // де про неї писала дока цього файла, поки код казав 11.
    //
    // 2026-08-15 — the nine surfaced by the previous pass are now fixed too:
    // MapSearchSheet.kt:167, PlaceDossierSheet.kt:634 (bare Icon -> wrapped
    // in a 48dp Box), OfflineTerritoriesPanel.kt:260 (delete control, 56dp),
    // PeerChatScreen.kt's four TOUCH sites (44dp -> 48dp; the one with a
    // background — the send button — kept its own SEND_VISUAL = 44.dp and
    // moved .clickable to a new outer box), and PeopleScreen.kt's TOUCH
    // (40dp -> 48dp). Zero is the new floor, not a resting point — the next
    // offender this test finds is a genuine regression, not backlog.
    private val ceiling = 0

    private val floorDp = 48.0

    private val uiRoots = listOf(
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
        "feature-precision/src/main",
        "core-design/src/main",
        // Declares no Compose UI today (AndroidX Car App Library, its own
        // touch model) — listed so the first `.clickable` that lands there
        // is not invisible on the day it does.
        "feature-car/src/main",
    )

    // A floor, not a fixed count: files are added and deleted daily. It
    // exists so a moved package or a broken root cannot empty the input and
    // leave the check below trivially green. 540 today.
    private val inspectedFloor = 500

    private val clickableCall = Regex("""\.(clickable|combinedClickable)\s*[({]""")
    private val sizeLiteral = Regex("""\.size\(\s*([0-9]+(?:\.[0-9]+)?)\.dp\s*\)""")
    private val sizeIdentifier = Regex("""\.size\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")
    private val constDecl =
        Regex("""(?:private\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*Dp\s*)?=\s*([0-9]+(?:\.[0-9]+)?)\.dp""")

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

    private fun candidates(): List<File> = uiRoots.flatMap { ktFiles(it) }

    /**
     * A modifier chain as this codebase writes it: one call per line, each
     * continuation line starting with `.`, anchored by a `Modifier`/
     * `modifier = Modifier` line — or, just as often here, the whole chain
     * on one line. Walks backward from [clickableLine] only through
     * continuation lines, so a `.size()` that belongs to an unrelated,
     * preceding box is never pulled in.
     */
    private fun chainWindow(lines: List<String>, clickableLine: Int): String {
        val parts = ArrayDeque<String>()
        parts.addLast(lines[clickableLine])
        var i = clickableLine - 1
        while (i >= 0) {
            val t = lines[i].trim()
            when {
                t.startsWith(".") -> {
                    parts.addFirst(lines[i])
                    i--
                }
                t == "Modifier" || t.endsWith("Modifier") || Regex("""modifier\s*=\s*Modifier\b""").containsMatchIn(t) -> {
                    parts.addFirst(lines[i])
                    return parts.joinToString("\n")
                }
                else -> return parts.joinToString("\n")
            }
        }
        return parts.joinToString("\n")
    }

    private fun offenders(): List<String> {
        val out = mutableListOf<String>()
        for (file in candidates()) {
            val text = file.readText()
            val lines = text.split("\n")
            val consts = constDecl.findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val rel = file.relativeTo(repoRoot()).path

            for ((i, line) in lines.withIndex()) {
                if (!clickableCall.containsMatchIn(line)) continue
                val window = chainWindow(lines, i)

                val literal = sizeLiteral.find(window)
                val (value, how) = if (literal != null) {
                    literal.groupValues[1].toDouble() to "${literal.groupValues[1]}.dp"
                } else {
                    val ident = sizeIdentifier.find(window) ?: continue
                    val resolved = consts[ident.groupValues[1]] ?: continue
                    resolved to "${ident.groupValues[1]} = ${resolved}.dp"
                }

                if (value < floorDp) {
                    out.add("$rel:${i + 1}  .size($how) on a clickable chain")
                }
            }
        }
        return out.sorted()
    }

    @Test
    fun `the sweep never inspects nothing`() {
        val seen = candidates()
        assertTrue(
            "this gate inspected ${seen.size} Kotlin files, floor is $inspectedFloor.\n" +
                "  A rename or a moved package emptied the input. Every check below is " +
                "trivially green while that is true, which is worse than the bug it exists " +
                "to catch.",
            seen.size >= inspectedFloor,
        )
    }

    @Test
    fun `clickable targets under 48dp never grow past today's count`() {
        val found = offenders()

        assertEquals(
            "clickable/combinedClickable chained onto a .size() under 48dp is " +
                "${found.size}, ceiling says $ceiling.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  Ceiling is 0: every previously known offender is fixed, so this " +
                "can only rise, never fall. A new or shrunk control is now too small " +
                "for a gloved, shaking hand to hit reliably. Fix it the way this " +
                "file's own doc comment describes — grow the tap area (a bigger " +
                "outer Box, or Modifier.minimumInteractiveComponentSize()), never " +
                "the drawn glyph — then this assertion goes back to 0 = 0.",
            found.size,
            ceiling,
        )
    }
}
