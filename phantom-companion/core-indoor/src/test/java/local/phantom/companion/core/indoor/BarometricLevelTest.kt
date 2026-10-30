package local.phantom.companion.core.indoor

import local.phantom.companion.core.indoor.baro.BarometricLevel
import local.phantom.companion.core.indoor.baro.LevelMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Тести вертикальної складової: тиск → рівень/поверх.
 *
 * Горизонталь під землею дорога й непевна, а вертикаль — майже безплатна й
 * напрочуд надійна: барометр телефона впевнено розрізняє поверх, і підмінити
 * його дистанційно неможливо. Для людини в метро «ти на рівні платформи, а не
 * вестибюля» часто важливіше за «ти за 8 метрів на північ».
 *
 * Головна пастка — не шум, а погода: 1 гПа = 8.3 м ≈ три поверхи, а проходження
 * фронту дає кілька гПа за годину. Некомпенсований барометр «підіймає» нерухому
 * людину на поверхи за ніч. Тому окремий і найважливіший тест — що повільний
 * дрейф погоди НЕ зсуває рівень.
 *
 * Тиск для заданої висоти рахуємо «від істини» — інверсією тієї ж барометричної
 * формули, яку реалізує код, тож будь-яке розходження — це помилка коду.
 */
class BarometricLevelTest {

    private val p0 = 1013.0

    /** Тиск, який дала б стандартна атмосфера на заданій висоті відносно p0. */
    private fun pressureForAltitude(altitudeM: Double, refHpa: Double = p0): Double =
        refHpa * (1.0 - altitudeM / 44_330.0).pow(5.255)

    /** Погодувати сталим тиском протягом seconds при 5 Гц. */
    private fun feedConstant(baro: BarometricLevel, hpa: Double, seconds: Double, startMs: Long): Long {
        var t = startMs
        val n = (seconds * 5).toInt()
        repeat(n) {
            t += 200
            baro.onPressure(t, hpa)
        }
        return t
    }

    // ————————————————————————————————————————————————————————————————
    // Заданий перепад тиску → правильний рівень
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `перепад тиску відновлює задану висоту й рівень`() {
        // Спуск на 9 м (поверх = 3 м → рівень −3). Тиск на такій глибині
        // рахуємо інверсією формули, згодовуємо, і вимагаємо, щоб код повернув
        // −9 м і рівень −3.
        val baro = BarometricLevel(floorHeightM = 3.0)
        var t = feedConstant(baro, p0, seconds = 2.0, startMs = 0L)
        // init поставив ref=p0 на першому семплі; тепер різкий спуск.
        val pDeep = pressureForAltitude(-9.0)
        t = feedConstant(baro, pDeep, seconds = 30.0, startMs = t)

        assertEquals("висота має відновитись як −9 м", -9.0, baro.relativeAltitudeM, 0.2)
        assertEquals("рівень при поверсі 3 м має бути −3", -3.0, baro.continuousLevel, 0.1)
    }

    @Test
    fun `дробові рівні для проміжних майданчиків за планом станції`() {
        // У метро глибокого закладення вестибюль −5 м, платформа −45 м. Людина
        // на сходовому майданчику посередині (−25 м) чесно має отримати −1.5, а
        // не «поверх по три метри». Перевіряємо інтерполяцію за планом.
        val plan = listOf(
            LevelMark(level = 0.0, altitudeM = 0.0),    // вулиця
            LevelMark(level = -1.0, altitudeM = -5.0),  // вестибюль
            LevelMark(level = -2.0, altitudeM = -45.0), // платформа
        )
        val baro = BarometricLevel(levelPlan = plan)
        var t = feedConstant(baro, p0, seconds = 2.0, startMs = 0L)
        val pMid = pressureForAltitude(-25.0) // рівно посередині вестибюль↔платформа
        t = feedConstant(baro, pMid, seconds = 30.0, startMs = t)

        assertEquals("−25 м між −5 і −45 має інтерполюватись у рівень −1.5", -1.5, baro.continuousLevel, 0.05)
    }

    // ————————————————————————————————————————————————————————————————
    // Головна пастка — погода
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `повільний дрейф погоди НЕ зсуває рівень`() {
        // Людина нерухомо стоїть на платформі, а атмосферний фронт повільно тисне
        // тиск угору на 0.0004 гПа/с (~1.4 гПа/год). Без компенсації це «підняло»
        // б її на ≈5 м = майже два рівні. Опора має повзти за погодою, і рівень
        // мусить лишитись там, де людина реально стоїть.
        val baro = BarometricLevel(floorHeightM = 3.0)
        var t = 0L
        t = feedConstant(baro, p0, seconds = 2.0, startMs = t)

        val slope = 0.0004 // гПа/с — нижче за гейт руху 0.08 гПа у сталому режимі
        val dtMs = 200L
        // 40 хв повільного дрейфу.
        repeat(40 * 60 * 5) {
            t += dtMs
            val elapsedS = (t) / 1000.0
            baro.onPressure(t, p0 + slope * elapsedS)
        }

        // Без компенсації висота була б ≈ −(0.0004·2400·8.3) ≈ −8 м → рівень ≈ −2.7.
        assertTrue(
            "погода не має зсувати рівень нерухомої людини, а зсунула на ${baro.continuousLevel}",
            kotlin.math.abs(baro.continuousLevel) < 0.5,
        )
        assertTrue(
            "оцінена швидкість погоди має лишитись у межах фізики фронту: ${baro.weatherRateHpaPerS}",
            kotlin.math.abs(baro.weatherRateHpaPerS) <= BarometricLevel.MAX_WEATHER_RATE + 1e-9,
        )
    }

    @Test
    fun `швидкий рух відрізняється від погоди й дає вертикальний транзит`() {
        // Ліфт/ескалатор змінює тиск на порядки швидше за погоду. Модуль має
        // визнати це рухом (а не дрейфом опори) і підняти прапор транзиту.
        val baro = BarometricLevel(floorHeightM = 3.0)
        var t = feedConstant(baro, p0, seconds = 2.0, startMs = 0L)
        // Швидкий спуск: тиск росте лінійно за 6 с на ~1 гПа (≈ −8 м).
        val dtMs = 200L
        var p = p0
        repeat(30) {
            t += dtMs
            p += 1.0 / 30.0
            baro.onPressure(t, p)
        }
        assertTrue("швидкий спуск має визнатись вертикальним транзитом", baro.inVerticalTransit)
    }

    // ————————————————————————————————————————————————————————————————
    // Калібрування та гістерезис
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `калібрування привʼязує поточний тиск до відомого рівня`() {
        // На вході у вестибюль людина (чи план станції) оголошує «це рівень −1».
        // Поточна висота стає нулем відносно цього рівня.
        val baro = BarometricLevel()
        feedConstant(baro, p0, seconds = 3.0, startMs = 0L)
        baro.calibrateAt(-1.0)
        assertEquals("після калібрування рівень = оголошений", -1.0, baro.continuousLevel, 1e-9)
        assertEquals("висота після калібрування обнуляється", 0.0, baro.relativeAltitudeM, 1e-9)
    }

    // ————————————————————————————————————————————————————————————————
    // Сміття
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `тиск поза фізичним діапазоном ігнорується`() {
        // Несправність сенсора дала 50 гПа (висота Евересту×кілька). Це не
        // атмосфера — вимір відкидається, попередній рівень тримається.
        val baro = BarometricLevel(floorHeightM = 3.0)
        feedConstant(baro, p0, seconds = 3.0, startMs = 0L)
        val before = baro.level
        val res = baro.onPressure(10_000L, 50.0)
        assertEquals("аномальний тиск не має міняти рівень", before, res)
    }

    @Test
    fun `перший вимір лише ініціалізує стан`() {
        val baro = BarometricLevel()
        // На старті рівня ще немає.
        assertNull(baro.level)
        baro.onPressure(0L, p0)
        assertNotNull("після першого валідного виміру рівень зʼявляється", baro.level)
    }

    @Test
    fun `час назад не інтегрує вигаданої висоти`() {
        val baro = BarometricLevel(floorHeightM = 3.0)
        feedConstant(baro, p0, seconds = 3.0, startMs = 1000L)
        val before = baro.relativeAltitudeM
        baro.onPressure(100L, p0 + 2.0) // час у минулому
        assertEquals("вимір із часом назад не має зсувати висоту", before, baro.relativeAltitudeM, 1e-12)
    }

    @Test
    fun `NaN тиск не отруює фільтр`() {
        val baro = BarometricLevel()
        feedConstant(baro, p0, seconds = 3.0, startMs = 0L)
        baro.onPressure(5_000L, Double.NaN)
        assertTrue("рівень має лишитись скінченним після NaN", baro.continuousLevel.isFinite())
    }
}
