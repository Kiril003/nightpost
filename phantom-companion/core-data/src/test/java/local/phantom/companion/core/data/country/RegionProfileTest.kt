package local.phantom.companion.core.data.country

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionProfileTest {

    @Test
    fun defaultsByCountry() {
        val ua = RegionProfile.of(
            signals = RegionSignals(networkIso = "ua"),
            overridePack = null,
            uiLanguage = "en",
        )
        assertEquals("UA", ua.country)
        assertTrue(ua.alertsUa)
        assertEquals("UAH", ua.currency)
        assertEquals("102", ua.emergency)
        assertTrue(ua.metric)

        val cz = RegionProfile.of(
            signals = RegionSignals(simIso = "cz"),
            overridePack = null,
            uiLanguage = "uk",
        )
        assertEquals("CZ", cz.country)
        assertFalse(cz.alertsUa)
        assertEquals("CZK", cz.currency)
        assertEquals("112", cz.emergency)
        assertTrue(cz.metric)

        val us = RegionProfile.of(
            signals = RegionSignals(networkIso = "us"),
            overridePack = null,
            uiLanguage = "en",
        )
        assertEquals("US", us.country)
        assertFalse(us.alertsUa)
        assertEquals("USD", us.currency)
        assertEquals("911", us.emergency)
        assertFalse(us.metric)

        val de = RegionProfile.of(
            signals = RegionSignals(networkIso = "de"),
            overridePack = null,
            uiLanguage = "en",
        )
        assertEquals("DE", de.country)
        assertFalse(de.alertsUa)
        assertEquals("EUR", de.currency)
        assertEquals("112", de.emergency)
        assertTrue(de.metric)

        val unknownEn = RegionProfile.of(
            signals = RegionSignals(),
            overridePack = null,
            uiLanguage = "en",
            zoneCountry = { null },
        )
        assertNull(unknownEn.country)
        assertFalse(unknownEn.alertsUa)
        assertEquals("", unknownEn.currency)
        assertEquals("112", unknownEn.emergency)
        assertTrue(unknownEn.metric)

        val unknownUk = RegionProfile.of(
            signals = RegionSignals(),
            overridePack = null,
            uiLanguage = "uk",
            zoneCountry = { null },
        )
        assertNull(unknownUk.country)
        assertTrue(unknownUk.alertsUa)
        assertEquals("", unknownUk.currency)
        assertEquals("112", unknownUk.emergency)
        assertTrue(unknownUk.metric)
    }

    @Test
    fun overrideUaWins() {
        val overriddenUa = RegionProfile.of(
            signals = RegionSignals(networkIso = "cz"),
            overridePack = UkrainePack.id,
            uiLanguage = "cs",
        )
        assertEquals("UA", overriddenUa.country)
        assertTrue(overriddenUa.alertsUa)
        assertEquals("UAH", overriddenUa.currency)
        assertEquals("102", overriddenUa.emergency)

        val euKept = RegionProfile.of(
            signals = RegionSignals(networkIso = "cz"),
            overridePack = EuropePack.id,
            uiLanguage = "uk",
        )
        assertEquals("CZ", euKept.country)
        assertFalse(euKept.alertsUa)

        val euDropsNonEu = RegionProfile.of(
            signals = RegionSignals(networkIso = "ua"),
            overridePack = EuropePack.id,
            uiLanguage = "en",
        )
        assertNull(euDropsNonEu.country)
        assertFalse(euDropsNonEu.alertsUa)

        val euUkUi = RegionProfile.of(
            signals = RegionSignals(networkIso = "ua"),
            overridePack = EuropePack.id,
            uiLanguage = "uk",
        )
        assertFalse(euUkUi.alertsUa)
    }
}
