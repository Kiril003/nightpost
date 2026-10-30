package local.phantom.companion.feature.stream.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Дозвіл на місце є, а перемикач «Місце» у шторці вимкнено: мапа мовчала «немає
 * фіксу», а веб чекав 15 с. Тепер це окреме питання з шляхом у налаштування.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationAccessTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun servicesSwitch(on: Boolean) =
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(on)

    private fun access(): LocationAccess {
        lateinit var out: LocationAccess
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            out = rememberLocationAccess(
                PermissionReason(
                    local.phantom.companion.core.design.R.drawable.lc_locate,
                    R.string.perm_location_map_title,
                    R.string.perm_location_map_body,
                ),
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
        return out
    }

    @Test
    fun `перемикач місця вимкнено - система про це знає`() {
        servicesSwitch(false)
        assertFalse(locationServicesOn(context))
        servicesSwitch(true)
        assertTrue(locationServicesOn(context))
    }

    @Test
    fun `дозвіл є, а місце вимкнено - дія не біжить мовчки, а питання вилітає`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        servicesSwitch(false)
        val access = access()
        var ran = 0

        assertTrue("приблизного досить для дозволу", access.granted())
        assertFalse(access.servicesOn())
        access.requestThen { ran++ }
        assertEquals("без перемикача фіксу не буде - дія не виконується", 0, ran)

        servicesSwitch(true)
        access.requestThen { ran++ }
        assertEquals(1, ran)
    }
}
