package local.phantom.companion.feature.stream.ui.map

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import javax.xml.parsers.DocumentBuilderFactory
import java.io.File
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.feature.stream.domain.OfflinePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk-w390dp-h844dp-xhdpi")
class OfflinePanelCountTest {

    private val place = OfflinePlaceEntry(
        placeId = "ua-46", nameUa = "Львівська область", minZoom = 5, maxZoom = 14,
        plannedChunks = 6, doneChunks = 6, completedBytes = 1_200L * 1024 * 1024,
        percent = 100, complete = true, downloading = false, failed = false, styleUrl = null,
    )
    private val corridor = RegionInfo(
        id = 7, name = "Київ", completedResourceCount = 40, requiredResourceCount = 100,
        completedResourceSizeBytes = 80L * 1024 * 1024, isComplete = false, downloadState = 1,
        kind = PackKind.Corridor, routeLabel = "Київ",
    )

    @Test
    fun `header counts the same maps as the all-maps row, not raw chunks`() {
        val texts = render(OfflineMapsGroups(listOf(place), listOf(corridor), emptyList()))
        assertTrue(texts.toString(), texts.any { it.contains("2 мапи") })
        assertTrue(texts.toString(), texts.none { it.contains("пакет") })
        assertTrue(texts.toString(), texts.any { it.contains("На телефоні: 1 · завантажується: 1") })
    }

    @Test
    fun `route corridor size carries its unit`() {
        val texts = render(OfflineMapsGroups(listOf(place), emptyList(), emptyList()))
        assertTrue(texts.toString(), texts.any { it.startsWith("1240 км² · зум 10–14") })
    }

    @Test
    fun `panel takes no separate pack count, the production caller cannot drift`() {
        val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/OfflineTerritoriesPanel.kt").readText()
        assertTrue(!src.contains("packCount"))
    }

    @Test
    fun `a route label never needs a case ending - uk and cs take the place name as is`() {
        val res = File("src/main/res")
        listOf("values-uk", "values-cs").forEach { dir ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            val value = (0 until nodes.length).map { nodes.item(it) as Element }
                .first { it.getAttribute("name") == "map_offline_corridor_to" }.textContent
            assertTrue("$dir: $value", !Regex("""(до|do)\s+%1\${'$'}s""").containsMatchIn(value))
        }
    }

    private fun render(groups: OfflineMapsGroups): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = SunriseWarm) {
                OfflinePanelContent(
                    offer = OfflinePacks.Offer.Save,
                    storeBytes = 1_840L * 1024 * 1024,
                    groups = groups,
                    usableBytes = 0L,
                    routeCorridorKm2 = 1240.0,
                    routeCorridorBoxes = emptyList(),
                    routeLabel = "Стрий",
                    onSaveCorridor = {},
                    onSaveView = {},
                    onDismiss = {},
                    onOpenAll = {},
                    places = { OfflinePlacesDoor(otherStyle = false, onOpen = {}) },
                    pcLayers = {},
                )
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        val out = nodes(activity).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        controller.pause().stop().destroy()
        assertEquals(false, out.isEmpty())
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
