package local.phantom.companion.feature.stream.ui.map.dossier

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

internal fun haversineMeters(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
): Double {
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a.coerceAtLeast(0.0))))
}

internal fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> MapText.s(R.string.mapl_b_dist_m, (meters / 10).roundToInt() * 10)
    meters < 10_000 -> MapText.s(R.string.mapl_b_dist_km_tenths, meters / 1_000)
    else -> MapText.s(R.string.mapl_b_dist_km, (meters / 1_000).roundToInt())
}

internal fun walkEtaMinutes(meters: Double): Int =
    maxOf(1, ((meters * DETOUR_FACTOR) / WALK_MPS / 60.0).roundToInt())

internal data class Proximity(val meters: Double, val walkMinutes: Int)

internal fun proximity(origin: Pair<Double, Double>?, lat: Double, lon: Double): Proximity? {
    val from = origin ?: return null
    val meters = haversineMeters(from.first, from.second, lat, lon)
    return Proximity(meters, walkEtaMinutes(meters))
}

internal data class WalkWords(
    val minutes: (Int) -> String,
    val hours: (Int) -> String,
    val hoursMinutes: (Int, Int) -> String,
)

internal fun walkEtaText(minutes: Int, words: WalkWords): String = when {
    minutes < 60 -> words.minutes(minutes)
    minutes < 180 -> (minutes % 60).let { m -> if (m == 0) words.hours(minutes / 60) else words.hoursMinutes(minutes / 60, m) }
    else -> words.hours((minutes / 60.0).roundToInt())
}

private const val EARTH_RADIUS_M = 6_371_000.0
private const val DETOUR_FACTOR = 1.3
private const val WALK_MPS = 1.35
