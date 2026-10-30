package local.phantom.companion.feature.stream.ui.map.crystal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrystalGeoTest {

    @Test
    fun `tile bounds contain the point that selected the tile`() {
        val kyiv = CityPoint(50.4501, 30.5234)
        val tile = CrystalGeo.tileFor(kyiv.lat, kyiv.lon)
        val b = CrystalGeo.boundsOf(tile)
        assertTrue(kyiv.lat > b.south && kyiv.lat < b.north)
        assertTrue(kyiv.lon > b.west && kyiv.lon < b.east)
        // z15 tile is ~1.2km wide at the equator; sanity-check the bbox isn't degenerate.
        assertTrue(b.north > b.south)
        assertTrue(b.east > b.west)
    }

    @Test
    fun `adjacent points map to adjacent or same tiles`() {
        val a = CrystalGeo.tileFor(50.4501, 30.5234)
        val b = CrystalGeo.tileFor(50.4501, 30.5234 + 0.011) // ~one z15 tile east
        assertEquals(a.y, b.y)
        assertEquals(a.x + 1, b.x)
    }

    @Test
    fun `tilesCovering returns full grid ordered centre-out`() {
        val centre = CityPoint(50.4501, 30.5234)
        val tile = CrystalGeo.tileFor(centre.lat, centre.lon)
        val b = CrystalGeo.boundsOf(tile)
        // Viewport spanning the tile plus a sliver of all 8 neighbours.
        val tiles = CrystalGeo.tilesCovering(
            north = b.north + 0.0001,
            east = b.east + 0.0001,
            south = b.south - 0.0001,
            west = b.west - 0.0001,
        )
        assertEquals(9, tiles.size)
        assertEquals(tile, tiles.first()) // centre tile wins the nearest-first sort
        assertEquals(9, tiles.toSet().size) // no duplicates
    }
}
