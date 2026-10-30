package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IRONCLAD-2 — the pure halves of the permanent mesh, on the JVM: the
 * locale-proof geometry codec and the cell-sharding math. (The SQLite shell
 * around them is exercised on-device; it is deliberately too thin to hide
 * logic.)
 */
class RoadMeshStoreTest {

    @Test
    fun `codec roundtrips geometry within a decimetre`() {
        val pts = listOf(
            50.4501 to 30.5234,
            50.451234 to 30.529876,
            -33.9 to 151.2,
        )
        val decoded = RoadWayCodec.decodePoints(RoadWayCodec.encodePoints(pts))
        checkNotNull(decoded)
        assertEquals(pts.size, decoded.size)
        for (i in pts.indices) {
            assertEquals(pts[i].first, decoded[i].first, 1e-6)
            assertEquals(pts[i].second, decoded[i].second, 1e-6)
        }
    }

    @Test
    fun `codec is locale-proof — no decimal commas ever`() {
        // A Ukrainian system locale formats 50.45 as «50,45»; the codec must
        // not (the row would decode as garbage or not at all).
        val encoded = RoadWayCodec.encodePoints(listOf(50.4501 to 30.5234, 50.5 to 30.6))
        assertTrue(encoded.matches(Regex("""[-0-9.]+,[-0-9.]+(;[-0-9.]+,[-0-9.]+)*""")))
    }

    @Test
    fun `corrupt rows decode to null, never to a wrong road`() {
        assertNull(RoadWayCodec.decodePoints(""))
        assertNull(RoadWayCodec.decodePoints("50.45"))
        assertNull(RoadWayCodec.decodePoints("50.45,30.52")) // one point is not a way
        assertNull(RoadWayCodec.decodePoints("50.45,30.52;abc,30.53"))
        assertNull(RoadWayCodec.decodePoints("50.45,30.52;30.53"))
    }

    @Test
    fun `the tag codec carries road class, surface and posted limit`() {
        // These three are the whole of A-04: without them the offline router
        // cannot tell a farm track from a trunk road, cannot prefer asphalt,
        // and cannot time a journey from a sign.
        val tags = mapOf("highway" to "trunk", "surface" to "asphalt", "maxspeed" to "90")
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(tags))
        assertEquals("trunk", back["highway"])
        assertEquals("asphalt", back["surface"])
        assertEquals("90", back["maxspeed"])
    }

    @Test
    fun `the tag codec keeps only what routing acts on`() {
        val back = RoadWayCodec.decodeTags(
            RoadWayCodec.encodeTags(
                mapOf("highway" to "residential", "name" to "Хрещатик", "operator" to "КМДА"),
            ),
        )
        assertEquals("residential", back["highway"])
        assertEquals(1, back.size)
    }

    @Test
    fun `a tag value never corrupts the row — pipes escape, equals is dropped`() {
        // Намір цього сторожа не змінився: рядок не сміє зіпсуватись. Змінилось
        // те, ЯК ми цього досягаємо для вертикальної риски.
        //
        // Раніше значення з `|` просто ВИКИДАЛОСЬ — і це тихо робило смуги
        // неможливими: `turn:lanes` записує їх саме через риску
        // (`left|through;right`), тобто кожне таке значення зникало ще в
        // кодері. Тепер риска екранується в %7C і доїжджає цілою.
        //
        // Знак рівності лишається відкинутим: його ні на що замінити, не
        // зруйнувавши саму пару `k=v`.
        val back = RoadWayCodec.decodeTags(
            RoadWayCodec.encodeTags(mapOf("highway" to "a|b", "surface" to "x=y", "maxspeed" to "50")),
        )
        assertEquals("риска мусить доїхати цілою", "a|b", back["highway"])
        assertEquals("знак рівності досі не проїжджає", null, back["surface"])
        assertEquals("сусідній тег не постраждав", "50", back["maxspeed"])
        assertEquals(2, back.size)
    }

    @Test
    fun `a way with no tags encodes to nothing and decodes back to nothing`() {
        assertEquals("", RoadWayCodec.encodeTags(emptyMap()))
        assertTrue(RoadWayCodec.decodeTags("").isEmpty())
    }

    @Test
    fun `a tile-sized bbox shards into a handful of cells`() {
        // One RoadTruth tile (0.02°) plus its skirt: at most a 2×2 block.
        val cells = RoadMeshStore.cellsCovering(50.44 - 0.001, 30.52 - 0.001, 50.46 + 0.001, 30.54 + 0.001)
        assertTrue("got ${cells.size} cells", cells.isNotEmpty() && cells.size <= 9)
        assertEquals("cells must be distinct", cells.size, cells.toSet().size)
    }

    @Test
    fun `a continent-spanning bbox shards into nothing, not into a million rows`() {
        assertEquals(0, RoadMeshStore.cellsCovering(-60.0, -170.0, 60.0, 170.0).size)
    }

    @Test
    fun `neighbouring points on either side of a cell edge land in different cells`() {
        val below = RoadMeshStore.cellsCovering(50.4399, 30.52, 50.4399, 30.52)
        val above = RoadMeshStore.cellsCovering(50.4401, 30.52, 50.4401, 30.52)
        assertEquals(1, below.size)
        assertEquals(1, above.size)
        assertTrue(below[0] != above[0])
    }
}
