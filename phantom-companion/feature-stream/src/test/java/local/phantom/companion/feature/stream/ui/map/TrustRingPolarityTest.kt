package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.nav.EwTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Що позиція малює НА ЗЕМЛІ, у двох полярностях.
 *
 * Кільце сумніву втратило заливку — і разом із нею милицю, якою край
 * тримався: 1.6dp #F59E0B на 55 % давали 1.94:1 проти денної землі, тобто
 * межу було видно лише тому, що під нею лежала пляма. Тому денні барви й
 * гучність кільця тепер міряються тут, композитно (як їх бачить скло), а не
 * записуються на око.
 *
 * Земля береться з [groundPalette] — тієї самої, яку кладе
 * applyGroundCartography. Жоден hex сюди не передруковується.
 */
class TrustRingPolarityTest {

    private val floor = 3.0

    private fun land(isDark: Boolean) = groundPalette(isDark).land and 0xFFFFFF

    private fun ink(hex: String) = hex.removePrefix("#").toInt(16)

    /** Вирок → нічний hex, як його віддає єдине джерело. */
    private val verdicts = mapOf(
        "DEGRADED" to EwTrail.colorFor(false, LocationTrust.DEGRADED),
        "JAMMED" to EwTrail.colorFor(false, LocationTrust.JAMMED),
        "SPOOFED" to EwTrail.colorFor(false, LocationTrust.SPOOFED),
    )

    /**
     * Головне зобовʼязання цього фронту: **де чип говорить, там кільце
     * читається.** Мовчазні стани мають право шепотіти, ці — ні.
     */
    @Test
    fun `a verdict the chip speaks is legible on its own ground`() {
        listOf(true, false).forEach { isDark ->
            val ground = land(isDark)
            verdicts.forEach { (name, night) ->
                val colour = ink(trustRingHex(night, isDark))
                val alpha = trustRingAlpha(night, isDark)
                val got = contrast(composite(colour, alpha, ground), ground)
                assertTrue(
                    "$name (isDark=$isDark) на землі ${hex(ground)} — ${"%.2f".format(got)}:1 " +
                        "при альфі $alpha. Саме так виглядав край кола до того, як прибрали заливку.",
                    got >= floor,
                )
            }
        }
    }

    /**
     * Альфа — стеля, якої не подолати барвою: навіть чорне чорнило на 55 %
     * бере 3.0:1 проти #F6F3EC. Запис лишається, щоб денний DEGRADED ніхто
     * не «вирівняв» назад до нічних 0.55, вважаючи різницю друкарською.
     */
    @Test
    fun `the day ladder is not the night ladder, and that is measured`() {
        val degraded = EwTrail.colorFor(false, LocationTrust.DEGRADED)
        assertTrue(
            "денний DEGRADED мусить бути гучнішим за нічний, інакше він бере 2.20:1",
            trustRingAlpha(degraded, isDark = false) > trustRingAlpha(degraded, isDark = true),
        )
    }

    /** Гучність росте з вагою повідомлення, а не з величиною помилки. */
    @Test
    fun `louder means worse, in both polarities`() {
        listOf(true, false).forEach { isDark ->
            val ladder = listOf(
                LocationTrust.TRUSTED, LocationTrust.DEGRADED,
                LocationTrust.JAMMED, LocationTrust.SPOOFED,
            ).map { trustRingAlpha(EwTrail.colorFor(false, it), isDark) }
            ladder.zipWithNext().forEach { (quiet, loud) ->
                assertTrue("сходинка вниз у драбині гучності (isDark=$isDark): $ladder", loud >= quiet)
            }
            assertTrue(
                "TRUSTED мусить лишатись шепотом (isDark=$isDark): $ladder",
                ladder.first() < ladder[1],
            )
        }
    }

    /** Барва виведена проти СВОЄЇ землі — це і є причина існування пар. */
    @Test
    fun `each ink was chosen for the ground it lies on`() {
        listOf(true, false).forEach { isDark ->
            val ground = land(isDark)
            MapSymbolPalette.TRUST_RING_DAY.keys.forEach { night ->
                val colour = ink(trustRingHex(night, isDark))
                assertTrue(
                    "${hex(colour)} (isDark=$isDark) на ${hex(ground)} — ${ratio(colour, ground)}:1",
                    contrast(colour, ground) >= floor,
                )
            }
        }
    }

    /** Слід теж лежить на землі, тож теж має дві полярності. */
    @Test
    fun `the trail speaks the polarity of the ground`() {
        val day = MapSymbolPalette.LIGHT
        val night = MapSymbolPalette.DARK
        assertNotEquals("одна барва сліду на обидві землі", day.trailClear, night.trailClear)
        listOf(true to night, false to day).forEach { (isDark, palette) ->
            val ground = land(isDark)
            listOf("слід" to palette.trailClear).forEach { (n, c) ->
                assertTrue(
                    "$n (isDark=$isDark) на ${hex(ground)} — ${ratio(c and 0xFFFFFF, ground)}:1 " +
                        "непрозоро; на склі його ще ділить згасання за віком",
                    contrast(c and 0xFFFFFF, ground) >= floor,
                )
            }
        }
    }

    /**
     * Конус був другим бурштиновим знаком поруч із точкою — і на бежевій
     * землі брав ~1.3:1, тобто вдень надворі його не існувало.
     */
    @Test
    fun `the cone is the ground's ink, never the operator's amber`() {
        listOf(true, false).forEach { isDark ->
            val coneInk = MapSymbolPalette.resolve(isDark).separator
            assertNotEquals(
                "конус знову бурштиновий — це другий знак того ж кольору, що й оператор",
                MapSymbolPalette.PUCK_CORE,
                coneInk,
            )
            val ground = land(isDark)
            assertTrue(
                "чорнило конуса (isDark=$isDark) на ${hex(ground)} — ${ratio(coneInk and 0xFFFFFF, ground)}:1 непрозоро",
                contrast(coneInk and 0xFFFFFF, ground) >= floor,
            )
        }
    }

    /** Ширина сектора Є похибкою компаса, сказаною чесно. */
    @Test
    fun `the sector widens exactly as the compass loses certainty`() {
        assertTrue(CompassFidelity.HIGH.sectorDeg < CompassFidelity.MEDIUM.sectorDeg)
        assertTrue(CompassFidelity.MEDIUM.sectorDeg < CompassFidelity.LOW.sectorDeg)
        assertEquals(
            "напрям, якого пристрій не знає, не малюється",
            0f,
            CompassFidelity.UNRELIABLE.sectorDeg,
            0f,
        )
        assertEquals(
            CompassFidelity.HIGH,
            CompassFidelity.fromSensorStatus(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH),
        )
        assertEquals(
            CompassFidelity.MEDIUM,
            CompassFidelity.fromSensorStatus(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM),
        )
        assertEquals(
            CompassFidelity.LOW,
            CompassFidelity.fromSensorStatus(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW),
        )
        assertEquals(
            CompassFidelity.UNRELIABLE,
            CompassFidelity.fromSensorStatus(android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE),
        )
    }

    /** Кожен сектор і кожна полярність мають ВЛАСНУ картинку, інакше вибір фіктивний. */
    @Test
    fun `no two cones share an image id`() {
        val ids = CompassFidelity.entries.flatMap { f ->
            listOf(coneImageId(f.orWidest(), true), coneImageId(f.orWidest(), false))
        }
        assertEquals(
            "три сектори × дві полярності = шість картинок; збіг означає, що вибір нічого не міняє",
            6,
            ids.toSet().size,
        )
    }

    /** Точка мусить вигравати у власного кола: Ø30dp і видимий світлий обід. */
    @Test
    fun `the dot keeps its two edges at the new size`() {
        assertEquals("Ø30dp", 30f, PUCK_HALO_R_DP * 2f, 0f)
        assertTrue(
            "світлого гало не видно з-під чорнильного обода",
            PUCK_HALO_R_DP > PUCK_CORE_R_DP + PUCK_RIM_W_DP,
        )
    }

    // ── WCAG над src-over композитингом (метод MapSymbolContrastTest) ────

    private fun composite(rgb: Int, alpha: Float, ground: Int): Int {
        var out = 0
        for (shift in listOf(16, 8, 0)) {
            val f = (rgb shr shift) and 0xFF
            val b = (ground shr shift) and 0xFF
            out = out or ((f * alpha + b * (1 - alpha)).toInt() shl shift)
        }
        return out
    }

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun ratio(a: Int, b: Int) = "%.2f".format(contrast(a, b))

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
