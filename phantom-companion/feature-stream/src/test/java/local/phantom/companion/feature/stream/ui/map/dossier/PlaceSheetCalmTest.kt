package local.phantom.companion.feature.stream.ui.map.dossier

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSheetCalmTest {

    private val map = "src/main/java/local/phantom/companion/feature/stream/ui/map"

    private fun body(file: String, fn: String): String {
        val src = File("$map/$file").readText()
        val start = src.indexOf(fn)
        assertTrue("$fn зник", start >= 0)
        return src.substring(start, src.indexOf("\n}\n", start))
    }

    @Test
    fun `secondary tiles carry no fill, so the route stays the one heavy action`() {
        assertFalse(body("MapSheetParts.kt", "fun RowScope.MapActionTile(").contains("mapRaised"))
    }

    @Test
    fun `the place sheet has one fold, the overview is an action`() {
        assertFalse(body("dossier/PlaceDossierSheet.kt", "private fun DossierBriefingSection(").contains("DossierDisclosureChevron"))
    }

    @Test
    fun `no dossier jargon in the overview words`() {
        for ((dir, word) in listOf("values-uk" to "досьє", "values" to "dossier")) {
            val xml = File("src/main/res/$dir/strings_dossier.xml").readText()
            Regex("<string name=\"dossier_overview[a-z_]*\">([^<]*)</string>").findAll(xml).forEach {
                assertFalse("$dir ${it.value}", it.groupValues[1].lowercase().contains(word))
            }
        }
    }

    @Test
    fun `a separator wraps together with the segment after it, never dangles at a line end`() {
        val peak = body("dossier/PlaceDossierSheet.kt", "private fun DossierPeakItem(")
        assertTrue(peak.contains("SheetNote(\"·\"") && peak.contains("segment.text"))
    }
}
