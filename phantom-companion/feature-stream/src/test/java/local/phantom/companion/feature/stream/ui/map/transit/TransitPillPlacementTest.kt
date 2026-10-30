package local.phantom.companion.feature.stream.ui.map.transit

import local.phantom.companion.core.data.transit.TransitOrigin
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.VehicleOccupancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ГОНТЛЕТ-1 §5 — «чіпи наїжджають один на одного».
 *
 * Стеля в сорок одиниць прорідила потік, але жодна пігулка не знала про
 * сусідку: на z14 маршрути йдуть одним проспектом, і чотири номери зливались
 * у пляму. Тут — той самий прохід, що колись розкладав POI-мітки: без
 * MapLibre, бо проєкція вже зроблена, і лишилась чиста геометрія екрана.
 */
class TransitPillPlacementTest {

    // Круглі числа, щоб арифметика тесту читалась як умова, а не як підбір.
    private val space = PillSpace(
        height = 20f,
        padH = 5f,
        icon = 10f,
        itemGap = 3f,
        liveDot = 4f,
        charW = 6f,
        gap = 4f,
        topSafe = 100f,
        bottomSafe = 150f,
        rightSafe = 60f,
    )

    private val viewW = 1000f
    private val viewH = 2000f

    private fun vehicle(id: String, live: Boolean = true) = TransitVehicle(
        vehicleId = id,
        tripId = null,
        routeId = "36",
        routeLabel = "36",
        lat = 0.0,
        lon = 0.0,
        bearingDegrees = null,
        occupancy = VehicleOccupancy.UNKNOWN,
        fixEpochMs = 0L,
        origin = if (live) TransitOrigin.LIVE_FEED else TransitOrigin.SIMULATED,
        mode = TransitMode.BUS,
    )

    private fun slot(id: String, x: Float, y: Float, width: Float = 60f) =
        PillSlot(vehicle(id), x, y, width)

    private fun idsOf(kept: List<PillSlot>) = kept.map { it.vehicle.vehicleId }

    @Test
    fun `дві пігулки на одному пікселі стають однією`() {
        val kept = placePills(listOf(slot("a", 500f, 900f), slot("b", 500f, 900f)), space, viewW, viewH)
        assertEquals(listOf("a"), idsOf(kept))
    }

    @Test
    fun `ближчий до центра екрана виграє тісноту`() {
        // «b» стоїть точно в центрі, «a» — за 30 px, тобто в межах коробки.
        val center = viewH / 2f
        val kept = placePills(
            listOf(slot("a", 530f, center), slot("b", 500f, center)),
            space,
            viewW,
            viewH,
        )
        assertEquals(listOf("b"), idsOf(kept))
    }

    @Test
    fun `розведені пігулки лишаються обидві`() {
        // 60px ширини + 2×4 проміжку = 68; 100px між центрами вже вільно.
        val kept = placePills(listOf(slot("a", 400f, 900f), slot("b", 500f, 900f)), space, viewW, viewH)
        assertEquals(setOf("a", "b"), idsOf(kept).toSet())
    }

    @Test
    fun `пігулка під декою не малюється`() {
        // Права смуга — 60px. Центр 960 з половиною ширини 30 дає праву межу
        // рівно на 990 > 1000−60.
        val kept = placePills(listOf(slot("rail", 960f, 900f)), space, viewW, viewH)
        assertTrue("під непрозорою декою пігулки немає", kept.isEmpty())
    }

    @Test
    fun `пігулка під смугою відповіді не малюється`() {
        val kept = placePills(listOf(slot("low", 500f, viewH - 100f)), space, viewW, viewH)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `пігулка під пігулкою тривоги не малюється`() {
        val kept = placePills(listOf(slot("high", 500f, 40f)), space, viewW, viewH)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `розрізана краєм екрана пігулка не малюється`() {
        val kept = placePills(listOf(slot("edge", 10f, 900f)), space, viewW, viewH)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `стеля кадру тримається`() {
        // Сітка з великим кроком: усі проходять колізію, спиняє лише стеля.
        val many = (0 until 200).map { i ->
            slot("v$i", 100f + (i % 8) * 100f, 200f + (i / 8) * 40f)
        }
        assertEquals(40, placePills(many, space, viewW, viewH).size)
    }

    /** Проспект: десять одиниць одна за одною з кроком, меншим за пігулку. */
    @Test
    fun `колона одним проспектом розріджується, а не зливається`() {
        val column = (0 until 10).map { i -> slot("v$i", 500f, 300f + i * 12f) }
        val kept = placePills(column, space, viewW, viewH)
        assertTrue("щось лишилось", kept.isNotEmpty())
        assertTrue("злиття немає", kept.size < column.size)
        // Жодна пара з тих, що лишились, не перетинається по вертикалі.
        kept.sortedBy { it.y }.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "пігулки ${a.vehicle.vehicleId}/${b.vehicle.vehicleId} наїхали",
                b.y - a.y >= space.height,
            )
        }
    }

    @Test
    fun `ширина пігулки росте разом із номером маршруту`() {
        val short = space.widthOf("№7", live = true, hasBearing = false)
        val long = space.widthOf("№201К", live = true, hasBearing = false)
        assertTrue("довший номер — ширша коробка", long > short)
    }

    @Test
    fun `стрілка курсу і крапка живого фіда додають ширини`() {
        val bare = space.widthOf("№7", live = false, hasBearing = false)
        val full = space.widthOf("№7", live = true, hasBearing = true)
        assertTrue(full > bare)
    }
}
