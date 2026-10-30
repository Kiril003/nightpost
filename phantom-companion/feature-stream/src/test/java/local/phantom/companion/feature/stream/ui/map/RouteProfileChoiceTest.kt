package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Вибір режиму за замовчуванням — рішення, яке користувач бачить щодня.
 *
 * Два питання, на які відповідає цей тест замість пристрою:
 *  • чи перестав застосунок пропонувати «пішки» на будь-яку відстань;
 *  • чи не пропонує він пішу прогулянку людині, яка зараз за кермом.
 */
class RouteProfileChoiceTest {

    private fun moving(
        state: KinematicsEngine.MotionState,
        confidence: Float = 0.9f,
    ) = KinematicsEngine.MotionProfile(state = state, confidence = confidence)

    // ── відстань ────────────────────────────────────────────────────────

    @Test
    fun `близька ціль — пішки`() {
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(300.0, null))
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(1_200.0, null))
    }

    @Test
    fun `півтора кілометра вже не транзит — саме та скарга з екрана`() {
        // 1,5 км «транзитом» за 17 хв: очікування на зупинці зʼїдає весь виграш.
        // Поріг стоїть на 1,2 км саме тому, і цей тест його стереже.
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(1_500.0, null))
        // …але пішки лишається доти, доки прогулянка розумна.
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(1_199.0, null))
    }

    @Test
    fun `далека ціль — транзит, авто без свідчення руху не припускається`() {
        // Вирок власника 29.08: «якщо транспорт — у людини немає авто».
        // Авто пропонується лише за детектором DRIVE або рукою через чип;
        // цей тест стереже, щоб відстань сама ніколи не обирала авто.
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(4_000.0, null))
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(6_000.0, null))
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(6_001.0, null))
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(40_000.0, null))
    }

    @Test
    fun `безглузда відстань не ламає вибір`() {
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(0.0, null))
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(-5.0, null))
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(Double.NaN, null))
    }

    // ── свідчення руху сильніше за відстань ─────────────────────────────

    @Test
    fun `за кермом — авто, навіть якщо йти було б близько`() {
        // Людина не покине машину через те, що застосунок вирішив «пішки».
        assertEquals(
            RouteProfile.CAR,
            RouteProfileChoice.forTrip(400.0, moving(KinematicsEngine.MotionState.DRIVE)),
        )
    }

    @Test
    fun `на велосипеді — вело`() {
        assertEquals(
            RouteProfile.BIKE,
            RouteProfileChoice.forTrip(3_000.0, moving(KinematicsEngine.MotionState.CYCLE)),
        )
    }

    @Test
    fun `у потязі — громадський транспорт`() {
        assertEquals(
            RouteProfile.TRANSIT,
            RouteProfileChoice.forTrip(500.0, moving(KinematicsEngine.MotionState.TRAIN)),
        )
    }

    @Test
    fun `невпевнений вердикт руху ігнорується — рахуємо відстань`() {
        // Детектор тримає DRIVE невпевненим, доки рух не стане стійким. Помилково
        // запропонувати авто пішоходу гірше, ніж просто порахувати відстань.
        assertEquals(
            RouteProfile.FOOT,
            RouteProfileChoice.forTrip(400.0, moving(KinematicsEngine.MotionState.DRIVE, confidence = 0.4f)),
        )
    }

    // ── сирена ──────────────────────────────────────────────────────────

    @Test
    fun `сирена перебиває далеку відстань — пішки, не транзит`() {
        // Сама скарга з екрана: 27,2 км під тривогою обирали транзит, а
        // транзит перший ламається без мережі («маршрутизатор недоступний»).
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(27_200.0, null, siren = true))
        assertEquals(RouteProfile.FOOT, RouteProfileChoice.forTrip(4_000.0, null, siren = true))
    }

    @Test
    fun `сирена не жене з машини — свідчення руху лишається сильнішим`() {
        assertEquals(
            RouteProfile.CAR,
            RouteProfileChoice.forTrip(4_000.0, moving(KinematicsEngine.MotionState.DRIVE), siren = true),
        )
    }

    @Test
    fun `без сирени поведінка не змінилась`() {
        assertEquals(RouteProfile.TRANSIT, RouteProfileChoice.forTrip(27_200.0, null))
    }

    @Test
    fun `пішохідні та невідомі стани не перебивають відстань`() {
        for (s in listOf(
            KinematicsEngine.MotionState.WALK,
            KinematicsEngine.MotionState.RUN,
            KinematicsEngine.MotionState.STILL,
            KinematicsEngine.MotionState.UNKNOWN,
        )) {
            assertEquals("стан $s", RouteProfile.TRANSIT, RouteProfileChoice.forTrip(3_000.0, moving(s)))
        }
    }
}
