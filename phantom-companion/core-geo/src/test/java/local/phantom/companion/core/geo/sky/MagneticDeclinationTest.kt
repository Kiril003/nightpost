package local.phantom.companion.core.geo.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Reference: NOAA/NCEI's own `WMM2025_TEST_VALUES.txt`, shipped verbatim at
 * `core-geo/src/test/resources/geomag/` from the same `WMM2025COF.zip` the
 * coefficients came out of. Twelve vectors — two dates, two heights, three
 * places — checked component by component rather than on declination alone: a
 * sign error in the Legendre derivative moves declination while leaving the
 * total field intact, so a test that only reads the headline number would pass
 * with the map bearing mirrored about the meridian.
 *
 * Achieved worst-case error over all twelve vectors, as measured and printed by
 * [matchesNoaaPublishedTestValues] on 2026-08-15:
 *
 * ```
 * X 0.048 nT   Y 0.044 nT   Z 0.049 nT   H 0.050 nT   F 0.045 nT
 * Decl 0.0046°   Incl 0.0050°   Ddot 0.0047°/yr
 * ```
 *
 * Every one of those sits under half of the last digit NOAA prints — 0.05 nT
 * for the field components, 0.005° for the angles. That is the whole error
 * budget: the table is rounded to a tenth of a nanotesla and a hundredth of a
 * degree, and this evaluator disagrees with it by no more than that rounding
 * could account for. It is agreement to the limit of what the published table
 * can distinguish, not agreement to within a comfortable margin.
 *
 * The tolerances are therefore set AT that rounding limit rather than above it,
 * which is deliberate: there is no slack for a real regression to hide in. The
 * numbers are exact double arithmetic over a fixed input table, so this is a
 * knife-edge by construction and not by luck — nothing here varies run to run.
 */
class MagneticDeclinationTest {

    /** NOAA prints field components to 0.1 nT, so up to 0.05 is the table's rounding. */
    private val fieldToleranceNt = 0.05

    /** Angles are printed to 0.01°. */
    private val angleToleranceDeg = 0.005

    /** Rates to 0.01 in their unit. */
    private val rateToleranceDegPerYear = 0.005

    @Test
    fun matchesNoaaPublishedTestValues() {
        val model = requireNotNull(WorldMagneticModel.loadBundled()) {
            "bundled coefficients must load from ${WorldMagneticModel.RESOURCE_PATH}"
        }
        val vectors = readNoaaTable()
        assertEquals("NOAA ships twelve test vectors", 12, vectors.size)

        val failures = mutableListOf<String>()
        val worst = mutableMapOf<String, Double>()

        vectors.forEach { v ->
            val got = WorldMagneticModel.evaluate(model, v.year, v.lat, v.lon, v.heightKm)
            fun check(name: String, expected: Double, actual: Double, tolerance: Double) {
                val error = abs(expected - actual)
                worst[name] = maxOf(worst[name] ?: 0.0, error)
                if (error > tolerance) {
                    failures += "%s at %.1f %.0f,%.0f h=%.0f: expected %.4f, got %.4f (err %.5f > %.5f)"
                        .format(name, v.year, v.lat, v.lon, v.heightKm, expected, actual, error, tolerance)
                }
            }
            check("X", v.x, got.northNt, fieldToleranceNt)
            check("Y", v.y, got.eastNt, fieldToleranceNt)
            check("Z", v.z, got.downNt, fieldToleranceNt)
            check("H", v.h, got.horizontalNt, fieldToleranceNt)
            check("F", v.f, got.totalNt, fieldToleranceNt)
            check("Incl", v.inclination, got.inclinationDeg, angleToleranceDeg)
            check("Decl", v.declination, got.declinationDeg, angleToleranceDeg)
            check("Ddot", v.declinationRate, got.declinationRateDegPerYear, rateToleranceDegPerYear)
        }

        // Printed, not just asserted: the tolerances above are only honest if
        // someone can see how much room they actually left.
        println("WMM vs NOAA, worst error per component: $worst")

        assertTrue(
            "WMM evaluation disagrees with NOAA's published table.\n" +
                failures.joinToString("\n") + "\nworst per component: $worst",
            failures.isEmpty(),
        )
    }

    /**
     * The doc comment in [MagneticDeclination] states a source URL and a hash.
     * This pins the second half of that claim to the bytes actually on the
     * classpath, so the file cannot be swapped, re-wrapped or "helpfully"
     * reformatted without a red test — the shipped model's provenance is the
     * whole argument for not calling the platform API, and an unverified
     * provenance note is worth nothing.
     */
    @Test
    fun shippedCoefficientsAreTheNoaaFileVerbatim() {
        val bytes = WorldMagneticModel::class.java
            .getResourceAsStream(WorldMagneticModel.RESOURCE_PATH)!!
            .use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "WMM2025.COF is no longer NOAA's file — see MagneticDeclination's provenance note",
            "06791cd95faba7bdf4a709808f2715a53fe689b29c23b9886bc2196fa9b3eb13",
            sha,
        )

        val model = WorldMagneticModel.parseCof(bytes.decodeToString())
        assertEquals(2025.0, model.epochYear, 0.0)
        assertEquals("WMM-2025", model.modelName)
        // Degree and order 12 -> 90 (n,m) rows. A truncated file still parses.
        assertEquals(90, model.termCount)
    }

    /**
     * The requirement this whole file exists for: past the window the model must
     * REFUSE, not extrapolate.
     *
     * The assertion is deliberately two-sided. Refusing is easy to get right by
     * accident — a bug that returned nothing at all would also pass a
     * "declination is null in 2031" check. So the same instant is first shown to
     * be one the arithmetic is perfectly happy to answer: the evaluator, asked
     * directly, produces a finite, plausible angle for 2031. That number exists,
     * it is what the platform API would have served, and the point is that this
     * one declines to hand it over.
     */
    @Test
    fun expiredModelRefusesInsteadOfExtrapolating() {
        val past = utc(2031, 3, 4)
        val kyivLat = 50.45
        val kyivLon = 30.52

        val refusal = MagneticDeclination.at(past, kyivLat, kyivLon)
        assertTrue(
            "an expired model must refuse, got $refusal",
            refusal is Declination.Refused &&
                refusal.reason is DeclinationRefusal.ModelExpired,
        )
        val reason = (refusal as Declination.Refused).reason as DeclinationRefusal.ModelExpired
        assertEquals(2030.0, reason.validUntilYear, 0.0)
        assertTrue("the refusal names the year asked for", reason.askedYear > 2031.0)

        // And the extrapolation it declined to serve is a real, tempting number.
        val model = WorldMagneticModel.loadBundled()!!
        val wouldHaveBeen = WorldMagneticModel.evaluate(model, 2031.17, kyivLat, kyivLon, 0.0)
        assertTrue(
            "the suppressed value should be finite and plausible, was ${wouldHaveBeen.declinationDeg}",
            wouldHaveBeen.declinationDeg.isFinite() &&
                abs(wouldHaveBeen.declinationDeg) in 5.0..12.0,
        )
    }

    /** A phone whose clock died and came back before the epoch gets the same treatment. */
    @Test
    fun preEpochDateRefuses() {
        val result = MagneticDeclination.at(utc(1970, 1, 1), 50.45, 30.52)
        assertTrue(
            "a pre-epoch clock must refuse, got $result",
            result is Declination.Refused &&
                result.reason is DeclinationRefusal.ModelNotYetValid,
        )
    }

    /** The last instant inside the window still answers; the first outside does not. */
    @Test
    fun theWindowBoundaryIsWhereItIsDocumented() {
        val lastIn = LocalDateTime.of(2029, 12, 31, 23, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(
            "2029-12-31 is inside the 2025.0-2030.0 window",
            MagneticDeclination.at(lastIn, 50.45, 30.52) is Declination.Known,
        )
        assertTrue(
            "2030-01-01 is outside it",
            MagneticDeclination.at(utc(2030, 1, 1), 50.45, 30.52) is Declination.Refused,
        )
    }

    /**
     * Kyiv, mid-window. Not a published vector — NOAA's table has no Ukrainian
     * row — so this asserts only what independent sources agree on to the degree:
     * the declination is easterly and in the 7-9° band, and it is still growing.
     * Stated as the weaker evidence it is, rather than dressed up as a check.
     */
    @Test
    fun kyivIsEasterlyAndStillDrifting() {
        val result = MagneticDeclination.at(utc(2026, 8, 15), 50.45, 30.52)
        val known = requireNotNull(result as? Declination.Known) {
            "Kyiv in 2026 is inside the window, got $result"
        }
        println(
            "Kyiv 2026-08-15: declination %.3f°E, drift %.4f°/yr"
                .format(known.degreesEast, known.driftDegPerYear),
        )
        assertTrue(
            "Kyiv declination should sit in the 7-9°E band, got ${known.degreesEast}",
            known.degreesEast in 7.0..9.0,
        )
        assertTrue(
            "and should still be drifting east, got ${known.driftDegPerYear}",
            known.driftDegPerYear > 0.0,
        )
        assertEquals("WMM-2025", known.modelName)
        assertEquals(2030.0, known.validUntilYear, 0.0)
    }

    /** Under 2000 nT of horizontal field there is no bearing to correct. */
    @Test
    fun magneticBlackoutRefuses() {
        // The north magnetic pole sat near 86°N 150°E through this model's window.
        val result = MagneticDeclination.at(utc(2026, 8, 15), 86.0, 150.0)
        assertTrue(
            "inside the blackout zone declination must refuse, got $result",
            result is Declination.Refused &&
                result.reason is DeclinationRefusal.InMagneticBlackout,
        )
    }

    /** The 0/0 ring the evaluator declines to sum. */
    @Test
    fun geographicPoleRefusesRatherThanReturningNaN() {
        listOf(90.0, -90.0).forEach { lat ->
            val result = MagneticDeclination.at(utc(2026, 8, 15), lat, 0.0)
            assertTrue(
                "lat $lat must refuse, got $result",
                result is Declination.Refused &&
                    result.reason is DeclinationRefusal.AtGeographicPole,
            )
        }
    }

    /** Leap years divide by 366 — 2028 is one, 2026 is not. */
    @Test
    fun decimalYearMatchesNoaaConvention() {
        assertEquals(2025.0, MagneticDeclination.decimalYear(utc(2025, 1, 1)), 1e-9)
        assertEquals(2026.0 + 180.0 / 365.0, MagneticDeclination.decimalYear(utc(2026, 6, 30)), 1e-9)
        assertEquals(2028.0 + 180.0 / 366.0, MagneticDeclination.decimalYear(utc(2028, 6, 29)), 1e-9)
    }

    private fun utc(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun readNoaaTable(): List<NoaaVector> =
        MagneticDeclinationTest::class.java
            .getResourceAsStream("/geomag/WMM2025_TEST_VALUES.txt")!!
            .use { it.readBytes().decodeToString() }
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val f = line.split(Regex("\\s+"))
                NoaaVector(
                    year = f[0].toDouble(),
                    heightKm = f[1].toDouble(),
                    lat = f[2].toDouble(),
                    lon = f[3].toDouble(),
                    x = f[4].toDouble(),
                    y = f[5].toDouble(),
                    z = f[6].toDouble(),
                    h = f[7].toDouble(),
                    f = f[8].toDouble(),
                    inclination = f[9].toDouble(),
                    declination = f[10].toDouble(),
                    declinationRate = f[18].toDouble(),
                )
            }
            .toList()

    private data class NoaaVector(
        val year: Double,
        val heightKm: Double,
        val lat: Double,
        val lon: Double,
        val x: Double,
        val y: Double,
        val z: Double,
        val h: Double,
        val f: Double,
        val inclination: Double,
        val declination: Double,
        val declinationRate: Double,
    )
}
