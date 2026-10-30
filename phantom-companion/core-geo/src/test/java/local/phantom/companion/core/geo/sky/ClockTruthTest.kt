package local.phantom.companion.core.geo.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The countdown's second limit, pinned. Every case here is a way for a device
 * clock to be wrong that the old build would have rendered as a confident
 * number, so the assertions are mostly about what the readout is FORBIDDEN to
 * claim rather than about arithmetic.
 */
class ClockTruthTest {

    private val noon = utc(2026, 6, 21) + hours(12)

    private fun hours(n: Int) = n * 3_600_000L
    private fun minutes(n: Int) = n * 60_000L

    @Test
    fun aClockAgreeingWithTheSatellitesIsVerifiedAndSaysNothing() {
        val record = ClockCheck.witness(deviceNowMs = noon + minutes(1), gpsTimeMs = noon)
        val truth = ClockCheck.assess(noon + minutes(1), record)
        assertTrue("got $truth", truth is ClockTruth.Verified)
    }

    @Test
    fun aClockWrongByMoreThanTheToleranceIsCorrectedNotVerified() {
        val record = ClockCheck.witness(deviceNowMs = noon + hours(3), gpsTimeMs = noon)
        val truth = ClockCheck.assess(noon + hours(3), record)
        assertTrue("got $truth", truth is ClockTruth.Corrected)
        assertEquals(hours(3), (truth as ClockTruth.Corrected).skewMs)
    }

    /**
     * The boundary is a decision, not an accident: two minutes is the most the
     * clock can be out without moving a five-minute-rounded countdown.
     */
    @Test
    fun theToleranceBoundaryItselfCountsAsClean() {
        val exact = ClockCheck.witness(noon + ClockCheck.TOLERANCE_MS, noon)
        assertTrue(ClockCheck.assess(noon + ClockCheck.TOLERANCE_MS, exact) is ClockTruth.Verified)

        val overBy1ms = ClockCheck.witness(noon + ClockCheck.TOLERANCE_MS + 1L, noon)
        assertTrue(
            ClockCheck.assess(noon + ClockCheck.TOLERANCE_MS + 1L, overBy1ms) is ClockTruth.Corrected,
        )
    }

    /** Both directions are a fault: a slow clock hands out light that is gone. */
    @Test
    fun aSlowClockIsCorrectedJustAsAFastOneIs() {
        val record = ClockCheck.witness(deviceNowMs = noon - hours(2), gpsTimeMs = noon)
        val truth = ClockCheck.assess(noon - hours(2), record)
        assertEquals(-hours(2), (truth as ClockTruth.Corrected).skewMs)
    }

    @Test
    fun noRecordAtAllIsUnverifiedRatherThanAssumedGood() {
        assertSame(ClockTruth.Unverified, ClockCheck.assess(noon, null))
    }

    /**
     * The headline case. A phone that saw satellites yesterday and has been
     * underground since must lose the clean face on its own, with nothing
     * happening to trigger it.
     */
    @Test
    fun aCheckOlderThanADayExpiresIntoUnverified() {
        val record = ClockCheck.witness(deviceNowMs = noon, gpsTimeMs = noon)
        val justInside = noon + ClockCheck.VERIFY_TTL_MS
        val justOutside = noon + ClockCheck.VERIFY_TTL_MS + 1L
        assertTrue(ClockCheck.assess(justInside, record) is ClockTruth.Verified)
        assertSame(ClockTruth.Unverified, ClockCheck.assess(justOutside, record))
    }

    /**
     * The reason [ClockSkew.verifiedAtMs] is stamped in satellite time. Ageing
     * the record with the device's own clock would let a clock that jumped
     * forward age its own witness out — or, worse, let one that jumped back
     * keep a stale witness alive forever by rewinding past it.
     */
    @Test
    fun aClockThatMovesAgainAfterTheCheckFallsBackToUnverified() {
        val record = ClockCheck.witness(deviceNowMs = noon, gpsTimeMs = noon)
        // The operator (or a dying battery) sets the clock back three hours
        // AFTER the satellite check. Corrected time is now before the
        // verification, which is impossible, so the record cannot be trusted.
        assertSame(ClockTruth.Unverified, ClockCheck.assess(noon - hours(3), record))
    }

    @Test
    fun theCountdownIsComputedFromCorrectedTimeWheneverACheckIsLive() {
        val record = ClockCheck.witness(deviceNowMs = noon + hours(3), gpsTimeMs = noon)
        val truth = ClockCheck.assess(noon + hours(3), record)
        assertEquals(noon, ClockCheck.correctedNow(noon + hours(3), truth))
    }

    /** Nothing better to offer than the device clock — and the UI says so. */
    @Test
    fun anUnverifiedClockIsUsedUncorrectedRatherThanGuessedAt() {
        assertEquals(noon, ClockCheck.correctedNow(noon, ClockTruth.Unverified))
    }

    /**
     * The whole point, end to end: three hours of clock error move the readout
     * by three hours of sun. Kyiv at midsummer — corrected, the sun is high and
     * the instrument stays silent; uncorrected by +3 h the phone believes it is
     * evening and would have drawn a countdown that is not happening.
     */
    @Test
    fun threeHoursOfSkewIsThreeHoursOfWrongSun() {
        val kyivLat = 50.45
        val kyivLon = 30.52
        val skewed = noon + hours(3)
        val record = ClockCheck.witness(deviceNowMs = skewed, gpsTimeMs = noon)
        val truth = ClockCheck.assess(skewed, record)

        val corrected = UsableLight.phase(ClockCheck.correctedNow(skewed, truth), kyivLat, kyivLon)
        val raw = UsableLight.phase(skewed, kyivLat, kyivLon)
        assertTrue("corrected: $corrected", corrected is LightPhase.Ample)
        assertTrue(
            "the uncorrected clock must land somewhere else, else this test proves nothing",
            (raw as? LightPhase.Ample)?.minutes != (corrected as LightPhase.Ample).minutes,
        )
    }

    @Test
    fun skewUnderAnHourIsStatedInMinutesAndAboveItInHours() {
        val fifty = ClockCheck.magnitudeOf(minutes(50))
        assertEquals(50, fifty.amount)
        assertEquals(SkewUnit.MINUTES, fifty.unit)
        assertTrue(fifty.ahead)

        val threeHours = ClockCheck.magnitudeOf(-hours(3))
        assertEquals(3, threeHours.amount)
        assertEquals(SkewUnit.HOURS, threeHours.unit)
        assertTrue("a negative skew is a SLOW clock", !threeHours.ahead)
    }

    @Test
    fun theStatedMagnitudeRoundsToTheNearestUnit() {
        assertEquals(2, ClockCheck.magnitudeOf(minutes(2) + 20_000L).amount)
        assertEquals(3, ClockCheck.magnitudeOf(minutes(2) + 40_000L).amount)
        assertEquals(4, ClockCheck.magnitudeOf(hours(4) + minutes(20)).amount)
        assertEquals(5, ClockCheck.magnitudeOf(hours(4) + minutes(40)).amount)
    }

    // ── The witness and its store ─────────────────────────────────────────────

    private class FakeStore(var saved: ClockSkew? = null) : ClockSkewStore {
        var loads = 0
        override fun load(): ClockSkew? { loads++; return saved }
        override fun save(skew: ClockSkew) { saved = skew }
    }

    @Test
    fun aFixWithNoSatelliteTimeIsNotEvidenceOfAnything() {
        val store = FakeStore()
        val witness = ClockWitness(store)
        witness.observe(gpsTimeMs = null, deviceNowMs = noon)
        assertNull(witness.record.value)
        assertNull(store.saved)
    }

    @Test
    fun aSatelliteFixIsRecordedAndHandedToTheStore() {
        val store = FakeStore()
        val witness = ClockWitness(store)
        witness.observe(gpsTimeMs = noon, deviceNowMs = noon + hours(3))
        assertEquals(hours(3), witness.record.value?.skewMs)
        assertEquals(noon, witness.record.value?.verifiedAtMs)
        assertEquals(witness.record.value, store.saved)
    }

    /**
     * The restart case, which is the reason a store exists at all: the blackout
     * that wrecks a phone's clock is also what kills the process.
     */
    @Test
    fun aStoredWitnessSurvivesIntoTheNextProcess() {
        val store = FakeStore(ClockSkew(skewMs = hours(3), verifiedAtMs = noon))
        val reborn = ClockWitness(store)
        assertEquals(hours(3), reborn.record.value?.skewMs)
        assertEquals(1, store.loads)
        assertTrue(ClockCheck.assess(noon + hours(4), reborn.record.value) is ClockTruth.Corrected)
    }

    /** No store (previews, tests) must not mean «clock is fine» — it means unknown. */
    @Test
    fun withNoStoreTheWitnessStartsWithNothingRatherThanWithAClean() {
        val witness = ClockWitness(null)
        assertNull(witness.record.value)
        assertSame(ClockTruth.Unverified, ClockCheck.assess(noon, witness.record.value))
    }

    @Test
    fun aStoreThatThrowsCannotTakeTheReadoutDownWithIt() {
        val hostile = object : ClockSkewStore {
            override fun load(): ClockSkew? = throw IllegalStateException("prefs corrupted")
            override fun save(skew: ClockSkew) = throw IllegalStateException("disk full")
        }
        val witness = ClockWitness(hostile)
        assertNull(witness.record.value)
        witness.observe(gpsTimeMs = noon, deviceNowMs = noon + hours(3))
        assertEquals(hours(3), witness.record.value?.skewMs)
    }
}
