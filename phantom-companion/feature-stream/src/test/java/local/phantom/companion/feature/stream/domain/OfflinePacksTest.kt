package local.phantom.companion.feature.stream.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePacksTest {

    private val day = "https://tiles/day.json"
    private val night = "https://tiles/night.json"

    private fun area(n: Double, e: Double, s: Double, w: Double) =
        OfflineCoverage.Area(northLat = n, eastLon = e, southLat = s, westLon = w)

    @Test
    fun `an unfinished pack reads as downloading whatever style it carries`() {
        assertEquals(
            OfflinePacks.State.Downloading,
            OfflinePacks.stateOf(complete = false, packStyleUrl = night, currentStyleUrl = day),
        )
    }

    @Test
    fun `a finished pack for another basemap is not usable here`() {
        assertEquals(
            OfflinePacks.State.OtherMap,
            OfflinePacks.stateOf(complete = true, packStyleUrl = night, currentStyleUrl = day),
        )
    }

    @Test
    fun `a pack that never recorded its style is not accused of the wrong one`() {
        assertEquals(
            OfflinePacks.State.Ready,
            OfflinePacks.stateOf(complete = true, packStyleUrl = null, currentStyleUrl = day),
        )
    }

    @Test
    fun `the offer follows the coverage verdict`() {
        assertEquals(OfflinePacks.Offer.Save, OfflinePacks.offer(OfflineCoverage.Verdict.None))
        assertEquals(OfflinePacks.Offer.SaveRest, OfflinePacks.offer(OfflineCoverage.Verdict.Partial))
        assertEquals(
            OfflinePacks.Offer.SaveForThisMap,
            OfflinePacks.offer(OfflineCoverage.Verdict.WrongStyle),
        )
        assertEquals(
            OfflinePacks.Offer.AlreadyHave,
            OfflinePacks.offer(OfflineCoverage.Verdict.Covered),
        )
    }

    @Test
    fun `a rectangle closes on itself so the fill has somewhere to go`() {
        val json = OfflinePacks.geoJson(
            listOf(OfflinePacks.Painted(id = 7, area = area(51.0, 31.0, 50.0, 30.0), colorHex = "#FFAA00")),
        )
        assertTrue(json.contains("\"pack_id\":\"7\""))
        assertTrue(json.contains("\"color\":\"#FFAA00\""))
        assertTrue(json.contains("[[[30.0,50.0],[31.0,50.0],[31.0,51.0],[30.0,51.0],[30.0,50.0]]]"))
    }

    @Test
    fun `an inverted pack is dropped rather than painted inside out`() {
        val json = OfflinePacks.geoJson(
            listOf(OfflinePacks.Painted(id = 1, area = area(50.0, 30.0, 51.0, 31.0), colorHex = "#FFAA00")),
        )
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", json)
    }

    @Test
    fun `selection rides on the feature so one layer can paint both`() {
        val json = OfflinePacks.geoJson(
            listOf(
                OfflinePacks.Painted(1, area(51.0, 31.0, 50.0, 30.0), "#FFAA00", selected = true),
                OfflinePacks.Painted(2, area(53.0, 33.0, 52.0, 32.0), "#888888"),
            ),
        )
        assertTrue(json.contains("\"selected\":true"))
        assertTrue(json.contains("\"selected\":false"))
        assertFalse(json.contains("NaN"))
    }
}
