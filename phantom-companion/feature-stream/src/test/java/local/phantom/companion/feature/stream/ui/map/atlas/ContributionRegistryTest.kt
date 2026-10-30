package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionRegistryTest {

    private object Style : MapStyleHandle

    private fun frameOf(vararg tiers: MapTier) = LensFrame.DARK.copy(tiers = tiers.toSet())

    private val allTiers = LensFrame.DARK.copy(tiers = MapTier.entries.toSet())

    private fun contribution(
        id: String,
        tier: MapTier,
        lensTiers: Set<MapTier> = setOf(tier),
        tap: TapSpec? = null,
        install: ContributionInstall? = null,
        update: ContributionUpdate? = null,
    ) = MapContribution(id, tier, lensTiers, tap, install, update)

    // ── ordering ──────────────────────────────────────────────────────────

    @Test
    fun `install order follows the tier ladder, not registration order`() {
        val registry = ContributionRegistry()
            .register(contribution("poi", MapTier.POI))
            .register(contribution("ground", MapTier.GROUND_CARTOGRAPHY))
            .register(contribution("route", MapTier.ROUTE_LINE))

        assertEquals(listOf("ground", "route", "poi"), registry.installOrder().map { it.id })
    }

    @Test
    fun `same-tier contributions keep registration order, stably`() {
        val ids = listOf("a", "b", "c", "d")
        val registry = ContributionRegistry()
        ids.forEach { registry.register(contribution(it, MapTier.PHANTOM_LAYERS)) }

        repeat(5) { assertEquals(ids, registry.installOrder().map { it.id }) }
    }

    @Test
    fun `order is total — every registered contribution appears exactly once`() {
        val registry = ContributionRegistry()
        MapTier.entries.forEach { registry.register(contribution(it.name, it)) }

        val order = registry.installOrder()
        assertEquals(MapTier.entries.size, order.size)
        assertEquals(order.size, order.map { it.id }.distinct().size)
        assertEquals(MapTier.entries.map { it.name }, order.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate ids are rejected`() {
        ContributionRegistry()
            .register(contribution("poi", MapTier.POI))
            .register(contribution("poi", MapTier.SAVED_PLACES))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two catch-all tap claims are rejected`() {
        ContributionRegistry()
            .register(contribution("a", MapTier.POI, tap = TapSpec(TapRank.GROUND_FEATURE, catchAll = true)))
            .register(contribution("b", MapTier.SAVED_PLACES, tap = TapSpec(TapRank.GROUND_FEATURE, catchAll = true)))
    }

    // ── fault isolation (the mapStyleStep contract) ───────────────────────

    @Test
    fun `one failing install does not stop the sweep`() {
        val ran = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val registry = ContributionRegistry(onStepFailure = { id, _ -> failures += id })
            .register(contribution("ground", MapTier.GROUND_CARTOGRAPHY, install = { ran += "ground" }))
            .register(
                contribution("route", MapTier.ROUTE_LINE, install = { error("boom") }),
            )
            .register(contribution("poi", MapTier.POI, install = { ran += "poi" }))

        val report = registry.install(Style)

        assertEquals(listOf("ground", "poi"), ran)
        assertEquals(listOf("route"), failures)
        assertFalse(report.allOk)
        assertEquals(listOf("route"), report.failed.map { it.id })
        assertEquals(3, report.steps.size)
    }

    @Test
    fun `darkened contributions are still updated so they can go dark`() {
        val updated = mutableListOf<String>()
        val registry = ContributionRegistry()
            .register(
                contribution("poi", MapTier.POI, update = { _, _ -> updated += "poi" }),
            )
            .register(
                contribution("transit", MapTier.TRANSIT, update = { _, _ -> updated += "transit" }),
            )

        registry.update(Style, frameOf(MapTier.POI))

        assertEquals(listOf("transit", "poi"), updated)
    }

    @Test
    fun `lensTiers gate visibility on every named tier`() {
        val maneuvers = contribution(
            "maneuvers",
            MapTier.MANEUVER_PINS,
            lensTiers = setOf(MapTier.MANEUVER_PINS, MapTier.ROUTE_LINE),
        )

        assertFalse(maneuvers.visibleIn(frameOf(MapTier.MANEUVER_PINS)))
        assertTrue(maneuvers.visibleIn(frameOf(MapTier.MANEUVER_PINS, MapTier.ROUTE_LINE)))
    }

    // ── tap dispatch ──────────────────────────────────────────────────────

    private fun tapRegistry() = ContributionRegistry()
        .register(
            contribution(
                "altRoutes", MapTier.ROUTE_LINE,
                tap = TapSpec(TapRank.ROUTE_ALTERNATIVE, listOf("alt-route", "alt-label")),
            ),
        )
        .register(
            contribution(
                "transitStops", MapTier.TRANSIT,
                tap = TapSpec(TapRank.TRANSIT_STOP, listOf("transit-stops")),
            ),
        )
        .register(
            contribution(
                "savedPlaces", MapTier.SAVED_PLACES,
                tap = TapSpec(TapRank.SAVED_PLACE, listOf("saved-poi")),
            ),
        )
        .register(
            contribution(
                "poi", MapTier.POI,
                tap = TapSpec(TapRank.POI, listOf("poi-pin", "poi-cluster")),
            ),
        )
        .register(
            contribution(
                "groundFeature", MapTier.GROUND_CARTOGRAPHY,
                tap = TapSpec(TapRank.GROUND_FEATURE, catchAll = true),
            ),
        )

    @Test
    fun `rank wins over pixel distance`() {
        val hits = listOf(
            TapHit("poi-pin", distancePx = 1f),
            TapHit("alt-route", distancePx = 20f),
        )

        val resolved = tapRegistry().resolveTap(hits, allTiers)

        assertEquals("altRoutes", resolved?.contributionId)
    }

    @Test
    fun `within one rank the nearest pixel wins`() {
        val hits = listOf(
            TapHit("poi-pin", distancePx = 18f, payload = "far"),
            TapHit("poi-cluster", distancePx = 3f, payload = "near"),
        )

        val resolved = tapRegistry().resolveTap(hits, allTiers)

        assertEquals("poi", resolved?.contributionId)
        assertEquals("near", resolved?.hit?.payload)
    }

    @Test
    fun `the full tolerance-box hit list resolves in one pass`() {
        val hits = listOf(
            TapHit("some-base-layer", distancePx = TapHit.AREA_DISTANCE_PX),
            TapHit("poi-pin", distancePx = 9f),
            TapHit("saved-poi", distancePx = 11f),
            TapHit("transit-stops", distancePx = 14f),
        )

        val resolved = tapRegistry().resolveTap(hits, allTiers)

        assertEquals("transitStops", resolved?.contributionId)
        assertEquals(TapRank.TRANSIT_STOP, resolved?.rank)
    }

    @Test
    fun `an unclaimed layer falls through to the catch-all`() {
        val resolved = tapRegistry().resolveTap(
            listOf(TapHit("openmaptiles-poi", distancePx = 4f)),
            allTiers,
        )

        assertEquals("groundFeature", resolved?.contributionId)
    }

    @Test
    fun `a darkened contribution cannot win a tap`() {
        val frame = LensFrame.DARK.copy(tiers = MapTier.entries.toSet() - MapTier.TRANSIT)

        val resolved = tapRegistry().resolveTap(
            listOf(TapHit("transit-stops", distancePx = 1f), TapHit("poi-pin", distancePx = 30f)),
            frame,
        )

        assertEquals("poi", resolved?.contributionId)
    }

    @Test
    fun `no hits means no dispatch`() {
        assertNull(tapRegistry().resolveTap(emptyList(), allTiers))
    }

    @Test
    fun `the single query pass asks only for lit tappable layers`() {
        val frame = LensFrame.DARK.copy(tiers = MapTier.entries.toSet() - MapTier.POI)

        val ids = tapRegistry().tapLayerIds(frame)

        assertEquals(listOf("alt-route", "alt-label", "transit-stops", "saved-poi"), ids)
        assertTrue(tapRegistry().catchAllActive(frame))
    }

    @Test
    fun `resolution reports which hit won`() {
        val resolved = tapRegistry().resolveTap(
            listOf(TapHit("saved-poi", distancePx = 2f, payload = 42L)),
            allTiers,
        )

        assertNotNull(resolved)
        assertEquals(42L, resolved?.hit?.payload)
        assertEquals("saved-poi", resolved?.hit?.layerId)
    }
}
