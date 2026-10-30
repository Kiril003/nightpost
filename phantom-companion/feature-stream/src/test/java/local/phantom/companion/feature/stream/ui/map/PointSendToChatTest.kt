package local.phantom.companion.feature.stream.ui.map

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** T10: кинута на мапі точка йде в розмову тим самим аркушем «Кому надіслати», що й місце. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class PointSendToChatTest {

    @Test
    fun `a dropped point offers To a chat and the tap reaches the caller`() {
        var sent = 0
        val shown = render(onSendToChat = { sent++ }, tap = "To a chat")
        assertTrue(shown.toString(), "To a chat" in shown)
        assertEquals(1, sent)
        assertFalse("без месенджера рядка немає", "To a chat" in render(onSendToChat = null))
    }

    @Test
    fun `the point keeps the short name it has in the sheet`() {
        val t = pointSendTarget(49.8 to 24.0, "вулиця Городоцька, 12, Львів, 79000, Україна")
        assertEquals(49.8, t.lat, 0.0)
        assertEquals(24.0, t.lon, 0.0)
        assertEquals("вулиця Городоцька, 12", t.nameHint)
        assertNull(pointSendTarget(49.8 to 24.0, null).nameHint)
    }

    @Test
    fun `SheetHost wires the point sheet into the same send sheet`() {
        val host = File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/SheetHost.kt").readText()
        val call = host.substringAfter("            PointActionsSheet(").substringBefore("\n            )\n")
        val wire = call.substringAfter("onSendToChat = {", "").substringBefore("},")
        assertTrue(call, wire.contains("openPlaceSend(viewModel.people, pointSendTarget(dp, ui.droppedAddress)"))
        assertTrue(wire, wire.contains("ui.droppedPoint = null"))
        val dossier = host.substringAfter("onSendToChat = {").substringBefore("ui.dossierTarget = null")
        assertTrue(dossier, dossier.contains("openPlaceSend("))
    }

    @Test
    fun `To a chat opens the messenger sheet with groups and Saved, people sheet only without it`() {
        val port = ChatPort(accepts = true)
        var fallback: local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget? = null
        openPlaceSend(port, pointSendTarget(49.8 to 24.0, "вулиця Городоцька, 12, Львів"), "Place") { fallback = it }
        assertEquals(listOf(Triple(49.8, 24.0, "вулиця Городоцька, 12")), port.offered)
        assertNull("аркуш людей не відкривається поверх аркуша розмов", fallback)

        val none = ChatPort(accepts = false)
        openPlaceSend(none, pointSendTarget(49.8 to 24.0, null), "Place") { fallback = it }
        assertEquals(listOf(Triple(49.8, 24.0, "Place")), none.offered)
        assertEquals(49.8, fallback!!.lat, 0.0)
        assertFalse(local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort.None.sendPlaceToChat(1.0, 2.0, "x"))
    }

    private class ChatPort(private val accepts: Boolean) :
        local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort by local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort.None {
        val offered = mutableListOf<Triple<Double, Double, String>>()
        override fun sendPlaceToChat(lat: Double, lon: Double, name: String): Boolean {
            offered += Triple(lat, lon, name)
            return accepts
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun render(onSendToChat: (() -> Unit)?, tap: String? = null): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = SunriseWarm) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    PointActionsSheet(
                        lat = 49.8,
                        lon = 24.0,
                        address = "вулиця Городоцька, 12, Львів",
                        here = null,
                        accentColor = LocalPhantomTheme.current.primary,
                        onWhatHere = {},
                        onRoute = {},
                        onRouteFrom = {},
                        onVia = null,
                        onSaveMark = { _, _ -> },
                        onShare = {},
                        onOpenExternal = {},
                        onSendToChat = onSendToChat,
                    )
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        val all = nodes(activity)
        if (tap != null) {
            all.filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && subtree(n).any { tap in words(it) } }
                .minByOrNull { subtree(it).size }
                ?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
        val out = all.flatMap(::words).filter { it.isNotBlank() }
        controller.pause().stop().destroy()
        return out
    }

    private fun words(n: SemanticsNode): List<String> =
        listOfNotNull(n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }) +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun subtree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::subtree)

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> =
        findRoot(activity.window.decorView)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::subtree).orEmpty()

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}
