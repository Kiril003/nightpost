package local.phantom.companion.core.geo.cameras

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Шість класів подій, які мапа вміє намалювати, плюс мертвий [SPEED_BUMP].
 *
 * [CHECKPOINT] — пункт пропуску чи оплати з OSM, окремо від [GATE]: правило
 * проріджування дворових воріт (z ≥ 16.5) не має його зрізати.
 */
enum class HazardType {
    SPEED_CAMERA,
    SPEED_BUMP,
    ACCIDENT_HOTSPOT,
    DANGER_ZONE,
    CHECKPOINT,
    GATE,
    SHELTER,
}

data class SafetyHazard(
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String,
    val type: HazardType,
    val speedLimitKmh: Int? = null,
    /** Провенанс мітки: «спільнота» для чужого звіту, null — власний. */
    val source: String? = null,
    /** Курс шляху крізь вузол, компасні градуси; null — курсу немає і брус
     *  воріт не малюється (білборд без орієнтації чесніший за вигаданий). */
    val bearingDeg: Double? = null,
)

data class SafetyAlert(
    val hazardId: String,
    val title: String,
    val type: HazardType,
    val distanceMeters: Double,
    val audioPrompt: String,
)

/**
 * Proximity Safety Radar Engine.
 * Warns driver or pedestrian when approaching speed cameras, high-hazard zones, or air-raid alerts.
 */
class SafetyRadarEngine(
    private val hazards: List<SafetyHazard> = emptyList(),
    private val alertRadiusMeters: Double = 300.0,
) {

    private val alertedHazardIds = mutableSetOf<String>()

    fun evaluate(currentLat: Double, currentLon: Double): List<SafetyAlert> {
        val alerts = mutableListOf<SafetyAlert>()

        for (hazard in hazards) {
            val distM = haversineMeters(currentLat, currentLon, hazard.lat, hazard.lon)
            if (distM <= alertRadiusMeters) {
                if (!alertedHazardIds.contains(hazard.id)) {
                    alertedHazardIds.add(hazard.id)

                    val audio = when (hazard.type) {
                        HazardType.SPEED_CAMERA -> "Увага! Попереду камера контролю швидкості ${hazard.speedLimitKmh ?: 50} кілометрів на годину."
                        HazardType.SPEED_BUMP -> "Попереду лежачий поліцейський."
                        HazardType.ACCIDENT_HOTSPOT -> "Обережно, аварійно-небезпечна ділянка."
                        HazardType.DANGER_ZONE -> "Увага! Позначена небезпека попереду."
                        HazardType.CHECKPOINT -> "Попереду пункт пропуску чи оплати. Знизьте швидкість."
                        HazardType.GATE -> "Попереду шлагбаум або ворота."
                        HazardType.SHELTER -> "Поруч позначене укриття."
                    }

                    alerts.add(
                        SafetyAlert(
                            hazardId = hazard.id,
                            title = hazard.title,
                            type = hazard.type,
                            distanceMeters = distM,
                            audioPrompt = audio,
                        )
                    )
                }
            } else if (distM > alertRadiusMeters * 1.5) {
                alertedHazardIds.remove(hazard.id)
            }
        }
        return alerts
    }

    private companion object {
        const val EARTH_R_M = 6371008.8

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_R_M * asin(sqrt(h).coerceIn(0.0, 1.0))
        }
    }
}
