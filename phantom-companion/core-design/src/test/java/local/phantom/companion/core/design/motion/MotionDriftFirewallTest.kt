package local.phantom.companion.core.design.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Μ0 — the motion firewall, built the same way [ShapeDriftFirewallTest]
 * guards the corner scale: a ratchet, not a wish.
 *
 * [MotionTokens] is the mint; [PhantomMotion] is the vocabulary. A raw
 * `tween(…)` / `spring(…)` / `keyframes(…)` constructor anywhere else is
 * an invented motion — a duration or stiffness somebody guessed, which is
 * exactly how the nav graph grew a transition zoo the first time.
 *
 *  - the navigation graph and the home shell (the files that *define* how
 *    the body moves) carry **zero** raw constructors;
 *  - the rest of the UI tree has a ceiling at today's count. It may fall,
 *    freely and without ceremony. It may not rise.
 *
 * A failure here is not «the test is annoying». It is: you invented
 * motion. Use a [PhantomMotion] verb, or — if the vocabulary genuinely
 * lacks one — add the verb to the grammar and say why in review.
 */
class MotionDriftFirewallTest {

    /**
     * Today's count (2026-07-16, Μ0 landing). Every one of these predates
     * the grammar. Lower this number as call-sites migrate to
     * [PhantomMotion] verbs; raising it needs an argument.
     */
    // Μ1b: 68 → 66 — the scroll-FAB's two raw fade tweens died with the
    // FAB itself (the Meridian pole rides the mint's unfoldQuart).
    // Μ2b/Μ3/Μ4: 66 → 57 — the fake-theater purges (SystemPulseHeader,
    // old Body rings, old Agenda) took nine invented clocks with them.
    //
    // GATE LAW (learned 2026-07-17): this test reads OTHER modules'
    // sources as raw file I/O, which Gradle's up-to-date check cannot
    // see — a wave gate that doesn't touch core-design shows a STALE
    // green here. Run `:core-design:testDebugUnitTest --rerun` at every
    // wave gate.
    // 2026-07-19 (LUX-7 calm pass): the map waves had crept to 59; the two
    // alarm-cadence dots (AlarmSheet, MapChromeParts) now speak the minted
    // pulseUrgent, and the tree sits exactly back on the 57 line.
    // VISUAL DAY 2 · Step 5 (57 → 55): the intent-chip dock's two raw fade
    // tweens (tween(220)/tween(160) in StreamScreen's StreamAmbientLayer) died
    // when the dock moved INLINE into the timeline — the trailing row now rides
    // the mint's fadeQuart via LazyItemScope.animateItem.
    // Μ6 touch verb (55 → 54): PttButton's hand-picked tween(600) pulse was the
    // last organ keeping its own clock — AwaitingFinal is PHANTOM working on
    // what it heard, so it now breathes on the minted thinking tempo the
    // presence orb and the Meridian already share.
    //
    // Μ6 chat sweep (54 → 41). The chat carried SIX different «I am working»
    // heartbeats — 600/700/800/800/1200/1600ms across ComposeRow, InputBar and
    // AssistantProse — one body with six pulses. All the working ones now ride
    // pulseSlow. Two were genuinely NOT breaths and the grammar was missing
    // them, so they were minted rather than bent (see MotionTokens Μ6):
    // `caretBlink`, because a cursor at the thinking tempo reads as a render
    // fault, and `voicePresenceIn/Out`, the one sanctioned asymmetric pair —
    // it morphs the operator's depth, it does not open a surface, so the
    // exit-is-faster law does not apply. The swipe-to-quote release also stopped
    // being a tween: the finger drove it, so it settles on the gesture spring.
    //
    // Μ6 working-organs sweep (41 → 32). Seven more «I am working» clocks —
    // the progress dot, the agent dot, three tool-trace pulses — joined
    // pulseSlow, and the two live quantities (progress fill, file transfer)
    // moved onto meterLinear, which is linear for the reason the verb was
    // minted: an eased curve on a target that keeps being re-set reads as
    // surging. Two of the nine were never code at all — countInFile now skips
    // comment lines, so a KDoc explaining «the snap spring (180 / 0.75)» no
    // longer counts as an instance of inventing one.
    // 33 → 32: the whisper preview's «ЗАПИС» badge had its own 1000ms pulse.
    // It went with the badge — a coral heartbeat asserting a recording that
    // was never happening.
        // 2026-08-01 (−2 → 30): злиття чотирьох гілок робітників.
    // 2026-08-07 (−1 → 29): дихання оболонки більше не сирий tween на кадровому
    // годиннику — крокує таймером, ~3 пробудження/с замість 60.
    // 2026-08-16 (−1 → 28): LivePresenceHeader's own spring folded into the
    // grammar during the twenty-five-agent wave. Measured per file against
    // committed HEAD — it is the only file in the tree whose count moved.
    // 2026-08-16, пізній вечір (−4 → 24). Щоденник, лінза й фасети перейшли з
    // власних tween на дієслова PhantomMotion. Знято на затихлому дереві.
    // 24 → 25: кільце утримання «стоп» у GuidanceChrome. Це єдиний випадок у
    // дереві, де сирий tween не є дрейфом, а вимогою: кільце показує СПРАВЖНІ
    // 800 мс утримання пальця, і будь-яка дизайнерська крива змусила б його
    // брехати про час — палець дійшов би до кінця раніше або пізніше за кільце.
    // 2026-09-03 (−5 → 30): ґрунт, зданий чужими хвилями без опускання стелі.
    // Не мій виграш: цей прогін лише забирає слабину. Число однакове на дереві
    // й на закоміченому HEAD, тож воно виміряне, а не зняте з чийогось столу.
    // 2026-09-06 (−1 → 26): `swipeOffset.animateTo(0f, spring())` — відпускання
    // свайпу-цитати в PeerChatScreen.kt. Єдина сира крива в усіх дванадцяти
    // файлах, які пішли з застосунком рідної розмови; решта видаленого сирих
    // кривих не мала. Не виграш цієї хвилі: число впало разом із екраном, і
    // ратчет просто забирає слабину, щоб наступна крива була видна.
    // 2026-09-12 (−1 → 25): знято LensScreen разом із його променем-сканером —
    // `rememberInfiniteTransition` + `tween(2500, LinearEasing)`. Екран замінив
    // ЗІР, який нескінченних кривих не заводить. Не виграш хвилі: число впало
    // разом з екраном, і ратчет забирає слабину, щоб наступна крива була видна.
    // 2026-10-04 (−1 → 6): перший запуск на мові форми 1.2 — заграва онбордингу
    // («ob-bg», нескінченний tween на власному годиннику) пішла: полотно стоїть.
    // 2026-10-04 (−3 → 4): system-and-subtraction видалив мертву стрічку карток —
    // ui/live/VoiceModeOverlay (2 сирі криві) і ui/widget/TacticalHudWidget (1).
    // Криві пішли разом із кодом, який ніхто не викликав; ратчет забирає слабину.
    private val rawMotionCeiling = 2

    /** The files that define how the body moves cannot themselves drift. */
    private val zeroTolerance = listOf(
        "app/src/main/java/local/phantom/companion/navigation",
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

    /** The mint itself — the one place raw specs are legal by definition. */
    private val mint =
        "core-design/src/main/java/local/phantom/companion/core/design/motion"

    /** A raw motion constructor: `tween(`, `spring(`, `keyframes(`. */
    private val rawMotion = Regex("""\b(tween|spring|keyframes)\s*\(""")

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

    private fun isMint(f: File): Boolean =
        f.absolutePath.contains(mint.replace('/', File.separatorChar))

    /**
     * Count on code lines only. The regex tolerates a space before the paren,
     * so prose like «the canonical snap spring (180 / 0.75)» — which several
     * KDoc headers use to *explain* the grammar — was being counted as a
     * violation of it. A gate that a comment can move is measuring the wrong
     * thing; the Cyrillic firewall skips comment lines for the same reason.
     */
    private fun countInFile(f: File): Int =
        f.readText().lineSequence().sumOf { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) 0
            else rawMotion.findAll(line).count()
        }

    @Test
    fun `the navigation graph and home shell never invent motion`() {
        val offenders = zeroTolerance
            .flatMap { kotlinFilesIn(it) }
            .mapNotNull { f ->
                val n = countInFile(f)
                if (n > 0) "${f.name}: $n raw tween/spring/keyframes" else null
            }

        assertTrue(
            "the files that DEFINE how the body moves must speak the grammar:\n" +
                offenders.joinToString("\n") +
                "\nUse PhantomMotion.{chamberEnter,chamberExit,chamberReturnEnter," +
                "chamberReturnExit,bloomEnter,bloomExit,rackSpring} or a MotionTokens spec.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `raw motion constructors never grow`() {
        val total = uiRoots
            .flatMap { kotlinFilesIn(it) }
            .filterNot(::isMint)
            .sumOf(::countInFile)

        // Strict equality — the whole ratchet in one line (see
        // ShapeDriftFirewallTest for the argument).
        assertEquals(
            "raw tween/spring/keyframes in the UI tree is $total, ceiling says " +
                "$rawMotionCeiling.\n" +
                "  ↑ rose: you invented motion — use a PhantomMotion verb.\n" +
                "  ↓ fell: good. Set rawMotionCeiling = $total to keep the ground.",
            total,
            rawMotionCeiling,
        )
    }
}
