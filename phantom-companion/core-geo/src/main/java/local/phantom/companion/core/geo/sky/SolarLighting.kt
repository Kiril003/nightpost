package local.phantom.companion.core.geo.sky

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Що це світло означає для людини — словами й числами, поверх [SolarAlmanac.sky].
 *
 * Тут НЕ рахується астрономія: висота й азимут приходять з альманаху, а цей
 * файл лише називає смугу. До ATLAS-1 було навпаки — «Ω-3 TERRAIN» у `core-nav`
 * вів власну ефемериду заради цього опису, і його наближення розходилося з
 * альманахом на 0,46°.
 *
 * Смуг прози пʼять проти чотирьох [SkyPhase], і це не друга система координат:
 * кожна смуга цілком лежить усередині однієї фази. Ніч ділиться надвоє тому, що
 * «астрономічні сутінки» й «повна темрява» — різні відповіді на питання, чи
 * потрібен ліхтар.
 */
object SolarLighting {

    data class Reading(
        val elevationDeg: Double,
        val azimuthDeg: Double,
        val phase: SkyPhase,
        val luxDirect: Float,
        val colorTempK: Int,
        val descriptionUk: String,
    )

    fun at(latDeg: Double, lonDeg: Double, epochMs: Long): Reading {
        val sky = SolarAlmanac.sky(epochMs, latDeg, lonDeg)
        val e = sky.elevationDeg
        val az = sky.azimuthDeg
        val (lux, kelvin) = band(e)
        return Reading(
            elevationDeg = e,
            azimuthDeg = az,
            phase = sky.phase,
            luxDirect = lux,
            colorTempK = kelvin,
            descriptionUk = when {
                e > SolarAlmanac.GOLDEN_DEG ->
                    "Денне світло: висота сонця ${e.roundToInt()}°, азимут ${az.roundToInt()}°, освітленість ${lux.toInt()} лк."
                e > SolarAlmanac.SEAM_DEG ->
                    "Золота година: сонце низько (${e.roundToInt()}°), довгі тіні, тепле світло ${kelvin}K."
                e > SolarAlmanac.NAUTICAL_DEG ->
                    "Сутінки: сонце за горизонтом (${e.roundToInt()}°), світло швидко йде — потрібен ліхтар."
                e > SolarAlmanac.ASTRONOMICAL_DEG ->
                    "Астрономічні сутінки: ледь помітне залишкове свічення неба."
                else ->
                    "Ніч: повна темрява, освітлення виключно місячне/штучне."
            },
        )
    }

    /** Прямий потік і температура світла. Уночі не нуль: нуль — це «датчик
     *  зламано», а не «темно». */
    private fun band(e: Double): Pair<Float, Int> = when {
        e > SolarAlmanac.GOLDEN_DEG ->
            100_000f * sin(Math.toRadians(e.coerceIn(SolarAlmanac.GOLDEN_DEG, 90.0))).toFloat() to 5800
        // Стеля золотої години = підлозі денної (100 000·sin 6° ≈ 10 450 лк).
        // Успадкована стеля 25 000 робила криву немонотонною: сонце піднімалось
        // через 6° — і освітленість у відповіді ПАДАЛА вдвічі.
        e > SolarAlmanac.SEAM_DEG ->
            10_000f * (e / SolarAlmanac.GOLDEN_DEG).coerceIn(0.02, 1.0).toFloat() to 3200
        e > SolarAlmanac.NAUTICAL_DEG -> 20f to 4500
        e > SolarAlmanac.ASTRONOMICAL_DEG -> 0.1f to 7500
        else -> 0.01f to 6000
    }
}
