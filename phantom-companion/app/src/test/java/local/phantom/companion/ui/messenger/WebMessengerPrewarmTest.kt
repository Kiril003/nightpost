package local.phantom.companion.ui.messenger

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMessengerPrewarmTest {

    @After
    fun clear() = WebMessengerPrewarm.drop()

    @Test
    fun `хук бере кадр, а не чекає його - біжить одразу після малювання`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = activity.window.decorView
        var ran = false
        WebMessengerPrewarm.afterNextDraw(view) { ran = true }
        assertFalse("хук біг до кадру", ran)
        view.viewTreeObserver.dispatchOnPreDraw()
        assertFalse("хук біг усередині кадру й затримав його", ran)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("хук не біг після кадру", ran)
    }

    @Test
    fun `WebView народжується і вантажить бандл з хука, без домівки`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = activity.window.decorView
        WebMessengerPrewarm.afterNextDraw(view) {
            WebMessengerPrewarm.start(activity) {
                MessengerWeb(
                    WebView(activity).apply { loadUrl(WebMessengerAssets.ENTRY) },
                    MutableStateFlow(false),
                    MutableStateFlow(false),
                )
            }
        }
        view.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()

        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertNull("чужа активність забрала WebView", WebMessengerPrewarm.take(other))
        val web = WebMessengerPrewarm.take(activity)
        assertNotNull("прогрітого WebView нема", web)
        assertEquals(WebMessengerAssets.ENTRY, shadowOf(web!!.view).lastLoadedUrl)
        assertNull("WebView віддано двічі", WebMessengerPrewarm.take(activity))
    }
}
