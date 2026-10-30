package local.phantom.companion.core.solana

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
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
import local.phantom.companion.core.design.kit.AmountSign
import local.phantom.companion.core.design.kit.AmountText
import local.phantom.companion.core.design.kit.ListRow
import local.phantom.companion.core.design.kit.ListRowHeight
import local.phantom.companion.core.design.kit.SheetFrame
import local.phantom.companion.core.design.kit.StatusChip
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class LookKitSemanticsTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private var colors: PhantomColors? = null

    @After fun close() {
        ShadowDialog.getLatestDialog()?.dismiss()
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun show(content: @Composable () -> Unit): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val context = Dispatchers.Main + clock
        val r = Recomposer(context)
        recomposer = r
        CoroutineScope(context + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    colors = LocalPhantomColors.current
                    content()
                }
            }
        }
        pump()
        return c.get()
    }

    private fun pump() = repeat(20) {
        Snapshot.sendApplyNotifications()
        frameNanos += 50_000_000L
        clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
    private fun nodes(view: View) = roots(view).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun screen(a: ComponentActivity) = nodes(a.window.decorView)
    private fun dialog() = nodes(ShadowDialog.getLatestDialog()!!.window!!.decorView)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun tagged(ns: List<SemanticsNode>, tag: String): SemanticsNode {
        val hit = ns.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        assertNotNull("no node tagged $tag; words=${ns.flatMap { it.words() }}", hit)
        return hit!!
    }

    private fun withText(ns: List<SemanticsNode>, text: String): SemanticsNode {
        val hit = ns.firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == text } }
        assertNotNull("no text node $text; words=${ns.flatMap { it.words() }}", hit)
        return hit!!
    }

    private fun px(a: ComponentActivity, dp: Int) = (dp * a.resources.displayMetrics.density).toInt()

    @Test fun listRowsKeepTheirHeights() {
        val a = show {
            Column {
                ListRow("One", Modifier.testTag("r1"), ListRowHeight.One)
                ListRow("Two", Modifier.testTag("r2"), ListRowHeight.Two, sub = "second line")
                ListRow("Chat", Modifier.testTag("r3"), ListRowHeight.Chat, sub = "second line")
            }
        }
        val ns = screen(a)
        assertEquals(px(a, 56), tagged(ns, "r1").size.height)
        assertEquals(px(a, 64), tagged(ns, "r2").size.height)
        assertEquals(px(a, 72), tagged(ns, "r3").size.height)
    }

    @Test fun listRowShowsItsSlotsAndClicks() {
        var taps = 0
        val a = show {
            ListRow(
                title = "Olena",
                sub = "14:02",
                leading = { Text("L") },
                trailing = { Text("T") },
                onClick = { taps++ },
            )
        }
        val ns = screen(a)
        val w = ns.flatMap { it.words() }
        assertTrue(w.toString(), w.containsAll(listOf("Olena", "14:02", "L", "T")))
        val row = ns.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        row.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, taps)
    }

    @Test fun aDisabledListRowSaysSo() {
        val a = show { ListRow("Wait", enabled = false, onClick = {}) }
        assertTrue(screen(a).any { SemanticsProperties.Disabled in it.config })
    }

    @Test fun aRowWithoutAnActionIsNotClickable() {
        val a = show { ListRow("Plain") }
        assertTrue(screen(a).flatMap { it.words() }.contains("Plain"))
        assertFalse(screen(a).any { it.config.getOrNull(SemanticsActions.OnClick) != null })
    }

    @Test fun aStatusChipIs24HighAndSilent() {
        val a = show { StatusChip("Pending", Modifier.testTag("chip")) }
        val ns = screen(a)
        assertEquals(px(a, 24), tagged(ns, "chip").size.height)
        assertTrue(ns.flatMap { it.words() }.contains("Pending"))
        assertFalse(ns.any { it.config.getOrNull(SemanticsActions.OnClick) != null })
    }

    private fun layoutOf(n: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    @Test fun amountsUseTheRealMinusPlusInSuccessAndTabularFigures() {
        val a = show {
            Column {
                AmountText("1.00", AmountSign.Out)
                AmountText("20.00", AmountSign.In)
                AmountText("5.00", AmountSign.None)
            }
        }
        val ns = screen(a)
        val out = layoutOf(withText(ns, "−1.00"))
        val inc = layoutOf(withText(ns, "+20.00"))
        val none = layoutOf(withText(ns, "5.00"))
        val c = colors!!
        assertEquals("tnum", out.layoutInput.style.fontFeatureSettings)
        assertEquals("outgoing is ink, not red", c.text, out.layoutInput.style.color)
        assertEquals("incoming is success", c.success, inc.layoutInput.style.color)
        assertEquals(c.text, none.layoutInput.style.color)
    }

    @Test fun theSheetIsBottomAlignedFullWidthWithATitleAFooterAndAClose() {
        var closed = 0
        show {
            SheetFrame(title = "Send USDC", closeLabel = "Close", onClose = { closed++ }, footer = { Text("FOOTER") }) { Text("BODY") }
        }
        val d = ShadowDialog.getLatestDialog()
        assertNotNull("SheetFrame opens no dialog", d)
        val attrs = d!!.window!!.attributes
        assertEquals("bottom gravity", Gravity.BOTTOM, attrs.gravity and Gravity.BOTTOM)
        assertEquals("full width", WindowManager.LayoutParams.MATCH_PARENT, attrs.width)
        val w = dialog().flatMap { it.words() }
        assertTrue(w.toString(), w.containsAll(listOf("Send USDC", "BODY", "FOOTER", "Close")))
        tagged(dialog(), "look-sheet-handle")
        val close = dialog().first { it.config.getOrNull(SemanticsActions.OnClick) != null && "Close" in it.words() }
        close.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, closed)
    }
}
