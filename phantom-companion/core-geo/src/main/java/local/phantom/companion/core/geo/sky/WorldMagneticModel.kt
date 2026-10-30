package local.phantom.companion.core.geo.sky

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Обчислення WMM: сферичні гармоніки над коефіцієнтами NOAA.
 *
 * The arithmetic half of [MagneticDeclination] — the product decisions (what
 * may be claimed, when to refuse) live there; this file only turns coefficients
 * plus a place and a date into a field vector, and is deliberately ignorant of
 * whether anyone should be asking.
 *
 * Method: degree-12 spherical-harmonic expansion of the geomagnetic potential,
 * exactly as specified in the WMM technical report — time-adjust the Gauss
 * coefficients by the secular-variation terms, convert geodetic to geocentric
 * spherical, sum against Schmidt semi-normalised associated Legendre functions,
 * rotate the result back to geodetic. Nothing here is approximated relative to
 * the published model; the accuracy statement in [MagneticDeclination] is the
 * model's own, not this evaluator's.
 *
 * The one place this file declines to compute is the exact geographic pole,
 * where the east component's `1/cos φ′` is genuinely 0/0. The published limit
 * exists and could be summed separately, but NOAA's test table does not cover
 * it — so that branch would ship as the one piece of arithmetic here that no
 * table ever checked. [MagneticDeclination] refuses that ring instead, which is
 * a smaller lie than untested code that looks like an answer.
 */
internal object WorldMagneticModel {

    /** Ships in the AAR as a Java resource; see [MagneticDeclination] for provenance. */
    const val RESOURCE_PATH = "/geomag/WMM2025.COF"

    /** WMM is defined to degree and order 12. Higher terms are not "more precise" — they do not exist. */
    const val MAX_DEGREE = 12

    private const val WGS84_SEMI_MAJOR_KM = 6378.137

    private const val WGS84_FLATTENING = 1.0 / 298.257223563

    /** The geomagnetic reference radius the model's coefficients are defined against. */
    private const val REFERENCE_RADIUS_KM = 6371.2

    /** Below this |cos φ′| the east component is 0/0 rather than merely small. */
    const val POLE_SINGULARITY_COS = 1.0e-10

    private val coefficientCount = (MAX_DEGREE + 1) * (MAX_DEGREE + 2) / 2

    /**
     * Reads the bundled coefficient file. Returns null when the resource is
     * missing or unparseable — a caller that cannot load coefficients has no
     * business inventing a field, and [MagneticDeclination] turns this into a
     * named refusal rather than a zero.
     */
    fun loadBundled(): WmmCoefficients? = runCatching {
        val stream = WorldMagneticModel::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: return@runCatching null
        stream.use { parseCof(it.readBytes().decodeToString()) }
    }.getOrNull()

    /**
     * Parses NOAA's `WMM.COF` layout: one header line carrying the epoch, model
     * name and release date, then `n m g h ġ ḣ` rows, terminated by the file's
     * `9999…` sentinel lines.
     *
     * Throws on a header it cannot read. That is deliberate: a coefficient file
     * whose epoch is unknown cannot have its validity window checked, and the
     * whole point of shipping our own file is that the window is checkable.
     */
    fun parseCof(text: String): WmmCoefficients {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.isNotEmpty()) { "WMM coefficient file is empty" }

        val header = lines.first().split(WHITESPACE)
        val epoch = header.getOrNull(0)?.toDoubleOrNull()
            ?: error("WMM coefficient file has no readable epoch: '${lines.first()}'")

        val g = DoubleArray(coefficientCount)
        val h = DoubleArray(coefficientCount)
        val gDot = DoubleArray(coefficientCount)
        val hDot = DoubleArray(coefficientCount)
        var terms = 0

        for (line in lines.drop(1)) {
            if (line.startsWith("9999")) break
            val f = line.split(WHITESPACE)
            if (f.size < 6) continue
            val n = f[0].toIntOrNull() ?: continue
            val m = f[1].toIntOrNull() ?: continue
            if (n < 1 || n > MAX_DEGREE || m < 0 || m > n) continue
            val i = index(n, m)
            g[i] = f[2].toDouble()
            h[i] = f[3].toDouble()
            gDot[i] = f[4].toDouble()
            hDot[i] = f[5].toDouble()
            terms++
        }

        return WmmCoefficients(
            epochYear = epoch,
            modelName = header.getOrNull(1).orEmpty(),
            releaseDate = header.getOrNull(2).orEmpty(),
            termCount = terms,
            g = g,
            h = h,
            gDot = gDot,
            hDot = hDot,
        )
    }

    /**
     * The field at one place and instant.
     *
     * @param heightKm height above the WGS-84 ellipsoid, the same datum NOAA's
     *   test table uses. Not altitude above sea level, and the difference is
     *   tens of metres — irrelevant to declination, stated so nobody has to
     *   guess which one this wanted.
     */
    fun evaluate(
        coefficients: WmmCoefficients,
        decimalYear: Double,
        latDeg: Double,
        lonDeg: Double,
        heightKm: Double,
    ): GeomagneticSample {
        val dt = decimalYear - coefficients.epochYear

        // Geodetic -> geocentric spherical. The map's latitude is measured off
        // the ellipsoid normal; the harmonics are defined off the geocentre, and
        // the two differ by up to ~0.19° at mid-latitudes. Skipping this step is
        // the classic way to be wrong by more than the model's whole error bar.
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val e2 = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)
        val curvature = WGS84_SEMI_MAJOR_KM / sqrt(1.0 - e2 * sinLat * sinLat)
        val p = (curvature + heightKm) * cosLat
        val zAxis = (curvature * (1.0 - e2) + heightKm) * sinLat
        val radiusKm = sqrt(p * p + zAxis * zAxis)
        val sinGeocentricLat = (zAxis / radiusKm).coerceIn(-1.0, 1.0)
        val geocentricLatRad = asin(sinGeocentricLat)
        val cosGeocentricLat = cos(geocentricLatRad)

        val legendre = schmidtLegendre(sinGeocentricLat)

        // (a/r)^(n+2), built once per call rather than pow()ed 91 times.
        val ratio = REFERENCE_RADIUS_KM / radiusKm
        val radiusPower = DoubleArray(MAX_DEGREE + 1)
        radiusPower[0] = ratio * ratio
        for (n in 1..MAX_DEGREE) radiusPower[n] = radiusPower[n - 1] * ratio

        // cos/sin of m·λ by angle addition — same reason.
        val cosML = DoubleArray(MAX_DEGREE + 1)
        val sinML = DoubleArray(MAX_DEGREE + 1)
        cosML[0] = 1.0
        sinML[0] = 0.0
        cosML[1] = cos(lonRad)
        sinML[1] = sin(lonRad)
        for (m in 2..MAX_DEGREE) {
            cosML[m] = cosML[m - 1] * cosML[1] - sinML[m - 1] * sinML[1]
            sinML[m] = cosML[m - 1] * sinML[1] + sinML[m - 1] * cosML[1]
        }

        var north = 0.0
        var east = 0.0
        var down = 0.0
        // The same summation driven by the secular-variation coefficients alone
        // gives the field's rate of change — that is where the drift figure comes
        // from. It is the model's own published rate, not a finite difference
        // between two evaluations.
        var northRate = 0.0
        var eastRate = 0.0
        var downRate = 0.0

        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val i = index(n, m)
                val gNow = coefficients.g[i] + dt * coefficients.gDot[i]
                val hNow = coefficients.h[i] + dt * coefficients.hDot[i]
                val rp = radiusPower[n]
                val pnm = legendre.p[i]
                val dpnm = legendre.dp[i]

                val cosTerm = gNow * cosML[m] + hNow * sinML[m]
                val sinTerm = gNow * sinML[m] - hNow * cosML[m]
                down -= rp * (n + 1).toDouble() * cosTerm * pnm
                east += rp * m.toDouble() * sinTerm * pnm
                north -= rp * cosTerm * dpnm

                val cosRate = coefficients.gDot[i] * cosML[m] + coefficients.hDot[i] * sinML[m]
                val sinRate = coefficients.gDot[i] * sinML[m] - coefficients.hDot[i] * cosML[m]
                downRate -= rp * (n + 1).toDouble() * cosRate * pnm
                eastRate += rp * m.toDouble() * sinRate * pnm
                northRate -= rp * cosRate * dpnm
            }
        }

        // Finite everywhere the caller is allowed to be: for m >= 1 the Legendre
        // terms carry (cos φ′)^m, so this numerator vanishes with its divisor.
        east /= cosGeocentricLat
        eastRate /= cosGeocentricLat

        // Geocentric -> geodetic: rotate by the same difference the conversion
        // above introduced, which leaves the east component untouched.
        val psi = geocentricLatRad - latRad
        val sinPsi = sin(psi)
        val cosPsi = cos(psi)
        val x = north * cosPsi - down * sinPsi
        val z = north * sinPsi + down * cosPsi
        val xRate = northRate * cosPsi - downRate * sinPsi
        val zRate = northRate * sinPsi + downRate * cosPsi

        val horizontal = sqrt(x * x + east * east)
        val total = sqrt(horizontal * horizontal + z * z)

        return GeomagneticSample(
            northNt = x,
            eastNt = east,
            downNt = z,
            horizontalNt = horizontal,
            totalNt = total,
            declinationDeg = Math.toDegrees(atan2(east, x)),
            inclinationDeg = Math.toDegrees(atan2(z, horizontal)),
            // d/dt of atan2(Y, X). Published as Ddot in NOAA's table, which is
            // what pins this line rather than a plausible-looking derivative.
            declinationRateDegPerYear =
                Math.toDegrees((x * eastRate - east * xRate) / (horizontal * horizontal)),
        )
    }

    /** True when [latDeg] sits inside the ring where the east component is 0/0. */
    fun isAtGeographicPole(latDeg: Double): Boolean =
        abs(cos(Math.toRadians(latDeg))) <= POLE_SINGULARITY_COS

    internal fun index(n: Int, m: Int): Int = n * (n + 1) / 2 + m

    /**
     * Schmidt semi-normalised associated Legendre functions and their
     * derivatives with respect to latitude, by the standard upward recursion.
     *
     * The derivative's sign is flipped at the end because the recursion above
     * differentiates with respect to colatitude while the summation wants
     * latitude — the single sign error that makes declination come out mirrored
     * about the meridian while every other component still looks plausible.
     */
    private fun schmidtLegendre(x: Double): Legendre {
        val p = DoubleArray(coefficientCount)
        val dp = DoubleArray(coefficientCount)
        val z = sqrt((1.0 - x) * (1.0 + x))
        p[0] = 1.0
        dp[0] = 0.0

        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val i = index(n, m)
                when {
                    n == m -> {
                        val prev = index(n - 1, m - 1)
                        p[i] = z * p[prev]
                        dp[i] = z * dp[prev] + x * p[prev]
                    }

                    n == 1 && m == 0 -> {
                        val prev = index(0, 0)
                        p[i] = x * p[prev]
                        dp[i] = x * dp[prev] - z * p[prev]
                    }

                    m > n - 2 -> {
                        val prev = index(n - 1, m)
                        p[i] = x * p[prev]
                        dp[i] = x * dp[prev] - z * p[prev]
                    }

                    else -> {
                        val twoBack = index(n - 2, m)
                        val prev = index(n - 1, m)
                        val k = ((n - 1) * (n - 1) - m * m).toDouble() /
                            ((2 * n - 1) * (2 * n - 3)).toDouble()
                        p[i] = x * p[prev] - k * p[twoBack]
                        dp[i] = x * dp[prev] - z * p[prev] - k * dp[twoBack]
                    }
                }
            }
        }

        val norm = DoubleArray(coefficientCount)
        norm[0] = 1.0
        for (n in 1..MAX_DEGREE) {
            norm[index(n, 0)] = norm[index(n - 1, 0)] * (2 * n - 1).toDouble() / n.toDouble()
            for (m in 1..n) {
                val doubled = if (m == 1) 2.0 else 1.0
                norm[index(n, m)] = norm[index(n, m - 1)] *
                    sqrt((n - m + 1).toDouble() * doubled / (n + m).toDouble())
            }
        }

        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val i = index(n, m)
                p[i] *= norm[i]
                dp[i] = -dp[i] * norm[i]
            }
        }
        return Legendre(p, dp)
    }

    private val WHITESPACE = Regex("\\s+")

    private class Legendre(val p: DoubleArray, val dp: DoubleArray)
}

/**
 * One parsed coefficient set.
 *
 * @property epochYear the model's reference epoch, read from the file rather
 *   than compiled in — it is what the validity window is measured from, so a
 *   hardcoded copy could disagree with the bytes actually shipped.
 * @property termCount how many `n m` rows were actually read; a truncated file
 *   parses without complaint otherwise.
 */
internal class WmmCoefficients(
    val epochYear: Double,
    val modelName: String,
    val releaseDate: String,
    val termCount: Int,
    val g: DoubleArray,
    val h: DoubleArray,
    val gDot: DoubleArray,
    val hDot: DoubleArray,
)

/** The full field vector, in the components NOAA's test table publishes. */
internal data class GeomagneticSample(
    val northNt: Double,
    val eastNt: Double,
    val downNt: Double,
    val horizontalNt: Double,
    val totalNt: Double,
    val declinationDeg: Double,
    val inclinationDeg: Double,
    val declinationRateDegPerYear: Double,
)
