package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSearchEngineTest {

    @Test
    fun matchesGeneratorsAndEnergyResilienceCategory() {
        val cat = OsmSpatialUniverse.findByKeyword("генератор")
        assertNotNull(cat)
        assertEquals("energy_generator", cat?.id)
        assertEquals(OsmSpatialUniverse.Domain.ENERGY, cat?.domain)
        assertTrue(cat?.isWartimeCritical == true)
        assertTrue(cat?.tacticalUsageReasonUk?.contains("відключень") == true)
    }

    @Test
    fun matchesWaterWellAndDrinkingWater() {
        val cat = OsmSpatialUniverse.findByKeyword("бювет")
        assertNotNull(cat)
        assertEquals("water_drinking", cat?.id)
        assertEquals("🚰", cat?.emoji)
    }

    @Test
    fun parsesDirectCoordinates() {
        val engine = SpatialSearchEngine()
        val result = engine.search(
            query = "50.4501, 30.5201",
            operatorLat = 50.4400,
            operatorLon = 30.5201,
        )

        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals(SpatialSearchEngine.ResultKind.COORDINATE_TARGET, item.kind)
        assertTrue(item.distanceMeters != null && item.distanceMeters > 0)
        assertEquals("Північ", item.compassDirectionUk)
    }

    @Test
    fun searchesSavedVaultTargetsWithBearingAndDistance() {
        val engine = SpatialSearchEngine()
        val saved = listOf(
            SpatialSearchEngine.SavedTarget(
                id = "v1",
                name = "Склад БК та дронів",
                lat = 50.460,
                lon = 30.520,
                category = "military",
            ),
            SpatialSearchEngine.SavedTarget(
                id = "v2",
                name = "Резервна квартира",
                lat = 50.420,
                lon = 30.500,
                category = "home",
            ),
        )

        val result = engine.search(
            query = "дронів",
            operatorLat = 50.450,
            operatorLon = 30.520,
            operatorHeadingDeg = 0f,
            savedTargets = saved,
        )

        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals("Склад БК та дронів", item.title)
        assertEquals("на 12 год", item.clockDirectionUk)
    }
}
