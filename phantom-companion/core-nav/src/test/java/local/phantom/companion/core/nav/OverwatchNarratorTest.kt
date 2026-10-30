package local.phantom.companion.core.nav

import local.phantom.companion.core.data.law.CurfewRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverwatchNarratorTest {

    @Test
    fun alertsOnApproachingHazardOnce() {
        var clock = 5000L
        val narrator = OverwatchNarrator(clockMs = { clock })

        val frame1 = OverwatchNarrator.SituationFrame(
            destinationName = "Центр",
            remainingDistanceM = 4000.0,
            remainingDurationS = 500.0,
            speedKmh = 50,
            speedLimitKmh = 50,
            approachingHazardLabel = "Камера швидкості (50 км/год)",
        )

        val cue1 = narrator.evaluate(frame1)
        assertNotNull(cue1)
        assertTrue(cue1!!.text.contains("Камера швидкості"))

        // Second evaluation with the same hazard must not re-announce
        clock += 1000
        val cue2 = narrator.evaluate(frame1)
        assertNull(cue2)
    }

    @Test
    fun announcesAirRaidAlarmChanges() {
        var clock = 10000L
        val narrator = OverwatchNarrator(clockMs = { clock })

        val frameWithAlarm = OverwatchNarrator.SituationFrame(
            destinationName = "Бровари",
            remainingDistanceM = 8000.0,
            remainingDurationS = 900.0,
            speedKmh = 60,
            speedLimitKmh = 80,
            activeAlarms = setOf("Київська область"),
        )

        val cue = narrator.evaluate(frameWithAlarm)
        assertNotNull(cue)
        assertTrue(cue!!.text.contains("повітряна тривога в Київська область"))
    }

    @Test
    fun announcesApproachingCheckpoint() {
        var clock = 10000L
        val narrator = OverwatchNarrator(clockMs = { clock })

        val frame = OverwatchNarrator.SituationFrame(
            destinationName = "Житомир",
            remainingDistanceM = 8000.0,
            remainingDurationS = 900.0,
            speedKmh = 70,
            speedLimitKmh = 90,
            approachingCheckpoint = true,
        )

        val cue = narrator.evaluate(frame)
        assertNotNull(cue)
        assertTrue(cue!!.text.contains("пункт пропуску"))
        assertFalse(cue.text.contains("блокпост"))
        assertFalse(cue.text.contains("документи"))
    }

    @Test
    fun announcesApproachingBadRoadSurface() {
        var clock = 10000L
        val narrator = OverwatchNarrator(clockMs = { clock })

        val frame = OverwatchNarrator.SituationFrame(
            destinationName = "Вінниця",
            remainingDistanceM = 8000.0,
            remainingDurationS = 900.0,
            speedKmh = 70,
            speedLimitKmh = 90,
            approachingBadSurfaceKm = 3.5,
        )

        val cue = narrator.evaluate(frame)
        assertNotNull(cue)
        assertTrue(cue!!.text.contains("поганим покриттям 3.5 км"))
    }

    @Test
    fun announcesDriverFatigueAlert() {
        var clock = 10000L
        val narrator = OverwatchNarrator(clockMs = { clock })

        val frame = OverwatchNarrator.SituationFrame(
            destinationName = "Львів",
            remainingDistanceM = 250_000.0,
            remainingDurationS = 10_000.0,
            speedKmh = 110,
            speedLimitKmh = 110,
            drivingDurationS = 7300L, // 2+ hours
        )

        val cue = narrator.evaluate(frame)
        assertNotNull(cue)
        assertTrue(cue!!.text.contains("за кермом вже 2 години"))
        assertTrue(cue.text.contains("відпочинок"))
    }

    // Law 3 (D8) — the narrator may only ever read a verified rule aloud.

    private fun kyivMs(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kyiv")).apply {
            set(2026, java.util.Calendar.AUGUST, 22, hour, minute, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun frameAt(curfew: local.phantom.companion.core.data.law.CurfewMatch?) =
        OverwatchNarrator.SituationFrame(
            destinationName = "Умань",
            remainingDistanceM = 40_000.0,
            remainingDurationS = 2_400.0,
            speedKmh = 80,
            speedLimitKmh = 90,
            curfew = curfew,
        )

    @Test
    fun staysSilentWithoutAVerifiedCurfewRule() {
        val narrator = OverwatchNarrator(clockMs = { 10_000L })
        assertNull(narrator.evaluate(frameAt(null)))
        // A region with no entry resolves to no match at all.
        assertNull(CurfewRules.matchAt("DE", kyivMs(1, 30)))
    }

    @Test
    fun speaksTheWindowAndCitesItsSource() {
        val narrator = OverwatchNarrator(clockMs = { 10_000L })
        val match = CurfewRules.matchAt(CurfewRules.UKRAINE, kyivMs(1, 30))
        assertNotNull(match)

        val cue = narrator.evaluate(frameAt(match))
        assertNotNull(cue)
        assertTrue(cue!!.text.contains("01:30"))
        assertTrue(cue.text.contains("00:00–05:00"))
        assertTrue(cue.text.contains(match!!.rule.source))
        assertTrue(cue.text.contains(match.rule.verifiedAt))
        // It names a window; it does not declare movement forbidden.
        assertTrue(!cue.text.contains("заборонено"))
    }
}
