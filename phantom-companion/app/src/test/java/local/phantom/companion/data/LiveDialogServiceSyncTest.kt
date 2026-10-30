package local.phantom.companion.data

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.service.LiveDialogService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The microphone service starts only with RECORD_AUDIO in hand: on Android 14 a
 * `microphone` foreground service without it is refused and the process dies.
 * Unmuting and every app resume reach the start, not just the live switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveDialogServiceSyncTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun unmuteWithLiveDialogueOn(): AppContainer {
        val container = AppContainer(app)
        container.voicePrefs.setLiveDialogEnabled(true)
        container.voicePrefs.setMicMuted(true)
        container.syncLiveDialogService()
        shadowOf(app).clearStartedServices()
        container.voicePrefs.setMicMuted(false)
        container.syncLiveDialogService()
        return container
    }

    @Test
    fun `unmuting with live dialogue on and no microphone permission starts no microphone service`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        unmuteWithLiveDialogueOn()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `with the permission the same unmute starts it`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        unmuteWithLiveDialogueOn()
        assertEquals(LiveDialogService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }
}
