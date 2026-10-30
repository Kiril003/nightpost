package local.phantom.companion.feature.stream.ui.map

import java.util.Calendar
import local.phantom.companion.core.data.law.CurfewRules
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteIntelReport
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смуга маршруту: та її частина, що є чистою математикою.
 *
 * Слово характеру варіанта — єдине місце, де UI має право назвати маршрут
 * прикметником, і воно тримається лише на порівнянні чисел. Тест стоїть саме
 * тут: «безпечніший» не має під собою виміру взагалі, і жоден шлях коду не
 * повинен мати змоги його вимовити.
 */
class RouteBandMathTest {

    private fun variant(
        seconds: Double,
        meters: Double,
        basis: String = "",
        profile: RouteProfile = RouteProfile.CAR,
    ) = RouteVariant(
        points = listOf(50.0 to 30.0, 50.1 to 30.1),
        distanceMeters = meters,
        durationSeconds = seconds,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = profile,
        speedBasis = basis,
    )

    private fun intel(lit: Double?) = RoutePlanner.Intel.Ready(
        RouteIntelReport(
            classShares = listOf("асфальт" to 0.82),
            surfaceShares = emptyList(),
            litFraction = lit,
            maxspeedMax = null,
            coverage = 0.9,
            cameras = emptyList(),
            elevations = null,
            ascentMeters = null,
            descentMeters = null,
        ),
    )

    private fun option(
        seconds: Double,
        meters: Double,
        lit: Double? = null,
        basis: String = "",
    ) = RoutePlanner.Option(
        variant = variant(seconds, meters, basis),
        intel = if (lit == null) RoutePlanner.Intel.Unavailable else intel(lit),
    )

    private fun info(
        profile: RouteProfile = RouteProfile.CAR,
        viaCount: Int = 0,
        itinerary: Itinerary? = null,
    ) = MapViewModel.RouteInfo(
        distanceMeters = 42_000.0,
        durationSeconds = 2_940.0,
        profile = profile,
        destLat = 50.1,
        destLon = 30.1,
        destName = "Кукільня",
        viaCount = viaCount,
        itinerary = itinerary,
    )

    // ── слово характеру ──────────────────────────────────────────────────

    @Test
    fun `single option carries no character word`() {
        assertEquals(listOf<VariantMark?>(null), variantMarks(listOf(option(1800.0, 20_000.0))))
    }

    @Test
    fun `fastest and shortest land on different cells`() {
        val marks = variantMarks(
            listOf(
                option(1800.0, 24_000.0),
                option(2100.0, 19_000.0),
                option(2400.0, 26_000.0),
            ),
        )
        assertEquals(VariantMark.Fastest, marks[0])
        assertEquals(VariantMark.Shortest, marks[1])
        assertNull(marks[2])
    }

    @Test
    fun `one variant that wins both axes is called only fastest`() {
        val marks = variantMarks(listOf(option(1800.0, 19_000.0), option(2100.0, 24_000.0)))
        assertEquals(VariantMark.Fastest, marks[0])
        assertNull(marks[1])
    }

    @Test
    fun `a shared first place is nobody's character`() {
        val marks = variantMarks(listOf(option(1800.0, 20_000.0), option(1800.0, 20_000.0)))
        assertTrue(marks.all { it == null })
    }

    @Test
    fun `lit share speaks only where no comparison word already does`() {
        val marks = variantMarks(
            listOf(
                option(1800.0, 24_000.0),
                option(2100.0, 19_000.0),
                option(2400.0, 26_000.0, lit = 0.64),
            ),
        )
        assertEquals(VariantMark.Lit(64), marks[2])
    }

    @Test
    fun `no character word exists beyond the three the data supports`() {
        // Вичерпний `when` — і є запобіжник: четвертий підтип не збереться,
        // доки хтось не прийде сюди й не напише його вголос. «Безпечніший»
        // порівнювального виміру не має, тож писати його буде нічим.
        val words = listOf<VariantMark>(
            VariantMark.Fastest,
            VariantMark.Shortest,
            VariantMark.Lit(50),
        ).map { mark ->
            when (mark) {
                VariantMark.Fastest -> "fastest"
                VariantMark.Shortest -> "shortest"
                is VariantMark.Lit -> "lit"
            }
        }
        assertEquals(listOf("fastest", "shortest", "lit"), words)
    }

    // ── застереження ─────────────────────────────────────────────────────

    // Тут стояли чотири перевірки на `RouteCaution.OfflineClassless` — стан,
    // якого більше немає. Клас доріг перестав бути застереженням 18.09 за
    // вироком власника; що він нікуди не подівся, а переїхав у деталі
    // маршруту, стереже [RouteBarFormTest]. Тутешні перевірки лишились рівно
    // в тій частині, що є чистою математикою: у ПОРЯДКУ застережень.

    @Test
    fun `no basis of speed ever becomes a caution`() {
        listOf("ASSUMED", "CLASS", "POSTED", "").forEach { basis ->
            val cautions = routeCautions(
                info(),
                option(2940.0, 42_000.0, basis = basis),
                deadlineLine = null,
                worstRegister = null,
            )
            assertTrue("«$basis» повернуло застереження: $cautions", cautions.isEmpty())
        }
    }

    private val NOON_MS: Long = 1724155200000L // 12:00 UTC

    @Test
    fun `caution order runs deadline then register then modelled`() {
        val cautions = routeCautions(
            info = info(itinerary = itinerary(modelled = true)),
            chosen = option(2940.0, 42_000.0, basis = "ASSUMED"),
            deadlineLine = "виїзд до 17:40",
            worstRegister = GroundRegister.REMEMBERED,
            nowEpochMs = NOON_MS,
        )
        assertEquals(
            listOf(
                RouteCaution.Deadline("виїзд до 17:40"),
                RouteCaution.Register(GroundRegister.REMEMBERED),
                RouteCaution.Modelled,
            ),
            cautions,
        )
    }

    @Test
    fun `only old ground speaks - a guessed road is the norm, not a caution`() {
        GroundRegister.entries.filter { it != GroundRegister.REMEMBERED }.forEach { r ->
            val cautions = routeCautions(info(), option(2940.0, 42_000.0), deadlineLine = null, worstRegister = r)
            assertTrue("$r зайняв єдине місце застереження: $cautions", cautions.isEmpty())
        }
    }

    @Test
    fun `a via point never outranks a caution that changes the decision`() {
        val cautions = routeCautions(
            info = info(viaCount = 1),
            chosen = option(2940.0, 42_000.0, basis = "ASSUMED"),
            deadlineLine = "виїзд до 17:40",
            worstRegister = null,
            nowEpochMs = NOON_MS,
        )
        assertEquals(RouteCaution.Deadline("виїзд до 17:40"), cautions.first())
        assertEquals(RouteCaution.NoVariantsVia, cautions.last())
    }

    // Law 3 (D8) — the curfew line follows the ARRIVAL point into a region
    // that has a verified rule, or it is never spoken.

    /** 23:30 Kyiv: with a 2940 s route the arrival lands ~00:19, inside the window. */
    private fun nightBeforeArrivalMs(): Long =
        Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kyiv")).apply {
            set(2026, Calendar.AUGUST, 22, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `an arrival inside Ukraine during the window raises the caution`() {
        val cautions = routeCautions(
            info = info(),
            chosen = option(2940.0, 42_000.0),
            deadlineLine = null,
            worstRegister = null,
            nowEpochMs = nightBeforeArrivalMs(),
            regionAt = { _, _ -> CurfewRules.UKRAINE },
        )
        val curfew = cautions.filterIsInstance<RouteCaution.CurfewCollision>().firstOrNull()
        org.junit.Assert.assertNotNull(curfew)
        assertTrue(curfew!!.match.atHhMm.startsWith("00:"))
        assertEquals("00:00–05:00", curfew.match.windowHhMm)
        assertTrue(curfew.match.rule.source.isNotBlank())
    }

    @Test
    fun `the same drive in Berlin says nothing`() {
        val cautions = routeCautions(
            info = info(),
            chosen = option(2940.0, 42_000.0),
            deadlineLine = null,
            worstRegister = null,
            nowEpochMs = nightBeforeArrivalMs(),
            regionAt = { _, _ -> "DE" },
        )
        assertTrue(cautions.none { it is RouteCaution.CurfewCollision })
    }

    @Test
    fun `with no region resolver at all the caution never appears`() {
        val cautions = routeCautions(
            info = info(),
            chosen = option(2940.0, 42_000.0),
            deadlineLine = null,
            worstRegister = null,
            nowEpochMs = nightBeforeArrivalMs(),
        )
        assertTrue(cautions.none { it is RouteCaution.CurfewCollision })
    }

    @Test
    fun `a daytime arrival inside Ukraine says nothing`() {
        val noon = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Kyiv")).apply {
            set(2026, Calendar.AUGUST, 22, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cautions = routeCautions(
            info = info(),
            chosen = option(2940.0, 42_000.0),
            deadlineLine = null,
            worstRegister = null,
            nowEpochMs = noon,
            regionAt = { _, _ -> CurfewRules.UKRAINE },
        )
        assertTrue(cautions.none { it is RouteCaution.CurfewCollision })
    }

    // ── транзит ──────────────────────────────────────────────────────────

    private fun itinerary(modelled: Boolean = false, transfers: Int = 2) = Itinerary(
        legs = buildList {
            add(
                Itinerary.Leg.Access(
                    mode = AccessMode.WALK,
                    fromLat = 50.0, fromLon = 30.0, toLat = 50.01, toLon = 30.01,
                    toStopName = "Театральна",
                    seconds = 300,
                    meters = 400,
                ),
            )
            repeat(transfers) {
                add(
                    Itinerary.Leg.Transfer(
                        fromStopName = "А",
                        toStopName = "Б",
                        seconds = 180,
                    ),
                )
            }
            add(
                Itinerary.Leg.Ride(
                    routeLabel = "M1",
                    mode = TransitMode.METRO,
                    colour = -1,
                    fromStopName = "Театральна",
                    toStopName = "Хрещатик",
                    fromLat = 50.01, fromLon = 30.01,
                    toLat = 50.02, toLon = 30.02,
                    departSec = 100,
                    arriveSec = 400,
                    intermediateStops = 1,
                    timesAreModelled = modelled,
                ),
            )
        },
        departSec = 0,
        arriveSec = 3_000,
        transfers = transfers,
        hasModelledTimes = modelled,
    )

    @Test
    fun `transfers are counted from the legs the thread actually draws`() {
        assertEquals(2, transferCount(itinerary(transfers = 2)))
        assertEquals(0, transferCount(itinerary(transfers = 0)))
        assertEquals(0, transferCount(null))
    }

    private fun ride(
        modelled: Boolean,
        stops: Int = 0,
        departSec: Int = 8 * 3600 + 5 * 60,
        arriveSec: Int = 8 * 3600 + 21 * 60,
    ) = Itinerary.Leg.Ride(
        routeLabel = "M5",
        mode = TransitMode.TRAM,
        colour = -1,
        fromStopName = "Театральна",
        toStopName = "Хрещатик",
        fromLat = 50.0, fromLon = 30.0,
        toLat = 50.1, toLon = 30.1,
        departSec = departSec,
        arriveSec = arriveSec,
        intermediateStops = stops,
        timesAreModelled = modelled,
    )

    /**
     * «≈» — не прикраса. Модельована хвилина без нього виглядає рівно як
     * розкладна, і людина не має способу відрізнити вимір від здогаду.
     */
    @Test
    fun `модельований час несе знак приблизності на обох хвилинах`() {
        val line = rideTimesLine(ride(modelled = true)) { "$it зуп." }
        assertEquals("Театральна ≈8:05 → Хрещатик ≈8:21", line)
    }

    @Test
    fun `розкладний час стоїть без знака`() {
        val line = rideTimesLine(ride(modelled = false)) { "$it зуп." }
        assertEquals("Театральна 8:05 → Хрещатик 8:21", line)
    }

    /** «0 зупинок» — теж твердження, і воно вигадане. Рядка просто нема. */
    @Test
    fun `нуль проміжних зупинок не малюється`() {
        assertTrue(rideTimesLine(ride(modelled = false, stops = 0)) { "$it зуп." }.none { it == '·' })
        assertEquals(
            "Театральна 8:05 → Хрещатик 8:21 · 4 зуп.",
            rideTimesLine(ride(modelled = false, stops = 4)) { "$it зуп." },
        )
    }

    @Test
    fun `corridor barriers in intel report produce a barrier caution`() {
        val optionWithBarriers = RoutePlanner.Option(
            variant = variant(1800.0, 20_000.0),
            intel = RoutePlanner.Intel.Ready(
                RouteIntelReport(
                    classShares = emptyList(),
                    surfaceShares = emptyList(),
                    litFraction = null,
                    maxspeedMax = null,
                    coverage = 1.0,
                    cameras = emptyList(),
                    barriers = listOf(
                        local.phantom.companion.core.net.map.BarrierSighting(50.4, 30.5, "checkpoint", null),
                        local.phantom.companion.core.net.map.BarrierSighting(50.41, 30.51, "lift_gate", null),
                    ),
                    elevations = null,
                    ascentMeters = null,
                    descentMeters = null,
                ),
            ),
        )
        val cautions = routeCautions(info(), optionWithBarriers, null, null)
        val barrierCaution = cautions.filterIsInstance<RouteCaution.Barrier>().firstOrNull()
        org.junit.Assert.assertNotNull(barrierCaution)
        assertTrue(barrierCaution!!.description.contains("КПП / блокпости"))
    }

    // ── профілі ──────────────────────────────────────────────────────────

    @Test
    fun `every profile has its own word and its own glyph`() {
        val profiles = RouteProfile.entries
        assertEquals(profiles.size, profiles.map { profileLabelRes(it) }.toSet().size)
        assertEquals(profiles.size, profiles.map { profileIcon(it).name }.toSet().size)
    }
}
