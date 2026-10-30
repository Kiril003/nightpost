package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.net.peer.PeerCallLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerCallNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ringing = PeerCallLine.Line.Ringing("call_9f31ac2b", "cccc3333dddd4444", 1L)

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        IncomingCallTelecom.telecomOf = { null }
    }

    @After
    fun reset() = IncomingCallTelecom.reset()

    private fun Notification.insistent() = flags and Notification.FLAG_INSISTENT != 0

    private fun Notification.settingsAction() = actions.orEmpty().firstOrNull {
        shadowOf(it.actionIntent).savedIntent.action == Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
    }

    @Test
    fun `вхідний дзвінок дзвонить, доки не візьмуть, і підіймає екран`() {
        val n = PeerCallNotifications.ringingNotification(app, ringing, "Марта", fullScreenAllowed = true)
        assertTrue("одне тенькання замість дзвінка: немає FLAG_INSISTENT", n.insistent())
        assertNotNull(n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertNull(n.settingsAction())
    }

    @Test
    fun `без права на весь екран дзвонить плашкою і веде туди, де це право дають`() {
        val n = PeerCallNotifications.ringingNotification(app, ringing, "Марта", fullScreenAllowed = false)
        assertTrue(n.insistent())
        assertNotNull("CallStyle без повноекранного наміру система не публікує взагалі", n.fullScreenIntent)
        val action = n.settingsAction()
        assertNotNull("людина не має як дати право дзвонити на весь екран", action)
        assertEquals("package:${app.packageName}", shadowOf(action!!.actionIntent).savedIntent.dataString)
    }

    @Test
    fun `show без системного телекому кладе в шторку саме дзвінок, що не замовкає`() {
        PeerCallNotifications.show(app, ringing, "Марта")
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerCallNotifications.ID)
        assertNotNull(posted)
        assertTrue(posted.insistent())
        assertNotNull(posted.fullScreenIntent)
    }
}
