package local.phantom.companion.feature.stream.ui.map.peers

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerSeeRow
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class WhoSeesMeSheetTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun code(rel: String): String =
        File(repoRoot(), rel).readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    private fun string(dir: String, name: String): String? {
        val xml = File(repoRoot(), "feature-stream/src/main/res/$dir/strings_peercard.xml").readText()
        return Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
    }

    @Test
    fun `empty rows with onAddPerson renders Add person button and invokes callback`() {
        var clicked = 0
        val withAdd = renderSheet(
            rows = emptyList(),
            onAddPerson = { clicked++ },
            clickContentDescription = "Add person",
        )
        assertTrue(withAdd.toString(), "No people yet" in withAdd)
        assertFalse(withAdd.toString(), withAdd.any { it.contains("Add someone in People") })
        assertTrue(withAdd.toString(), "Add person" in withAdd)
        assertEquals(1, clicked)

        val withoutAdd = renderSheet(
            rows = emptyList(),
            onAddPerson = null,
        )
        assertTrue(withoutAdd.toString(), "No people yet" in withoutAdd)
        assertFalse(withoutAdd.toString(), "Add person" in withoutAdd)
    }

    @Test
    fun `only not-yet-proven rows still offer Add person`() {
        val rows = listOf(PeerSeeRow("b", "Taras", PeerPrecision.EXACT, false))
        val shown = renderSheet(rows = rows, onAddPerson = {})
        assertTrue(shown.toString(), "Add person" in shown)
        val eligible = renderSheet(rows = listOf(PeerSeeRow("a", "Oksana", PeerPrecision.EXACT, true)), onAddPerson = {})
        assertFalse(eligible.toString(), "Add person" in eligible)
    }

    @Test
    fun `SheetHost passes onAddPerson and closes whoSeesOpen`() {
        val host = code("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/SheetHost.kt")
        val block = host.substringAfter("WhoSeesMeSheet(").substringBefore("\n        }")
        assertTrue("SheetHost must pass onAddPerson to WhoSeesMeSheet", block.contains("onAddPerson ="))
        val addBlock = block.substringAfter("onAddPerson =")
        assertTrue("onAddPerson must close whoSeesOpen", addBlock.contains("ui.whoSeesOpen = false"))
        assertTrue("onAddPerson must invoke viewModel.onAddPerson()", addBlock.contains("viewModel.onAddPerson()"))
    }

    @Test
    fun `peer_where_vanish is rendered with danger style after a divider`() {
        val sheet = code("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/peers/WhoSeesMeSheet.kt")
        val vanishBlock = sheet.substringAfter("PhantomDivider()", "").substringBefore("if (confirm)")
        assertTrue("PhantomDivider must precede vanish action", vanishBlock.contains("MapSecondaryAction("))
        assertTrue("confirm uses the same danger ink", sheet.substringAfter("if (confirm)").contains("LocalPhantomColors.current.danger"))
        assertTrue("vanish action must use peer_where_vanish", vanishBlock.contains("R.string.peer_where_vanish"))
        assertTrue("vanish action must render with danger = true", vanishBlock.contains("danger = true"))

        val rows = listOf(PeerSeeRow("a", "Oksana", PeerPrecision.EXACT, true))
        val shown = renderSheet(rows = rows, onAddPerson = {})
        assertTrue(shown.toString(), "Stop sharing with everyone" in shown)
        assertFalse(shown.toString(), "Disappear" in shown)
    }

    @Test
    fun `strings_peercard carries updated empty and vanish labels in en, uk, cs`() {
        assertEquals("No people yet", string("values", "peer_where_empty"))
        assertEquals("Ще нікого немає", string("values-uk", "peer_where_empty"))
        assertEquals("Zatím nikdo", string("values-cs", "peer_where_empty"))

        assertEquals("Add person", string("values", "peer_where_add_person"))
        assertEquals("Додати людину", string("values-uk", "peer_where_add_person"))
        assertEquals("Přidat osobu", string("values-cs", "peer_where_add_person"))

        assertEquals("Stop sharing with everyone", string("values", "peer_where_vanish"))
        assertEquals("Припинити ділитися з усіма", string("values-uk", "peer_where_vanish"))
        assertEquals("Přestat sdílet se všemi", string("values-cs", "peer_where_vanish"))

        assertEquals("Everyone loses your location", string("values", "peer_where_vanish_confirm_body"))
        assertEquals("Усі втратять ваше місце", string("values-uk", "peer_where_vanish_confirm_body"))
        assertEquals("Všichni ztratí vaši polohu", string("values-cs", "peer_where_vanish_confirm_body"))
    }

    private fun renderSheet(
        rows: List<PeerSeeRow>,
        onAddPerson: (() -> Unit)?,
        clickContentDescription: String? = null,
    ): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = SunriseWarm) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    WhoSeesMeSheet(
                        rows = rows,
                        accentColor = LocalPhantomTheme.current.primary,
                        onSet = { _, _ -> },
                        onVanish = {},
                        onAddPerson = onAddPerson,
                    )
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        val allNodes = nodes(activity)
        if (clickContentDescription != null) {
            allNodes.firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(clickContentDescription) == true
            }?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
        val out = allNodes.flatMap { n ->
            listOfNotNull(
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text },
            ) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
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
