package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * Whether now is a decent moment to pull hundreds of megabytes of tiles.
 *
 * Downloads used to start the instant a region was created, on whatever
 * connection and whatever charge — a pack begun on mobile data in the field
 * competes with the operator's own traffic and drains the battery he needs.
 */
object OfflineConditions {

    enum class Verdict { GO, METERED, LOW_BATTERY, OFFLINE }

    /** True while the pack may keep pulling tiles. */
    fun check(context: Context, allowMetered: Boolean): Verdict {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Verdict.GO
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return Verdict.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Verdict.OFFLINE

        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = battery?.isCharging ?: false
        if (!charging && level in 1 until LOW_BATTERY_PERCENT) return Verdict.LOW_BATTERY

        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (!unmetered && !allowMetered) return Verdict.METERED
        return Verdict.GO
    }

    /** Below this, on battery, tiles wait — the map is worthless on a dead phone. */
    const val LOW_BATTERY_PERCENT = 20
}
