package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.geo.sky.ClockTruth
import local.phantom.companion.core.geo.sky.LightPhase
import local.phantom.companion.feature.stream.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail line is the whole of what the operator sees, so what it may and may
 * not say is pinned here rather than left to a `when` nobody re-reads.
 */
class LightReadoutTest {

    @Test
    fun theThreeSilentPhasesRenderNothingAtAll() {
        assertNull(lightFace(LightPhase.Ample(430)))
        assertNull(lightFace(LightPhase.Dawning))
        assertNull(lightFace(LightPhase.Night))
    }

    @Test
    fun lightAndDarknessNeverShareCopy() {
        val light = lightFace(LightPhase.UsableLight(40))!!
        val dark = lightFace(LightPhase.FallingDark(40))!!
        val dawn = lightFace(LightPhase.FirstLight(40))!!
        assertEquals(R.string.map_light_remaining, light.textRes)
        assertEquals(R.string.map_light_until_dark, dark.textRes)
        assertEquals(R.string.map_light_until_first, dawn.textRes)
        assertEquals(3, setOf(light.textRes, dark.textRes, dawn.textRes).size)
    }

    @Test
    fun theDisplayedNumberIsRoundedNotRaw() {
        assertEquals(40, lightFace(LightPhase.UsableLight(42))!!.minutes)
        assertEquals(45, lightFace(LightPhase.FallingDark(43))!!.minutes)
        assertEquals(7, lightFace(LightPhase.FirstLight(7))!!.minutes)
    }

    @Test
    fun theSunlessStatesCarryTheirOwnWordsAndNoNumber() {
        val allNight = lightFace(LightPhase.LightAllNight)!!
        val noDay = lightFace(LightPhase.NoDaylight)!!
        val noDark = lightFace(LightPhase.NoFullDark)!!
        listOf(allNight, noDay, noDark).forEach { assertNull(it.minutes) }
        assertEquals(R.string.map_light_all_night, allNight.textRes)
        assertEquals(R.string.map_light_no_day, noDay.textRes)
        assertEquals(R.string.map_light_no_dark, noDark.textRes)
        assertEquals(3, setOf(allNight.textRes, noDay.textRes, noDark.textRes).size)
    }

    /**
     * H-02, clause two. A clean check earns silence; the other two states have
     * to be able to say what is wrong, which is the whole reason this stopped
     * being a sentence buried in the TalkBack description.
     */
    @Test
    fun onlyAVerifiedClockIsAllowedToSayNothing() {
        assertNull(clockNote(ClockTruth.Verified(0L)))
        assertNull(clockNote(ClockTruth.Verified(-45_000L)))
        assertEquals(
            R.string.map_light_clock_unverified,
            clockNote(ClockTruth.Unverified)!!.textRes,
        )
        assertNull("the unverified line takes no number", clockNote(ClockTruth.Unverified)!!.amount)
    }

    /**
     * A correction the operator can act on names its direction. Fast and slow
     * must never share copy — «спішить» and «відстає» point at opposite fixes.
     */
    @Test
    fun aCorrectedClockStatesBothItsSizeAndItsDirection() {
        val fast = clockNote(ClockTruth.Corrected(3 * 3_600_000L))!!
        val slow = clockNote(ClockTruth.Corrected(-3 * 3_600_000L))!!
        assertEquals(R.string.map_light_clock_fast_hr, fast.textRes)
        assertEquals(R.string.map_light_clock_slow_hr, slow.textRes)
        assertEquals(3, fast.amount)
        assertEquals(3, slow.amount)
        assertNotEquals(fast.textRes, slow.textRes)
    }

    @Test
    fun aSkewUnderAnHourIsStatedInMinutes() {
        val fast = clockNote(ClockTruth.Corrected(9 * 60_000L))!!
        val slow = clockNote(ClockTruth.Corrected(-9 * 60_000L))!!
        assertEquals(R.string.map_light_clock_fast_min, fast.textRes)
        assertEquals(R.string.map_light_clock_slow_min, slow.textRes)
        assertEquals(9, fast.amount)
        assertEquals(9, slow.amount)
    }

    /** Four distinct lines: nothing may fall through to another state's copy. */
    @Test
    fun theFourCorrectionLinesAreAllDifferent() {
        val res = listOf(
            ClockTruth.Corrected(9 * 60_000L),
            ClockTruth.Corrected(-9 * 60_000L),
            ClockTruth.Corrected(3 * 3_600_000L),
            ClockTruth.Corrected(-3 * 3_600_000L),
        ).map { clockNote(it)!!.textRes }
        assertEquals(4, res.toSet().size)
        assertEquals(5, (res + R.string.map_light_clock_unverified).toSet().size)
    }

    /**
     * Every assertion above stays green on an instrument nobody composes — the
     * house's most common defect, and the one this readout was accused of on
     * 16.08 before the call site was found by hand. So the path is pinned.
     *
     * The countdown no longer draws its own pane: it is a row inside the one
     * instrument plate, so reachability is now two hops and BOTH are pinned. A
     * plate that stopped asking for the light would leave this file green with
     * nothing on the glass — the same defect, one file further away.
     *
     * The argument is pinned with it. `ui.viewportCenter` is in scope at that
     * line and carries a lat/lon just like a fix does; passing it would leave
     * this whole file green while the plate counted down the dusk of wherever
     * the camera had been dragged, which is worse than no countdown at all.
     */
    @Test
    fun theRailActuallyComposesTheCountdown() {
        val call = mapChromeCall("MapInstrumentPlate")
        assertTrue(
            "MapInstrumentPlate не викликається у MapChrome — приладів ніхто не побачить",
            call.isNotEmpty(),
        )
        assertTrue(
            "платівка приладів читає не позицію оператора: $call",
            call.any { it.contains("operatorLocation = operatorLocation") },
        )

        val plate = mapSource("MapControls.kt")
        assertTrue(
            "платівка більше не питає, що казати про світло — рядок мовчки зник",
            plate.any { it.contains("rememberLightLine(") },
        )
        // Раніше тут стояв виклик MapLightReadout. Рядки світла переїхали в саму
        // платівку — виноска про відкритий горизонт перестала бути окремим
        // рядком, — тож пін переїхав на те, що справді доводить видимість:
        // headline мусить потрапити на скло, хоч би хто його малював.
        assertTrue(
            "платівка питає про світло, але не малює його — відповідь нікуди не йде",
            plate.any { it.contains("line.headline") || it.contains("MapLightReadout(") },
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root with feature-stream/ not found")
    }

    private fun mapSource(name: String): List<String> {
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        )
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines()
    }

    private fun mapChromeCall(composable: String): List<String> {
        val lines = mapSource("MapChrome.kt")
        val start = lines.indexOfFirst { it.trimStart().startsWith("$composable(") }
        return if (start < 0) emptyList() else lines.subList(start, minOf(start + 6, lines.size))
    }
}
