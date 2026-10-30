package local.phantom.companion.feature.stream.ui.map.dossier

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaceSheetNoRatingTest {
    @Test
    fun placeSheetDoesNotRenderRating() {
        val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/PlaceDossierSheet.kt").readText()
        assertFalse(src.contains("placeRatingLine("))
        assertFalse(src.contains("place_rating_label"))
    }
}
