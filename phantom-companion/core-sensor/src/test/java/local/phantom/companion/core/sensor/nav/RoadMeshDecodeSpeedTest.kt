package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скільки коштує ДІСТАТИ мережу з памʼяті пристрою — на противагу тому, скільки
 * коштує по ній пройти.
 *
 * `RoadGraphSpeedTest` показав, що сам A* дешевий (7,5 мс на 10 км). Але в
 * продукті перед графом стоїть `RoadMeshStore.waysIn`, і він робить дві речі:
 * запит до SQLite і РОЗБІР рядків `pts` у точки. Друге — чистий Kotlin, тож
 * міряється тут; перше — на пристрої, бо це вже залізо.
 *
 * Питання, заради якого це існує: що саме лікувати до бети. Якщо розбір коштує
 * у рази більше за пошук, то оптимізувати граф — марна праця, а кеш коридору
 * дає все.
 *
 * МЕЖА: тут немає ані диска, ані SQLite. Це нижня межа вартості `waysIn`, не
 * вона сама.
 */
class RoadMeshDecodeSpeedTest {

    /** Місто середньої щільності: 3000 доріг по 15 вузлів у коридорі. */
    private fun encodedMesh(ways: Int, pointsPerWay: Int): List<String> =
        (0 until ways).map { w ->
            val lat0 = 50.0 + (w % 60) * 0.0009
            val lon0 = 30.0 + (w / 60) * 0.0014
            RoadWayCodec.encodePoints(
                (0 until pointsPerWay).map { i ->
                    (lat0 + i * 0.00008) to (lon0 + i * 0.00011)
                },
            )
        }

    @Test
    fun `розбір мережі коштує стільки ж порядком, скільки й пошук`() {
        val encoded = encodedMesh(ways = 3_000, pointsPerWay = 15)
        val bytes = encoded.sumOf { it.length }

        // Прогрів: перший прохід платить за завантаження класів.
        encoded.forEach { RoadWayCodec.decodePoints(it) }

        val runs = 5
        val times = DoubleArray(runs)
        var decoded = 0
        for (r in 0 until runs) {
            val t0 = System.nanoTime()
            var n = 0
            for (s in encoded) n += RoadWayCodec.decodePoints(s)?.size ?: 0
            times[r] = (System.nanoTime() - t0) / 1_000_000.0
            decoded = n
        }
        times.sort()
        val median = times[runs / 2]

        // Побудова графа з уже розібраного — друга половина ціни.
        val ways = encoded.mapIndexed { i, s ->
            RoadWay(
                id = i.toLong(),
                points = RoadWayCodec.decodePoints(s)!!,
                tags = mapOf("highway" to "residential"),
            )
        }
        val gStart = System.nanoTime()
        val graph = RoadGraph(ways)
        val buildMs = (System.nanoTime() - gStart) / 1_000_000.0

        val rStart = System.nanoTime()
        LocalRouter().route(graph, 50.001, 30.001, 50.040, 30.060)
        val routeMs = (System.nanoTime() - rStart) / 1_000_000.0

        println(
            "RoadMeshDecodeSpeed: доріг=3000, точок=$decoded, рядків=${bytes / 1024} КіБ | " +
                "розбір медіана=${"%.1f".format(median)} мс | " +
                "побудова графа=${"%.1f".format(buildMs)} мс | " +
                "маршрут=${"%.1f".format(routeMs)} мс",
        )
        assertTrue("розбір 3000 доріг не має коштувати секунди: $median мс", median < 1_000.0)
    }
}
