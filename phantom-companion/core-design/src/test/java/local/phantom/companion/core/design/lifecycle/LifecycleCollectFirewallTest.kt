package local.phantom.companion.core.design.lifecycle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.fail

/**
 * BATTERY-1 — the lifecycle-collection firewall, built the same way
 * [local.phantom.companion.core.design.motion.MotionDriftFirewallTest] guards
 * motion: a ratchet, not a wish.
 *
 * A bare `flow.collectAsState()` inside a composable keeps collecting while the
 * app is backgrounded — the map pipeline, the sensor feed, the alert poll all
 * keep running behind a black screen, and the phone is flat by evening. The
 * lifecycle-aware `collectAsStateWithLifecycle()` stops at STOPPED and resumes
 * at STARTED, which is what almost every screen actually wants. The map and
 * stream surfaces (the always-composed, high-traffic pipelines) were migrated
 * in `02a72fe` + `b8351cb`; this firewall keeps the count from creeping back
 * up as new screens land.
 *
 * The remaining sites are, by inspection, lower-stakes: settings screens
 * (composed only while the operator is IN settings, backing cheap preference
 * flows) and a few overlay-window service compositions whose lifecycle owner is
 * unusual. They may migrate freely; they may not multiply.
 *
 * A failure here means: you added a `collectAsState()`. Either use
 * `collectAsStateWithLifecycle()` (the right answer for a screen), or — if this
 * genuinely must collect while backgrounded — lower the ceiling by hand in the
 * same commit and say why in review.
 *
 * GATE LAW (shared with the motion/shape firewalls): this test reads OTHER
 * modules' sources as raw file I/O, invisible to Gradle's up-to-date check.
 * Run `:core-design:testDebugUnitTest --rerun` at every wave gate.
 */
class LifecycleCollectFirewallTest {

    /**
     * Today's count (2026-07-21). Every one is a settings screen or an
     * overlay-service composition — see the class doc. Lower this as sites
     * migrate to `collectAsStateWithLifecycle()`; raising it needs an argument.
     */
        // 2026-08-01 (−12 → 31): злиття чотирьох гілок робітників.
    //
    // 2026-08-15 (→ 30): the scan now skips comment lines. Two of the 32 it
    // was counting are KDoc prose — `AudioLevel.kt:13` and
    // `QuickAskBubble.kt:44` — so this ceiling has been two above the real
    // ground since it was written, and 31 was never a true measurement.
    // Honest accounting: 31 contaminated on 2026-08-01 was 29 real, and the
    // tree measures 30 real today. One genuine bare collector landed in
    // between and is NOT reclaimed here — it belongs to whoever added it, in
    // a file this wave does not own. Named so it is not lost.
    // 2026-08-16 (→ 29): the twenty-five-agent wave landed three new bare
    // collectors — MicPrivacyScreen's liveDialogEnabled, OfflinePlacesSection's
    // region list and the peer secure-window wrapper. All three are now
    // collectAsStateWithLifecycle(); the OfflinePlaces one mattered most, it
    // backs an offline-download manager that kept ticking behind a black
    // screen. HEAD measured 29 before the wave and measures 29 again, so this
    // is the true ground rather than the one-above-ground 30 the note below
    // left standing.
    // 05.09: 29 -> 31. Обидва нові — у WhisperLineService (feature-ambient),
    // рядки 183 і 185: накладка-стрічка живе у СЛУЖБІ ПЕРЕДНЬОГО ПЛАНУ й
    // існує рівно для того, щоб оновлюватись, коли застосунок у фоні.
    // collectAsStateWithLifecycle привʼязав би її до життєвого циклу, якого
    // в неї немає, і стрічка б замерзла — тобто ліки вбили б функцію.
    // Це виняток для накладок служби, НЕ для екранів: якщо число росте, а
    // новий рядок не в службі — це та сама вада, від якої ворота й стоять.
    private val bareCollectCeiling = 24

    /** Same UI roots the motion firewall ratchets over. */
    private val uiRoots = listOf(
        "core-design/src/main",
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /** The bare, non-lifecycle collector: `.collectAsState()` with no args
     *  (a StateFlow read). The `collectAsStateWithLifecycle()` form and the
     *  `collectAsState(initial)` overload for cold Flows are both left alone. */
    private val bareCollect = Regex("""\.collectAsState\(\s*\)""")

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

    /**
     * Comment lines are skipped, exactly as `HardcodedCyrillicFirewallTest`
     * skips them. Two of the sites this gate was counting are prose:
     * `AudioLevel.kt:13` and `QuickAskBubble.kt:44` both SPELL the call in a
     * KDoc to say where the value comes from. Neither collects anything, and
     * a doc comment cannot flatten a battery.
     */
    private fun countInFile(file: File): Int =
        file.readText().lineSequence().sumOf { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) 0
            else bareCollect.findAll(line).count()
        }

    @Test
    fun `bare collectAsState never grows`() {
        val total = uiRoots.flatMap { kotlinFilesIn(it) }.sumOf { countInFile(it) }

        assertEquals(
            "bare flow.collectAsState() in the UI tree is $total, ceiling says " +
                "$bareCollectCeiling.\n" +
                "  ↑ rose: a screen collects while backgrounded — use " +
                "collectAsStateWithLifecycle().\n" +
                "  ↓ fell: good. Set bareCollectCeiling = $total to keep the ground.",
            total,
            bareCollectCeiling,
        )
    }
}
