package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MapLangETest {

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun `batch E labels resolve to English under en locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Air alert", ThreatType.BALLISTIC.meta.label)
        assertEquals("just now", durationShortUk(1_000_000L, 1_010_000L))
        assertEquals("tram", packStopModeWord(TransitMode.TRAM.name))
        assertEquals("with shelter", packStopComfort(mapOf("shelter" to "yes")))
    }

    @Test
    @Config(sdk = [34], qualifiers = "uk")
    fun `batch E labels resolve to Ukrainian under uk locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals("Повітряна тривога", ThreatType.BALLISTIC.meta.label)
        assertEquals("щойно", durationShortUk(1_000_000L, 1_010_000L))
        assertEquals("трамвай", packStopModeWord(TransitMode.TRAM.name))
        assertEquals("з навісом", packStopComfort(mapOf("shelter" to "yes")))
    }
}
