package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.net.peer.PeerCallLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTelecomManager.CallRequestMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncomingCallTelecomTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val telecom: TelecomManager get() = app.getSystemService(TelecomManager::class.java)
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val ringing = PeerCallLine.Line.Ringing("call_9f31ac2b", "cccc3333dddd4444", 1L)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(telecom).setCallRequestMode(CallRequestMode.MANUAL)
        IncomingCallTelecom.live.clear()
    }

    @After
    fun tearDown() {
        IncomingCallTelecom.reset()
    }

    private fun posted() = shadowOf(nm).getNotification(PeerCallNotifications.ID)

    @Test
    fun `дзвінок віддається системі через власний обліковий запис`() {
        assertTrue(IncomingCallTelecom.announce(app, ringing, "Марта"))

        val accounts = shadowOf(telecom).selfManagedPhoneAccounts
        assertEquals(listOf(IncomingCallTelecom.handle(app)), accounts)
        val call = shadowOf(telecom).onlyIncomingCall
        assertEquals(IncomingCallTelecom.handle(app), call.phoneAccount)
        assertEquals(ringing.callId, call.extras.getString(IncomingCallTelecom.EXTRA_CALL))
        assertEquals(ringing.peerId, call.extras.getString(IncomingCallTelecom.EXTRA_PEER))
        assertEquals("Марта", call.extras.getString(IncomingCallTelecom.EXTRA_NAME))
    }

    @Test
    fun `той самий дзвінок системі віддається один раз`() {
        assertTrue(IncomingCallTelecom.announce(app, ringing, "Марта"))
        assertTrue(IncomingCallTelecom.announce(app, ringing, "Марта"))
        assertEquals(1, shadowOf(telecom).allIncomingCalls.size)
    }

    @Test
    fun `show віддає дзвінок системі і не клеїть другу плашку`() {
        PeerCallNotifications.show(app, ringing, "Марта")

        assertEquals(1, shadowOf(telecom).allIncomingCalls.size)
        assertNull("плашка йде з onShowIncomingCallUi, а не ще раз звідси", posted())
    }

    @Test
    fun `без системного телекому дзвонить по-старому`() {
        IncomingCallTelecom.telecomOf = { null }
        assertEquals(false, IncomingCallTelecom.announce(app, ringing, "Марта"))

        PeerCallNotifications.show(app, ringing, "Марта")
        assertNotNull(posted())
    }

    @Test
    fun `тиха година дзвінка системі не віддає`() {
        PeerCallNotifications.show(app, ringing, "Марта", quiet = true)
        assertEquals(0, shadowOf(telecom).allIncomingCalls.size)
        assertNull(posted())
    }

    private fun requestFor(extras: Bundle) = ConnectionRequest(IncomingCallTelecom.handle(app), null, extras)

    private fun ringExtras() = Bundle().apply {
        putString(IncomingCallTelecom.EXTRA_CALL, ringing.callId)
        putString(IncomingCallTelecom.EXTRA_PEER, ringing.peerId)
        putString(IncomingCallTelecom.EXTRA_NAME, "Марта")
        putLong(IncomingCallTelecom.EXTRA_SINCE, ringing.sinceMs)
    }

    private fun service() = Robolectric.buildService(PeerCallConnectionService::class.java).create().get()

    @Test
    fun `система відмовила - дзвінок усе одно дзвонить плашкою`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        assertNull(posted())

        service().onCreateIncomingConnectionFailed(IncomingCallTelecom.handle(app), requestFor(ringExtras()))

        val n = posted()
        assertNotNull("людина не почує дзвінка, якщо система відмовила", n)
        assertNotNull(n.fullScreenIntent)
    }

    @Test
    fun `система мовчить - після дедлайну дзвонить плашка`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IncomingCallTelecom.deadlineMs - 1))
        assertNull(posted())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNotNull("дзвінок пропав у системі без звуку", posted())
    }

    @Test
    fun `система показала плашку - дедлайн нічого не додає`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        IncomingCallTelecom.shown(ringing.callId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IncomingCallTelecom.deadlineMs))
        assertNull(posted())
    }

    @Test
    fun `дзвінок скінчився до дедлайну - плашка не з'являється`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        IncomingCallTelecom.follow(PeerCallLine.Line.Over(ringing.callId, ringing.peerId, PeerCallLine.MISSED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IncomingCallTelecom.deadlineMs))
        assertNull(posted())
    }

    @Test
    fun `розмова почалась до дедлайну - дедлайн не вертає дзвінок поверх розмови`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        IncomingCallTelecom.follow(PeerCallLine.Line.Talking(ringing.callId, ringing.peerId, 2L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IncomingCallTelecom.deadlineMs))
        assertNull(posted())
    }

    @Test
    fun `дзвінок скінчився до приходу з'єднання від системи - з'єднання скасоване`() {
        PeerCallNotifications.show(app, ringing, "Марта")
        PeerCallNotifications.show(app, PeerCallLine.Line.Over(ringing.callId, ringing.peerId, PeerCallLine.MISSED), "Марта")
        shadowOf(telecom).setConnectionService(service())

        val connection = shadowOf(telecom).allowIncomingCall(shadowOf(telecom).onlyIncomingCall)
        assertEquals(DisconnectCause.CANCELED, connection.disconnectCause.code)
        assertTrue(IncomingCallTelecom.live.isEmpty())
        assertNull(posted())
    }

    @Test
    fun `додатки дзвінка читаються і пласкими, і вкладеними`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        val flat = service().onCreateIncomingConnection(IncomingCallTelecom.handle(app), requestFor(ringExtras()))
        val nested = service().onCreateIncomingConnection(
            IncomingCallTelecom.handle(app),
            requestFor(Bundle().apply { putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, ringExtras()) }),
        )
        assertEquals(ringing.callId, (flat as PeerCallConnection).line.callId)
        assertEquals(ringing.peerId, (nested as PeerCallConnection).line.peerId)
        assertEquals("Марта", nested.name)
    }

    @Test
    fun `без відомостей про дзвінок з'єднання не народжується`() {
        val c = service().onCreateIncomingConnection(IncomingCallTelecom.handle(app), requestFor(Bundle()))
        assertEquals(DisconnectCause.ERROR, c.disconnectCause.code)
    }

    @Test
    fun `повний круг - система створює з'єднання і просить плашку`() {
        PeerCallNotifications.show(app, ringing, "Марта")
        shadowOf(telecom).setConnectionService(service())

        val connection = shadowOf(telecom).allowIncomingCall(shadowOf(telecom).onlyIncomingCall)
        assertTrue(connection is PeerCallConnection)
        assertEquals(Connection.STATE_RINGING, connection.state)

        connection.onShowIncomingCallUi()
        assertNotNull(posted())
        assertNotNull(posted().fullScreenIntent)
    }

    @Test
    fun `лінія розмови веде з'єднання до кінця`() {
        PeerCallNotifications.show(app, ringing, "Марта")
        shadowOf(telecom).setConnectionService(service())
        val connection = shadowOf(telecom).allowIncomingCall(shadowOf(telecom).onlyIncomingCall)

        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(ringing.callId, ringing.peerId, 2L), "Марта")
        assertEquals(Connection.STATE_ACTIVE, connection.state)

        PeerCallNotifications.show(app, PeerCallLine.Line.Over(ringing.callId, ringing.peerId, PeerCallLine.HUNG_UP), "Марта")
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(DisconnectCause.REMOTE, connection.disconnectCause.code)
        assertTrue("з'єднання лишилось у реєстрі після кінця", IncomingCallTelecom.live.isEmpty())
    }

    @Test
    fun `служба оголошена в маніфесті так, як її шукає система`() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, PeerCallConnectionService::class.java), 0)
        assertEquals("android.permission.BIND_TELECOM_CONNECTION_SERVICE", info.permission)
        assertTrue(info.exported)
        val found = app.packageManager.queryIntentServices(Intent(ConnectionService.SERVICE_INTERFACE), 0)
        assertTrue(found.any { it.serviceInfo.name == PeerCallConnectionService::class.java.name })
    }

    @Test
    fun `обліковий запис самокерований`() {
        IncomingCallTelecom.announce(app, ringing, "Марта")
        val account = shadowOf(telecom).allPhoneAccounts.single()
        assertTrue(account.capabilities and PhoneAccount.CAPABILITY_SELF_MANAGED != 0)
    }
}
