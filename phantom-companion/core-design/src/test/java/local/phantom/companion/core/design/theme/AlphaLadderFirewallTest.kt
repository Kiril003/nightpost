package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The opacity ladder's ratchet, built like `ShapeDriftFirewallTest`.
 *
 * Scope is deliberately narrow: this guards only the alphas whose value **is**
 * a rung. Writing `copy(alpha = 0.55f)` when [PhantomAlpha.Secondary] is that
 * exact float is drift with no upside — the number and the token render the
 * same pixel, and only one of them survives a change to the ladder.
 *
 * The 388 remaining literals in these two modules (0.5, 0.6, 0.7, 0.45, 0.35,
 * 0.85 …) are NOT policed here and must not be: none of them is a rung, and
 * rounding them into one would repaint the app under cover of a refactor. They
 * are the argument for re-cutting the ladder, not for widening this test.
 */
class AlphaLadderFirewallTest {

    /**
     * Every `copy(alpha = …)` whose literal equals a rung.
     *
     * The rungs moved on 2026-08-16 (the bottom four were drawing lines at
     * 1.1–1.3:1); these are the new ones. They are deliberately not round
     * decimals, so `0.1f` / `0.2f` / `0.5f` — of which the tree is full, none
     * of them a rung — stay other people's business and not this test's.
     */
    private val rawRungAlpha = Regex(
        """copy\(\s*alpha\s*=\s*0?\.(?:09|19|28|38|48|55|72|88)f\b""",
    )

    /**
     * feature-stream's ground on 2026-08-15, after the ladder sweep. All 17 sit
     * in six files another agent held open that night (TurnTrace, MapToolsCapsule,
     * MapControls, OfflineTerritoriesPanel, WhisperPreviewScreen, CanvasWidget)
     * and were skipped rather than collided with.
     *
     * A ceiling, not an equality: the tree is edited by many hands at once and a
     * strict count turns another agent's unrelated deletion into a red gate.
     * Rising still means someone hand-wrote a rung.
     */
    private val featureStreamCeiling = 17

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun offendersIn(relPath: String): List<String> {
        val dir = File(repoRoot(), relPath)
        if (!dir.isDirectory) {
            // ПЕРЕДУМОВА, а не дрібниця: якщо теки немає, обхід поверне порожньо,
            // «порушників» не буде — і сторож стане ЗЕЛЕНИМ ні про що саме тоді,
            // коли пакет перейменували чи перенесли, тобто в мить найімовірнішого
            // дрейфу. Мовчазна порожнеча тут гірша за відсутність сторожа.
            fail("сторож дивиться в порожнє: не існує $relPath")
        }
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    rawRungAlpha.findAll(line).map { "${f.name}:${i + 1}  ${it.value}" }
                }
            }
            .toList()
    }

    @Test
    fun `the design-system module never writes a rung as a raw number`() {
        val offenders = offendersIn("core-design/src/main")

        assertTrue(
            "core-design defines the opacity ladder and does not get to guess:\n" +
                offenders.joinToString("\n") +
                "\nUse PhantomAlpha.{Ghost,Hairline,Wash,Veil,Recessed,Secondary,Emphasis,Near}.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `hand-written rungs in feature-stream never grow`() {
        val offenders = offendersIn("feature-stream/src/main")

        assertTrue(
            "raw rung-valued copy(alpha = N) in feature-stream is ${offenders.size}, " +
                "ceiling says $featureStreamCeiling.\n" +
                offenders.joinToString("\n") +
                "\n  ↑ rose: that number IS a rung — write PhantomAlpha.<rung> instead.\n" +
                "  ↓ fell: good. Lower featureStreamCeiling to ${offenders.size}.",
            offenders.size <= featureStreamCeiling,
        )
    }
}
