package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.nav.GuidanceProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ведення: що банер має право показати на цьому кадрі.
 *
 * Головне тут — off-route. Раніше банер поверх стану «поза маршрутом» далі
 * малював маневр і відстань СТАРОГО маршруту звичайною фарбою, а сам стан
 * діставав рядок 10sp під ними. Це не питання кегля: числа, яких дані вже не
 * підтверджують, не мають права стояти на екрані взагалі.
 */
class GuidanceBannerFaceTest {

    private fun progress(
        offRoute: Boolean = false,
        distanceToManeuverM: Double = 250.0,
        maneuverType: String? = "turn",
        roadName: String? = "Хрещатик",
    ) = GuidanceProgress(
        snappedLat = 50.45,
        snappedLon = 30.52,
        bearingDeg = 90.0,
        maneuverType = maneuverType,
        maneuverModifier = "right",
        roadName = roadName,
        distanceToManeuverM = distanceToManeuverM,
        remainingDistanceM = 42_000.0,
        remainingDurationS = 2_940.0,
        speedKmh = 62,
        limitKmh = 50,
        offRoute = offRoute,
    )

    @Test
    fun `no fix is the waiting face, never an empty maneuver`() {
        assertEquals(BannerFace.Waiting, bannerFace(null))
    }

    @Test
    fun `off route erases the stale maneuver entirely`() {
        val face = bannerFace(progress(offRoute = true))
        assertEquals(BannerFace.OffRoute, face)
        // Жодного шляху від цього стану до числа старого маршруту.
        assertFalse(face is BannerFace.Maneuver)
    }

    @Test
    fun `on route the face carries exactly the live maneuver`() {
        val face = bannerFace(progress(distanceToManeuverM = 250.0)) as BannerFace.Maneuver
        assertEquals(250.0, face.distanceM, 0.0)
        assertEquals("turn", face.type)
        assertEquals("Хрещатик", face.roadName)
    }

    /**
     * Власник надіслав кадр, де одночасно стоять два наші написи:
     * «Поза маршрутом — перепрокладаю…» і «Глушіння GPS — тримаю останню певну
     * позицію». Перший каже, що ми знаємо, де людина, і вона не там; другий — що
     * не знаємо. Обидва наші, в одну мить.
     *
     * Суперечність не косметична: «поза маршрутом» ВИВЕДЕНО з позиції. Без
     * довіри до неї висновок не наш — ми знаємо лише, що не знаємо, де людина.
     */
    @Test
    fun `недовірена позиція знімає право казати «поза маршрутом»`() {
        assertEquals(BannerFace.Waiting, bannerFace(progress(offRoute = true), positionTrusted = false))
    }

    /** Але сам зʼїзд нікуди не дівається: віримо позиції — кажемо прямо. */
    @Test
    fun `довірена позиція лишає вирок про зʼїзд недоторканим`() {
        assertEquals(BannerFace.OffRoute, bannerFace(progress(offRoute = true), positionTrusted = true))
    }

    /** Недовіра гасить ЛИШЕ зʼїзд. Живий маневр — не висновок про відхилення. */
    @Test
    fun `недовіра не гасить маневр на маршруті`() {
        val face = bannerFace(progress(distanceToManeuverM = 250.0), positionTrusted = false)
        assertTrue(face is BannerFace.Maneuver)
    }

    @Test
    fun `eta is an estimate only while off route`() {
        assertTrue(etaIsEstimate(progress(offRoute = true)))
        assertFalse(etaIsEstimate(progress(offRoute = false)))
        assertFalse(etaIsEstimate(null))
    }

    @Test
    fun `stopping the drive costs a hold, not a tap`() {
        // Кишеня і вібрація на кріпленні вбивали живу навігацію миттєвим тапом.
        assertTrue(STOP_HOLD_MS >= 800)
    }

    @Test
    fun `the countdown keeps its buckets`() {
        assertEquals("now" to "", distanceParts(0.0))
        assertEquals("250" to "m", distanceParts(248.0))
        assertEquals("1,2" to "km", distanceParts(1_240.0))
        assertEquals("12" to "km", distanceParts(12_400.0))
    }
}
