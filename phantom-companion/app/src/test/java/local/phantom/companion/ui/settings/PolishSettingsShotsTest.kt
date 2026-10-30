package local.phantom.companion.ui.settings

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.ai.RoutingStrategy
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.Segmented
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Кадри 390 для T68–T70, T55/T56: «Помічник», «Голос», «Інструменти» вночі; лягають у build/polish-settings/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = PhantomApp::class, qualifiers = "uk-w390dp-h1500dp-xhdpi")
class PolishSettingsShotsTest {

    @Test fun assistantNight() = shot("assistant-night", AmberNight) {
        Column {
            RowGroup(caption = "Порядок") {
                BrainOrderRows(RoutingStrategy.LocalFirst, {}, keyStored = true)
            }
        }
    }
    @Test fun voiceNight() = shot("voice-night", AmberNight) { VoiceSettingsScreen(onBack = {}) }
    @Test fun toolsNight() = shot("tools-night", AmberNight) {
        val labels = listOf(Decision.ASK to "Питати", Decision.ALWAYS to "Дозволено", Decision.NEVER to "Заборонено")
        Column {
            RowGroup(caption = "Для всіх") { Segmented<Decision?>(options = labels, selected = null, onSelect = {}) }
            RowGroup(caption = "Пристрій") {
                ToolRow("Камера", Decision.ASK) {}
                RowDivider()
                ToolRow("Мікрофон", Decision.ALWAYS) {}
            }
        }
    }

    private fun shot(name: String, spec: PhantomThemeSpec, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        CoroutineScope(context + Job()).launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(local.phantom.companion.core.design.motion.LocalMotionScale provides 0f) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(
            activity.window.decorView.width.coerceAtLeast(1),
            activity.window.decorView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
        val out = File("build/polish-settings").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bg = bitmap.getPixel(2, 2)
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bg) inked++
        }
        assertTrue("$name: nothing drew", inked > 50)
    }
}
