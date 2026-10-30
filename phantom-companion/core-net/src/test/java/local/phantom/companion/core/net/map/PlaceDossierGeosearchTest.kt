package local.phantom.companion.core.net.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceDossierGeosearchTest {

    @Test
    fun `геопошук Вікіпедії отримує точку з точністю до 0,001°, не точну`() {
        val url = PlaceDossierClient().geosearchUrl(50.4501234, 30.5234567, 400)
        assertTrue(url, "gscoord=50.450|30.523&" in url)
        assertFalse(url, "50.4501" in url || "30.5234" in url)
        assertTrue(url.startsWith("https://uk.wikipedia.org/w/api.php?action=query&list=geosearch&gsradius=400"))
    }
}
