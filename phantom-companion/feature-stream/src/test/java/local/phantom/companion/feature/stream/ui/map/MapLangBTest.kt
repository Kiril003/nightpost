package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.dossier.DossierCategory
import local.phantom.companion.feature.stream.ui.map.dossier.formatDistance
import local.phantom.companion.feature.stream.ui.map.dossier.kindLabelUk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangBTest {

    @Test
    @Config(qualifiers = "en")
    fun `batch B labels speak English under en locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Library", DossierCategory.LIBRARY.labelUk)
        assertEquals("bus stop", kindLabelUk(mapOf("highway" to "bus_stop")))
        assertEquals("280 m", formatDistance(280.0))
    }

    @Test
    @Config(qualifiers = "uk")
    fun `batch B labels speak Ukrainian under uk locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Бібліотека", DossierCategory.LIBRARY.labelUk)
        assertEquals("зупинка автобуса", kindLabelUk(mapOf("highway" to "bus_stop")))
        assertEquals("280 м", formatDistance(280.0))
    }
}
