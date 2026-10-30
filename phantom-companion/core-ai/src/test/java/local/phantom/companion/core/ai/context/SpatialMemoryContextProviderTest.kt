package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAP-AI-2 — pins the SPATIAL_MEMORY prompt block contract.
 *
 * Privacy contract: only count + closest distance surface — no IDs, no
 * titles. These tests regression-pin both the format AND the omission.
 */
class SpatialMemoryContextProviderTest {

    @Test
    fun `formatter emits none for empty list`() {
        assertEquals(
            "[SPATIAL_MEMORY:none]",
            SpatialMemoryContextFormatter.format(emptyList()),
        )
    }

    @Test
    fun `formatter emits count and closest metric distance under 1km`() {
        val nearby = listOf(
            NearbyMemorySummary(distanceMeters = 320.0),
            NearbyMemorySummary(distanceMeters = 85.0),
            NearbyMemorySummary(distanceMeters = 410.0),
        )
        assertEquals(
            "[SPATIAL_MEMORY: count=3 closest=85m]",
            SpatialMemoryContextFormatter.format(nearby),
        )
    }

    @Test
    fun `formatter rounds meter distance down to integer`() {
        val nearby = listOf(NearbyMemorySummary(distanceMeters = 123.78))
        assertEquals(
            "[SPATIAL_MEMORY: count=1 closest=123m]",
            SpatialMemoryContextFormatter.format(nearby),
        )
    }

    @Test
    fun `formatter switches to km at 1000m threshold with one decimal`() {
        val nearby = listOf(
            NearbyMemorySummary(distanceMeters = 1250.0),
            NearbyMemorySummary(distanceMeters = 1850.5),
        )
        assertEquals(
            "[SPATIAL_MEMORY: count=2 closest=1.3km]",
            SpatialMemoryContextFormatter.format(nearby),
        )
    }

    @Test
    fun `formatter never includes titles or ids in output`() {
        val nearby = listOf(NearbyMemorySummary(distanceMeters = 50.0))
        val out = SpatialMemoryContextFormatter.format(nearby)
        // Explicit privacy regression — formatter output must contain
        // only count + closest, never a title or identifier substring.
        assert("title" !in out)
        assert("id=" !in out)
        assert("name" !in out)
    }

    @Test
    fun `provider lambda returns supplier snapshot`() {
        var snapshot = listOf(NearbyMemorySummary(distanceMeters = 1.0))
        val provider = SpatialMemoryContextProvider { snapshot }
        assertEquals(snapshot, provider.current())

        snapshot = emptyList()
        assertEquals(emptyList<NearbyMemorySummary>(), provider.current())
    }
}
