package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.crystal.CityBuilding
import local.phantom.companion.feature.stream.ui.map.crystal.CityGreenery
import local.phantom.companion.feature.stream.ui.map.crystal.CityPoint
import local.phantom.companion.feature.stream.ui.map.crystal.CityTileData
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalGeo
import local.phantom.companion.feature.stream.ui.map.crystal.RoofShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRegisterProfileTest {

    // Kyiv (measured tile) and a rural point ~5km away that lands in a
    // different crystal tile (zoom 15, ~1.2km cells) with no cached data.
    private val known = 50.4501 to 30.5234
    private val unknown = 50.49 to 30.60

    private fun building(register: GroundRegister) = CityBuilding(
        id = 1L,
        rings = listOf(listOf(CityPoint(0.0, 0.0), CityPoint(0.0, 1.0), CityPoint(1.0, 1.0))),
        heightM = 8f,
        minHeightM = 0f,
        roofShape = RoofShape.FLAT,
        roofHeightM = 0f,
        wallColor = null,
        roofColor = null,
        register = register,
        editedYear = null,
    )

    @Test
    fun `points outside any cached tile classify as unknown`() {
        val profile = RouteRegisterProfile.classify(listOf(known, unknown), tiles = emptyMap())
        assertTrue(profile.all { it.register == null })
    }

    @Test
    fun `a cached tile's worst building register wins for points inside it`() {
        val tile = CrystalGeo.tileFor(known.first, known.second)
        val tiles = mapOf(
            tile to CityTileData(
                buildings = listOf(building(GroundRegister.DERIVED), building(GroundRegister.GUESSED)),
                greenery = CityGreenery.EMPTY,
            ),
        )
        val profile = RouteRegisterProfile.classify(listOf(known, unknown), tiles)
        assertEquals(GroundRegister.GUESSED, profile[0].register)
        assertNull(profile[1].register)
    }

    @Test
    fun `worstKnown ignores unknown points instead of treating absence as low confidence`() {
        val profile = listOf(
            RouteRegisterPoint(0.0, 0.0, null),
            RouteRegisterPoint(0.0, 0.0, GroundRegister.MEASURED),
            RouteRegisterPoint(0.0, 0.0, null),
        )
        assertEquals(GroundRegister.MEASURED, RouteRegisterProfile.worstKnown(profile))
        assertNull(RouteRegisterProfile.worstKnown(listOf(RouteRegisterPoint(0.0, 0.0, null))))
    }

    @Test
    fun `occupied ground forces REMEMBERED even with no cached building`() {
        val profile = RouteRegisterProfile.classify(
            points = listOf(known),
            tiles = emptyMap(),
            isOccupied = { _, _ -> true },
        )
        assertEquals(GroundRegister.REMEMBERED, profile.single().register)
    }

    @Test
    fun `occupied ground overrides a confident tag reading, never the other way`() {
        val tile = CrystalGeo.tileFor(known.first, known.second)
        val tiles = mapOf(tile to CityTileData(listOf(building(GroundRegister.MEASURED)), CityGreenery.EMPTY))
        val profile = RouteRegisterProfile.classify(listOf(known), tiles, isOccupied = { _, _ -> true })
        assertEquals(GroundRegister.REMEMBERED, profile.single().register)
    }

    @Test
    fun `degradedSegments only emits GUESSED and REMEMBERED runs, merged and separated correctly`() {
        val profile = listOf(
            RouteRegisterPoint(0.0, 0.0, GroundRegister.MEASURED),
            RouteRegisterPoint(1.0, 0.0, GroundRegister.GUESSED),
            RouteRegisterPoint(2.0, 0.0, GroundRegister.GUESSED),
            RouteRegisterPoint(3.0, 0.0, null),
            RouteRegisterPoint(4.0, 0.0, GroundRegister.REMEMBERED),
        )
        val segments = RouteRegisterProfile.degradedSegments(profile)
        assertEquals(2, segments.size)
        assertEquals(GroundRegister.GUESSED, segments[0].second)
        assertEquals(2, segments[0].first.size)
        assertEquals(GroundRegister.REMEMBERED, segments[1].second)
        assertEquals(1, segments[1].first.size)
    }

    @Test
    fun `an all-confident route produces no degraded segments`() {
        val profile = listOf(
            RouteRegisterPoint(0.0, 0.0, GroundRegister.MEASURED),
            RouteRegisterPoint(1.0, 0.0, GroundRegister.DERIVED),
        )
        assertTrue(RouteRegisterProfile.degradedSegments(profile).isEmpty())
    }
}
