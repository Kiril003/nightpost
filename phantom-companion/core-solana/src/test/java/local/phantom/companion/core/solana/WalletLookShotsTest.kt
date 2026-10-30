package local.phantom.companion.core.solana

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Кадри гаманця й аркушів купюри в «Ночі» й «Дні» — те, що побачать судді, без телефона.
 *
 * Екран малюється з ТОГО САМОГО входу, що й на апараті: [WalletBody] зі станом шару, а аркуш
 * купюри відкривається сам із чернетки (Opened з peer/cash/amount), як після «Дай Олені 5 USDC».
 * Кадри лягають у build/wallet-look/. Окрім «намалювалось», кадр перевіряє ґрунт (canvas
 * токена) і бурштин: на екрані з купюрами заливки немає (лише знак), на екрані без
 * записаних слів — рівно одна, на аркуші — рівно одна («Дати купюру · 5 USDC»).
 *
 * Власний Recomposer і годинник кадрів — як у ThemeBridgeLookShotsTest: глобальний
 * диспетчер Compose запамʼятовує Choreographer першого тесту в JVM.
 */
@RunWith(RobolectricTestRunner::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class WalletLookShotsTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val olena = SolanaContact("olena", "Олена", SolanaKey.of(ByteArray(32) { (it * 11 + 3).toByte() }))
    private val taras = SolanaContact("taras", "Тарас", SolanaKey.of(ByteArray(32) { (it * 13 + 5).toByte() }))
    private val ivan = SolanaContact("ivan", "Іван", null)
    private val people = listOf(olena, taras, ivan)

    private fun notes(n: Int) = List(n) { CashNote(seed = "s$it", nonce = "n$it", value = "5") }

    /** Демо-стан: devnet, гроші на рахунку, кишеня з купюрами, слова записано. */
    private val ready = WalletState(
        kind = WalletKind.BUILT_IN,
        address = me,
        builtIn = me,
        backedUp = true,
        cluster = Cluster.DEVNET,
        balances = mapOf(Coin.USDC to 12_500_000L, Coin.EURC to 0L, Coin.SOL to 1_500_000_000L),
        cash = CashPocket(coin = "usdc", units = 20_000_000L, notes = notes(4), checkedAtMs = 1L),
    )
    private val fresh = ready.copy(backedUp = false, cash = CashPocket(), balances = mapOf(Coin.USDC to 0L, Coin.EURC to 0L, Coin.SOL to 0L))
    private val external = ready.copy(kind = WalletKind.EXTERNAL, cash = CashPocket())
    private val nothing = WalletActivity.Opened(peer = null, sealHash = null, sealLabel = "")
    private val cashDraft = WalletActivity.Opened(peer = "olena", sealHash = null, sealLabel = "", coin = Coin.USDC, amount = "5", cash = true, byAssistant = true)
    private val payDraft = WalletActivity.Opened(peer = "olena", sealHash = null, sealLabel = "")

    private val door = object : WalletDoor {
        override val hasScreenLock = true
        override fun host(titleRes: Int, detail: String?): WalletHost = error("кадр не підписує")
        override fun secure(on: Boolean) = Unit
    }

    @Test fun walletReadyNight() = shot("wallet-ready-night", AmberNight, amber = AMBER_HOME) { wallet(ready) }
    @Test fun walletReadyDay() = shot("wallet-ready-day", SunriseWarm, amber = AMBER_HOME) { wallet(ready) }
    /** Новий гаманець не веде словами: смуга тиха, бурштинової заливки на обличчі немає. */
    @Test fun walletFreshNight() = shot("wallet-fresh-night", AmberNight, amber = AMBER_HOME) { wallet(fresh) }
    @Test fun walletFreshDay() = shot("wallet-fresh-day", SunriseWarm, amber = AMBER_HOME) { wallet(fresh) }
    /** Уся сторінка одним кадром — кишеня, люди з «Дати готівкою», мережа, Seeker. */
    @Config(qualifiers = "uk-w411dp-h2600dp-xhdpi")
    @Test fun walletWholeNight() = shot("wallet-whole-night", AmberNight, amber = AMBER_HOME) { wallet(ready) }

    @Test fun walletExternalNight() = shot("wallet-external-night", AmberNight) { wallet(external) }

    @Test fun cashSheetNight() = shot("cash-sheet-night", AmberNight, dialog = true, amber = AMBER_ONE) { wallet(ready, cashDraft) }
    @Test fun cashSheetDay() = shot("cash-sheet-day", SunriseWarm, dialog = true, amber = AMBER_ONE) { wallet(ready, cashDraft) }
    @Test fun paySheetNight() = shot("pay-sheet-night", AmberNight, dialog = true) { wallet(ready, payDraft) }
    @Test fun paySheetDay() = shot("pay-sheet-day", SunriseWarm, dialog = true) { wallet(ready, payDraft) }

    @Test fun wordsSheetNight() = shot("words-sheet-night", AmberNight, dialog = true, amber = AMBER_ONE) {
        Box(Modifier.fillMaxSize().background(LocalPhantomColors.current.canvas))
        BackupWordsSheet(WORDS, onDone = {}, onDismiss = {})
    }

    @Test fun handoffNight() = shot("handoff-night", AmberNight) { handoffs() }
    @Test fun handoffDay() = shot("handoff-day", SunriseWarm) { handoffs() }

    @Composable
    private fun wallet(state: WalletState, open: WalletActivity.Opened = nothing) =
        WalletBody(
            door = door,
            state = state,
            open = open,
            onBack = {},
            people = { people },
            points = { null },
            activity = { coin, _ -> if (coin == Coin.SOL) listOf(ActivityLine("a", 1_000_000_000L, 1_760_000_000L, olena.address)) else emptyList() },
            known = { null },
            split = {},
            refreshBalances = {},
        )

    /** Дорога купюри: шукаємо → передано → поруч не знайшли → у розмові. */
    @Composable
    private fun handoffs() {
        val h = Handoff("Олена", "5 USDC", "sig")
        Column(
            Modifier.fillMaxSize().background(LocalPhantomColors.current.canvas).padding(PhantomSpace.gutterPhone),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            HandoffView(h, HandoffPhase.SEARCHING)
            HandoffView(h, HandoffPhase.HANDED)
            HandoffView(h, HandoffPhase.NOT_FOUND)
            HandoffView(h, HandoffPhase.QUEUED)
        }
    }

    private fun shot(
        name: String,
        spec: PhantomThemeSpec,
        dialog: Boolean = false,
        amber: IntRange? = null,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(LocalMotionScale provides 0f) { content() }
            }
        }
        repeat(40) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val full = capture(activity.window)
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height).copy(Bitmap.Config.ARGB_8888, true)
        val colors = if (spec.isDark) NightColors else DayColors
        // Ґрунт — canvas токена (у полі зліва, куди плити не заходять), до того як ляже пелена діалогу.
        assertEquals("$name: ґрунт не canvas", colors.canvas.toArgb(), bitmap.getPixel(4, bitmap.height - 4))
        var paneAmber = 0
        if (dialog) {
            val d = ShadowDialog.getLatestDialog()
            assertTrue("$name: аркуш не зʼявився", d != null && d.isShowing)
            val pane = capture(d!!.window!!)
            paneAmber = count(pane, colors.accent.toArgb())
            val canvas = Canvas(bitmap)
            canvas.drawColor(colors.scrim.toArgb())
            assertEquals("$name: аркуш не на всю ширину", bitmap.width, pane.width)
            canvas.drawBitmap(pane, 0f, (bitmap.height - pane.height).toFloat(), null)
        }
        val out = File("build/wallet-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: екран не намалювався", inked > 50)
        if (amber != null) {
            val lit = if (dialog) paneAmber else count(bitmap, colors.accent.toArgb())
            assertTrue("$name: бурштину $lit px, чекали $amber", lit in amber)
        }
        if (dialog) ShadowDialog.getLatestDialog()?.dismiss()
        recomposer.cancel()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    private fun count(b: Bitmap, argb: Int): Int {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) if (b.getPixel(x, y) == argb) n++
        return n
    }

    /** PixelCopy малює через HardwareRenderer — інакше шари graphicsLayer не потрапляють у кадр. */
    private fun capture(window: android.view.Window): Bitmap {
        val decor = window.decorView
        val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(window, bmp, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        return bmp
    }

    private companion object {
        /** Знак 24dp на xhdpi — 48×48 px, і бурштин у ньому — менше за його квадрат. */
        const val AMBER_MARK = 48 * 48

        /**
         * Одна бурштинова кнопка на всю ширину плити: 48dp заввишки, понад 280dp завширшки на
         * xhdpi — від 25 тис. px (мінус напис), а дві такі вже понад 75 тис.
         */
        val AMBER_ONE = 25_000..75_000

        /** Головна гаманця (W4): одна бурштинова пігулка «Надіслати» ≈ 48×150dp мінус напис, плюс знак; друга — вже понад межу. */
        val AMBER_HOME = 20_000..36_000

        /** Тестові слова з переліку BIP-39 — лише щоб сітка мала що показати. */
        val WORDS = listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse", "access", "accident")
    }
}
