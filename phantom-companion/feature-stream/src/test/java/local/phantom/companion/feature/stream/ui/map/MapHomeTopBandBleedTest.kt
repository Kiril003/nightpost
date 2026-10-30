package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Власник 18.10 на A52: «Кава» різалась до «ава», а правий чип обривався
 * за 12 dp від краю — рядок чипів сидів у полях колонки пошуку. Чипи мусять
 * прокручуватися на всю ширину екрана, а в спокої перший стоїть на 16 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class MapHomeTopBandBleedTest {

    @Test fun chipsBleedToTheScreenEdgeAndRestAt16dp() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = SunriseWarm) {
                Box(Modifier.fillMaxSize()) {
                    MapHomeTopBand(showSearch = true, onSearch = {}, initials = "K") {
                        MapHomeCategoryBar(
                            chips = HOME_CATEGORY_CHIPS,
                            activeLabel = null,
                            searchAreaVisible = false,
                            onSelectChip = { _, _ -> },
                            onSearchArea = {},
                        )
                    }
                }
            }
        }
        repeat(20) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        try {
            val root = composeRoot(activity.window.decorView) ?: error("no compose root")
            val density = activity.resources.displayMetrics.density
            val screen = (root as View).width.toFloat()
            val row = all(root.semanticsOwner.rootSemanticsNode)
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == TAG_MAP_HOME_CHIPS }
            val clip = row.boundsInRoot
            assertEquals("chip row fills the top card", screen - 2 * 12 * density, clip.width, 1f)
            val first = row.children.minBy { it.positionInRoot.x }
            val left = first.positionInRoot.x / density
            assertTrue("first chip rests at $left dp, wants 16 dp", left >= 15.9f && left <= 16.1f)
            shot(activity)
        } finally {
            scope.cancel()
        }
    }

    private fun composeRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { all(it) }

    private fun shot(activity: ComponentActivity) {
        val decor = activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val top = Bitmap.createBitmap(full, 0, 0, full.width, (full.height / 4).coerceAtLeast(1))
        val out = File("build/map-look").apply { mkdirs() }
        File(out, "top-band.png").outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
