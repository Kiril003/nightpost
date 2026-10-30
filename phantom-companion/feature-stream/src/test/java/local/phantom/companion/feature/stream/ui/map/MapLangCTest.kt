package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.FALLBACK_TITLE
import local.phantom.companion.feature.stream.ui.map.dossier.instantDossier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MapLangCTest {

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun labelsAreEnglishUnderEn() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Point on the map", FALLBACK_TITLE)
        assertEquals("Point on the map", instantDossier(DossierTarget(50.45, 30.52)).title)
        assertEquals("NEAR STATION", MapText.s(R.string.mapl_c_station_nearby_title))
    }

    @Test
    @Config(sdk = [34], qualifiers = "uk")
    fun labelsAreUkrainianUnderUk() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Точка на мапі", FALLBACK_TITLE)
        assertEquals("Точка на мапі", instantDossier(DossierTarget(50.45, 30.52)).title)
        assertEquals("ПОРУЧ ЗІ СТАНЦІЄЮ", MapText.s(R.string.mapl_c_station_nearby_title))
    }
}
