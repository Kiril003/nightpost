package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.wifi.WifiManager
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import local.phantom.companion.core.net.peer.PeerCallLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Розмова мусить пережити замкнений екран і вбитий рендерер WebView (10.10):
 * служба тримає Wi‑Fi і процесор, WebView на час розмови не віддає пріоритет,
 * а нова сторінка дізнається від моста, яку розмову підняти.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallKeepTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun grant() {
        PeerCallService.running = false
        PeerCallService.window.startRefused()
        PeerCallNotifications.current = null
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
    }

    private fun startedCallService(): Intent? {
        while (true) {
            val next = shadowOf(app).nextStartedService ?: return null
            if (next.component?.className == PeerCallService::class.java.name) return next
        }
    }

    @Test
    fun `розмова тримає Wi-Fi і процесор, кінець розмови їх віддає`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking("c1", "peer", 1L), "Марта")
        val controller = Robolectric.buildService(PeerCallService::class.java, startedCallService()!!)
            .create().startCommand(0, 1)
        val keep = controller.get().keep
        assertTrue("Wi‑Fi засинає посеред розмови", keep.wifiHeld)
        assertTrue("процесор засинає посеред розмови", keep.cpuHeld)
        controller.destroy()
        assertFalse("Wi‑Fi лишився тримати батарею після розмови", keep.wifiHeld)
        assertFalse("процесор лишився тримати батарею після розмови", keep.cpuHeld)
    }

    @Test
    fun `зупинка служби замків не бере`() {
        val stop = PeerCallService.intent(app, false).setAction(PeerCallService.ACTION_STOP)
        val service = Robolectric.buildService(PeerCallService::class.java, stop).create().startCommand(0, 1).get()
        assertFalse(service.keep.wifiHeld)
        assertFalse(service.keep.cpuHeld)
    }

    @Test
    fun `Android 14 не знає HIGH_PERF — там лише LOW_LATENCY`() {
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, CallKeep.wifiMode(34))
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, CallKeep.wifiMode(33))
    }

    private class FakeRenderer(override var priority: Int, override var waived: Boolean) : CallKeep.Renderer {
        override fun set(priority: Int, waived: Boolean) {
            this.priority = priority
            this.waived = waived
        }
    }

    @Test
    fun `на час розмови рендерер важливий і невидимим, після — як було`() {
        val r = FakeRenderer(WebView.RENDERER_PRIORITY_BOUND, true)
        CallKeep.renderer(r, call = true)
        assertEquals(WebView.RENDERER_PRIORITY_IMPORTANT, r.priority)
        assertFalse(r.waived)
        CallKeep.renderer(r, call = true)
        CallKeep.renderer(r, call = false)
        assertEquals(WebView.RENDERER_PRIORITY_BOUND, r.priority)
        assertTrue(r.waived)
    }

    @Test
    fun `нова сторінка дізнається лише про розмову, що йде`() {
        val back = CallKeep.resumeOf(PeerCallLine.Line.Talking("c_live", "peer9", 1L), video = true) { "Марта" }
        assertNotNull(back)
        assertEquals("c_live", back!!.getString("call_id"))
        assertEquals("peer9", back.getString("peer_id"))
        assertEquals("Марта", back.getString("name"))
        assertEquals("video", back.getString("media"))
        assertNull(CallKeep.resumeOf(PeerCallLine.Line.Dialing("c", "p", 1L), false) { "" })
        assertNull(CallKeep.resumeOf(PeerCallLine.Line.Ringing("c", "p", 1L), false) { "" })
        assertNull(CallKeep.resumeOf(PeerCallLine.Line.Idle, false) { "" })
    }

    private fun mono(rel: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-stream").isDirectory }
        return File(root, rel).readText()
    }

    @Test
    fun `досяжність - міст, хост і WebView справді це кличуть`() {
        val bridge = mono("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
        val ready = bridge.substringAfter("seg.getOrNull(2) == \"ready\"").take(400)
        assertTrue("«готовий» не каже вебу про живу розмову", "CallKeep.resumeOf(" in ready && "\"resume\"" in ready)

        val host = mono("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt")
        val make = host.substringAfter("internal fun createMessengerWeb(")
        val gone = make.substringAfter("override fun onRenderProcessGone(", "")
        assertTrue("смерть рендерера не перехоплено — Android вбиває весь застосунок", gone.isNotEmpty())
        assertTrue("onRenderProcessGone не каже «впорались»", "return true" in gone.substringBefore("override fun"))
        assertTrue("хост не пересоздає WebView після смерті рендерера", "createMessengerWeb(" in host.substringAfter("web.gone"))
        assertTrue("пріоритет рендерера не йде за дзвінком", "CallKeep.renderer(" in host)
    }
}
