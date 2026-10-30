package local.phantom.companion.feature.stream.ui.map.around

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w390dp-h844dp-xxhdpi")
class PeekPlacesStripTest {

    @Test fun aLongPlaceNameIsReadInFullOnTwoLines() {
        val long = "Pizzerie na kolejích Studentské"
        val places = PeekPlaces.Shown(
            listOf(
                PeekRow(AroundPlace(long, 50.08, 14.42, category = "amenity", subcategory = "restaurant"), 100.0),
                PeekRow(AroundPlace("Café Louvre", 50.08, 14.42, category = "amenity", subcategory = "cafe"), 120.0),
            ),
            total = 2,
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = SunriseWarm) { PeekPlacesStrip(places, onPlace = {}) }
        }
        repeat(20) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        try {
            val root = composeRoot(activity.window.decorView) ?: error("no compose root")
            val nodes = all(root.semanticsOwner.unmergedRootSemanticsNode)
            for (name in listOf(long, "Café Louvre")) {
                val node = nodes.single { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == name } == true }
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                assertFalse("«$name» is cut", layout.multiParagraph.didExceedMaxLines || layout.isLineEllipsized(layout.lineCount - 1))
                assertTrue("«$name» takes ${layout.lineCount} lines", layout.lineCount <= 2)
            }
            val heights = nodes.filter { n -> n.config.getOrNull(SemanticsProperties.Role) != null }.map { it.size.height }.distinct()
            assertTrue("cards differ in height: $heights", heights.size == 1)
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
}
