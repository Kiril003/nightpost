package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Доказ дефекту «не перемикається назад на справжній GPS», написаний навмисно
 * ЛИШЕ через відкритий API рушія.
 *
 * Навіщо окремо від [SkyReturnCadenceTest]: той тест посилається на константи,
 * яких у старому коді не існувало, тож на старому коді він **не компілювався** —
 * а це не те саме, що «червонів». Тест, який не збирається, нічого не доводить
 * про поведінку: він доводить лише, що імені немає.
 *
 * Цей тест не знає жодної внутрішньої константи. Він ставить питання, яке
 * ставить людина: **якщо небо повернулось і чесно відповідає — скільки часу
 * навігація ще вестиме за галюцинацією?** І відповідь тут виміряна в секундах
 * оператора, а не в приходах чипа, бо кадансом розпоряджається чип, не людина.
 *
 * Каданс 125 с — не вигадка: він заміряний на справжньому записі
 * `_grant/records/reb-20260804` (32 фікси за 4002 с недовіри).
 *
 * МЕЖА ЦЬОГО ДОКАЗУ: він відтворює МЕХАНІЗМ на заміряному кадансі, а не сам
 * запис. Він не доводить, що в тій конкретній поїздці спрацювала саме ця гілка
 * (для цього потрібен рядок `kal` із пройденою інерцією), — він доводить, що
 * при такому небі повернення було недосяжним.
 */
class SkyReturnProofTest {

    private val lat0 = 50.4501
    private val lon0 = 30.5234

    private fun northOf(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)

    private fun eastOf(m: Double) =
        lon0 + Math.toDegrees(m / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    private fun fix(northM: Double, eastM: Double = 0.0, speed: Float = 11f, acc: Float = 5f) =
        GpsSample(
            lat = northOf(northM),
            lon = eastOf(eastM),
            accuracyM = acc,
            speedMps = speed,
            bearingDeg = 0f,
        )

    /** Телефон лежить рівно: платформа не додає ані прискорення, ані обертання. */
    private fun imu() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /** Датчики 50 Гц, тік раз на секунду — рівно як їх годує застосунок. */
    private fun coast(engine: CustomLocationEngine, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        val end = fromMs + forMs
        var nextTick = fromMs + 1_000
        while (t < end) {
            t += 20
            engine.onImu(imu(), t)
            if (t >= nextTick) {
                engine.onTick(t)
                nextTick += 1_000
            }
        }
        return t
    }

    private fun blind(engine: CustomLocationEngine): Boolean =
        engine.trust.value == LocationTrust.SPOOFED || engine.trust.value == LocationTrust.JAMMED

    @Test
    fun `небо, що відповідає раз на дві хвилини, мусить повернути довіру за пів години`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        // Тридцять секунд чесного неба: рушій має від чого відштовхнутись.
        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals("чисте небо мусить бути довіреним", LocationTrust.TRUSTED, engine.trust.value)
        val beforeTunnel = 11.0 * 29

        // Дві хвилини тунелю — сліпота, і числення несе точку далі на північ.
        t = coast(engine, t, 120_000)
        assertTrue("тунель мусить читатись як сліпота", blind(engine))

        // Оператор звернув і став. Небо повертається — чесне, стабільне, але
        // рідке: раз на 125 секунд, як у справжньому записі.
        val truth = { fix(northM = beforeTunnel, eastM = 4_000.0, speed = 0f, acc = 25f) }
        val startedMs = t
        var recoveredAfterS = -1L

        repeat(14) {
            engine.onGps(truth(), t)
            if (!blind(engine) && recoveredAfterS < 0) recoveredAfterS = (t - startedMs) / 1_000
            t = coast(engine, t, 125_000)
        }
        if (!blind(engine) && recoveredAfterS < 0) recoveredAfterS = (t - startedMs) / 1_000

        assertTrue(
            "небо повернулось і чесно відповідало 29 хвилин, а навігація так і вела " +
                "за галюцинацією (стан ${engine.trust.value})",
            recoveredAfterS >= 0,
        )
        assertTrue(
            "повернення тривало $recoveredAfterS с — це довше за десять хвилин, " +
                "тобто людина півдороги їде за вигадкою",
            recoveredAfterS <= 600,
        )
    }

    @Test
    fun `один-єдиний фікс після паузи не повертає довіру`() {
        // Друга половина правди: лікуючи застрягання, не можна відчинити двері
        // підмінникові, який чекає, поки ми осліпнемо, і подає ОДИН гарний фікс.
        val engine = CustomLocationEngine()
        var t = 1_000L
        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        t = coast(engine, t, 120_000)
        assertTrue(blind(engine))

        engine.onGps(fix(northM = 11.0 * 29, eastM = 4_000.0, speed = 0f, acc = 25f), t)
        assertTrue(
            "одного фікса після сліпоти не досить, хоч би скільки тривала пауза",
            blind(engine),
        )
    }
}
