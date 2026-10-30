package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * am_crash, A52, двічі за три хвилини (03:21:56 і 03:24:08):
 * `IllegalArgumentException: Key "place-49.8046309-18.2342778" was already
 * used` — два геокодовані рядки з однаковими координатами діставали
 * однаковий ключ LazyColumn, і `SubcomposeLayout` кидав виняток.
 */
class MapSearchResultsKeyTest {

    private fun result(name: String, lat: Double, lon: Double) = NominatimResult(
        lat = lat.toString(),
        lon = lon.toString(),
        displayName = name,
    )

    @Test
    fun `два різні місця на однакових координатах не дають однакового ключа`() {
        // Точні координати з логу падіння: Братислава двома рядками поспіль,
        // а не той самий запис двічі — двічі поспіль ІНШЕ місце.
        val results = listOf(
            result("Bratislava", 49.8046309, 18.2342778),
            result("Bratislava - Staré Mesto", 49.8046309, 18.2342778),
        )

        val deduped = dedupePlaceResults(results)
        val keys = deduped.mapIndexed(::placeResultKey)

        assertEquals("різні назви на тому самому місці не мали зникнути", 2, deduped.size)
        assertEquals("ключ мусить бути унікальним за побудовою", keys.size, keys.toSet().size)
    }

    @Test
    fun `справжній дубль ІДЕНТИЧНОГО рядка не показується двічі`() {
        val results = listOf(
            result("Bratislava", 49.8046309, 18.2342778),
            result("Bratislava", 49.8046309, 18.2342778),
        )

        val deduped = dedupePlaceResults(results)

        assertEquals("той самий рядок на тому самому місці показаний людині вдруге без причини", 1, deduped.size)
    }

    @Test
    fun `три однакові координати з трьома різними назвами дають три унікальні ключі`() {
        val results = listOf(
            result("A", 50.45, 30.52),
            result("B", 50.45, 30.52),
            result("C", 50.45, 30.52),
        )

        val keys = dedupePlaceResults(results).mapIndexed(::placeResultKey)

        assertEquals(3, keys.size)
        assertTrue("ключ, побудований лише з координат, тут би зіткнувся втретє", keys.toSet().size == 3)
    }

    @Test
    fun `та сама площа двома точками за кілька метрів дає один рядок`() {
        val results = listOf(
            result("Freedom Square, Brno, South Moravian Region, Czechia", 49.1951024, 16.6080720),
            result("Freedom Square, Brno, South Moravian Region, Czechia", 49.1951861, 16.6079244),
            result("Freedom Square, Brno, South Moravian Region, Czechia", 49.1990000, 16.6080720),
        )

        val deduped = dedupePlaceResults(results)

        assertEquals("дві точки однієї площі за ~15 м — один рядок, третя за 430 м лишається", 2, deduped.size)
    }
}
