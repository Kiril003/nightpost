package local.phantom.companion.core.solana

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTheme
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
class WalletSheetLookTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val olena = SolanaContact("olena", "Olena", SolanaKey.of(ByteArray(32) { (it * 11 + 3).toByte() }))
    private val taras = SolanaContact("taras", "Taras", SolanaKey.of(ByteArray(32) { (it * 13 + 5).toByte() }))
    private val ready = WalletState(
        kind = WalletKind.BUILT_IN,
        address = me,
        builtIn = me,
        backedUp = true,
        cluster = Cluster.DEVNET,
        balances = mapOf(Coin.USDC to 12_500_000L, Coin.EURC to 0L, Coin.SOL to 1_500_000_000L),
    )
    private val door = object : WalletDoor {
        override val hasScreenLock = true
        override fun host(titleRes: Int, detail: String?): WalletHost = error("not signing")
        override fun secure(on: Boolean) = Unit
    }

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    @After fun close() {
        ShadowDialog.getLatestDialog()?.dismiss()
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun open(
        state: WalletState = ready,
        opened: WalletActivity.Opened = WalletActivity.Opened(peer = "olena", sealHash = null, sealLabel = ""),
        contacts: List<SolanaContact> = listOf(olena, taras),
    ): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val context = Dispatchers.Main + clock
        val r = Recomposer(context)
        recomposer = r
        CoroutineScope(context + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    WalletBody(
                        door = door,
                        state = state,
                        open = opened,
                        onBack = null,
                        people = { contacts },
                        points = { null },
                        activity = { _, _ -> emptyList() },
                        known = { emptyList() },
                        split = {},
                        refreshBalances = {},
                        connect = { WalletOutcome.Declined },
                        recoveryWords = { emptyList() },
                        markBackedUp = {},
                    )
                }
            }
        }
        pump()
        return c.get()
    }

    private fun pump() = repeat(30) {
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
    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun screen(a: ComponentActivity) = nodes(a.window.decorView)
    private fun dialog() = nodes(ShadowDialog.getLatestDialog()!!.window!!.decorView)
    private fun words(ns: List<SemanticsNode>) = ns.flatMap { it.words() }

    private fun tap(ns: List<SemanticsNode>, text: (String) -> Boolean) {
        val hit = ns.filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { m -> m.words().any(text) } }
            .minByOrNull { all(it).size } ?: error("no button")
        hit.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
    }

    private fun src(name: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "core-solana/src/main/java").isDirectory }
        return File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/$name").readText()
    }

    private fun body(text: String, fn: String): String {
        val at = text.indexOf("fun $fn(")
        assertTrue("fun $fn not found", at >= 0)
        val next = text.indexOf("\n@Composable", at)
        return if (next < 0) text.substring(at) else text.substring(at, next)
    }

    @Test fun theTransferSheetIsBottomAlignedAtFullWidth() {
        open()
        val d = ShadowDialog.getLatestDialog()
        assertNotNull("no transfer sheet", d)
        val attrs = d!!.window!!.attributes
        assertEquals(Gravity.BOTTOM, attrs.gravity and Gravity.BOTTOM)
        val screen = d.context.resources.displayMetrics.widthPixels
        assertTrue("full width: ${attrs.width} of $screen", attrs.width == WindowManager.LayoutParams.MATCH_PARENT || attrs.width == screen)
        assertTrue("sheet handle", dialog().any { it.config.getOrNull(SemanticsProperties.TestTag) == "look-sheet-handle" })
    }

    @Test fun theSheetDropsTheDevnetSentenceAndCancelAndShowsATestChipAndAClose() {
        open()
        val w = words(dialog())
        assertFalse("devnet sentence: $w", w.contains("Test money — not real"))
        assertFalse("Cancel: $w", w.contains("Cancel"))
        assertTrue("Test chip: $w", w.contains("Test"))
        assertTrue("close: $w", w.contains("Close"))
        assertTrue("one primary: $w", w.any { it.startsWith("Send") })
    }

    @Test fun aMainnetSheetHasNoTestChip() {
        open(ready.copy(cluster = Cluster.MAINNET))
        assertFalse(words(dialog()).contains("Test"))
    }

    @Test fun closeDismissesTheSheet() {
        open()
        tap(dialog()) { it == "Close" }
        val d = ShadowDialog.getLatestDialog()
        assertTrue("sheet still showing", d == null || !d.isShowing)
    }

    @Test fun theRecipientIsARowOf64WithAnAvatarAndTheName() {
        val a = open()
        val row = dialog().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "pay-recipient" }
        assertNotNull("no recipient row", row)
        assertEquals((64 * a.resources.displayMetrics.density).toInt(), row!!.size.height)
        assertTrue(words(all(row)).contains("Olena"))
    }

    @Test fun theAmountIsCentredInTheDisplayStyleWithTabularFigures() {
        open()
        val field = dialog().first { it.config.getOrNull(SemanticsActions.SetText) != null && "Amount" in it.words() }
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        field.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        val style = out.single().layoutInput.style
        assertEquals(PhantomText.display.fontSize, style.fontSize)
        assertEquals("tnum", style.fontFeatureSettings)
        assertEquals(TextAlign.Center, style.textAlign)
    }

    @Test fun theNoteRowIs56AndItsFocusBorderIsNeverAmber() {
        val a = open()
        val note = dialog().first { it.config.getOrNull(SemanticsActions.SetText) != null && "Note" in it.words() }
        assertTrue("note row under 56dp", note.size.height >= (56 * a.resources.displayMetrics.density).toInt())
        val kit = src("WalletKit.kt")
        val noteBody = body(kit, "NoteField")
        assertFalse("amber border on the note", noteBody.contains("accentLine"))
        assertTrue("neutral focus border", noteBody.contains("c.text2"))
        assertFalse("the amount field is borderless", body(kit, "AmountField").contains(".border("))
    }

    @Test fun pickerRowsNameThePersonAndNeverShowAnAddress() {
        val a = open(opened = WalletActivity.Opened(null, null, ""))
        tap(screen(a)) { it == "Send" }
        val w = words(dialog())
        assertTrue(w.toString(), w.containsAll(listOf("Olena", "Taras", "Send USDC")))
        assertFalse("base58 in the picker: $w", w.any { olena.address!!.short() in it || taras.address!!.short() in it || olena.address!!.base58 in it })
        assertFalse("Cancel in the picker: $w", w.contains("Cancel"))
        assertTrue("close in the picker: $w", w.contains("Close"))
    }

    @Test fun twoPeopleWithTheSameNameAreToldApartByTheShortAddress() {
        val twin = SolanaContact("olena-2", "Olena", SolanaKey.of(ByteArray(32) { (it * 17 + 9).toByte() }))
        val a = open(opened = WalletActivity.Opened(null, null, ""), contacts = listOf(olena, twin, taras))
        tap(screen(a)) { it == "Send" }
        val w = words(dialog())
        assertTrue("first Olena unmarked: $w", w.any { olena.address!!.short() in it })
        assertTrue("second Olena unmarked: $w", w.any { twin.address!!.short() in it })
        assertFalse("a unique name needs no address: $w", w.any { taras.address!!.short() in it })
    }

    @Test fun theConfirmSheetShowsWhoReceivesByShortAddressToo() {
        open()
        val row = dialog().first { it.config.getOrNull(SemanticsProperties.TestTag) == "pay-recipient" }
        assertTrue(words(all(row)).any { olena.address!!.short() in it })
    }

    @Test fun theGuardLineUsesAQuietCheckNotGreen() {
        val origin = body(src("WalletActivity.kt"), "OriginLine")
        assertFalse("green check in the guard line", origin.contains("pc.success"))
        assertTrue(origin.contains("pc.text2"))
    }

    @Test fun theRecoveryWordsSheetStaysSecureWhileUsingTheNewFrame() {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = a
        val context = Dispatchers.Main + clock
        val r = Recomposer(context)
        recomposer = r
        CoroutineScope(context + Job()).launch { r.runRecomposeAndApplyChanges() }
        a.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) { BackupWordsSheet(List(12) { "word$it" }, onDone = {}, onDismiss = {}) }
        }
        pump()
        val d = ShadowDialog.getLatestDialog()
        assertNotNull(d)
        assertTrue("FLAG_SECURE lost", d!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(Gravity.BOTTOM, d.window!!.attributes.gravity and Gravity.BOTTOM)
        assertTrue(words(dialog()).contains("word0"))
    }
}
