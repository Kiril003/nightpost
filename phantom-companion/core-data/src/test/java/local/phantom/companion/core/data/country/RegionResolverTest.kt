package local.phantom.companion.core.data.country

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * Країна без жодного дозволу: мережа, SIM, пояс, мова — саме в такому порядку.
 * Чех з англійською мовою телефона в Празі — не українець і не американець.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionResolverTest {

    private val zoneBefore = TimeZone.getDefault()
    private val localeBefore = Locale.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(zoneBefore)
        Locale.setDefault(localeBefore)
    }

    @Test
    fun `Крим — Україна, навіть з окупаційною мережею і SIM`() {
        val got = RegionResolver.resolve(
            RegionSignals(networkIso = "ru", simIso = "ru", timeZoneId = "Europe/Simferopol", localeCountry = "RU"),
        )
        assertEquals("UA", got)
        assertEquals("UA", RegionResolver.zoneCountry("Europe/Simferopol"))
    }

    @Test
    fun `мережа головніша за SIM, пояс і мову`() {
        val got = RegionResolver.resolve(
            RegionSignals(networkIso = "cz", simIso = "ua", timeZoneId = "Europe/Kyiv", localeCountry = "US"),
        )
        assertEquals("CZ", got)
    }

    @Test
    fun `без мережі (режим польоту) бере SIM`() {
        val got = RegionResolver.resolve(
            RegionSignals(networkIso = "", simIso = "pl", timeZoneId = "Europe/Kyiv", localeCountry = "US"),
        )
        assertEquals("PL", got)
    }

    @Test
    fun `планшет без SIM бере пояс, який поставила мережа`() {
        assertEquals("UA", RegionResolver.resolve(RegionSignals(timeZoneId = "Europe/Kyiv", localeCountry = "US")))
        assertEquals("UA", RegionResolver.resolve(RegionSignals(timeZoneId = "Europe/Kiev", localeCountry = "US")))
    }

    @Test
    fun `пояс без країни (UTC) віддає слово мові`() {
        assertEquals("SK", RegionResolver.resolve(RegionSignals(timeZoneId = "Etc/UTC", localeCountry = "sk")))
    }

    @Test
    fun `сміття замість коду не країна`() {
        assertNull(RegionResolver.resolve(RegionSignals(networkIso = "zz", simIso = "  ", localeCountry = "")))
    }

    @Test
    fun `пак за країною - Україна, Європа або глобус`() {
        assertEquals(UkrainePack.id, RegionResolver.packId("UA"))
        assertEquals(EuropePack.id, RegionResolver.packId("CZ"))
        assertNull(RegionResolver.packId("US"))
        assertNull(RegionResolver.packId(null))
    }

    @Test
    fun `читає мережу телефона без жодного дозволу`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setNetworkCountryIso("ua")
        shadowOf(tm).setSimCountryIso("de")
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)

        assertEquals("UA", RegionResolver.of(context))
    }

    @Test
    fun `без мережі й SIM телефон питає пояс`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setNetworkCountryIso("")
        shadowOf(tm).setSimCountryIso("")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
        Locale.setDefault(Locale.US)

        assertEquals("CZ", RegionResolver.of(context))
    }
}
