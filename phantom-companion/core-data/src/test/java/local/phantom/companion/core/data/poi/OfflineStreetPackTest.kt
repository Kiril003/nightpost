package local.phantom.companion.core.data.poi

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bake-format contract [scripts/bake_streets.py] and the phone must agree
 * on: a header line the re-seed check reads alone, then one street per line,
 * tab-separated, malformed lines skipped rather than aborting the whole pack.
 */
class OfflineStreetPackTest {

    private fun reader(body: String): BufferedReader = StringReader(body).buffered()

    @Test
    fun `parses generated epoch off the header`() {
        assertEquals(1788643160L, OfflineStreetPack.parseGenerated("generated=1788643160 count=265447"))
    }

    @Test
    fun `an unreadable header is generation zero, not a crash`() {
        assertEquals(0L, OfflineStreetPack.parseGenerated("garbage"))
    }

    @Test
    fun `one line becomes one gazetteer entity, category street`() {
        val rows = OfflineStreetPack.chunkLines(reader("50.444547\t30.521123\tprimary\tвулиця Хрещатик\n"))
            .flatten().toList()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("вулиця Хрещатик", row.name)
        assertEquals(OfflineStreetPack.CATEGORY, row.category)
        assertEquals("primary", row.subcategory)
        assertEquals(50.444547, row.lat, 1e-9)
        assertEquals(30.521123, row.lon, 1e-9)
    }

    @Test
    fun `an empty highway column becomes a null subcategory, not an empty string`() {
        val row = OfflineStreetPack.chunkLines(reader("50.0\t30.0\t\tБезіменна\n")).flatten().first()
        assertNull(row.subcategory)
    }

    @Test
    fun `a short or blank-name line is skipped, not a crash`() {
        val rows = OfflineStreetPack.chunkLines(
            reader(
                "50.0\t30.0\tresidential\n" + // missing the name column
                    "50.0\t30.0\tresidential\t\n" + // blank name
                    "50.0\t30.0\tresidential\tреальна вулиця\n",
            ),
        ).flatten().toList()
        assertEquals(1, rows.size)
        assertEquals("реальна вулиця", rows.first().name)
    }

    @Test
    fun `chunks at the requested size, last chunk short`() {
        val body = (1..1203).joinToString("") { "50.0\t30.0\tresidential\tвулиця $it\n" }
        val chunks = OfflineStreetPack.chunkLines(reader(body), chunkSize = 500).toList()
        assertEquals(listOf(500, 500, 203), chunks.map { it.size })
        assertEquals(1203, chunks.sumOf { it.size })
    }

    @Test
    fun `synthetic ids never collide across rows and stay clear of real OSM ids`() {
        val body = (1..5).joinToString("") { "50.0\t30.0\tresidential\tвулиця $it\n" }
        val ids = OfflineStreetPack.chunkLines(reader(body)).flatten().map { it.id }.toList()
        assertEquals(ids.size, ids.toSet().size)
        // Current OSM element ids run to the order of 10^10 — three orders of
        // magnitude of headroom is the point, not the exact constant.
        assertTrue(ids.all { it > 100_000_000_000L })
    }
}
