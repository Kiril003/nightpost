package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The z-ladder is a decision, not an accident. These lock the two orders it
 * was seeded from so a later edit that «just moves one line» has to argue with
 * the sources it came from.
 */
class MapTierLadderTest {

    /**
     * MapCanvas's install chain, in the order the style-loaded block runs it.
     * The three lazily-installed tiers (radar, maneuvers, offline packs) are
     * absent on purpose — their position today is where the update pass
     * happened to reach them, which is the thing D3 abolishes.
     */
    private val groundSeed = listOf(
        MapTier.GROUND_CARTOGRAPHY,
        MapTier.LABEL_VOICE,
        MapTier.GROUND_BACKFILL,
        MapTier.GROUND_FACTS,
        MapTier.HILLSHADE,
        MapTier.ALERT_WASH,
        MapTier.COVERAGE_HATCH,
        MapTier.EW_TOPOLOGY,
        MapTier.OPERATOR_PIN,
        MapTier.SEARCH_PIN,
        MapTier.ROUTE_LINE,
        MapTier.ROUTE_REGISTER,
        MapTier.TRANSIT,
        MapTier.PHANTOM_LAYERS,
        // ATLAS-0b ruling: extrusions installed LAST here and swallowed the
        // pins standing on the roofs they raised. A building is ground the
        // operator reads through, never a lid over a place somebody marked —
        // so the seed records the decision, not the history it replaced.
        MapTier.BUILDING_EXTRUSION,
        MapTier.POI,
        MapTier.SAVED_PLACES,
    )

    /** mapBackStep's dismissal order, reversed; its two ground steps live in [groundSeed]. */
    private val chromeSeed = listOf(
        MapTier.CHROME_TOOLS,
        MapTier.CHROME_TAP_PROVENANCE,
        MapTier.CHROME_DROPPED_POINT,
        MapTier.CHROME_THREAT_OBLAST,
        MapTier.CHROME_LAYER_MARKER,
        MapTier.CHROME_SAVED_PLACE,
        MapTier.CHROME_DOSSIER,
        MapTier.CHROME_OFFLINE_PANEL,
        MapTier.CHROME_STYLE_PICKER,
        MapTier.CHROME_ROUTE_COMPARE,
        MapTier.CHROME_TRANSIT_HUB,
        MapTier.CHROME_ALARM_SHEET,
        MapTier.CHROME_PROVENANCE_SHEET,
        MapTier.CHROME_ANSWER,
        MapTier.CHROME_SEARCH,
        MapTier.CHROME_MOUNT,
    )

    private fun isSubsequence(seed: List<MapTier>, ladder: List<MapTier>): Boolean {
        var i = 0
        for (tier in ladder) if (i < seed.size && tier == seed[i]) i++
        return i == seed.size
    }

    @Test
    fun `ladder preserves the canvas install order`() {
        assertTrue(
            "install order drifted from MapCanvas's chain",
            isSubsequence(groundSeed, MapTier.entries),
        )
    }

    @Test
    fun `ladder preserves the back-stack order, reversed`() {
        assertTrue(
            "chrome order drifted from mapBackStep",
            isSubsequence(chromeSeed, MapTier.entries),
        )
    }

    @Test
    fun `every ground tier sits below every chrome tier`() {
        val highestGround = MapTier.GROUND.maxOf { it.ordinal }
        val lowestChrome = MapTier.CHROME.minOf { it.ordinal }
        assertTrue("D1: chrome may not sink under the ground", highestGround < lowestChrome)
    }

    @Test
    fun `ordering is total and stable`() {
        val ordinals = MapTier.entries.map { it.ordinal }
        assertEquals(ordinals.distinct().size, ordinals.size)
        assertEquals(ordinals.sorted(), ordinals)
        assertEquals(MapTier.entries.size, MapTier.GROUND.size + MapTier.CHROME.size)
    }

    @Test
    fun `radar sits under the alert wash, where applyRadar puts it`() {
        assertTrue(MapTier.RADAR.ordinal < MapTier.ALERT_WASH.ordinal)
    }

    @Test
    fun `maneuver pins sit directly over the route they annotate`() {
        assertTrue(MapTier.ROUTE_LINE.ordinal < MapTier.MANEUVER_PINS.ordinal)
        assertTrue(MapTier.ROUTE_REGISTER.ordinal < MapTier.MANEUVER_PINS.ordinal)
    }

    @Test
    fun `tap ranks are ordered by the canvas click chain`() {
        assertEquals(
            listOf(
                TapRank.ROUTE_ALTERNATIVE,
                TapRank.TRANSIT_STOP,
                TapRank.LAYER_MARKER,
                // СИМБІОЗ: палець на фігурці означає людину, а не землю під нею.
                TapRank.PEER_POINT,
                // Знаки подій прийшли з Compose-оверлея, який лежав ПОВЕРХ
                // движка і вигравав палець у всіх; ранг над місцями зберігає
                // ту саму поведінку в реєстрі.
                TapRank.EVENT_MARK,
                TapRank.SAVED_PLACE,
                TapRank.POI,
                TapRank.GROUND_FEATURE,
            ),
            TapRank.entries.toList(),
        )
    }

    @Test
    fun `a contact places before every hazard and every sign, after the maneuver`() {
        // Порядок реєстрації Є порядком розкладки: хто ляже раніше, той виграє
        // тісноту. Людина, яка поділилась місцем, не поступається вивісці.
        assertTrue(MapTier.MANEUVER_PINS.ordinal < MapTier.PEER_FIGURES.ordinal)
        assertTrue(MapTier.PEER_FIGURES.ordinal < MapTier.EVENT_MARKS.ordinal)
        assertTrue(MapTier.PEER_FIGURES.ordinal < MapTier.POI.ordinal)
        assertTrue(MapTier.PEER_FIGURES.ordinal < MapTier.SAVED_PLACES.ordinal)
    }

    @Test
    fun `event marks place before transit and places, after maneuvers`() {
        // Порядок встановлення = порядок розкладки: знак бʼє вивіску, маневр
        // лишається головнішим за знак.
        assertTrue(MapTier.MANEUVER_PINS.ordinal < MapTier.EVENT_MARKS.ordinal)
        assertTrue(MapTier.EVENT_MARKS.ordinal < MapTier.TRANSIT.ordinal)
        assertTrue(MapTier.EVENT_MARKS.ordinal < MapTier.POI.ordinal)
    }
}
