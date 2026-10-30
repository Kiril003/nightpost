package local.phantom.companion.ui.messenger

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessengerScrollbarsTest {

    @Test
    fun `messenger web draws no native scrollbars`() {
        val view = WebView(ApplicationProvider.getApplicationContext<Application>())
        assertTrue(view.isVerticalScrollBarEnabled)
        hideMessengerScrollbars(view)
        assertFalse(view.isVerticalScrollBarEnabled)
        assertFalse(view.isHorizontalScrollBarEnabled)
    }

    @Test
    fun `messenger web factory hides them`() {
        val mono = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "phantom-os/src/frontend/src").isDirectory }
        val host = File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue("hideMessengerScrollbars(this)" in host.substringAfter("internal fun createMessengerWeb("))
    }
}
