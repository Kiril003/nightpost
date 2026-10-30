package local.phantom.companion.core.solana

import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
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
class WalletFaucetUiTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val olena = SolanaContact("olena", "Olena", SolanaKey.of(ByteArray(32) { (it * 11 + 3).toByte() }))

    private val baseState = WalletState(
        kind = WalletKind.BUILT_IN,
        address = me,
        builtIn = me,
        backedUp = true,
        cluster = Cluster.DEVNET,
        balances = mapOf(Coin.USDC to 0L, Coin.EURC to 0L, Coin.SOL to 0L),
    )

    private val door = object : WalletDoor {
        override val hasScreenLock = true
        override fun host(titleRes: Int, detail: String?): WalletHost = WalletHost(null) { true }
        override fun secure(on: Boolean) = Unit
    }

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var faucetCalls = 0
    private var frameNanos = 0L

    @After
    fun close() {
        ShadowDialog.getLatestDialog()?.dismiss()
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun open(
        state: WalletState = baseState,
        opened: WalletActivity.Opened = WalletActivity.Opened(null, null, ""),
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
                        people = { listOf(olena) },
                        points = { null },
                        activity = { _, _ -> emptyList() },
                        known = { emptyList() },
                        split = {},
                        refreshBalances = {},
                        connect = { WalletOutcome.Declined },
                        recoveryWords = { emptyList() },
                        markBackedUp = {},
                        requestTestUsdc = { faucetCalls++ },
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
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun screen(a: ComponentActivity) = nodes(a.window.decorView)
    private fun dialog() = nodes(ShadowDialog.getLatestDialog()!!.window!!.decorView)
    private fun words(ns: List<SemanticsNode>) = ns.flatMap { it.words() }

    private fun buttonWithText(ns: List<SemanticsNode>, label: String): SemanticsNode? =
        ns.filter { n ->
            n.config.getOrNull(SemanticsActions.OnClick) != null &&
                all(n).any { m -> m.words().contains(label) }
        }.minByOrNull { all(it).size }

    @Test
    fun devnetBuiltInShowsEnabledFaucetButtonWhileMainnetAndExternalHideIt() {
        val devnet = open(baseState.copy(cluster = Cluster.DEVNET, kind = WalletKind.BUILT_IN, faucet = FaucetStatus.READY))
        val btn = buttonWithText(screen(devnet), "Get test USDC")
        assertNotNull("Expected 'Get test USDC' button on devnet built-in wallet", btn)
        assertFalse("Expected node to be enabled", SemanticsProperties.Disabled in btn!!.config)

        close()
        val mainnet = open(baseState.copy(cluster = Cluster.MAINNET, kind = WalletKind.BUILT_IN, faucet = FaucetStatus.READY))
        assertFalse("Mainnet must not show test USDC button", words(screen(mainnet)).contains("Get test USDC"))

        close()
        val external = open(baseState.copy(cluster = Cluster.DEVNET, kind = WalletKind.EXTERNAL, faucet = FaucetStatus.READY))
        assertFalse("External wallet must not show test USDC button", words(screen(external)).contains("Get test USDC"))
    }

    private fun assertDisabled(status: FaucetStatus, secondary: String) {
        val ns = screen(open(baseState.copy(faucet = status)))
        assertTrue(words(ns).containsAll(listOf("Get test USDC", secondary)))
        val btn = ns.firstOrNull { n -> all(n).any { it.words().contains("Get test USDC") } && SemanticsProperties.Disabled in n.config }
        assertNotNull("Button must be disabled for $status", btn)
    }

    @Test
    fun stateOffShowsDisabledButtonWithUnavailableLabel() = assertDisabled(FaucetStatus.OFF, "Unavailable")

    @Test
    fun stateLimitedShowsDisabledButtonWithAlreadyReceivedLabel() = assertDisabled(FaucetStatus.LIMITED, "Already received")

    @Test
    fun stateSendingShowsDisabledButtonWithRequestingLabel() = assertDisabled(FaucetStatus.SENDING, "Requesting")

    @Test
    fun stateRetryShowsTryLaterLabelAndStaysTappable() {
        faucetCalls = 0
        val a = open(baseState.copy(faucet = FaucetStatus.RETRY))
        val ns = screen(a)
        assertTrue(words(ns).containsAll(listOf("Get test USDC", "Try later")))
        val btn = buttonWithText(ns, "Get test USDC")
        assertNotNull("Expected 'Get test USDC' button after an unreachable server", btn)
        assertFalse("Retry row must stay enabled", SemanticsProperties.Disabled in btn!!.config)
        btn.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertEquals(1, faucetCalls)
    }

    @Test
    fun unreachableServerLeavesVisibleTryLaterLabelOnTheRowAfterATap() {
        SolanaLayer.testReset(
            baseState.copy(faucet = FaucetStatus.READY),
            SolanaConfig(
                licencePurchase = false,
                licenceBaseUrl = "",
                activateLicence = { false },
                contacts = { emptyList() },
                send = { _, _ -> true },
                sponsorBaseUrl = "https://phantom.test",
                selfId = { "device_12345678" },
            ),
        )
        SolanaLayer.http = HttpCall { _, _ -> throw java.io.IOException("unreachable") }
        kotlinx.coroutines.runBlocking { SolanaLayer.requestTestUsdc() }
        val after = SolanaLayer.state.value
        assertEquals(FaucetStatus.RETRY, after.faucet)

        val ns = screen(open(after))
        assertTrue("label must be visible after Unavailable: ${words(ns)}", words(ns).containsAll(listOf("Get test USDC", "Try later")))
        val btn = buttonWithText(ns, "Get test USDC")
        assertNotNull(btn)
        assertFalse("row stays tappable", SemanticsProperties.Disabled in btn!!.config)
        SolanaLayer.testReset(baseState)
    }

    @Test
    fun openingWalletNeverCallsFaucetUntilButtonIsTapped() {
        faucetCalls = 0
        val a = open(baseState.copy(faucet = FaucetStatus.READY))
        assertEquals("Opening the wallet must not trigger faucet", 0, faucetCalls)
        val btn = buttonWithText(screen(a), "Get test USDC")
        assertNotNull("Expected 'Get test USDC' button", btn)
        btn!!.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertEquals(1, faucetCalls)
    }

    @Test
    fun paySheetShowsNoteFieldForTransferAndHidesItForCash() {
        val a = open(
            state = baseState,
            opened = WalletActivity.Opened(peer = "olena", sealHash = null, sealLabel = "", cash = false),
        )
        assertNotNull("Expected pay dialog to open", ShadowDialog.getLatestDialog())
        val transferWords = words(dialog())
        assertTrue("Transfer sheet must include 'Note' field: $transferWords", transferWords.contains("Note"))

        close()
        open(
            state = baseState.copy(
                cash = CashPocket(coin = "usdc", units = 20_000_000L, notes = listOf(CashNote("s", "n", value = "5")), checkedAtMs = 1L),
            ),
            opened = WalletActivity.Opened(peer = "olena", sealHash = null, sealLabel = "", cash = true),
        )
        val cashWords = words(dialog())
        assertFalse("Cash sheet must not show 'Note' field: $cashWords", cashWords.contains("Note"))
    }
}
