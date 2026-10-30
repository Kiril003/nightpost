package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.PeakWords
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier
import local.phantom.companion.feature.stream.ui.map.dossier.WalkWords
import local.phantom.companion.feature.stream.ui.map.dossier.entityRefOf
import local.phantom.companion.feature.stream.ui.map.dossier.peakSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFocusTest {

    private val w = 1080f
    private val h = 2400f
    private val d = 2.75f

    private fun src(name: String) = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$name").readText()

    @Test
    fun `a place under the sheet makes the camera fly`() {
        assertTrue(PlaceFocus.shouldFly(540f, 1800f, w, h, 16.0, d))
    }

    @Test
    fun `a place off screen or under the search bar makes the camera fly`() {
        assertTrue(PlaceFocus.shouldFly(-50f, 600f, w, h, 16.0, d))
        assertTrue(PlaceFocus.shouldFly(1200f, 600f, w, h, 16.0, d))
        assertTrue(PlaceFocus.shouldFly(540f, 100f, w, h, 16.0, d))
    }

    @Test
    fun `a far zoomed-out pick still flies in`() {
        assertTrue(PlaceFocus.shouldFly(540f, 700f, w, h, 9.0, d))
    }

    @Test
    fun `a place already above the sheet stays put`() {
        assertFalse(PlaceFocus.shouldFly(540f, 700f, w, h, 16.0, d))
    }

    @Test
    fun `the sheet names where the place is`() {
        assertEquals("Národní 5", streetOf("Kavárna Lipa, 5, Národní, Staré Město, Praha", "Kavárna Lipa"))
        assertEquals("вулиця Хрещатик 22", streetOf("22, вулиця Хрещатик, Київ", null))
        assertEquals(null, streetOf(null, "x"))
        val words = PeakWords(
            openUntil = { it }, open = "open", closedUntil = { it }, closed = "closed",
            floor = { it }, inContainer = { it },
            walk = WalkWords(minutes = { "$it m" }, hours = { "$it h" }, hoursMinutes = { a, b -> "$a $b" }),
            feePaid = "paid", feeFree = "free",
        )
        val entity = entityRefOf(tags = emptyMap())
        val dossier = PlaceDossier(
            target = DossierTarget(lat = 50.081, lon = 14.419),
            title = "Kavárna Lipa", kindLabel = null, category = entity.category, entity = entity,
            address = "Kavárna Lipa, 5, Národní, Staré Město", tags = emptyMap(), openStatus = null,
            rawHours = null, building = null, reference = null, transitLines = emptyList(), similar = emptyList(),
        )
        val segs = peakSegments(dossier, origin = 50.087 to 14.421, words = words).map { it.text }
        assertTrue(segs.toString(), "Národní 5" in segs)
        assertTrue(segs.toString(), segs.any { it.endsWith(" m") })
    }

    @Test
    fun `every opened place gets a pin and a camera check`() {
        val screen = src("MapScreen.kt")
        assertTrue(screen.contains("placeOutOfView(map, t.lat, t.lon, density)"))
        assertTrue(src("MapCanvasWiring.kt").contains("PlaceFocus.shouldFly("))
        assertTrue(screen.contains("LaunchedEffect(placeShown?.lat, placeShown?.lon)"))
        assertTrue(src("MapCanvas.kt").contains("searchPin = ui.searchPin ?: ui.dossierTarget?.let { it.lat to it.lon }"))
        assertTrue(src("dossier/DossierPeakLine.kt").contains("streetOf(dossier.address, dossier.title)"))
    }
}
