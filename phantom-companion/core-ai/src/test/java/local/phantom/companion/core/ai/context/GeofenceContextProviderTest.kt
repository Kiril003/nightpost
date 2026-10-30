package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAP-AI-2 — pins the GEOFENCES prompt block contract.
 *
 * Privacy contract: only counts surface — never raw polygon, never
 * Matter-action name. Pinned here so MAP-AI-6 cannot accidentally leak
 * details through this preamble when wiring the real provider.
 */
class GeofenceContextProviderTest {

    @Test
    fun `formatter emits none for empty list`() {
        assertEquals(
            "[GEOFENCES:none]",
            GeofenceContextFormatter.format(emptyList()),
        )
    }

    @Test
    fun `formatter counts armed fences and how many operator is inside`() {
        val fences = listOf(
            GeofenceSummary(inside = true),
            GeofenceSummary(inside = false),
            GeofenceSummary(inside = true),
            GeofenceSummary(inside = false),
        )
        assertEquals(
            "[GEOFENCES: count=4 inside=2]",
            GeofenceContextFormatter.format(fences),
        )
    }

    @Test
    fun `formatter reports zero inside when all fences are outside`() {
        val fences = listOf(
            GeofenceSummary(inside = false),
            GeofenceSummary(inside = false),
        )
        assertEquals(
            "[GEOFENCES: count=2 inside=0]",
            GeofenceContextFormatter.format(fences),
        )
    }

    @Test
    fun `formatter never includes coords or action names in output`() {
        val fences = listOf(GeofenceSummary(inside = true))
        val out = GeofenceContextFormatter.format(fences)
        assert("lat" !in out)
        assert("lon" !in out)
        assert("scene" !in out)
        assert("action" !in out)
    }

    @Test
    fun `noop provider returns empty list`() {
        assertEquals(emptyList<GeofenceSummary>(), NoopGeofenceContextProvider.current())
    }

    @Test
    fun `noop provider chained through formatter yields none`() {
        val out = GeofenceContextFormatter.format(NoopGeofenceContextProvider.current())
        assertEquals("[GEOFENCES:none]", out)
    }
}
