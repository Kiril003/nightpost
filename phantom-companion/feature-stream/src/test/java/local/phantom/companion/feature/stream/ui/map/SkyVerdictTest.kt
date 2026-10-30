package local.phantom.companion.feature.stream.ui.map

import java.io.File
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * СИРЕНА-3 — свіжість стрічки як ОДНЕ джерело правди, суджене при ЧИТАННІ.
 *
 * Виміряно на склі 03.09: мапа фарбувала ґрунт і писала «почалась 7 год тому»,
 * поки шторка поруч казала «стрічка тривог мовчить». Одне з двох було брехнею,
 * і жодне не могло почервоніти — бо рахували вони порізно.
 */
class SkyVerdictTest {

    private val now = 1_770_000_000_000L
    private val sevenHours = 7 * 60 * 60 * 1000L

    private fun state(active: Boolean) = AlertState(active = active, changedEpochMs = now - sevenHours)

    private fun sky(active: Boolean, fetchedAtMs: Long?) = AlertSky(
        oblasts = mapOf(OWN to state(active)),
        ownOblast = OWN,
        fetchedAtMs = fetchedAtMs,
    )

    // ── Свіжість ────────────────────────────────────────────────────────────

    @Test
    fun `a feed read just now is fresh`() {
        val s = sky(active = false, fetchedAtMs = now)
        assertEquals(AlertFreshness.FRESH, s.freshnessAt(now))
        assertEquals(SkyVerdict.QUIET, s.verdict(now))
        assertFalse(s.verdict(now).feedSilent)
    }

    @Test
    fun `a feed silent for seven hours is not fresh`() {
        val s = sky(active = false, fetchedAtMs = now - sevenHours)
        assertEquals(AlertFreshness.STALE, s.freshnessAt(now))
        assertEquals(SkyVerdict.STALE, s.verdict(now))
        assertTrue(s.verdict(now).feedSilent)
    }

    /** Ніколи не читана стрічка — це «не знаю», і ніколи не «свіжо». */
    @Test
    fun `a feed never read is null and never fresh`() {
        val s = AlertSky(ownOblast = OWN)
        assertNull(s.fetchedAtMs)
        assertEquals(AlertFreshness.UNKNOWN, s.freshnessAt(now))
        assertEquals(SkyVerdict.NOFEED, s.verdict(now))
        assertTrue(s.verdict(now).feedSilent)
        assertNull("небо без стрічки — не «тихо»", s.ownActiveAt(now))
    }

    /** Поріг — рівно один, і межа належить свіжості. */
    @Test
    fun `the threshold has exactly one edge`() {
        assertEquals(AlertFreshness.FRESH, skyFreshness(now - SKY_STALE_AFTER_MS, now))
        assertEquals(AlertFreshness.STALE, skyFreshness(now - SKY_STALE_AFTER_MS - 1, now))
    }

    // ── Свіжість судиться при читанні, а не при публікації ──────────────────

    /**
     * Ядро правки 03.09. Знімок публікується свіжим і БІЛЬШЕ НІЧОГО не
     * відбувається: жодного тіку, жодної події. У Doze саме так і є — `delay()`
     * циклу спить разом із процесом, а стінний годинник іде. Той самий обʼєкт,
     * прочитаний пізніше, мусить сам сказати, що стрічка мовчить.
     */
    @Test
    fun `a snapshot published seven minutes ago reads silent with no new events`() = runTest {
        var clock = now
        val watch = AirAlertWatch(
            scope = this,
            fetchBase = { mapOf(OWN to state(true)) },
            oblastAt = { _, _ -> OWN },
            locationSource = { LocationFix(lat = 50.45, lon = 30.52) },
            nowMs = { clock },
        )
        assertTrue(watch.pollOnce())
        val published = watch.sky.value
        assertEquals(SkyVerdict.ALERT, published.verdict(clock))

        // Сім хвилин минуло. Цикл не тікав, знімок той самий обʼєкт.
        clock += 7 * 60 * 1000L
        assertSame("знімок мусить лишитись тим самим — судить читач", published, watch.sky.value)
        assertEquals(SkyVerdict.ALERT_NOFEED, published.verdict(clock))
        assertTrue(published.verdict(clock).feedSilent)
    }

    // ── Вирок ───────────────────────────────────────────────────────────────

    @Test
    fun `every verdict is reachable and only silence says the feed is silent`() {
        assertEquals(SkyVerdict.ALERT, sky(active = true, fetchedAtMs = now).verdict(now))
        assertEquals(SkyVerdict.ALERT_NOFEED, sky(active = true, fetchedAtMs = now - sevenHours).verdict(now))
        assertEquals(SkyVerdict.QUIET, sky(active = false, fetchedAtMs = now).verdict(now))
        assertEquals(SkyVerdict.STALE, sky(active = false, fetchedAtMs = now - sevenHours).verdict(now))
        assertEquals(SkyVerdict.NOFEED, AlertSky(ownOblast = OWN).verdict(now))
        assertEquals(SkyVerdict.NO_OBLAST, AlertSky(fetchedAtMs = now).verdict(now))
        assertEquals(
            setOf(SkyVerdict.ALERT_NOFEED, SkyVerdict.STALE, SkyVerdict.NOFEED),
            SkyVerdict.entries.filter { it.feedSilent }.toSet(),
        )
    }

    // ── Домашній підсумок — третій читач ────────────────────────────────────


    // ── Сторожі ─────────────────────────────────────────────────────────────

    /**
     * Рішення дому: протермінована тривога ДАЛІ фарбує площу — мовчання каналу
     * ніколи не читається як безпека, і помилка в інший бік коштує людини.
     * Знімається не пелена, а слово «діє».
     */
    @Test
    fun `a seven-hour alert on a silent feed still paints, and the words say the feed is silent`() {
        val s = sky(active = true, fetchedAtMs = now - sevenHours)

        // Половина перша — шар має що малювати. Саме цей набір бере
        // updateAlertOverlay: alerts.filterValues { it.active }.
        assertEquals(setOf(OWN), s.oblasts.filterValues { it.active }.keys)
        assertEquals("піднята тривога знялась через мовчання каналу", true, s.ownActiveAt(now))

        // Половина друга — слово більше не обіцяє теперішнього часу.
        assertEquals(SkyVerdict.ALERT_NOFEED, s.verdict(now))
        assertTrue(s.verdict(now).feedSilent)
    }

    /**
     * Пелена не має права стати залежною від свіжості. Якщо в шар зайде
     * `AlertFreshness`/`verdict`, це вже інше рішення дому — і воно приймається
     * не мовчазною правкою файлу.
     */
    @Test
    fun `the alert layer never gates its paint on feed freshness`() {
        val code = sourceOf("ui/map/MapAlertLayers.kt")
        for (token in listOf("AlertFreshness", "freshnessAt", "verdict(", "SkyVerdict")) {
            assertFalse(
                "шар тривоги почав гасити площу за свіжістю стрічки ($token) — " +
                    "це скасовує рішення «мовчання каналу не читається як безпека»",
                code.contains(token),
            )
        }
    }

    /**
     * Прочитати тривогу й не спитати про стрічку — це і є дефект домашнього
     * підсумку. Поза модулем компілятор уже не пустить ([AlertSky.ownActiveAt]
     * `internal`); усередині — стереже цей скан.
     *
     * `AirAlertWatch` сам себе питає законно: там вирішується ТЕМП опитування,
     * а не слово на склі.
     */
    @Test
    fun `nobody reads the raw alarm without asking about the feed`() {
        val root = File("src/main/java/local/phantom/companion/feature/stream")
        assertTrue("не знайдено джерела для сканування", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AirAlertWatch.kt" }
            .filter { file ->
                file.readLines()
                    .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
                    .any { it.contains("ownActiveAt(") }
            }
            .map { it.name }
            .toList()
        assertEquals(
            "сира тривога читається повз вирок: ${offenders.joinToString()}",
            emptyList<String>(),
            offenders,
        )
    }

    private fun sourceOf(rel: String): String {
        val f = File("src/main/java/local/phantom/companion/feature/stream/$rel")
        assertTrue("не знайдено: $rel", f.isFile)
        return f.readLines()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private companion object {
        const val OWN = "Київська область"
    }
}
