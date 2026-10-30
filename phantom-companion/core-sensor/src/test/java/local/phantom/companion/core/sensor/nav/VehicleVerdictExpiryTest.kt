package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вирок про транспорт мусить померти разом із доказами.
 *
 * 1 вересня 2026, 02:18. Телефон власника лежав удома нерухомо, швидкість у
 * системі 0,08 м/с — а чип казав «авто». Годиною раніше він бачив «потяг» і
 * написав: «звідки потяг?? я дома блять».
 *
 * Витримка тут ні до чого: `VEHICLE_TO_STILL_DWELL_MS` = 25 с давно минули.
 * Причина глибша — **класифікація відбувається лише коли приходить фікс**, а
 * в приміщенні фіксів немає. Останній вирок лишався назавжди, бо його не було
 * чим спростувати. Тобто чип не помилявся, він **не вмів замовкнути**.
 *
 * Ці тести стережуть три різні речі, і кожна ламається окремо.
 */
class VehicleVerdictExpiryTest {

    private val ttl = KinematicsEngine.VEHICLE_EVIDENCE_TTL_MS

    /**
     * DRIVE чи TRAIN — для терміну придатності це одне й те саме.
     *
     * Перша версія цього тесту вимагала саме DRIVE і **провалилась на власній
     * умові**: мій синтетичний IMU ідеально гладкий — нуль ривка, нуль
     * рискання, — а рушій за своєю ж докстрінгою читає «гладко + прямо +
     * швидко» як рейки. Тобто не продукт помилився, а мій стенд був
     * несхожий на авто. Обидва стани стоять на ШВИДКОСТІ, і саме тому обидва
     * протухають без неба.
     */
    private fun KinematicsEngine.MotionState.isVehicleVerdict() =
        this == KinematicsEngine.MotionState.DRIVE ||
            this == KinematicsEngine.MotionState.TRAIN

    /** Телефон лежить рівно: платформа не додає ані прискорення, ані обертання. */
    private fun imu() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /** Датчики 50 Гц — рівно як їх годує PhantomLocator. */
    private fun idle(engine: KinematicsEngine, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        val end = fromMs + forMs
        while (t < end) {
            t += 20
            engine.onImu(imu(), t)
        }
        return t
    }

    /** Довезти рушій до вироку «авто»: швидке небо плюс рівний хід. */
    private fun driveUntilVehicle(engine: KinematicsEngine, fromMs: Long): Long {
        var t = fromMs
        repeat(60) {
            engine.onFix(speedMps = 15f, altitudeM = Float.NaN, nowMs = t)
            t = idle(engine, t, 1_000)
        }
        return t
    }

    @Test
    fun `авто без свіжої швидкості протухає в невідомо`() {
        val engine = KinematicsEngine()
        var t = 1_000L
        t = driveUntilVehicle(engine, t)
        assertTrue(
            "умова тесту не склалась: рушій мусив дійти до транспорту, а дав ${engine.profile.value.state}",
            engine.profile.value.state.isVehicleVerdict(),
        )

        // Небо зникло — людина зайшла в дім. IMU далі йде, фіксів немає.
        t = idle(engine, t, ttl + 5_000)

        assertEquals(
            "телефон лежить нерухомо, а чип досі стверджує транспорт",
            KinematicsEngine.MotionState.UNKNOWN,
            engine.profile.value.state,
        )
        assertEquals(0f, engine.profile.value.confidence, 1e-6f)
    }

    @Test
    fun `протухає саме в НЕВІДОМО, а не в «стою»`() {
        // «Стою» — це теж твердження. Зробити його з самої лише тиші
        // акселерометра означало б замінити одну вигадку іншою: людина в
        // тихому вагоні не стоїть.
        val engine = KinematicsEngine()
        var t = 1_000L
        t = driveUntilVehicle(engine, t)
        t = idle(engine, t, ttl + 5_000)
        assertNotEquals(KinematicsEngine.MotionState.STILL, engine.profile.value.state)
    }

    @Test
    fun `поки небо відповідає — вирок живий`() {
        // Інакше ліки коштували б дорожче за хворобу: чип миготів би на
        // кожному мості й у кожному дворі-колодязі.
        val engine = KinematicsEngine()
        var t = 1_000L
        t = driveUntilVehicle(engine, t)
        repeat(10) {
            // Проміжок майже до самої стелі, але фікси йдуть.
            t = idle(engine, t, ttl - 5_000)
            engine.onFix(speedMps = 15f, altitudeM = Float.NaN, nowMs = t)
        }
        assertTrue(
            "вирок помер, хоч небо відповідало: ${engine.profile.value.state}",
            engine.profile.value.state.isVehicleVerdict(),
        )
    }

    @Test
    fun `тиша неба не чіпає пішохідних станів`() {
        // WALK/RUN/STILL спираються на кроки й вібрацію, тобто на IMU, який
        // іде далі й без супутників. Забирати їх разом із транспортом було б
        // зайвою втратою.
        val engine = KinematicsEngine()
        var t = 1_000L
        repeat(30) {
            engine.onFix(speedMps = 0f, altitudeM = Float.NaN, nowMs = t)
            t = idle(engine, t, 1_000)
        }
        val before = engine.profile.value.state
        assertTrue(
            "умова тесту: нерухоме небо мусить дати не-транспортний стан, а дало $before",
            before != KinematicsEngine.MotionState.DRIVE &&
                before != KinematicsEngine.MotionState.TRAIN,
        )
        t = idle(engine, t, ttl + 5_000)
        assertEquals("пішохідний стан не мав протухати", before, engine.profile.value.state)
    }
}
