package local.phantom.companion.ui.messenger

import android.content.Context
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WebHapticsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    private class RecordingView(context: Context) : View(context) {
        val calls = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            calls.add(feedbackConstant)
            return true
        }
    }

    @Test
    @Config(sdk = [34])
    fun `sdk 34 maps threshold release confirm reject`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        WebHaptics.attach(view)
        assertTrue(WebHaptics.perform("threshold"))
        assertTrue(WebHaptics.perform("RELEASE"))
        assertTrue(WebHaptics.perform("confirm"))
        assertTrue(WebHaptics.perform("reject"))
        assertTrue(WebHaptics.perform("tick"))
        assertTrue(WebHaptics.perform("tap"))
        assertTrue(WebHaptics.perform("long"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
                HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
                HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.REJECT,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.LONG_PRESS,
            ),
            view.calls,
        )
    }

    @Test
    @Config(sdk = [29])
    fun `sdk 29 falls back for threshold release confirm reject`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        WebHaptics.attach(view)
        assertTrue(WebHaptics.perform("threshold"))
        assertTrue(WebHaptics.perform("release"))
        assertTrue(WebHaptics.perform("confirm"))
        assertTrue(WebHaptics.perform("reject"))
        assertTrue(WebHaptics.perform("long"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(
                HapticFeedbackConstants.CONTEXT_CLICK,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.LONG_PRESS,
            ),
            view.calls,
        )
    }

    @Test
    fun `unknown kind returns false and does not call view`() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        WebHaptics.attach(view)
        assertNull(WebHaptics.constantFor("buzz"))
        assertFalse(WebHaptics.perform("buzz"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.calls.isEmpty())
    }

    @Test
    fun `detached view returns false`() {
        var view: RecordingView? = RecordingView(ApplicationProvider.getApplicationContext())
        WebHaptics.attach(view!!)
        view = null
        System.gc()
        Runtime.getRuntime().gc()
        assertFalse(WebHaptics.perform("tap"))
    }

    @Test
    fun `bridge and host wire WebHaptics and overscroll`() {
        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("if (!WebHaptics.perform(type)) triggerHaptic(type)"))
        val host = src("ui/messenger/WebMessengerHost.kt")
        assertTrue(host.contains("overScrollMode = View.OVER_SCROLL_NEVER"))
        assertTrue(host.contains("WebHaptics.attach(this)"))
    }
}
