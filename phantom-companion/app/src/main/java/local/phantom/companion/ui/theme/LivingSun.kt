package local.phantom.companion.ui.theme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import java.util.Locale
import java.util.TimeZone
import local.phantom.companion.core.design.theme.DayPhase
import local.phantom.companion.core.design.theme.SunLook
import local.phantom.companion.core.geo.sky.SolarAlmanac

/**
 * Кадр сонця для [local.phantom.companion.core.design.theme.ThemeMode.Living] —
 * з висоти СОНЦЯ в точці людини, а не з годинника.
 *
 * Астрономії тут немає: висоту й напрям руху сонця дає [SolarAlmanac.sky] —
 * єдине джерело сонця в застосунку (його ж читає мапа). Вікна шести точок —
 * [SunLook.at] з tokens.json (варіант B, власник 28.10), ті самі, що у вебі.
 *
 * Шов полярності МАПИ лежить на −5° ([SolarAlmanac.SEAM_DEG]) і навмисно
 * інший: мапа темніє, коли сонце ЗАЙШЛО, бо це земля під ногами. Мапу цей
 * файл не чіпає.
 */
object LivingSun {

    /** Висота + напрям (+ пік дня для зими) → кадр. */
    fun lookOf(elevationDeg: Double, rising: Boolean, peakDeg: Double = 90.0): SunLook =
        SunLook.at(elevationDeg, rising, peakDeg)

    fun phaseOf(elevationDeg: Double, rising: Boolean, peakDeg: Double = 90.0): DayPhase =
        lookOf(elevationDeg, rising, peakDeg).phase

    fun lookAt(epochMs: Long, place: SunPlace): SunLook {
        val sky = SolarAlmanac.sky(epochMs, place.lat, place.lon)
        return lookOf(sky.elevationDeg, sky.morning, peakAround(epochMs, place))
    }

    fun phaseAt(epochMs: Long, place: SunPlace): DayPhase = lookAt(epochMs, place).phase

    /** Кадр зараз, там, де людина. Binder-виклик до LocationManager — не з гарячого циклу. */
    fun lookNow(context: Context, nowMs: Long = System.currentTimeMillis()): SunLook =
        lookAt(nowMs, SunPlaces.current(context))

    /** Найвища точка сонця за ±12 год — для зимового полудня. */
    private fun peakAround(epochMs: Long, place: SunPlace): Double =
        (-48..48).maxOf { SolarAlmanac.altitudeDeg(epochMs + it * 900_000L, place.lat, place.lon) }
}

/** Точка, для якої рахується сонце, і звідки вона взялась. */
data class SunPlace(val lat: Double, val lon: Double, val source: Source) {
    enum class Source { FIX, ZONE, COUNTRY, OFFSET }

    companion object {
        /** Координати з будь-якого джерела — зведені в допустимі межі або null. */
        fun of(lat: Double, lon: Double, source: Source): SunPlace? {
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0) return null
            val l = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return SunPlace(lat, l, source)
        }
    }
}

/**
 * Де людина — для сонця, не для навігації: помилка в 100 км зсуває схід на
 * хвилини, тож досить останньої відомої позиції. Нічого не вмикає — ні GPS, ні
 * вартового узгодженості: лише питає систему, що вона вже знає.
 *
 * Драбина: остання позиція → центр часового поясу → центр країни з локалі →
 * довгота зі зсуву пояса на екваторі. Останній щабель навмисно на 0° широти:
 * там день і ніч по 12 годин цілий рік, тож хибна пора року не дасть ні
 * полярної ночі, ні білої — лише грубу «годинникову» добу.
 */
object SunPlaces {

    fun current(context: Context, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): SunPlace =
        lastKnown(context)?.also { keep(context, it) } ?: fallback(zone.id, zone.rawOffset, locale.country)

    /** Без жодного виклику в систему — для першого кадру, поки тікер не спитав позицію. */
    fun fallbackNow(zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): SunPlace =
        fallback(zone.id, zone.rawOffset, locale.country)

    /**
     * Місце для ПЕРШОГО кадру: останнє справжнє, яке бачив тікер [current], а
     * без нього — центр поясу.
     *
     * Лише центр поясу тут не годиться: телефон у поясі CEST, а людина в
     * Києві, — і годину після київських сутінків перший кадр малював День, а
     * за мить тікер приносив позицію й перефарбовував застосунок у Ніч.
     * Збережене місце читається з SharedPreferences без жодного виклику в
     * LocationManager.
     */
    fun firstFrame(context: Context): SunPlace = remembered(context) ?: fallbackNow()

    private const val PREFS = "phantom_sun"

    internal fun remembered(context: Context): SunPlace? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat") || !p.contains("lon")) return null
        return SunPlace.of(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), SunPlace.Source.FIX)
    }

    private fun keep(context: Context, place: SunPlace) {
        val old = remembered(context)
        // Для сонця 0,05° (~5 км) — нічого: не пишемо диск щохвилини.
        if (old != null && kotlin.math.abs(old.lat - place.lat) < 0.05 && kotlin.math.abs(old.lon - place.lon) < 0.05) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", place.lat.toFloat()).putFloat("lon", place.lon.toFloat()).apply()
    }

    fun fallback(zoneId: String, rawOffsetMs: Int, country: String?): SunPlace =
        ZONES[zoneId]?.let { SunPlace(it.first, it.second, SunPlace.Source.ZONE) }
            ?: country?.uppercase(Locale.ROOT)?.let { COUNTRIES[it] }
                ?.let { SunPlace(it.first, it.second, SunPlace.Source.COUNTRY) }
            ?: SunPlace(0.0, (rawOffsetMs / 3_600_000.0 * 15.0).coerceIn(-180.0, 180.0), SunPlace.Source.OFFSET)

    @Suppress("MissingPermission")
    private fun lastKnown(context: Context): SunPlace? = runCatching {
        val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return@runCatching null
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@runCatching null
        listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { mgr.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { SunPlace.of(it.latitude, it.longitude, SunPlace.Source.FIX) }
    }.getOrNull()

    /** Центри поясів, де PHANTOM живе найчастіше. Решта — через країну або зсув. */
    internal val ZONES: Map<String, Pair<Double, Double>> = mapOf(
        "Europe/Kyiv" to (50.45 to 30.52),
        "Europe/Kiev" to (50.45 to 30.52),
        "Europe/Uzhgorod" to (48.62 to 22.30),
        "Europe/Zaporozhye" to (47.84 to 35.14),
        "Europe/Simferopol" to (44.95 to 34.10),
        "Europe/Chisinau" to (47.01 to 28.86),
        "Europe/Warsaw" to (52.23 to 21.01),
        "Europe/Bucharest" to (44.43 to 26.10),
        "Europe/Vilnius" to (54.69 to 25.28),
        "Europe/Riga" to (56.95 to 24.11),
        "Europe/Tallinn" to (59.44 to 24.75),
        "Europe/Helsinki" to (60.17 to 24.94),
        "Europe/Prague" to (50.08 to 14.44),
        "Europe/Bratislava" to (48.15 to 17.11),
        "Europe/Budapest" to (47.50 to 19.04),
        "Europe/Berlin" to (52.52 to 13.40),
        "Europe/Vienna" to (48.21 to 16.37),
        "Europe/Paris" to (48.86 to 2.35),
        "Europe/London" to (51.51 to -0.13),
        "Europe/Madrid" to (40.42 to -3.70),
        "Europe/Lisbon" to (38.72 to -9.14),
        "Europe/Rome" to (41.90 to 12.50),
        "Europe/Amsterdam" to (52.37 to 4.90),
        "Europe/Stockholm" to (59.33 to 18.07),
        "Europe/Oslo" to (59.91 to 10.75),
        "Europe/Istanbul" to (41.01 to 28.98),
        "Asia/Tbilisi" to (41.72 to 44.79),
        "America/New_York" to (40.71 to -74.01),
        "America/Toronto" to (43.65 to -79.38),
        "America/Chicago" to (41.88 to -87.63),
        "America/Los_Angeles" to (34.05 to -118.24),
    )

    /** Географічні центри країн (ISO 3166-1 alpha-2). */
    internal val COUNTRIES: Map<String, Pair<Double, Double>> = mapOf(
        "UA" to (49.0 to 31.4),
        "PL" to (52.0 to 19.4),
        "MD" to (47.2 to 28.5),
        "RO" to (45.9 to 24.9),
        "SK" to (48.7 to 19.7),
        "HU" to (47.2 to 19.5),
        "CZ" to (49.8 to 15.5),
        "DE" to (51.2 to 10.4),
        "LT" to (55.2 to 23.9),
        "LV" to (56.9 to 24.6),
        "EE" to (58.6 to 25.0),
        "FI" to (64.5 to 26.0),
        "GB" to (54.0 to -2.5),
        "FR" to (46.6 to 2.2),
        "ES" to (40.2 to -3.7),
        "IT" to (42.8 to 12.6),
        "NL" to (52.1 to 5.3),
        "SE" to (62.0 to 15.0),
        "NO" to (64.5 to 12.0),
        "GE" to (42.3 to 43.4),
        "TR" to (39.0 to 35.2),
        "US" to (39.8 to -98.6),
        "CA" to (56.1 to -106.3),
    )
}
