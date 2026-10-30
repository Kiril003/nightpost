package local.phantom.companion.core.design.boot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Brief 03 defect #1 — the composition-time asset-parse firewall.
 *
 * `remember { OblastShapes.load(context) }` reads as free. It is not: that
 * lambda runs on the UI thread during composition, and behind it sit 708 KB of
 * oblast geojson and a 156 KB region index. At the Continuum shell — which
 * composes on the very first frame — that was a 100–300 ms stall on a weak
 * phone before the operator saw anything. A background warm-up at boot only
 * *races* it; it cannot be relied on to win, so the seam itself has to change:
 * the shell now hands the loaders down as lambdas and the ViewModel pulls them
 * on `Dispatchers.IO`, attaching the result when it lands.
 *
 * This ratchets the direct form so the cheap-looking version cannot creep back.
 *
 * A failure here means: you parsed a bundled geo asset inside a `remember {}`.
 * Pass a `suspend () -> T` loader down instead and resolve it off the main
 * thread — see `MapViewModel.oblastShapesLoad`.
 *
 * GATE LAW (shared with the motion/shape/lifecycle firewalls): this test reads
 * OTHER modules' sources as raw file I/O, invisible to Gradle's up-to-date
 * check. Run `:core-design:testDebugUnitTest --rerun` at every wave gate.
 */
class MainThreadAssetParseFirewallTest {

    /**
     * Today's count (2026-07-21): one — `MapScreen`'s own
     * `remember { OblastShapes.load(context) }`. That one is knowingly left:
     * it runs when the MAP first composes, not at shell boot, by which time
     * the warm-up thread has had seconds and the loader's double-checked cache
     * makes it a hit. Undoing it means threading nullability through six
     * non-null `MapCanvas` call sites, which is not worth doing blind.
     *
     * This may fall. It may not rise.
     */
    private val composedLoadCeiling = 1

    /** The same UI roots the motion and lifecycle firewalls ratchet over. */
    private val uiRoots = listOf(
        "core-design/src/main",
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /**
     * A bundled-asset load evaluated DIRECTLY in a `remember` block: no brace
     * may stand between the two. That deliberately spares the seam we want —
     * a load captured inside a nested lambda (`refOf = { id -> ... }`,
     * `oblastShapesLoad = { ... }`), which no UI thread invokes.
     */
    private val composedLoad =
        Regex("""remember\s*(?:\([^)]*\))?\s*\{[^{}]*(?:OblastShapes|RegionIndex)\.load\(""")

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
    fun `bundled geo assets are never parsed inside a remember block`() {
        val hits = uiRoots
            .flatMap { kotlinFilesIn(it) }
            .flatMap { f -> composedLoad.findAll(f.readText()).map { f.name } }

        assertEquals(
            "geo-asset parses inside remember{} = ${hits.size} ($hits), ceiling says " +
                "$composedLoadCeiling.\n" +
                "  ↑ rose: a composable parses a bundled asset on the UI thread — pass a\n" +
                "    suspend loader down and resolve it on Dispatchers.IO instead.\n" +
                "  ↓ fell: good. Set composedLoadCeiling = ${hits.size} to keep the ground.",
            composedLoadCeiling,
            hits.size,
        )
    }
}
