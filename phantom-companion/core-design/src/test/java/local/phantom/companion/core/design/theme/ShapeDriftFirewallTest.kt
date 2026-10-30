package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The corner scale's guard, built the same way `TypographyDriftFirewallTest`
 * guards the type scale — because the lesson of this codebase is that a token
 * nobody enforces is not a token, it is a suggestion.
 *
 * `PhantomSpacings` proves the point: an 8px grid was written, documented as
 * «mathematically rigid», and then used in **one single file** while 2126 raw
 * `dp` literals accumulated around it. Nothing failed, so nothing stopped.
 *
 * So this test is a **ratchet**, not a wish:
 *
 *  - the design-system core (`core-design/component`, the `app/ui/home` shell) must
 *    carry **zero** raw `RoundedCornerShape(N)` — these are the files that
 *    define what a corner means, and they do not get to guess;
 *  - the rest of the UI tree has a **ceiling** at today's count. It may fall,
 *    freely and without ceremony. It may not rise.
 *
 * A failure here is not «the test is annoying». It is: you invented a corner.
 * Use [PhantomRadii], or — if the scale genuinely lacks a rung — add the rung
 * and say why in review.
 */
class ShapeDriftFirewallTest {

    /**
     * Today's count. Every one of these is a corner someone guessed before the
     * scale existed. Lower this number as they are migrated; raising it needs
     * an argument.
     */
    // 2026-07-16: 285 → 284 — PhotoHeroWidget's dead vault button took two
    // raw pills with it (one came back as the load-failure pill). The
    // ratchet clicks down.
    // 2026-07-17 (Μ2b/Μ3): 284 → 277 — the fake-theater purges
    // (SystemPulseHeader, old People/Agenda bodies) took seven invented
    // corners with them. NOTE: this test reads other modules' sources as
    // raw file I/O — invisible to Gradle's up-to-date check; run with
    // `--rerun` at wave gates or a stale green will hide count moves.
    // 2026-07-19 (LUX-7 calm pass): 277 → 236. The transit/map/alarm waves
    // had quietly pushed the tree to 302 (the gate law above was ignored at
    // their gates); the six noisiest map-chrome files (MapChromeParts,
    // MapToolsCapsule, MapSearchSheet, MapCommandSheet, AlarmSheet,
    // PoiMarkerOverlay) were migrated to PhantomRadii wholesale — 66 guessed
    // corners became rungs, and the ground is 41 below the old ceiling.
    // 2026-07-21 (VISUAL DAY 1 settings hero): 236 → 235. The SettingsRealm
    // `StateButton` (a raw `RoundedCornerShape(8.dp)`) was replaced by the
    // PhantomRadii-based `SettingsSegmented`; its one guessed corner left with it.
    // 2026-07-22 (VISUAL DAY 2 Vault onto the kit): 235 → 227. The secret room
    // rode its own corners — six raw pills and two 14dp cards. Folded to
    // PhantomRadii.{Pill,LargeShape}; eight guessed corners left with them.
    // 2026-07-24 (−6): the guidance banner's hand-picked 17dp/14dp corners
    // folded into PhantomRadii along with the rest of this wave's chrome.
    // 2026-07-26 (night wave): 225 → 146. The tree had drifted four above the
    // ceiling under a stale green — exactly the failure the note above predicts.
    // Paying it down showed 79 of the "raw" corners were `RoundedCornerShape(50)`,
    // the percent overload — a hand-written Pill in 26 files. They are the token.
    // 2026-07-26 (−2): the chat-atom wave migrated two more guessed corners in
    // feature-stream. Not my change; the ratchet follows the ground either way,
    // which is the only reason it stays honest.
    // 2026-07-27 (−2 → 137): the orphaned-screen sweep. PermissionsScreen's
    // 14dp «Готово» button and ModelDownloadStep's 2dp progress-track clip
    // were the only guessed corners in the deleted files. Ground gained by
    // deletion counts the same as ground gained by migration.
    // 2026-07-27 (−6 → 131): the chamber-rewrite wave. ПАМʼЯТЬ lost the radial
    // memory graph and every hand-picked corner with it; ТІЛО, РУКИ, ДЕНЬ and
    // СХОВИЩЕ moved theirs onto PhantomRadii while their states were rebuilt.
    // 2026-07-27 (−1 → 130): the onboarding rebuild moved its last guessed
    // corner onto PhantomRadii.
    // −41 → 87: the card kit put every corner on the scale in one pass.
    // −6 → 81: the chat surface followed — chip rail, composer, table, quote.
        // 2026-08-01 (−6 → 75): злиття чотирьох гілок робітників.
    // 2026-08-15 (−1 → 74): HistorySearchScreen's private FilterChip left with
    // the chip consolidation, and `RoundedCornerShape(999.dp)` went with it —
    // a guessed corner that was only ever trying to say «capsule», which is
    // what PhantomRadii.Pill says exactly, at any height.
    // 2026-08-16 (−45 → 29): the twenty-five-agent wave put feature-stream's
    // chat, live and map chrome onto PhantomRadii wholesale — TurnTrace (9),
    // MentionPicker (5), CouncilDebateCard, CommandPalette and GuidanceChrome
    // (4 each), VoiceModeOverlay, ThreatBriefSheet (3 each) and a dozen
    // single-corner files. Measured per file against committed HEAD: 75 → 29,
    // every one of the 46 a migration, none a deletion of a screen.
    // 2026-08-16, пізній вечір (−15 → 13). Спершу хвиля форми зняла рамку
    // GlassCard з кожного рядка «Людей», потім тринадцять добудованих екранів
    // перейшли з вручну підібраних кутів на PhantomRadii. Знято на затихлому
    // дереві, не під час чужого запису.
    // 2026-08-24 (+3 → 71): stale-green resync, same cause and same method as
    // the Cyrillic ratchet's entry of this date. Counted over a clean `git
    // archive HEAD` export; the working tree agrees at 71 and no uncommitted
    // diff adds a RoundedCornerShape(N). Debt from earlier commits, surfaced by
    // the first --rerun in a while, not invented here.
    // 04.10: 47 → 43, ПОСИЛЕНО поверхнею мапи v1.2: пігулки, плита, капсула,
    // пошук і офлайн-панель — на PhantomRadius, а не RoundedCornerShape(N).
    // 2026-10-04 (−2 → 41): сітка профілів (ProfileLauncherScreen) — дві плитки
    // з RoundedCornerShape(20.dp) стали PhantomRadius.sheet мови форми 1.2.
    // 2026-10-04 (−3 → 44): system-and-subtraction deleted QuickAskBubble (2)
    // and PhantomMenu (1), both unreachable since the messenger moved to the
    // web base. The corners left with the files; not a migration win, the
    // ratchet just takes the slack.
    // 04.10 (−35 → 12): аркуші мапи (досьє, смуга маршруту, хаб транспорту,
    // відповідь PHANTOM) перейшли на PhantomRadius v1.2 одним каркасом MapSheet.
    // Виміряно по закоміченому дереву гілки cloud/sweep-map-sheets проти b629fd08.
    private val rawRadiusCeiling = 3

    /** Files that define the design system cannot themselves drift. */
    private val zeroTolerance = listOf(
        "core-design/src/main/java/local/phantom/companion/core/design/component",
        "app/src/main/java/local/phantom/companion/ui/home",
    )

    private val uiRoots = listOf(
        "core-design/src/main",
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /** A literal radius: `RoundedCornerShape(12` / `RoundedCornerShape( 8`. */
    private val rawRadius = Regex("""RoundedCornerShape\(\s*[0-9]""")

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

    private fun countIn(files: List<File>): Int =
        files.sumOf { rawRadius.findAll(it.readText()).count() }

    @Test
    fun `the design-system core never invents a corner`() {
        val offenders = zeroTolerance
            .flatMap { kotlinFilesIn(it) }
            .mapNotNull { f ->
                val n = rawRadius.findAll(f.readText()).count()
                if (n > 0) "${f.name}: $n raw RoundedCornerShape(N)" else null
            }

        assertTrue(
            "the files that DEFINE the corner scale must use it:\n" +
                offenders.joinToString("\n") +
                "\nUse PhantomRadii.{Tight,Small,Medium,Large,XLarge,Hero,Pill}.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `raw corner radii never grow`() {
        val total = countIn(uiRoots.flatMap { kotlinFilesIn(it) })

        // A strict equality, deliberately — it is the whole ratchet in one line.
        //
        // Went UP: you invented a corner. Use PhantomRadii.
        // Went DOWN: you migrated some. Lower the ceiling to match, in the same
        // commit, or the slack you just earned silently becomes room for the
        // next regression to hide in.
        assertEquals(
            "raw RoundedCornerShape(N) in the UI tree is $total, ceiling says " +
                "$rawRadiusCeiling.\n" +
                "  ↑ rose: a hand-picked corner is drift — use PhantomRadii.\n" +
                "  ↓ fell: good. Set rawRadiusCeiling = $total to keep the ground.",
            total,
            rawRadiusCeiling,
        )
    }

    @Test
    fun `the corner scale rungs stay on the grid`() {
        // The scale is only a scale if its steps are the 4/8 grid. A future
        // «PhantomRadii.Large = 17.dp» would make it a zoo with a nicer name.
        listOf(
            "Tight" to PhantomRadii.Tight.value,
            "Small" to PhantomRadii.Small.value,
            "Medium" to PhantomRadii.Medium.value,
            "Large" to PhantomRadii.Large.value,
            "XLarge" to PhantomRadii.XLarge.value,
            "Hero" to PhantomRadii.Hero.value,
        ).forEach { (name, value) ->
            assertEquals(
                "PhantomRadii.$name = ${value}dp is off the 4dp grid",
                0f,
                value % 4f,
                0.001f,
            )
        }
    }
}
