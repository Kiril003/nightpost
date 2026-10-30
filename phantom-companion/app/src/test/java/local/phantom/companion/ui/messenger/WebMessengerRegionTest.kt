package local.phantom.companion.ui.messenger

import local.phantom.companion.core.data.country.RegionProfile
import local.phantom.companion.core.data.country.RegionSignals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebMessengerRegionTest {

    @Test
    fun czProfile_body() {
        assertTrue(WebMessengerRegion.claims("GET", listOf("messenger", "region")))
        val profile = RegionProfile.of(
            signals = RegionSignals(networkIso = "cz"),
            overridePack = null,
            uiLanguage = "cs",
        )
        val json = WebMessengerRegion.body(profile)
        assertEquals("CZ", json.getString("country"))
        assertEquals("CZK", json.getString("currency"))
        assertEquals("112", json.getString("emergency"))
        assertTrue(json.getBoolean("metric"))

        val unknown = RegionProfile.of(
            signals = RegionSignals(),
            overridePack = null,
            uiLanguage = "en",
            zoneCountry = { null },
        )
        val unknownJson = WebMessengerRegion.body(unknown)
        assertTrue(unknownJson.isNull("country") || unknownJson.opt("country") == JSONObject.NULL)
        assertEquals("", unknownJson.getString("currency"))
        assertEquals("112", unknownJson.getString("emergency"))
        assertTrue(unknownJson.getBoolean("metric"))
    }

    @Test
    fun postNotClaimed() {
        assertFalse(WebMessengerRegion.claims("POST", listOf("messenger", "region")))
        assertFalse(WebMessengerRegion.claims("GET", listOf("messenger", "region", "extra")))
        assertFalse(WebMessengerRegion.claims("GET", listOf("messenger", "other")))
    }
}
