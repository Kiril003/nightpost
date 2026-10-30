package local.phantom.companion.core.geo.precision

import kotlin.math.cos
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Два числа, які ЕКФ віддає на екран «Точність», і які він до цього вигадував.
 *
 * Обидва мали спільну ваду: вони виводились із власного стану фільтра й ні з чим
 * не звірялись, тож почервоніти не могли — виміром їх ніхто не бив.
 */
class EkfHonestyTest {

    private val mPerDegLat = 111_320.0
    private fun metresPerDegLon(lat: Double) = mPerDegLat * cos(Math.toRadians(lat))

    @Test
    fun `filter never claims better accuracy than the receiver reports`() {
        // Той самий телефон, що стоїть на місці й ЩОРАЗУ звітує ±12 м.
        // Коваріація фільтра при цьому скорочується на кожному оновленні
        // (p *= 1-k), тож раніше плитка доїжджала до підлоги 0,5 м — «±1 м» на
        // апараті, який каже ±12. Саме це число живить trustedForAnchor.
        val ekf = UltraPrecisionEkfEngine()
        var accuracy = Float.MAX_VALUE
        repeat(40) { i ->
            accuracy = ekf.updateGnssFix(
                lat = 50.4501,
                lon = 30.5234,
                alt = 150.0,
                accuracyM = 12.0f,
                speedMps = 0f,
                bearingDeg = 0f,
                timestampMs = 1_000L + i * 1_000L,
            ).accuracyMeters
        }
        assertTrue(
            "фільтр заявив ±$accuracy м там, де приймач звітує ±12 м — " +
                "це самооцінка коваріації, а не похибка апарата",
            accuracy >= 12.0f,
        )
    }

    @Test
    fun `bearing follows where you are going, not where you started`() {
        // Тисяча метрів на північ, потім поворот на схід. Курс має стати східним.
        // Стара формула брала atan2 ПОЗИЦІЙНИХ станів, тобто напрямок від якоря
        // фільтра до поточної точки: після такого шляху вона дає ~17°, і чим
        // далі відійдеш від місця ініціалізації, тим упертіше вона бреше.
        val lat0 = 50.0
        val lon0 = 30.0
        val ekf = UltraPrecisionEkfEngine()
        var t = 1_000L
        var north = 0.0
        var east = 0.0

        fun step(): Float {
            t += 1_000L
            return ekf.updateGnssFix(
                lat = lat0 + north / mPerDegLat,
                lon = lon0 + east / metresPerDegLon(lat0),
                alt = 100.0,
                accuracyM = 5.0f,
                speedMps = 0f,
                bearingDeg = 0f,
                timestampMs = t,
            ).bearingDegrees
        }

        step() // якір
        repeat(50) { north += 20.0; step() }
        var bearing = 0f
        repeat(15) { east += 20.0; bearing = step() }

        assertTrue(
            "курс $bearing° після повороту на схід — стара формула віддає тут " +
                "напрямок від якоря (~17°), а не напрямок руху",
            bearing in 60f..120f,
        )
    }
}
