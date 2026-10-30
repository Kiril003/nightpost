package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.country.CountryPackRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** СИРЕНА — pins the alarm brain: transition detection, phrasing, ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class AlertIntelTest {

    private val now = 1_000_000_000_000L

    // Словник загроз — дані ПАКА, а не літерал у коді: щоб читати українське
    // небо, треба спершу стояти в Україні. Раніше це давала мовчазна гілка
    // «в збірці один пак, отже ми там» — вона померла разом із памʼяттю про
    // країну, і тест мусить називати країну так само, як її називає телефон.
    @Before
    fun standInUkraine() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        CountryPackRegistry.locate(50.4501, 30.5234)
    }

    @After
    fun tearDown() = CountryPackRegistry.reset()

    private fun state(
        active: Boolean,
        changed: Long? = null,
        threat: ThreatType = ThreatType.UNKNOWN,
    ) = AlertState(active = active, changedEpochMs = changed, threatType = threat)

    // ── diffAlerts ──────────────────────────────────────────────────────────

    @Test
    fun `cold start diffs to nothing - an empty previous snapshot is ignorance`() {
        val next = mapOf("Київська область" to state(active = true))
        assertTrue(diffAlerts(emptyMap(), next, now).isEmpty())
    }

    @Test
    fun `a fresh alarm is STARTED with the feed's own stamp`() {
        val prev = mapOf("Київська область" to state(active = false))
        val next = mapOf("Київська область" to state(active = true, changed = now - 60_000L, threat = ThreatType.SHAHED))
        val out = diffAlerts(prev, next, now)
        assertEquals(1, out.size)
        assertEquals(AlertTransitionKind.STARTED, out[0].kind)
        assertEquals(ThreatType.SHAHED, out[0].threatType)
        assertEquals(now - 60_000L, out[0].atEpochMs)
    }

    @Test
    fun `an alarm going quiet is ENDED and remembers what the threat was`() {
        val prev = mapOf("Київська область" to state(active = true, threat = ThreatType.MISSILE))
        val next = mapOf("Київська область" to state(active = false, changed = now))
        val out = diffAlerts(prev, next, now)
        assertEquals(1, out.size)
        assertEquals(AlertTransitionKind.ENDED, out[0].kind)
        assertEquals(ThreatType.MISSILE, out[0].threatType)
    }

    @Test
    fun `a type change mid-alarm is no transition - the journal never shows an empty row`() {
        val prev = mapOf("Київська область" to state(active = true, threat = ThreatType.SHAHED))
        val next = mapOf("Київська область" to state(active = true, threat = ThreatType.BALLISTIC))
        assertEquals(emptyList<AlertTransition>(), diffAlerts(prev, next, now))
    }

    @Test
    fun `losing the typed threat is feed degradation - not a transition`() {
        val prev = mapOf("Київська область" to state(active = true, threat = ThreatType.SHAHED))
        val next = mapOf("Київська область" to state(active = true, threat = ThreatType.UNKNOWN))
        assertTrue(diffAlerts(prev, next, now).isEmpty())
    }

    @Test
    fun `an oblast vanishing from the feed is drift - not a vidbiy`() {
        val prev = mapOf("Київська область" to state(active = true))
        assertTrue(diffAlerts(prev, emptyMap(), now).isEmpty())
    }

    @Test
    fun `a steady sky diffs to nothing`() {
        val snapshot = mapOf(
            "Київська область" to state(active = true, threat = ThreatType.SHAHED),
            "Львівська область" to state(active = false),
        )
        assertTrue(diffAlerts(snapshot, snapshot, now).isEmpty())
    }

    // ── phrasing ────────────────────────────────────────────────────────────

    @Test
    fun `started phrase names the oblast and never the threat type`() {
        val t = AlertTransition("Київська область", AlertTransitionKind.STARTED, ThreatType.SHAHED, now)
        assertEquals("Повітряна тривога — Київська область.", alarmPhraseUk(t))
    }

    @Test
    fun `started phrase claims no threat it does not know`() {
        val t = AlertTransition("Київська область", AlertTransitionKind.STARTED, ThreatType.UNKNOWN, now)
        assertEquals("Повітряна тривога — Київська область.", alarmPhraseUk(t))
    }

    @Test
    fun `ended phrase is the vidbiy`() {
        val t = AlertTransition("Київська область", AlertTransitionKind.ENDED, ThreatType.MISSILE, now)
        assertEquals("Відбій повітряної тривоги — Київська область.", alarmPhraseUk(t))
    }

    // ── durations ───────────────────────────────────────────────────────────

    @Test
    fun `duration reads schoyno then minutes then hours`() {
        assertEquals("щойно", durationShortUk(now - 30_000L, now))
        assertEquals("12 хв", durationShortUk(now - 12 * 60_000L, now))
        assertEquals("1 год 05 хв", durationShortUk(now - 65 * 60_000L, now))
    }

    private fun ago(days: Long) = durationShortUk(now - days * 24 * 3_600_000L, now)

    @Test
    fun `a multi-year alarm rolls over instead of counting hours forever`() {
        // Луганська read «37769 год 59 хв» on the real feed.
        assertEquals("23 год 00 хв", durationShortUk(now - 23 * 3_600_000L, now))
        assertEquals("2 дн 0 год", ago(2))
        assertEquals("1 міс", ago(31))
        assertEquals("4 р", ago(1573))
    }

    @Test
    fun `a future stamp is schoyno - never a negative age`() {
        assertEquals("щойно", durationShortUk(now + 5 * 60_000L, now))
    }

    // ── board ordering ──────────────────────────────────────────────────────

    @Test
    fun `board pins own oblast first then severity then recency`() {
        val alerts = mapOf(
            "Львівська область" to state(active = true, changed = now, threat = ThreatType.BALLISTIC),
            "Київська область" to state(active = true, changed = now - 60_000L, threat = ThreatType.SHAHED),
            "Одеська область" to state(active = true, changed = now, threat = ThreatType.SHAHED),
            "Тиха область" to state(active = false),
        )
        val board = sortAlertsForBoard(alerts, ownOblast = "Київська область")
        assertEquals(
            listOf("Київська область", "Львівська область", "Одеська область"),
            board.map { it.first },
        )
    }

    // ── alerts-in-ua type mapping ───────────────────────────────────────────

    @Test
    fun `typed alert_type wins and air_raid falls back to notes`() {
        assertEquals(ThreatType.ARTILLERY, threatFromAlertsInUa("artillery_shelling", null))
        assertEquals(ThreatType.URBAN, threatFromAlertsInUa("urban_fights", null))
        assertEquals(ThreatType.CHEMICAL, threatFromAlertsInUa("chemical", null))
        assertEquals(ThreatType.NUCLEAR, threatFromAlertsInUa("nuclear", null))
        assertEquals(ThreatType.SHAHED, threatFromAlertsInUa("air_raid", "група шахедів курсом на північ"))
        assertEquals(ThreatType.UNKNOWN, threatFromAlertsInUa("air_raid", null))
    }

    /**
     * Той самий рядок за кордоном не класифікується взагалі. Це не втрата —
     * без пака нема й стрічки, з якої той рядок міг би прийти; вигадана
     * категорія була б категорією чужого неба.
     */
    @Test
    fun `без пака словника загроз не існує`() {
        CountryPackRegistry.locate(41.3874, 2.1686)
        assertEquals(ThreatType.UNKNOWN, threatFromAlertsInUa("air_raid", "група шахедів курсом на північ"))
        // Типізоване поле стрічки лишається собою — його не треба перекладати.
        assertEquals(ThreatType.ARTILLERY, threatFromAlertsInUa("artillery_shelling", null))
    }

    @Test
    fun `oblast short name drops the suffix and the city prefix`() {
        assertEquals("Київська", oblastShortUk("Київська область"))
        assertEquals("Київ", oblastShortUk("м. Київ"))
        assertEquals("Київ", oblastShortUk("Київ"))
    }
}
