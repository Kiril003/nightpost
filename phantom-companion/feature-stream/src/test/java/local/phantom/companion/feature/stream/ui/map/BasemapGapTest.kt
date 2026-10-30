package local.phantom.companion.feature.stream.ui.map

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import local.phantom.companion.core.design.motion.LocalMotionScale
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk-w390dp-h844dp-xhdpi")
class BasemapGapTest {

    @Test
    fun `decision - style failure beats coverage, offline uncovered asks to save, covered is silent`() {
        assertEquals(BasemapGap.MAP, basemapGap(groundNotSavedHere = false, styleFailed = true))
        assertEquals(BasemapGap.MAP, basemapGap(groundNotSavedHere = true, styleFailed = true))
        assertEquals(BasemapGap.AREA, basemapGap(groundNotSavedHere = true, styleFailed = false))
        assertEquals(BasemapGap.NONE, basemapGap(groundNotSavedHere = false, styleFailed = false))
    }

    @Test
    fun `style failed with no region - empty state shows and the save action opens the panel`() {
        var opened = 0
        val texts = render(BasemapGap.MAP, onSave = { opened++ }, click = "Зберегти область")
        assertTrue(texts.toString(), "Мапа не збережена" in texts)
        assertTrue(texts.toString(), "Зберегти область" in texts)
        assertEquals(1, opened)
        val pill = render(BasemapGap.NONE, onSave = {})
        assertTrue(pill.toString(), pill.isNotEmpty() && texts.containsAll(pill))
    }

    @Test
    fun `area not saved offline - line keeps its words and gains the action`() {
        val texts = render(BasemapGap.AREA, onSave = {})
        assertTrue(texts.toString(), "Ця ділянка ще не збережена офлайн" in texts)
        assertTrue(texts.toString(), "Зберегти область" in texts)
    }

    @Test
    fun `ground visible - nothing is drawn`() {
        val texts = render(BasemapGap.NONE, onSave = {})
        assertTrue(texts.toString(), texts.none { it == "Зберегти область" || it == "Мапа не збережена" })
    }

    @Test
    fun `chrome wires the banner to the offline panel and the ground is rechecked on network change`() {
        val dir = "src/main/java/local/phantom/companion/feature/stream/ui/map"
        val chrome = File("$dir/MapChrome.kt").readText()
        assertTrue(chrome.contains("gap = basemapGap(ui.groundNotSavedHere, ui.basemapStyleFailed)"))
        assertTrue(Regex("""onSave = \{[^}]*ui\.offlineOpen = true""").containsMatchIn(chrome))
        val effects = File("$dir/MapCanvasEffects.kt").readText()
        val netEffect = effects.substringAfter("DisposableEffect(ui.mapRef) {").substringBefore("DisposableEffect(")
        assertTrue("мережевий слухач має жити лише від мапи", netEffect.contains("registerDefaultNetworkCallback(net, main)"))
        assertTrue(effects.contains("unregisterNetworkCallback(net)"))
        assertTrue(effects.contains("addOnDidFailLoadingMapListener"))
        assertTrue(!effects.contains("override fun onCapabilitiesChanged"))
    }

    private fun render(gap: BasemapGap, onSave: () -> Unit, click: String? = null): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = SunriseWarm) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    Column {
                        AlertBanner(
                            ownOblast = "Київська область",
                            ownState = null,
                            activeCount = 1,
                            verdict = SkyVerdict.QUIET,
                            onOpen = {},
                        )
                        GroundNotSavedBanner(gap = gap, onSave = onSave)
                    }
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        val all = nodes(activity)
        if (click != null) {
            all.first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == click } == true }
                .config[SemanticsActions.OnClick].action!!.invoke()
        }
        val out = all.flatMap { n ->
            listOfNotNull(n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }) +
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.filter { it.isNotBlank() }
        controller.pause().stop().destroy()
        return out
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val owner = findRoot(activity.window.decorView)?.semanticsOwner ?: return emptyList()
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(owner.unmergedRootSemanticsNode)
        return out
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}
