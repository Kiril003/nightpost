package local.phantom.companion.feature.car

import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спокійний екран машини та чесна година прибуття.
 *
 * Три поразки, які тут сторожаться, — не гіпотетичні, їх було видно в коді:
 *
 *  1. У стані спокою шаблон ставив `RoutingInfo.setLoading(true)` — спінер,
 *     що стверджував завантаження, коли не вантажилось нічого. І це не
 *     крайній випадок: `PhantomCarAppService.onCreateScreen` створює цей
 *     екран для приладової панелі ЗАВЖДИ, тож спінер був її типовим станом.
 *  2. Смуга дій будувалась до розбору стану й у спокої несла «Зупинити» —
 *     кнопку, якій нічого зупиняти; вона лише закривала екран.
 *  3. Година прибуття малювалась точним часом навіть тоді, коли весь
 *     маршрут пораховано однією вгаданою швидкістю (basis `ASSUMED`) — а це
 *     стан КОЖНОГО спеченого пакета доріг, що зараз лежить на пристроях.
 *
 * Випробовуються вузли, які екран справді кличе, а не їхні копії: сам
 * `Screen` потребує CarContext і в звичайному тесті не будується, тож
 * рішення винесені у верхньорівневі функції того ж файлу.
 */
class CalmScreenTest {

    private fun progress(
        speedBasis: String = "",
        remainingDurationS: Double = 600.0,
        remainingDistanceM: Double = 5_000.0,
    ) = GuidanceProgress(
        snappedLat = 50.4501,
        snappedLon = 30.5234,
        bearingDeg = 0.0,
        maneuverType = "turn",
        maneuverModifier = "left",
        roadName = "вул. Хрещатик",
        distanceToManeuverM = 240.0,
        remainingDistanceM = remainingDistanceM,
        remainingDurationS = remainingDurationS,
        speedKmh = 50,
        limitKmh = null,
        speedBasis = speedBasis,
        offRoute = false,
    )

    // ── картка шаблону ───────────────────────────────────────────────

    @Test
    fun `у спокої картки немає взагалі — жодного спінера`() {
        assertNull(
            "спокій не має що сказати: Idle приходить і коли маршруту не " +
                "просили, і коли він не запустився",
            navigationInfoFor(GuidanceState.Idle),
        )
    }

    @Test
    fun `маршрут, що справді рахується, спінер зберігає`() {
        val info = navigationInfoFor(GuidanceState.Guiding(null))
        assertTrue("тут спінер чесний — маршрут будується", (info as RoutingInfo).isLoading)
    }

    @Test
    fun `прибуття лишається карткою`() {
        assertNotNull(navigationInfoFor(GuidanceState.Arrived))
    }

    // ── обовʼязкова смуга дій ────────────────────────────────────────

    @Test
    fun `у спокої кнопка кличе вперед, а не зупиняє`() {
        assertEquals(
            CarActionChoice.WHERE_TO,
            carActionChoice(GuidanceState.Idle, isCluster = false, hasPorts = true),
        )
    }

    @Test
    fun `на приладовій панелі кнопки нема кому натиснути`() {
        // Кермо не має дотику, а сесія кластера має право лише на
        // NavigationTemplate — сітку категорій звідти не відкрити.
        assertEquals(
            CarActionChoice.APP_ICON,
            carActionChoice(GuidanceState.Idle, isCluster = true, hasPorts = true),
        )
    }

    @Test
    fun `без портів вести нікуди`() {
        assertEquals(
            CarActionChoice.APP_ICON,
            carActionChoice(GuidanceState.Idle, isCluster = false, hasPorts = false),
        )
    }

    @Test
    fun `під час ведення зупинка лишається на місці`() {
        assertEquals(
            CarActionChoice.STOP,
            carActionChoice(GuidanceState.Guiding(null), isCluster = false, hasPorts = true),
        )
        assertEquals(
            CarActionChoice.STOP,
            carActionChoice(GuidanceState.Arrived, isCluster = false, hasPorts = true),
        )
    }

    // ── година прибуття ──────────────────────────────────────────────

    @Test
    fun `вгадана швидкість не дає права на годинник`() {
        val est = travelEstimateOf(progress(speedBasis = "ASSUMED"), nowMs = 1_000_000L)
        assertEquals(
            "маршрут, порахований однією пласкою вгадкою, не сміє показувати " +
                "точну годину прибуття",
            TravelEstimate.REMAINING_TIME_UNKNOWN,
            est.remainingTimeSeconds,
        )
    }

    @Test
    fun `але відстань лишається — вона виміряна`() {
        val est = travelEstimateOf(progress(speedBasis = "ASSUMED"), nowMs = 1_000_000L)
        assertEquals(5.0, est.remainingDistance!!.displayDistance, 0.001)
    }

    @Test
    fun `знак на дорозі дає годинник`() {
        val est = travelEstimateOf(progress(speedBasis = "POSTED"), nowMs = 1_000_000L)
        assertEquals(600L, est.remainingTimeSeconds)
    }

    @Test
    fun `мережевий рушій теж дає — порожня основа це не вгадка`() {
        // OSRM рахує по справжніх швидкостях свого графа й просто не звітує
        // про основу. Плутати «не сказав як» із «вгадав» означало б викинути
        // єдину чесну оцінку часу, яку ми взагалі маємо.
        val est = travelEstimateOf(progress(speedBasis = ""), nowMs = 1_000_000L)
        assertEquals(600L, est.remainingTimeSeconds)
    }

    @Test
    fun `при вгадці час прибуття не тікає вперед`() {
        // Хазяїн отримує обовʼязковий час прибуття в конструкторі. При вгадці
        // туди йде «зараз», а не «зараз плюс вигадані хвилини»: якщо якийсь
        // хазяїн усе-таки намалює годину попри REMAINING_TIME_UNKNOWN, хай це
        // буде видима безглуздість, а не правдоподібна брехня.
        val now = 1_000_000L
        val est = travelEstimateOf(progress(speedBasis = "ASSUMED"), nowMs = now)
        val real = travelEstimateOf(progress(speedBasis = "POSTED"), nowMs = now)
        assertFalse(
            est.arrivalTimeAtDestination!!.timeSinceEpochMillis ==
                real.arrivalTimeAtDestination!!.timeSinceEpochMillis,
        )
    }
}
