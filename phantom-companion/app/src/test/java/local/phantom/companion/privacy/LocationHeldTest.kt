package local.phantom.companion.privacy

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Брама місця приймає «приблизне». Рахувати фікс «Ділюсь положенням» мусить
 * так само, інакше картка пише «відмовлено», а «Дозволити» нічого не робить.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationHeldTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `приблизне місце - це дозвіл на фікс`() {
        assertFalse(LocationGrants.held(context))
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(LocationGrants.held(context))
    }

    @Test
    fun `точне місце теж дозвіл`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(LocationGrants.held(context))
    }
}
