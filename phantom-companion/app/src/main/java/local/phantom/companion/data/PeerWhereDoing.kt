package local.phantom.companion.data

import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.net.peer.PeerWhere

/**
 * «Вдома», «на роботі», «пішки», «за кермом», «телефон сідає» — з того, що в
 * телефона вже є в мить кадру: швидкість виміру, власні місця людини, батарея.
 * Жодного нового датчика: кадр іде раз на кілька хвилин, і стан міряється лише тоді.
 */
object PeerWhereDoing {

    data class Anchor(val lat: Double, val lon: Double, val spot: PeerWhere.Spot)

    /** Батарея в мить кадру; [level] у відсотках. */
    data class Battery(val level: Int, val charging: Boolean)

    const val LOW_BATTERY = 15
    private const val STILL_MPS = 0.6f
    private const val FOOT_MPS = 3.5f
    private const val SPOT_FLOOR_M = 80.0

    fun of(fix: LocationFix, anchors: List<Anchor>, battery: Battery?, shareLowBattery: Boolean): PeerWhere.State {
        val motion = when (val v = fix.speedMps) {
            null -> PeerWhere.Motion.UNKNOWN
            else -> when {
                v < STILL_MPS -> PeerWhere.Motion.STILL
                v < FOOT_MPS -> PeerWhere.Motion.FOOT
                else -> PeerWhere.Motion.VEHICLE
            }
        }
        // У дорозі людина не «вдома», навіть проїжджаючи під вікнами.
        val reach = maxOf(SPOT_FLOOR_M, fix.accuracyM?.toDouble() ?: 0.0)
        val spot = if (motion == PeerWhere.Motion.VEHICLE) {
            PeerWhere.Spot.NONE
        } else {
            anchors.map { it to metres(fix.lat, fix.lon, it.lat, it.lon) }
                .filter { it.second <= reach }
                .minByOrNull { it.second }?.first?.spot ?: PeerWhere.Spot.NONE
        }
        val low = shareLowBattery && battery != null && !battery.charging && battery.level in 0..LOW_BATTERY
        return PeerWhere.State(motion, spot, low)
    }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6_371_000.0 * Math.asin(Math.sqrt(h))
    }
}
