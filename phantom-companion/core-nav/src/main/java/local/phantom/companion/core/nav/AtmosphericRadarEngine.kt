package local.phantom.companion.core.nav

import kotlin.math.roundToInt

/**
 * M-F01 / M-F02 — Atmospheric Radar & Precipitation Overlay Engine.
 *
 * Evaluates weather radar reflectivity (dBZ), precipitation intensity,
 * wind vectors, and surface conditions for spatial map rendering and tactical previews.
 */
class AtmosphericRadarEngine {

    enum class PrecipitationType {
        CLEAR,
        LIGHT_RAIN,
        MODERATE_RAIN,
        HEAVY_SHOWER,
        THUNDERSTORM,
        SNOW,
        FOG,
    }

    data class AtmosphericState(
        val lat: Double,
        val lon: Double,
        val precipitationType: PrecipitationType,
        val reflectivityDbz: Int,
        val temperatureCelsius: Double,
        val windSpeedKmh: Double,
        /** Звідки дме — або null, коли джерело напрямку не назвало. */
        val windDirectionDeg: Int?,
        /** Румб словом — або null разом із [windDirectionDeg]. */
        val windCompassUk: String?,
        val activeMapLayer: String?,
        val briefUk: String,
    )

    /**
     * Evaluates atmospheric state from raw meteorology indicators.
     *
     * [windDeg] nullable СВІДОМО. До 13.09 єдиний виклик передавав сюди сталу
     * `270`, бо клієнт Open-Meteo напрямку не питав: бриф казав «вітер
     * Західний» у будь-яку погоду, на будь-якій широті, і це речення читали
     * вголос як вимір. Мовчання джерела тепер лишається мовчанням — румб
     * просто зникає з речення, а не підміняється правдоподібним.
     */
    fun evaluateAtmosphere(
        lat: Double,
        lon: Double,
        temperatureC: Double,
        precipitationMmH: Double,
        windKmh: Double,
        windDeg: Int?,
        cloudCoverPercent: Int = 0,
        weatherCodeWmo: Int? = null,
    ): AtmosphericState {
        val (precipType, dbz, layer) = classifyPrecipitation(precipitationMmH, temperatureC, weatherCodeWmo)
        val windCompass = windDeg?.let { compassQuadrantUk(it) }
        // «вітер Західний 12 км/год» проти «вітер 12 км/год»: друге — усе, що
        // справді виміряли, і саме його можна сказати.
        val wind = if (windCompass != null) {
            "вітер $windCompass ${windKmh.roundToInt()} км/год"
        } else {
            "вітер ${windKmh.roundToInt()} км/год"
        }

        val brief = when (precipType) {
            PrecipitationType.CLEAR -> "Небо ясне/хмарність $cloudCoverPercent%, температура ${temperatureC.roundToInt()}°C, $wind."
            PrecipitationType.LIGHT_RAIN -> "Невеликий дощ (радар $dbz dBZ), температура ${temperatureC.roundToInt()}°C, $wind."
            PrecipitationType.MODERATE_RAIN -> "Помірний дощ ($precipitationMmH мм/год, радар $dbz dBZ), температура ${temperatureC.roundToInt()}°C, пориви вітру до ${windKmh.roundToInt()} км/год."
            PrecipitationType.HEAVY_SHOWER -> "Злива ($precipitationMmH мм/год, радар $dbz dBZ), видимість знижена, $wind."
            PrecipitationType.THUNDERSTORM -> "Гроза та шквалистий вітер ($windKmh км/год, радар $dbz dBZ), високий ризик аквапланування."
            PrecipitationType.SNOW -> "Снігопад, температура ${temperatureC.roundToInt()}°C, ожеледиця на дорогах."
            PrecipitationType.FOG -> "Густий туман, видимість менше 200 м, температура ${temperatureC.roundToInt()}°C."
        }

        return AtmosphericState(
            lat = lat,
            lon = lon,
            precipitationType = precipType,
            reflectivityDbz = dbz,
            temperatureCelsius = temperatureC,
            windSpeedKmh = windKmh,
            windDirectionDeg = windDeg,
            windCompassUk = windCompass,
            activeMapLayer = layer,
            briefUk = brief,
        )
    }

    private fun classifyPrecipitation(
        precipMmH: Double,
        temperatureC: Double,
        wmoCode: Int?,
    ): Triple<PrecipitationType, Int, String?> {
        if (wmoCode != null) {
            when (wmoCode) {
                95, 96, 99 -> return Triple(PrecipitationType.THUNDERSTORM, 52, "layer_radar_storm")
                71, 73, 75, 77, 85, 86 -> return Triple(PrecipitationType.SNOW, 35, "layer_radar_snow")
                45, 48 -> return Triple(PrecipitationType.FOG, 15, "layer_fog_attenuation")
            }
        }

        return when {
            precipMmH <= 0.05 -> Triple(PrecipitationType.CLEAR, 0, null)
            temperatureC <= 0.0 -> Triple(PrecipitationType.SNOW, (20 + precipMmH * 5).toInt().coerceAtMost(45), "layer_radar_snow")
            precipMmH < 2.5 -> Triple(PrecipitationType.LIGHT_RAIN, (18 + precipMmH * 6).toInt(), "layer_radar_rain_light")
            precipMmH < 10.0 -> Triple(PrecipitationType.MODERATE_RAIN, (32 + precipMmH * 2).toInt(), "layer_radar_rain_moderate")
            precipMmH < 30.0 -> Triple(PrecipitationType.HEAVY_SHOWER, (46 + precipMmH * 0.5).toInt().coerceAtMost(55), "layer_radar_rain_heavy")
            else -> Triple(PrecipitationType.THUNDERSTORM, 58, "layer_radar_storm")
        }
    }

    private fun compassQuadrantUk(bearingDeg: Int): String = when (((bearingDeg + 22) % 360) / 45) {
        0 -> "Північний"
        1 -> "Північно-Східний"
        2 -> "Східний"
        3 -> "Південно-Східний"
        4 -> "Південний"
        5 -> "Південно-Західний"
        6 -> "Західний"
        7 -> "Північно-Західний"
        else -> "Північний"
    }
}
