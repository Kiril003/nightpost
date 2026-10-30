package local.phantom.companion.service

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.country.UkrainePack
import local.phantom.companion.data.MapPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirAlertGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zoneBefore = TimeZone.getDefault()
    private val localeBefore = Locale.getDefault()

    @Before
    fun clean() {
        CountryPackRegistry.reset()
    }

    @After
    fun restore() {
        CountryPackRegistry.reset()
        TimeZone.setDefault(zoneBefore)
        Locale.setDefault(localeBefore)
    }

    private fun phoneIn(iso: String): MapPreferences {
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setNetworkCountryIso(iso)
        shadowOf(tm).setSimCountryIso(iso)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        return MapPreferences(context)
    }

    @Test
    fun czSim_ukrainianUi_alertsOff() {
        RuntimeEnvironment.setQualifiers("uk")
        val prefs = phoneIn("cz")
        prefs.setUkraineAlerts(true)
        var asked = false

        assertNotEquals("UA", prefs.rememberedCountry.value)
        assertFalse(AirAlertGate.available(context, prefs))
        assertFalse(AirAlertGate.on(context, prefs))
        assertFalse(AirAlertGate.skyDeclared(context, prefs) { asked = true; true })
        assertFalse(asked)
    }

    @Test
    fun uaNetwork_englishUi_alertsAvailable() {
        RuntimeEnvironment.setQualifiers("en")
        val prefs = phoneIn("ua")

        assertEquals("UA", prefs.rememberedCountry.value)
        assertTrue(AirAlertGate.available(context, prefs))
        assertFalse(AirAlertGate.skyDeclared(context, prefs) { true })
        prefs.setUkraineAlerts(true)
        assertTrue(AirAlertGate.on(context, prefs))
        assertTrue(AirAlertGate.skyDeclared(context, prefs) { true })
    }

    @Test
    fun `український телефон українською типово мовчить, а після згоди читає небо`() {
        RuntimeEnvironment.setQualifiers("uk")
        val prefs = phoneIn("ua")

        assertEquals("UA", prefs.rememberedCountry.value)
        assertTrue(AirAlertGate.available(context, prefs))
        assertFalse(AirAlertGate.skyDeclared(context, prefs) { true })
        prefs.setUkraineAlerts(true)
        assertTrue(AirAlertGate.skyDeclared(context, prefs) { true })
    }

    @Test
    fun `українець за кордоном з вибраним паком UA вмикає сам`() {
        RuntimeEnvironment.setQualifiers("uk")
        val abroad = phoneIn("cz")
        assertFalse(AirAlertGate.skyDeclared(context, abroad) { true })
        abroad.setCountryOverride(UkrainePack.id)
        abroad.setUkraineAlerts(true)

        assertTrue(AirAlertGate.skyDeclared(context, abroad) { true })
    }
}
