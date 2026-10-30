package local.phantom.companion.feature.stream.ui.map.live

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * K5: live location survives the screen. While at least one share is open a
 * `location` foreground service runs with an ongoing "Sharing · Stop" notice;
 * Stop on the notice ends every share; the last share ending stops the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveShareServiceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `notice exists only while someone is shared with`() {
        assertNull(LiveShareForeground.notice(emptyList()))
        assertEquals(listOf("Ana", "Petr"), LiveShareForeground.notice(listOf("Ana", "Petr"))?.names)
    }

    @Test
    fun `first share starts the service, the last one ending stops it`() = runTest {
        val names = MutableStateFlow<List<String>>(emptyList())
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        LiveShareForeground.bind(app, scope, names, onStop = {})
        scope.advanceUntilIdle()
        assertNull("nobody shared: no service", shadowOf(app).nextStartedService)

        names.value = listOf("Ana")
        scope.advanceUntilIdle()
        val started = shadowOf(app).nextStartedService
        assertNotNull("sharing must start the foreground service", started)
        assertEquals(LiveShareService::class.java.name, started!!.component?.className)
        assertEquals(arrayListOf("Ana"), started.getStringArrayListExtra(LiveShareForeground.EXTRA_NAMES))

        names.value = emptyList()
        scope.advanceUntilIdle()
        val stopped = shadowOf(app).nextStoppedService
        assertNotNull("the last share ending must stop the service", stopped)
        assertEquals(LiveShareService::class.java.name, stopped!!.component?.className)
        scope.cancel()
    }

    @Test
    fun `service runs in the foreground with an ongoing notice and one Stop action`() {
        val intent = Intent(app, LiveShareService::class.java)
            .putStringArrayListExtra(LiveShareForeground.EXTRA_NAMES, arrayListOf("Ana", "Petr"))
        val controller = Robolectric.buildService(LiveShareService::class.java, intent).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        val n: Notification? = shadow.lastForegroundNotification
        assertNotNull("startForeground was not called", n)
        assertEquals(LiveShareForeground.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertTrue("the notice must be ongoing", n!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(LiveShareForeground.CHANNEL_ID, n.channelId)
        assertEquals(app.getString(R.string.live_share_title), n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(text, text.contains("Ana") && text.contains("Petr"))
        assertEquals(1, n.actions?.size ?: 0)
        assertEquals(app.getString(R.string.live_share_stop), n.actions[0].title.toString())
    }

    @Test
    fun `Stop on the notice ends every share and the service`() = runTest {
        var stops = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        LiveShareForeground.bind(app, scope, MutableStateFlow(listOf("Ana")), onStop = { stops++ })
        scope.advanceUntilIdle()
        val stop = Intent(app, LiveShareService::class.java).setAction(LiveShareForeground.ACTION_STOP)
        val controller = Robolectric.buildService(LiveShareService::class.java, stop).create().startCommand(0, 1)
        assertEquals(1, stops)
        assertTrue("the service must stop itself", shadowOf(controller.get()).isStoppedBySelf)
        scope.cancel()
    }
}
