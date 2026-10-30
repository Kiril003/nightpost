package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.geo.sky.Declination
import local.phantom.companion.core.geo.sky.DeclinationRefusal
import local.phantom.companion.feature.stream.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-05's rail line, pinned the same way H-02's is.
 *
 * The case this file exists for is the one the core test cannot reach: a
 * refusal that the core produces correctly and the surface then renders as
 * nothing. A `when` branch returning a face with no string, or two branches
 * quietly sharing one, both leave every core test green while the operator sees
 * a blank rail — so every state is asserted to carry a real, distinct string
 * resource here.
 */
class DeclinationReadoutTest {

    private fun known(degreesEast: Double) = Declination.Known(
        degreesEast = degreesEast,
        driftDegPerYear = 0.1,
        modelName = "WMM-2025",
        validUntilYear = 2030.0,
    )

    @Test
    fun easterlyAndWesterlyNeverShareCopy() {
        val east = declinationFace(known(7.6))
        val west = declinationFace(known(-7.6))
        assertEquals(R.string.map_declination_east, east.textRes)
        assertEquals(R.string.map_declination_west, west.textRes)
        assertNotEquals(east.textRes, west.textRes)
        // The sign lives in the copy, so the number itself is unsigned — «схилення
        // -8° зх.» would state the direction twice and contradict itself once.
        assertEquals(8, east.value)
        assertEquals(8, west.value)
    }

    /**
     * Whole degrees, because the model is only specified to ±0.5°. 7.6 must
     * present as 8, not as 7.6 and not as 7.
     */
    @Test
    fun theAngleIsRoundedToTheModelsOwnResolution() {
        assertEquals(8, declinationFace(known(7.6)).value)
        assertEquals(7, declinationFace(known(7.4)).value)
        assertEquals(9, declinationFace(known(-8.7)).value)
    }

    /** Inside half a degree there is no direction to name. */
    @Test
    fun aVanishingCorrectionNamesNoDirection() {
        listOf(0.0, 0.3, -0.4).forEach { d ->
            val face = declinationFace(known(d))
            assertEquals(R.string.map_declination_none, face.textRes)
            assertNull("the ~0° line takes no number", face.value)
            assertTrue("~0° is a real answer, not a refusal", !face.refused)
        }
    }

    /**
     * The spec's load-bearing case: an expired model must produce a line that
     * says so and names the year it lapsed — not an empty slot, and not a number.
     */
    @Test
    fun anExpiredModelRendersItsReasonAndTheYear() {
        val face = declinationFace(
            Declination.Refused(DeclinationRefusal.ModelExpired(2030.0, 2031.2)),
        )
        assertEquals(R.string.map_declination_expired, face.textRes)
        assertEquals("the operator is told WHEN it lapsed", 2030, face.value)
        assertTrue(face.refused)
    }

    /** Every refusal renders something, and is marked as a refusal. */
    @Test
    fun noRefusalRendersAnEmptyLine() {
        val refusals = listOf(
            DeclinationRefusal.ModelExpired(2030.0, 2031.2),
            DeclinationRefusal.ModelNotYetValid(2025.0, 1970.0),
            DeclinationRefusal.InMagneticBlackout(1400.0),
            DeclinationRefusal.AtGeographicPole,
            DeclinationRefusal.CoefficientsUnavailable,
        )
        refusals.forEach { reason ->
            val face = declinationFace(Declination.Refused(reason))
            assertNotEquals("$reason must carry a string", 0, face.textRes)
            assertTrue("$reason must read as a refusal", face.refused)
        }
    }

    /**
     * A stale model and a broken clock demand different fixes — update the app
     * versus fix the date — so they must never collapse into one sentence. The
     * two undefined-here cases share copy on purpose (see [declinationFace]),
     * which is why this counts four distinct strings across five refusals.
     */
    @Test
    fun refusalsThatNeedDifferentActionsReadDifferently() {
        val expired = declinationFace(
            Declination.Refused(DeclinationRefusal.ModelExpired(2030.0, 2031.2)),
        ).textRes
        val clock = declinationFace(
            Declination.Refused(DeclinationRefusal.ModelNotYetValid(2025.0, 1970.0)),
        ).textRes
        val blackout = declinationFace(
            Declination.Refused(DeclinationRefusal.InMagneticBlackout(1400.0)),
        ).textRes
        val pole = declinationFace(
            Declination.Refused(DeclinationRefusal.AtGeographicPole),
        ).textRes
        val missing = declinationFace(
            Declination.Refused(DeclinationRefusal.CoefficientsUnavailable),
        ).textRes

        assertEquals("the two undefined-here causes share one line", blackout, pole)
        assertEquals(4, setOf(expired, clock, blackout, missing).size)
    }

    /** A real angle must never be dressed as a refusal, or it loses the operator's ink. */
    @Test
    fun aKnownAngleIsNotMarkedRefused() {
        assertTrue(!declinationFace(known(7.6)).refused)
        assertTrue(!declinationFace(known(-3.0)).refused)
    }

    /**
     * Same guard as the countdown's, for the same reason: none of the states
     * above is worth anything if the plate never asks for one. Two hops, both
     * pinned — the correction is now a row inside the instrument plate rather
     * than a pill of its own, and a plate that quietly stopped asking would
     * leave every assertion above green over an empty corner.
     *
     * The position argument is pinned too. A bearing correction is a fact about
     * where the compass is being held, so the camera's centre would be a wrong
     * answer that still looks like a right one — 8° in Zhytomyr, 11° after
     * someone scrolled east, with nothing on screen to say which.
     */
    @Test
    fun theRailActuallyComposesTheCorrection() {
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
            "платівка більше не питає про схилення — рядок мовчки зник",
            plate.any { it.contains("rememberDeclinationLine(") },
        )
        assertTrue(
            "платівка питає про схилення, але не малює його — відповідь нікуди не йде",
            plate.any { it.contains("MapDeclinationReadout(") },
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
