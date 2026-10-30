package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTapTest {

    private val cafe = DossierTarget(49.8300, 18.1630, nameHint = "Kafe Jarošů", categoryHint = "cafe")

    @Test
    fun `a second tap on the open place keeps it instead of reloading`() {
        val ui = MapUiState()
        ui.dossierTarget = cafe
        ui.dossierTarget = cafe.copy(lat = cafe.lat + 0.00006, lon = cafe.lon - 0.00004)
        assertSame(cafe, ui.dossierTarget)
        ui.dossierTarget = cafe.copy(nameHint = null)
        assertSame(cafe, ui.dossierTarget)
        val venue = cafe.copy(venueId = "0123456789abcdef")
        ui.dossierTarget = venue
        assertSame(venue, ui.dossierTarget)
    }

    @Test
    fun `another place retargets the open sheet`() {
        val ui = MapUiState()
        ui.dossierTarget = cafe
        val shop = DossierTarget(49.8302, 18.1631, nameHint = "Coop", categoryHint = "supermarket")
        ui.dossierTarget = shop
        assertSame(shop, ui.dossierTarget)
        val far = cafe.copy(lat = cafe.lat + 0.001)
        ui.dossierTarget = far
        assertSame(far, ui.dossierTarget)
    }

    @Test
    fun `two venues on one spot stay two places`() {
        val a = cafe.copy(venueId = "aaaa")
        assertFalse(PlaceTap.samePlace(a, a.copy(venueId = "bbbb")))
        assertTrue(PlaceTap.samePlace(a, a.copy(lat = a.lat + 0.0001)))
        assertFalse(PlaceTap.samePlace(cafe, cafe.copy(lat = cafe.lat + 0.0004)))
    }

    @Test
    fun `a blank tap closes the place`() {
        val ui = MapUiState()
        ui.dossierTarget = cafe
        ui.dossierTarget = null
        assertNull(ui.dossierTarget)
        val onClick = source("MapCanvasEffects.kt").substringAfter("OnMapClickListener { point ->").substringBefore("val onLongClick")
        assertTrue("дотик повз усе не закриває місце", onClick.contains("ui.dossierTarget = null"))
    }

    @Test
    fun `the place sheet lives in the map, not in its own window`() {
        val host = source("SheetHost.kt")
        val branch = host.substringAfter("val dossierTarget = ui.dossierTarget").substringBefore("val placeSend = ui.placeSend")
        assertFalse("місце знову відкривається у вікні, що ковтає дотики мапи", Regex("""(?<![A-Za-z])MapSheet\(""").containsMatchIn(branch))
        assertTrue(branch.contains("MapPlaceSheet("))
        val screen = source("MapScreen.kt")
        val host0 = screen.indexOf("\n        MapSheetHost(")
        val overlay = screen.indexOf("MountModeOverlay(ui = ui")
        assertTrue("MapSheetHost стоїть поза Box мапи", host0 in 0 until overlay)
        assertEquals(-1, screen.indexOf("\n    MapSheetHost("))
    }

    private fun source(name: String): String =
        listOf("src/main/java", "feature-stream/src/main/java")
            .map { File(it, "local/phantom/companion/feature/stream/ui/map/$name") }
            .first { it.exists() }
            .readText()
}
