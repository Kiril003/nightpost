package local.phantom.companion.core.data.country

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * Свіже встановлення, дозволу на місце немає. Країну, з якою стартує реєстр,
 * дає [RegionResolver] (так робить MapPreferences, коли памʼяті ще нема) — і
 * лише українському телефону дістається стрічка неба України.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreshInstallSkyTest {

    private val zoneBefore = TimeZone.getDefault()
    private val localeBefore = Locale.getDefault()

    @After
    fun restore() {
        CountryPackRegistry.reset()
        TimeZone.setDefault(zoneBefore)
        Locale.setDefault(localeBefore)
    }

    private fun coldStartOn(networkIso: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = context.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setNetworkCountryIso(networkIso)
        shadowOf(tm).setSimCountryIso(networkIso)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        CountryPackRegistry.reset()
        CountryPackRegistry.restore(RegionResolver.packId(RegionResolver.of(context)), manual = null)
    }

    @Test
    fun `чеський телефон без місця не опитує небо України`() {
        coldStartOn("cz")
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
        assertEquals(EuropePack.id, CountryPackRegistry.current()?.id)
    }

    @Test
    fun `американський телефон відкриває глобус, а не Київ`() {
        coldStartOn("us")
        assertNull(CountryPackRegistry.current())
        assertEquals(WORLD_VIEW, CountryPackRegistry.home())
    }

    @Test
    fun `український телефон без місця має небо України`() {
        coldStartOn("ua")
        assertNotNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
    }
}
