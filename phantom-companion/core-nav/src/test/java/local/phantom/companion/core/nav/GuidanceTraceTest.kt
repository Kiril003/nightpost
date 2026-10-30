package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Скарги власника №4 («притягує на ту дорогу, де маршрут») і №5 («каже
 * "поверніться" і показує, що я їду в той бік») були непідсудні не тому, що
 * навігація мовчить, а тому, що в записі не було ЖОДНОГО числа про маршрут.
 *
 * Ці тести стережуть саме те, що тепер записується: бічний зсув, серію сходу
 * і — головне — ДВА напрямки поруч. Поки в рядку лежить лише напрямок дороги,
 * на яку зшили, твердження «показує не туди» неможливо ні довести, ні зняти.
 */
class GuidanceTraceTest {

    /** Маршрут строго на північ уздовж 30.0°: 40 точок по ~111 м. */
    private val points = (0 until 40).map { (50.0 + it * 0.001) to 30.0 }

    private val route = RouteVariant(
        points = points,
        distanceMeters = 4300.0,
        durationSeconds = 300.0,
        steps = listOf(
            RouteStep("depart", null, "Прямо", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("arrive", null, "", 0.0, 0.0, points[39].first, points[39].second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    private val baseMs = 1_000_000_000L

    /** 25 м на схід на широті 50° ≈ 0.000349° довготи. */
    private val eastOffsetDeg = 0.000349

    private fun line(
        session: GuidanceSession,
        lat: Double,
        lon: Double,
        atMs: Long,
        motionBearingDeg: Float?,
        speedMps: Float = 14f,
    ): GuidanceTraceLine {
        val update = session.onFix(
            lat, lon, speedMps, atMs, trusted = true, accuracyM = 5f,
            motionBearingDeg = motionBearingDeg,
        )
        return guidanceTraceLine(
            update = update,
            lat = lat,
            lon = lon,
            speedMps = speedMps,
            trusted = true,
            accuracyM = 5f,
            gait = NavGait.UNKNOWN,
            motionBearingDeg = motionBearingDeg,
        )
    }

    @Test
    fun `слід несе обидва напрямки, і розбіжність між ними видно`() {
        val session = GuidanceSession.of(route) { null }
        assertNotNull("сесія мусить зібратись із синтетичного маршруту", session)
        session!!

        // Їдемо по маршруту на північ — обидва напрямки збігаються.
        val onRoute = line(session, 50.005, 30.0, baseMs, motionBearingDeg = 0f)
        assertTrue("на маршруті зсув мусить бути малим: ${onRoute.offMeters}", onRoute.offMeters < 5.0)

        // А тепер — сусідня дорога за 25 м, і рух НА СХІД, поперек маршруту.
        // Саме тут стрілка показувала «на північ», бо брала напрямок дороги.
        val parallel = line(
            session,
            50.006,
            30.0 + eastOffsetDeg,
            baseMs + 1_000,
            motionBearingDeg = 90f,
        )

        assertEquals(0.0, parallel.routeBearingDeg, 2.0)
        assertEquals(90.0, (parallel.motionBearingDeg ?: 0f).toDouble(), 0.001)

        val divergence = abs(parallel.routeBearingDeg - (parallel.motionBearingDeg ?: 0f).toDouble())
        assertTrue(
            "розбіжність напрямків мусить бути в рядку, а не в здогаді: $divergence",
            divergence > 60.0,
        )
    }

    @Test
    fun `бічний зсув від сусідньої дороги записано числом`() {
        val session = GuidanceSession.of(route) { null }
        assertNotNull("сесія мусить зібратись із синтетичного маршруту", session)
        session!!
        line(session, 50.004, 30.0, baseMs, motionBearingDeg = 0f)

        val parallel = line(
            session,
            50.005,
            30.0 + eastOffsetDeg,
            baseMs + 1_000,
            motionBearingDeg = 0f,
        )

        // ~25 м: саме та відстань, на якій сусідня вулиця виглядає як «наша».
        assertEquals(25.0, parallel.offMeters, 6.0)
        // І це головне: 25 м НЕ вважаються сходом з маршруту — тобто людину
        // тримають на дорозі, якою вона не їде. Тест не судить поріг, він
        // фіксує теперішню поведінку числом, щоб її було з чим порівняти.
        assertTrue("за теперішнім порогом 25 м — ще «на маршруті»", !parallel.offRoute)
        assertEquals(0L, parallel.offStreak.toLong())
    }

    @Test
    fun `серія сходу росте лише коли ми справді рухаємось`() {
        val session = GuidanceSession.of(route) { null }
        assertNotNull("сесія мусить зібратись із синтетичного маршруту", session)
        session!!
        line(session, 50.004, 30.0, baseMs, motionBearingDeg = 0f)

        // 300 м убік — це вже точно не наша дорога.
        val farLon = 30.0 + eastOffsetDeg * 12
        val moving = line(session, 50.005, farLon, baseMs + 1_000, motionBearingDeg = 90f)
        assertTrue("300 м убік мусить читатись як схід: ${moving.offMeters}", moving.offRoute)
        assertTrue("серія мусить рости під час руху", moving.offStreak >= 1)

        // А той самий зсув, але пристрій стоїть: серія мусить обнулитись —
        // розсіювання на місці не доводить, що людина покинула маршрут.
        val standing = line(session, 50.005, farLon, baseMs + 2_000, motionBearingDeg = null, speedMps = 0f)
        assertEquals("той, хто стоїть, не сходить з маршруту", 0L, standing.offStreak.toLong())
    }

    @Test
    fun `їзда поперек коридору читається як схід, хоч лінія поруч`() {
        val session = GuidanceSession.of(route) { null }
        assertNotNull("сесія мусить зібратись", session)
        session!!
        line(session, 50.004, 30.0, baseMs, motionBearingDeg = 0f)

        // За 25 м від коридору — ближче за будь-який розумний поріг відстані.
        // Але рух іде на схід, поперек маршруту: це сусідня вулиця.
        val across = line(
            session,
            50.005,
            30.0 + eastOffsetDeg,
            baseMs + 1_000,
            motionBearingDeg = 90f,
        )
        assertTrue(
            "рух під 90° до коридору мусить читатись як схід: зсув ${across.offMeters} м",
            across.offRoute,
        )
    }

    @Test
    fun `без курсу поведінка лишається такою, як була`() {
        val session = GuidanceSession.of(route) { null }
        assertNotNull("сесія мусить зібратись", session)
        session!!
        line(session, 50.004, 30.0, baseMs, motionBearingDeg = null)

        // Той самий фікс за 25 м, але напрямок невідомий: жодного нового
        // приводу оголосити схід ми не вигадуємо.
        val quiet = line(session, 50.005, 30.0 + eastOffsetDeg, baseMs + 1_000, motionBearingDeg = null)
        assertTrue("невідомий курс не може бути підставою", !quiet.offRoute)
    }
}
