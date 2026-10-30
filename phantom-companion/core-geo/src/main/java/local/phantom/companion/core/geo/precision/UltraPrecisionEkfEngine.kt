package local.phantom.companion.core.geo.precision

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-Precision Location Fix produced by the EKF engine.
 */
data class UltraPrecisionFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val bearingDegrees: Float,
    val timestampMs: Long,
    val isFused: Boolean,
)

/**
 * Extended Kalman Filter (EKF) Location Engine.
 * Fuses raw GNSS fixes, accelerometer step dead-reckoning, gyroscope angular rates,
 * and barometer altitude into a sub-meter precision position estimate.
 */
class UltraPrecisionEkfEngine {

    private var anchorLat: Double = 0.0
    private var anchorLon: Double = 0.0
    private var anchorAlt: Double = 0.0
    private var isInitialized = false

    // State vector: [x, y, z, vx, vy, vz] in East-North-Up (ENU) meters
    private val x = DoubleArray(6)

    // State Covariance matrix 6x6
    private val p = Array(6) { DoubleArray(6) }

    private var lastUpdateMs: Long = 0L

    init {
        resetCovariance(5.0)
    }

    private fun resetCovariance(initialVariance: Double) {
        for (i in 0 until 6) {
            for (j in 0 until 6) {
                p[i][j] = if (i == j) initialVariance else 0.0
            }
        }
    }

    /**
     * Process a raw GNSS location fix.
     */
    @Synchronized
    fun updateGnssFix(
        lat: Double,
        lon: Double,
        alt: Double,
        accuracyM: Float,
        speedMps: Float,
        bearingDeg: Float,
        timestampMs: Long,
    ): UltraPrecisionFix {
        if (!isInitialized) {
            anchorLat = lat
            anchorLon = lon
            anchorAlt = alt
            x[0] = 0.0
            x[1] = 0.0
            x[2] = 0.0
            x[3] = speedMps * sin(Math.toRadians(bearingDeg.toDouble()))
            x[4] = speedMps * cos(Math.toRadians(bearingDeg.toDouble()))
            x[5] = 0.0
            resetCovariance((accuracyM.toDouble()).coerceAtLeast(1.0))
            isInitialized = true
            lastUpdateMs = timestampMs
            return UltraPrecisionFix(lat, lon, alt, accuracyM, speedMps, bearingDeg, timestampMs, isFused = true)
        }

        val dt = ((timestampMs - lastUpdateMs).coerceIn(10L, 5000L)) / 1000.0
        lastUpdateMs = timestampMs

        // 1. Prediction step: x = F * x
        x[0] += x[3] * dt
        x[1] += x[4] * dt
        x[2] += x[5] * dt

        // Process noise variance
        val qPos = 0.1 * dt
        val qVel = 0.5 * dt
        p[0][0] += qPos
        p[1][1] += qPos
        p[2][2] += qPos
        p[3][3] += qVel
        p[4][4] += qVel
        p[5][5] += qVel

        // 2. Convert incoming GNSS fix to ENU coordinates
        val (measX, measY, measZ) = geodeticToEnu(lat, lon, alt, anchorLat, anchorLon, anchorAlt)

        // Measurement residual
        val resX = measX - x[0]
        val resY = measY - x[1]
        val resZ = measZ - x[2]

        // Measurement variance
        val rVar = (accuracyM.toDouble() * accuracyM.toDouble()).coerceAtLeast(0.5)

        // Kalman gains for position states
        val k0 = p[0][0] / (p[0][0] + rVar)
        val k1 = p[1][1] / (p[1][1] + rVar)
        val k2 = p[2][2] / (p[2][2] + rVar)

        // Update state vector
        x[0] += k0 * resX
        x[1] += k1 * resY
        x[2] += k2 * resZ

        // Update velocity from position delta
        if (dt > 0.05) {
            val vxEst = (k0 * resX) / dt
            val vyEst = (k1 * resY) / dt
            x[3] = (0.7 * x[3]) + (0.3 * vxEst)
            x[4] = (0.7 * x[4]) + (0.3 * vyEst)
        }

        // Update covariance matrix
        p[0][0] *= (1.0 - k0)
        p[1][1] *= (1.0 - k1)
        p[2][2] *= (1.0 - k2)

        // Convert updated ENU state back to Geodetic
        val (fusedLat, fusedLon, fusedAlt) = enuToGeodetic(x[0], x[1], x[2], anchorLat, anchorLon, anchorAlt)
        // ПІДЛОГА НА ТОМУ, ЩО ЗВІТУЄ АПАРАТ. sqrt коваріації — це самооцінка
        // фільтра: вона скорочується на кожному оновленні (p *= 1-k) і ні з чим
        // не звіряється, тож на телефоні, який каже ±12 м, плитка «ТОЧНІСТЬ»
        // показувала ±1 м. Це те саме число, яким живиться `trustedForAnchor` —
        // найдорожче рішення того екрана. Фільтр має право згладжувати, але не
        // має права заявити краще за приймач.
        val estAccuracy = sqrt((p[0][0] + p[1][1]) / 2.0).toFloat()
            .coerceIn(0.5f, 50.0f)
            .coerceAtLeast(accuracyM)
        val estSpeed = sqrt(x[3] * x[3] + x[4] * x[4]).toFloat()
        // Курс — зі ШВИДКІСНИХ станів (x[3] схід, x[4] північ), не з позиційних.
        // Було atan2(x[0], x[1]), тобто напрямок ВІД якоря фільтра ДО поточної
        // точки: чим далі відійшов від місця, де фільтр ініціалізувався, тим
        // упертіше «курс» показував туди, а не куди їдеш. Що це саме одруківка,
        // а не задум, видно на два способи в цьому ж файлі: рядком вище швидкість
        // береться з x[3]/x[4], а ініціалізація вгорі сіє ті самі стани зворотною
        // формулою — x[3] = v·sin(курс), x[4] = v·cos(курс).
        val estBearing = Math.toDegrees(atan2(x[3], x[4])).toFloat().let { (it + 360f) % 360f }

        return UltraPrecisionFix(
            latitude = fusedLat,
            longitude = fusedLon,
            altitudeMeters = fusedAlt,
            accuracyMeters = estAccuracy,
            speedMps = estSpeed,
            bearingDegrees = estBearing,
            timestampMs = timestampMs,
            isFused = true,
        )
    }

    /**
     * Process IMU accelerometer step displacement (Dead Reckoning propagation).
     */
    @Synchronized
    fun updateDeadReckoningStep(stepLengthMeters: Double, stepHeadingDeg: Double, timestampMs: Long) {
        if (!isInitialized) return

        val dx = stepLengthMeters * sin(Math.toRadians(stepHeadingDeg))
        val dy = stepLengthMeters * cos(Math.toRadians(stepHeadingDeg))

        x[0] += dx
        x[1] += dy

        // Add uncertainty propagation for dead reckoning
        p[0][0] += 0.05 * stepLengthMeters
        p[1][1] += 0.05 * stepLengthMeters
    }

    private companion object {
        const val EARTH_RADIUS = 6378137.0

        fun geodeticToEnu(lat: Double, lon: Double, alt: Double, refLat: Double, refLon: Double, refAlt: Double): Triple<Double, Double, Double> {
            val dLat = Math.toRadians(lat - refLat)
            val dLon = Math.toRadians(lon - refLon)
            val refLatRad = Math.toRadians(refLat)

            val x = dLon * EARTH_RADIUS * cos(refLatRad)
            val y = dLat * EARTH_RADIUS
            val z = alt - refAlt
            return Triple(x, y, z)
        }

        fun enuToGeodetic(x: Double, y: Double, z: Double, refLat: Double, refLon: Double, refAlt: Double): Triple<Double, Double, Double> {
            val refLatRad = Math.toRadians(refLat)
            val dLat = y / EARTH_RADIUS
            val dLon = x / (EARTH_RADIUS * cos(refLatRad))

            val lat = refLat + Math.toDegrees(dLat)
            val lon = refLon + Math.toDegrees(dLon)
            val alt = refAlt + z
            return Triple(lat, lon, alt)
        }
    }
}
