package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.context.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * СИРЕНА-3 — правила, від яких залежить, чи прокинеться оператор. Усе тут
 * чисте: годинник, мережа й екран — параметри, тож жоден тест не спить.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class AirAlertWatchTest {

    private val now = 1_770_000_000_000L

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private fun state(active: Boolean, threat: ThreatType = ThreatType.UNKNOWN, at: Long? = null) =
        AlertState(active = active, changedEpochMs = at, threatType = threat)

    // ── Воротар: чужа область ───────────────────────────────────────────────

    @Test
    fun `alert in another oblast never notifies`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(false), "Харківська область" to state(false)),
            mapOf("Львівська область" to state(false), "Харківська область" to state(true)),
            now,
        )
        assertEquals(1, transitions.size)
        assertNull(gate.evaluate("Львівська область", transitions, AlertFreshness.FRESH))
    }

    @Test
    fun `own oblast alert notifies once`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(false)),
            mapOf("Львівська область" to state(true, ThreatType.BALLISTIC)),
            now,
        )
        val notice = gate.evaluate("Львівська область", transitions, AlertFreshness.FRESH)
        assertEquals(AirAlertNoticeKind.RAISED, notice?.kind)
        assertFalse(notice!!.phrase.contains("Балістика"))
        assertTrue(notice!!.phrase.startsWith("Повітряна тривога — Львівська область."))
    }

    @Test
    fun `unknown oblast never notifies`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(false)),
            mapOf("Львівська область" to state(true)),
            now,
        )
        assertNull(gate.evaluate(null, transitions, AlertFreshness.FRESH))
    }

    // ── Воротар: повтори ────────────────────────────────────────────────────

    @Test
    fun `same transition twice notifies once`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(false)),
            mapOf("Львівська область" to state(true)),
            now,
        )
        assertTrue(gate.evaluate("Львівська область", transitions, AlertFreshness.FRESH) != null)
        assertNull(gate.evaluate("Львівська область", transitions, AlertFreshness.FRESH))
    }

    @Test
    fun `raise clear raise notifies three times`() {
        val gate = OwnOblastGate()
        val off = mapOf("Львівська область" to state(false))
        val on = mapOf("Львівська область" to state(true))
        val raise = diffAlerts(off, on, now)
        val clear = diffAlerts(on, off, now)

        assertEquals(
            AirAlertNoticeKind.RAISED,
            gate.evaluate("Львівська область", raise, AlertFreshness.FRESH)?.kind,
        )
        assertEquals(
            AirAlertNoticeKind.CLEARED,
            gate.evaluate("Львівська область", clear, AlertFreshness.FRESH)?.kind,
        )
        assertEquals(
            AirAlertNoticeKind.RAISED,
            gate.evaluate("Львівська область", raise, AlertFreshness.FRESH)?.kind,
        )
    }

    @Test
    fun `threat refinement does not notify`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(true, ThreatType.UNKNOWN)),
            mapOf("Львівська область" to state(true, ThreatType.MISSILE)),
            now,
        )
        assertTrue(transitions.isEmpty())
        assertNull(gate.evaluate("Львівська область", transitions, AlertFreshness.FRESH))
    }

    @Test
    fun `stale sky never notifies`() {
        val gate = OwnOblastGate()
        val transitions = diffAlerts(
            mapOf("Львівська область" to state(false)),
            mapOf("Львівська область" to state(true)),
            now,
        )
        assertNull(gate.evaluate("Львівська область", transitions, AlertFreshness.STALE))
        assertNull(gate.evaluate("Львівська область", transitions, AlertFreshness.UNKNOWN))
    }

    // ── Чесність офлайну ────────────────────────────────────────────────────

    @Test
    fun `never fetched reads as unknown not clear`() {
        val sky = AlertSky(ownOblast = "Львівська область")
        assertEquals(AlertFreshness.UNKNOWN, sky.freshnessAt(now))
        assertNull(sky.ownActiveAt(now))
    }

    @Test
    fun `stale quiet reads as unknown not clear`() {
        val sky = AlertSky(
            oblasts = mapOf("Львівська область" to state(false)),
            ownOblast = "Львівська область",
            fetchedAtMs = now - SKY_STALE_AFTER_MS - 1,
        )
        assertEquals(AlertFreshness.STALE, sky.freshnessAt(now))
        assertNull(sky.ownActiveAt(now))
    }

    @Test
    fun `stale alarm still reads as alarm`() {
        val sky = AlertSky(
            oblasts = mapOf("Львівська область" to state(true)),
            ownOblast = "Львівська область",
            fetchedAtMs = now - SKY_STALE_AFTER_MS - 1,
        )
        assertEquals(true, sky.ownActiveAt(now))
    }

    @Test
    fun `fresh quiet reads as clear`() {
        val sky = AlertSky(
            oblasts = mapOf("Львівська область" to state(false)),
            ownOblast = "Львівська область",
            fetchedAtMs = now,
        )
        assertEquals(AlertFreshness.FRESH, sky.freshnessAt(now))
        assertEquals(false, sky.ownActiveAt(now))
    }

    @Test
    fun `live fix outside every oblast clears own oblast`() = runTest {
        var lat = 49.84
        var lon = 24.03
        val watch = AirAlertWatch(
            scope = this,
            fetchBase = { mapOf("Львівська область" to state(false, at = now)) },
            oblastAt = { la, _ -> if (la > 49.0) "Львівська область" else null },
            locationSource = { LocationFix(lat = lat, lon = lon) },
            nowMs = { now },
        )
        assertTrue(watch.pollOnce())
        assertEquals("Львівська область", watch.sky.value.ownOblast)
        assertEquals(false, watch.sky.value.ownActiveAt(now))

        // Кишинів: рамка пака ще каже «Україна», полігони — вже ні.
        lat = 47.01
        lon = 28.86
        assertTrue(watch.pollOnce())
        assertNull(watch.sky.value.ownOblast)
        // «Тихо · Київщина» зі старої області читалось би як «тихо тут».
        assertNull(watch.sky.value.ownActiveAt(now))
    }

    @Test
    fun `oblast lookup failure holds the previous oblast`() = runTest {
        var broken = false
        val watch = AirAlertWatch(
            scope = this,
            fetchBase = { mapOf("Львівська область" to state(false, at = now)) },
            oblastAt = { _, _ -> if (broken) error("shapes asset unreadable") else "Львівська область" },
            locationSource = { LocationFix(lat = 49.84, lon = 24.03) },
            nowMs = { now },
        )
        assertTrue(watch.pollOnce())
        broken = true
        assertTrue(watch.pollOnce())
        assertEquals("Львівська область", watch.sky.value.ownOblast)
    }

    @Test
    fun `offline poll keeps the snapshot and ages it`() = runTest {
        var clock = now
        var online = true
        val watch = AirAlertWatch(
            scope = this,
            fetchBase = { mapOf("Львівська область" to state(false, at = clock)) },
            oblastAt = { _, _ -> "Львівська область" },
            locationSource = { LocationFix(lat = 49.84, lon = 24.03) },
            online = { online },
            nowMs = { clock },
        )
        assertTrue(watch.pollOnce())
        assertEquals(AlertFreshness.FRESH, watch.sky.value.freshnessAt(clock))
        assertEquals(false, watch.sky.value.ownActiveAt(clock))

        online = false
        clock += SKY_STALE_AFTER_MS + 1
        assertTrue(!watch.pollOnce())
        assertEquals(1, watch.sky.value.oblasts.size)
        assertEquals(AlertFreshness.STALE, watch.sky.value.freshnessAt(clock))
        // Найважливіше: мовчання мережі НЕ читається як «тихо».
        assertNull(watch.sky.value.ownActiveAt(clock))
    }

    // ── Темп ────────────────────────────────────────────────────────────────

    @Test
    fun `cadence follows demand`() {
        assertEquals(
            SKY_POLL_HOT_MS,
            skyPollDelayMs(ownAlertLive = true, screenOn = false, online = true, consecutiveFailures = 0),
        )
        assertEquals(
            SKY_POLL_WARM_MS,
            skyPollDelayMs(ownAlertLive = false, screenOn = true, online = true, consecutiveFailures = 0),
        )
        assertEquals(
            SKY_POLL_COLD_MS,
            skyPollDelayMs(ownAlertLive = false, screenOn = false, online = true, consecutiveFailures = 0),
        )
    }

    @Test
    fun `screen off with no alert backs off`() {
        val pocket = skyPollDelayMs(false, screenOn = false, online = true, consecutiveFailures = 0)
        val watching = skyPollDelayMs(false, screenOn = true, online = true, consecutiveFailures = 0)
        assertTrue("кишеня має опитувати рідше за увімкнений екран", pocket > watching)
    }

    @Test
    fun `failures double the pause up to the ceiling`() {
        assertEquals(
            SKY_POLL_WARM_MS * 2,
            skyPollDelayMs(false, screenOn = true, online = true, consecutiveFailures = 1),
        )
        assertEquals(
            SKY_POLL_WARM_MS * 4,
            skyPollDelayMs(false, screenOn = true, online = true, consecutiveFailures = 2),
        )
        assertEquals(
            SKY_POLL_MAX_MS,
            skyPollDelayMs(false, screenOn = true, online = true, consecutiveFailures = 8),
        )
    }

    @Test
    fun `offline never polls faster than its floor`() {
        assertEquals(
            SKY_POLL_OFFLINE_FLOOR_MS,
            skyPollDelayMs(ownAlertLive = true, screenOn = true, online = false, consecutiveFailures = 0),
        )
        assertTrue(
            skyPollDelayMs(true, screenOn = true, online = false, consecutiveFailures = 3) >=
                SKY_POLL_OFFLINE_FLOOR_MS,
        )
    }

    // ── Злиття стрічок ──────────────────────────────────────────────────────

    @Test
    fun `siren adds an oblast ubilling never reports`() {
        val merged = mergeSky(
            base = mapOf("Львівська область" to state(false)),
            siren = mapOf(
                "Автономна Республіка Крим" to SirenOblast(
                    stateActive = true,
                    stateThreat = ThreatType.ARTILLERY,
                    startedEpochMs = now,
                    districts = emptyList(),
                ),
            ),
            detailed = null,
        )
        assertEquals(true, merged["Автономна Республіка Крим"]?.active)
        assertEquals(ThreatType.ARTILLERY, merged["Автономна Республіка Крим"]?.threatType)
    }

    @Test
    fun `detail feed refines the threat but never the active flag`() {
        val merged = mergeSky(
            base = mapOf("Львівська область" to state(true, ThreatType.UNKNOWN)),
            siren = null,
            detailed = mapOf("Львівська область" to state(false, ThreatType.SHAHED)),
        )
        assertEquals(true, merged["Львівська область"]?.active)
        assertEquals(ThreatType.SHAHED, merged["Львівська область"]?.threatType)
    }
}
