package local.phantom.companion.packaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.Distribution
import local.phantom.companion.feature.precision.BlackBoxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoreDietTest {

    @Test
    fun `store asks nothing it cannot use`() {
        assertFalse(Distribution.batteryAsk)
        assertFalse(Distribution.airAlertWatch)
        assertFalse(Distribution.alertFullScreen)
        assertFalse(Distribution.blackBox)
        assertFalse(Distribution.plainKeys)
    }

    @Test
    fun `store alert switch promises nothing in the background`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(local.phantom.companion.R.string.settings_ua_alerts_body_map, local.phantom.companion.ui.settings.uaAlertsBody())
        for (tag in listOf("en", "uk", "cs")) {
            val conf = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            val body = context.createConfigurationContext(conf).getString(local.phantom.companion.ui.settings.uaAlertsBody()).lowercase()
            for (word in listOf("alert", "сповіщ", "тривог", "upozorn", "poplach")) assertFalse("$tag: $body", word in body)
        }
    }

    @Test
    fun `store records no black box`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.resources.getBoolean(local.phantom.companion.feature.precision.R.bool.precision_black_box))
        val writer = BlackBoxWriter(context)
        writer.write("""{"test":1}""")
        assertNull(writer.currentFile)
        assertTrue(writer.listRecordings().isEmpty())
    }

    @Test
    fun `only the call raises a full screen in store`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("не знайшов корінь дерева")
        val hits = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "src/main/java") }
            .filter { it.isDirectory }
            .flatMap { src -> src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { "setFullScreenIntent(" in it.readText() }
        assertEquals(
            setOf("PeerCallNotifications.kt", "AirAlertNotifications.kt"),
            hits.map { it.name }.toSet(),
        )
        val alertText = hits.first { it.name == "AirAlertNotifications.kt" }.readText()
        assertTrue("if (!Distribution.alertFullScreen) return null" in alertText)
    }
}
