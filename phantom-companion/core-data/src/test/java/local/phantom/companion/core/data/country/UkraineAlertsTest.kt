package local.phantom.companion.core.data.country

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UkraineAlertsTest {

    private val uaProfile = RegionProfile.of(
        signals = RegionSignals(networkIso = "ua"),
        overridePack = null,
        uiLanguage = "en",
    )

    private val czProfile = RegionProfile.of(
        signals = RegionSignals(simIso = "cz"),
        overridePack = null,
        uiLanguage = "uk",
    )

    @Test
    fun uaUnchanged() {
        assertTrue(UkraineAlerts.available(uaProfile))
        assertFalse(UkraineAlerts.on(choice = null, profile = uaProfile))
        assertTrue(UkraineAlerts.on(choice = true, profile = uaProfile))
        assertFalse(UkraineAlerts.on(choice = false, profile = uaProfile))
    }

    @Test
    fun `cz profile with uk language keeps alerts off`() {
        assertFalse(UkraineAlerts.available(czProfile))
        assertFalse(UkraineAlerts.on(choice = true, profile = czProfile))
    }
}
