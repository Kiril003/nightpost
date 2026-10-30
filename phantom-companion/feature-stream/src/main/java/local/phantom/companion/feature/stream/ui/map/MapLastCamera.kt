package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.content.SharedPreferences

/** Де мапа була востаннє: наступне відкриття стає там, а не на домі пака. */
internal object MapLastCamera {
    private const val FILE = "map_last_camera"
    private const val LAT = "lat"
    private const val LON = "lon"
    private const val ZOOM = "zoom"

    data class Spot(val lat: Double, val lon: Double, val zoom: Double)

    fun valid(lat: Double, lon: Double, zoom: Double): Boolean =
        lat in -85.0..85.0 && lon in -180.0..180.0 && zoom in 1.0..22.0 && !(lat == 0.0 && lon == 0.0)

    fun read(prefs: SharedPreferences): Spot? {
        if (!prefs.contains(LAT) || !prefs.contains(LON)) return null
        val spot = Spot(
            prefs.getFloat(LAT, 0f).toDouble().let { Math.round(it * 1e5) / 1e5 },
            prefs.getFloat(LON, 0f).toDouble().let { Math.round(it * 1e5) / 1e5 },
            prefs.getFloat(ZOOM, DEFAULT_ZOOM.toFloat()).toDouble(),
        )
        return spot.takeIf { valid(it.lat, it.lon, it.zoom) }
    }

    fun write(prefs: SharedPreferences, lat: Double, lon: Double, zoom: Double) {
        if (!valid(lat, lon, zoom)) return
        prefs.edit().putFloat(LAT, lat.toFloat()).putFloat(LON, lon.toFloat()).putFloat(ZOOM, zoom.toFloat()).apply()
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
