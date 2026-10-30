package local.phantom.companion.core.solana

import android.graphics.Bitmap
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Картки гаманця з того самого входу, що на апараті ([WalletBody]): що на картках, що на
 * звороті, куди ведуть дії. Дерево семантики Compose читається напряму — без ui-test.
 * Кадри дня й ночі (лицем і зворотом) лягають у build/wallet-look/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class WalletCardsUiTest {

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
        cash = CashPocket(coin = "usdc", units = 20_000_000L, notes = List(4) { CashNote("s$it", "n$it", value = "5") }, checkedAtMs = 1L),
    )
    private val lines = listOf(
        ActivityLine("a", 5_000_000L, System.currentTimeMillis() / 1000 - 3_600, olena.address),
        ActivityLine("b", -2_000_000L, System.currentTimeMillis() / 1000 - 86_400, taras.address),
    )
    private val stranger = SolanaKey.of(ByteArray(32) { (it * 17 + 9).toByte() })!!
    private val door = object : WalletDoor {
        override val hasScreenLock = true
        override fun host(titleRes: Int, detail: String?): WalletHost = error("тест не підписує")
        override fun secure(on: Boolean) = Unit
    }

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var asked = mutableListOf<Pair<Coin, Boolean>>()
    private var splits = 0
    private var connects = 0
    private var wordAsks = 0
    private val words12 = List(12) { "word$it" }

    @After fun close() {
        ShadowDialog.getLatestDialog()?.dismiss()
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun open(
        spec: PhantomThemeSpec = AmberNight,
        state: WalletState = ready,
        points: WalletPoints? = null,
        history: List<ActivityLine> = lines,
        pending: (String) -> Boolean = { false },
        hang: Boolean = false,
    ): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val context = Dispatchers.Main + clock
        val r = Recomposer(context)
        recomposer = r
        CoroutineScope(context + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    WalletBody(
                        door = door,
                        state = state,
                        open = WalletActivity.Opened(null, null, ""),
                        onBack = null,
                        people = { listOf(olena, taras) },
                        points = { points },
                        activity = { coin, fresh ->
                            asked += coin to fresh
                            if (hang) kotlinx.coroutines.awaitCancellation()
                            if (coin == Coin.USDC) history else emptyList()
                        },
                        pending = pending,
                        known = { null },
                        split = { splits++ },
                        refreshBalances = {},
                        connect = { connects++; WalletOutcome.Declined },
                        recoveryWords = { wordAsks++; words12 },
                        markBackedUp = {},
                    )
                }
            }
        }
        pump()
        return c.get()
    }

    private var frameNanos = 0L

    /** Кадри по 50 мс годинника кадрів: анімація сторінки бачить, що час іде. */
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

    /** Тиснемо найглибший вузол з дією, у піддереві якого є [text]. */
    private fun tap(ns: List<SemanticsNode>, text: (String) -> Boolean) {
        val hit = ns.filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { m -> m.words().any(text) } }
            .minByOrNull { all(it).size } ?: error("немає кнопки")
        hit.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
    }

    @Test fun cardsAreTheRealAssetsOnly() {
        val a = open()
        val w = words(screen(a))
        assertTrue(w.any { it.startsWith("SOL card, 1.5 SOL") })
        assertTrue("долар — грошима: $w", w.any { it.startsWith("USDC card, $12.50") })
        assertTrue("першою — картка долара", w.indexOfFirst { it.startsWith("USDC card") } < w.indexOfFirst { it.startsWith("SOL card") })
        assertFalse("EURC 0 — не картка", w.any { it.startsWith("EURC card") })
        assertFalse("сервера балів немає — картки балів немає", w.any { it.startsWith("Points") })
        assertFalse("одна мітка тестової мережі — чип у шапці: $w", w.contains("Test"))
        assertFalse("жодного «devnet» на обличчі", w.any { it.contains("devnet", ignoreCase = true) })
        assertFalse("оновлення — потягом донизу", w.contains("Refresh"))
        assertFalse("тестові SOL — у «Ще › Мережа», не на обличчі", w.contains("Get test SOL"))
    }

    @Test fun pointsCardWhenTheServerCounts() {
        assertTrue(words(screen(open())).contains("Card 1 of 3"))
        close()
        val a = open(points = WalletPoints.Shown(40, listOf(PointsLine("Meetup happened", 40, 1L))))
        val w = words(screen(a))
        assertTrue("SOL, USDC, бали і «+»: $w", w.contains("Card 1 of 4"))
        assertFalse("бали — остання картка, не перша", w.any { it.startsWith("Points 40") })
    }

    @Test fun flipShowsTheRealAddressAndShots() {
        val a = open()
        shot(a, "cards-front-night")
        tap(screen(a)) { it.startsWith("USDC card") }
        assertTrue("зворот — справжня адреса", words(screen(a)).contains(me.base58))
        assertTrue(words(screen(a)).contains("Copy address"))
        shot(a, "cards-back-night")
    }

    @Test @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun backFitsANarrowPhone() {
        val a = open()
        tap(screen(a)) { it.startsWith("Картка USDC") }
        shot(a, "cards-back-360")
        val labels = screen(a).filter { n -> n.words().contains("Скопіювати адресу") && n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        assertTrue("зворот на 360dp має кнопку копіювання", labels.isNotEmpty())
        labels.forEach { label ->
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
            val l = layout.single()
            assertTrue("підпис «Скопіювати адресу» не обрізано: «${l.layoutInput.text.text.take(l.getLineEnd(0, visibleEnd = true))}»", l.getLineEnd(0, visibleEnd = true) == l.layoutInput.text.length)
            assertTrue("підпис у рядок", layout.single().lineCount == 1)
        }
    }

    @Test fun receiveTurnsTheCardOver() {
        val a = open()
        assertFalse(words(screen(a)).contains(me.base58))
        tap(screen(a)) { it == "Receive" }
        assertTrue(words(screen(a)).contains(me.base58))
    }

    @Test fun sendReachesTheExistingAmountSheet() {
        val a = open()
        tap(screen(a)) { it == "Send" }
        assertTrue(words(dialog()).contains("Send USDC"))
        tap(dialog()) { it == "Olena" }
        val sheet = words(dialog())
        assertTrue("аркуш суми — той самий, що з розмови: $sheet", sheet.any { it.contains("Olena") } && sheet.contains("Amount"))
    }

    @Test fun amountAboveTheBalanceIsRefusedInPlace() {
        val a = open()
        tap(screen(a)) { it == "Send" }
        tap(dialog()) { it == "Olena" }
        type("13")
        val over = dialog()
        assertTrue("T27: сума більша за 12.5 USDC — сказано в аркуші: ${words(over)}", words(over).any { it.startsWith("More than you have") && it.contains("12.5") })
        assertTrue("T27: «Переказати» не тиснеться", disabled(over) { it == "Send 13 USDC" })
        type("5")
        val fine = dialog()
        assertFalse(words(fine).any { it.startsWith("More than you have") })
        assertFalse(disabled(fine) { it == "Send 5 USDC" })
    }

    private fun type(text: String) {
        val field = dialog().first { it.config.getOrNull(SemanticsActions.SetText) != null }
        field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(text))
        pump()
    }

    private fun disabled(ns: List<SemanticsNode>, text: (String) -> Boolean): Boolean =
        ns.first { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { m -> m.words().any(text) } }
            .config.contains(SemanticsProperties.Disabled)

    @Test fun splitOpensTheBillFlow() {
        val a = open()
        tap(screen(a)) { it == "Split" }
        assertEquals(1, splits)
    }

    @Test fun requestBuildsASolanaPayLink() {
        val a = open()
        tap(screen(a)) { it == "Request" }
        assertTrue(words(dialog()).contains("Request USDC"))
    }

    @Test fun activityIsTheCardsOwnAndReadOnce() {
        val a = open()
        assertEquals(listOf(Coin.USDC to false), asked)
        val w = words(screen(a))
        assertTrue("рухи картки долара: $w", w.contains("+$5.00") && w.contains("−$2.00"))
    }

    @Test fun activityRowsNameThePersonAndKeepTheAddressOutOfTheTitle() {
        val now = System.currentTimeMillis() / 1000
        val a = open(history = lines + ActivityLine("c", 3_000_000L, now - 7_200, stranger) + ActivityLine("d", -1_000_000L, now - 9_000, null))
        tap(screen(a)) { it == "All · 4" }
        val w = words(screen(a))
        assertTrue("відома людина — імʼям у заголовку: $w", w.contains("Olena") && w.contains("Taras"))
        assertFalse("«From …»/«To …» у заголовках немає: $w", w.any { it.startsWith("From ") || it.startsWith("To ") })
        assertTrue("невідомий відправник — «Received» у заголовку: $w", w.contains("Received"))
        assertTrue("коротка адреса — у рядку під заголовком: $w", w.any { it.startsWith(stranger.short()) })
        assertFalse("адреса не в заголовку: $w", w.any { it.startsWith("Received") && stranger.short() in it })
        assertTrue("без контрагента — «Sent», без адреси: $w", w.contains("Sent"))
        assertEquals("адреса відома лише в одному рядку", 1, w.count { stranger.short() in it })
    }

    @Test fun oldSectionsAreBehindMore() {
        val a = open()
        val w = words(screen(a))
        assertFalse(w.contains("Transfers in chats"))
        assertFalse("справжні гроші — у налаштуваннях за «Ще»", w.contains("Real money"))
        assertFalse("тестові SOL не висять на головному екрані", w.contains("Get test SOL"))
        tap(screen(a)) { it == "Network and settings" }
        val openWords = words(screen(a))
        assertTrue(openWords.contains("Transfers in chats"))
        assertTrue(openWords.contains("Real money"))
        assertTrue("тестові SOL — у «Ще › Мережа»", openWords.contains("Get test SOL"))
    }

    @Test fun freshWalletLeadsWithCardsNotTheWords() {
        val a = open(state = ready.copy(backedUp = false))
        val w = words(screen(a))
        assertFalse("кнопки слів на обличчі немає", w.contains("Show the words"))
        assertFalse("про слова на обличчі мовчимо: $w", w.any { it.contains("12 words") })
        assertTrue(w.contains("Not saved"))
        assertTrue("рядок — після карток і дій, перед «Останні»", w.indexOf("Not saved") > w.indexOf("Split") && w.indexOf("Not saved") < w.indexOf("Recent"))
        close()
        assertFalse(words(screen(open())).contains("Not saved"))
    }

    @Test fun pointsPillHiddenWhenPointsCardIsInCarousel() {
        val a = open(points = WalletPoints.Shown(40, listOf(PointsLine("Meetup happened", 40, 1L))))
        assertFalse("пігулка балів у шапці не дублює картку балів у каруселі", words(screen(a)).any { it.startsWith("Points:") })
        toPage(a, 2)
        val w = words(screen(a))
        assertTrue(w.toString(), w.contains("Card 3 of 4"))
        assertTrue("під карткою балів — її історія: $w", w.contains("Meetup happened"))
    }

    @Test fun walletCardsRemainFlatWithoutShadersTiltOrExplainers() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "core-solana/src/main/java").isDirectory }
        val ui = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletCardsUi.kt").readText()
        val act = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletActivity.kt").readText()
        val cards = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletCards.kt").readText()
        for (banned in listOf("Sheen(", "EmbossedMark(", "embossed(", "drawWithCache", "shadowElevation", "rememberTilt", "linearGradient", "PointMode")) {
            assertFalse("WalletCardsUi містить $banned", banned in ui)
        }
        assertFalse("TiltSensor лишився в WalletCards.kt", "class TiltSensor" in cards)
        assertTrue("картка має радіус PhantomRadius.card", "RoundedCornerShape(PhantomRadius.card)" in ui)
        assertTrue("картка має рамку lineStrong", "lineStrong" in ui)
        val actions = ui.substringAfter("private fun CardActions(").substringBefore("private fun MoneyActivity(")
        assertTrue("Send — єдина бурштинова пігулка (W4), решта — c.raised", actions.split("background(c.accent)").size == 2 && "background(c.raised)" in actions)
        assertFalse("CardAction більше не 56.dp", "56.dp" in ui)
        assertFalse("MoreRow / BackupBanner не використовують PhantomRadius.sheet", "PhantomRadius.sheet" in act)
        assertFalse("WalletChip видалено", "fun WalletChip(" in act)
        assertFalse("пояснювальні речення explainer прибрано з WalletActivity", "explainer =" in act)
        assertFalse("підказки-речення sol_*_hint прибрано з WalletActivity (плейсхолдер поля слів лишається)", Regex("""R\.string\.sol_(?!restore_hint)\w+_hint\)""").containsMatchIn(act))
    }

    private fun todayAt(h: Int, m: Int): Long =
        java.time.LocalDate.now().atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()

    private fun daysAgoAt(days: Long, h: Int, m: Int): Long =
        java.time.LocalDate.now().minusDays(days).atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()

    @Test fun recentRowsCarryTheDayAndTwentyFourHourTime() {
        val older = java.time.LocalDate.now().minusDays(9)
        val a = open(
            history = listOf(
                ActivityLine("t1", 5_000_000L, todayAt(14, 2), olena.address),
                ActivityLine("t2", -1_000_000L, todayAt(2, 5), taras.address),
                ActivityLine("y1", 2_000_000L, daysAgoAt(1, 18, 30), olena.address),
                ActivityLine("o1", -3_000_000L, daysAgoAt(9, 9, 15), taras.address),
            ),
        )
        tap(screen(a)) { it == "All · 4" }
        val w = words(screen(a))
        assertTrue("розділ Recent: $w", w.contains("Recent"))
        val dated = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH).format(older)
        assertTrue("день і час у рядку: $w", w.containsAll(listOf("Today, 14:02", "Today, 02:05", "Yesterday, 18:30", "$dated, 09:15")))
        assertFalse("окремих заголовків днів більше немає: $w", w.contains("Today") || w.contains("Yesterday"))
        assertFalse("24 h без AM/PM: $w", w.any { it.contains("AM") || it.contains("PM") })
    }

    @Test fun failedSendSaysSoAndStrikesTheAmount() {
        val a = open(history = listOf(ActivityLine("f", -6_500_000L, todayAt(10, 0), taras.address, failed = true)))
        val ns = screen(a)
        assertTrue(words(ns).toString(), words(ns).contains("Didn’t go through · Today, 10:00"))
        assertTrue("сума перекреслена", ns.any { it.config.getOrNull(SemanticsProperties.TestTag) == "activity-failed" })
    }

    @Test fun devnetEmptyHistoryLeavesOneReceiveActionTheFaucet() {
        val ns = screen(open(history = emptyList()))
        val empty = ns.single { it.config.getOrNull(SemanticsProperties.TestTag) == "activity-empty" }
        assertFalse(words(all(empty)).toString(), words(all(empty)).contains("Receive USDC"))
        assertTrue(words(ns).toString(), words(ns).contains("Get test USDC"))
    }

    @Test fun moreRowNamesWhatItOpens() {
        val w = words(screen(open()))
        assertTrue(w.toString(), w.contains("Network and settings"))
        assertFalse(w.toString(), w.contains("More"))
    }

    @Test fun emptyHistoryOffersTheNextStep() {
        val a = open(state = ready.copy(cluster = Cluster.MAINNET), history = emptyList())
        val ns = screen(a)
        val empty = ns.single { it.config.getOrNull(SemanticsProperties.TestTag) == "activity-empty" }
        assertTrue(words(all(empty)).toString(), words(all(empty)).contains("Receive USDC"))
        assertFalse(words(ns).contains(me.base58))
        tap(all(empty)) { it == "Receive USDC" }
        assertTrue("зворот із адресою", words(screen(a)).contains(me.base58))
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-xhdpi")
    fun lookShotsReturnRowAndEmptyHistory() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        WalletReturn.expect(ctx, SolanaKey.of(ByteArray(32) { (it * 19 + 2).toByte() })!!.base58)
        shot(open(spec = local.phantom.companion.core.design.theme.DayClear, history = emptyList()), "look-wallet-day-uk")
        close()
        shot(open(spec = AmberNight, history = emptyList()), "look-wallet-night-uk")
        WalletReturn.clear(ctx)
    }

    @Test fun oldWalletIsOneQuietRowWithoutAKeyFragment() {
        val old = SolanaKey.of(ByteArray(32) { (it * 19 + 2).toByte() })!!.base58
        WalletReturn.expect(androidx.test.core.app.ApplicationProvider.getApplicationContext(), old)
        val a = open()
        val ns = screen(a)
        val w = words(ns)
        val row = ns.single { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-return" }
        assertTrue(words(all(row)).toString(), words(all(row)).containsAll(listOf("Restore your wallet", "Another one is open now")))
        assertFalse("жодного уривка ключа: $w", w.any { it.contains(old.take(4)) || it.contains(old.takeLast(4)) })
        assertFalse("дії — в аркуші, не на екрані: $w", w.contains("Keep the new one") || w.contains("Restore from words"))
        tap(all(row)) { it == "Restore your wallet" }
        val sheet = words(dialog())
        assertTrue(sheet.toString(), sheet.containsAll(listOf("Restore from words", "Keep the new one")))
        WalletReturn.clear(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test fun cardFaceNamesTheCoinAndShowsTheShortAddress() {
        val w = words(screen(open()))
        assertTrue(w.toString(), w.contains("Dollar") && w.contains(CardFace.shortKey(ready.address!!)))
    }

    @Test fun rootHeaderShowsTheTestNetworkChipOnlyOnDevnet() {
        val dev = screen(open())
        assertTrue(words(dev).contains("Test network"))
        assertTrue("Надіслати — окрема пігулка", dev.any { n -> n.config.getOrNull(SemanticsProperties.TestTag) == "card-send" && all(n).any { it.words().contains("Send") } })
        close()
        val main = words(screen(open(state = ready.copy(cluster = Cluster.MAINNET))))
        assertFalse("на справжній мережі чипа немає: $main", main.contains("Test network"))
    }

    @Test @Config(qualifiers = "uk-w390dp-h844dp-xhdpi")
    fun w4HomeDay390() {
        shot(open(spec = local.phantom.companion.core.design.theme.DayClear), "w4-home-day-uk")
    }

    @Test @Config(qualifiers = "uk-w390dp-h844dp-xhdpi")
    fun w4HomeNight390() {
        shot(open(), "w4-home-night-uk")
    }

    @Test fun walletHomeShowsThreeRecentAndAllOpensTheRest() {
        val a = open(history = (1..6).map { ActivityLine("r$it", it * 1_000_000L, todayAt(20, 10 - it), olena.address) })
        val w = words(screen(a))
        assertTrue("три останні: $w", w.contains("+$1.00") && w.contains("+$3.00"))
        assertFalse("четвертий — за «Усі»: $w", w.contains("+$4.00"))
        assertTrue(w.contains("All · 6"))
        tap(screen(a)) { it == "All · 6" }
        val all = words(screen(a))
        assertTrue("після «Усі» — всі шість: $all", all.contains("+$6.00"))
        assertFalse(all.contains("All · 6"))
    }

    private fun tapTag(ns: List<SemanticsNode>, tag: String) {
        val hit = ns.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag && it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: error("немає $tag")
        hit.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
    }

    private fun tags(ns: List<SemanticsNode>) = ns.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    @Test fun historyFiltersBySentAndReceived() {
        val now = System.currentTimeMillis() / 1000
        val a = open(history = lines + ActivityLine("c", 3_000_000L, now - 7_200, stranger) + ActivityLine("d", -1_000_000L, now - 9_000, null))
        assertTrue(tags(screen(a)).containsAll(listOf("activity-filter-all", "activity-filter-sent", "activity-filter-received")))
        tapTag(screen(a), "activity-filter-sent")
        val sent = words(screen(a))
        assertTrue("надіслані — обидва: $sent", sent.contains("\u2212$2.00") && sent.contains("\u2212$1.00"))
        assertFalse("отриманих немає: $sent", sent.contains("+$5.00") || sent.contains("+$3.00"))
        tapTag(screen(a), "activity-filter-received")
        val got = words(screen(a))
        assertTrue("отримані — обидва: $got", got.contains("+$5.00") && got.contains("+$3.00"))
        assertFalse("надісланих немає: $got", got.contains("\u2212$2.00") || got.contains("\u2212$1.00"))
        tapTag(screen(a), "activity-filter-all")
        assertTrue(words(screen(a)).contains("All · 4"))
    }

    @Test fun historyFilterOnlyWhenThereIsBothWays() {
        val a = open(history = (1..4).map { ActivityLine("r$it", it * 1_000_000L, todayAt(20, 10 - it), olena.address) })
        assertFalse(tags(screen(a)).any { it.startsWith("activity-filter-") })
    }

    @Test fun historyFilterKeepsTheChipsFlat() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "core-solana/src/main/java").isDirectory }
        val ui = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletCardsUi.kt").readText()
        val feed = ui.substringAfter("is FeedView.Lines ->").substringBefore("private const val RECENT_ROWS")
        assertTrue("фільтр — тихі чипи без підйому", feed.contains("PhantomChip(") && !feed.contains("ChipEmphasis.Solid") && !feed.contains("ChipEmphasis.Outline"))
    }

    @Test fun amountsKeepTheRealMinusAndAPlus() {
        val w = words(screen(open(history = listOf(ActivityLine("m", -1_000_000L, todayAt(10, 0), taras.address), ActivityLine("p", 20_000_000L, todayAt(9, 0), olena.address)))))
        assertTrue(w.toString(), w.contains("\u2212$1.00") && w.contains("+$20.00"))
        assertFalse("ASCII дефіс у сумі: $w", w.any { it.startsWith("-$") })
    }

    @Test fun pendingRowsCarryAPendingChip() {
        val a = open(pending = { it == "a" })
        val w = words(screen(a))
        assertEquals("рівно один Pending: $w", 1, w.count { it == "Pending" })
        close()
        assertEquals("без PENDING чипа немає", 0, words(screen(open())).count { it == "Pending" })
    }

    @Test fun loadingIsTwoFlatRowsWithNoText() {
        val a = open(hang = true)
        val ns = screen(a)
        val w = words(ns)
        assertFalse("старий рядок читання історії: $w", w.any { it.contains("Reading history") })
        val rows = ns.filter { it.config.getOrNull(SemanticsProperties.TestTag) == "activity-loading" }
        assertEquals("дві пласкі смуги", 2, rows.size)
        rows.forEach { r -> assertTrue("у смузі тексту немає", all(r).none { it.words().isNotEmpty() }) }
    }

    @Test fun builtInWalletLineIsGone() {
        val w = words(screen(open()))
        assertFalse("«Built-in Nightpost wallet» прибрано: $w", w.any { it.contains("Built-in", ignoreCase = true) })
        assertTrue(w.any { it.startsWith("USDC card") })
    }

    @Test fun backupIsItsOwnRowAndTheUtilityCardKeepsFaucetAndMore() {
        val a = open(state = ready.copy(backedUp = false, faucet = FaucetStatus.READY))
        val ns = screen(a)
        val cards = ns.filter { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-utility" }
        assertEquals("одна картка утиліт", 1, cards.size)
        val utility = words(all(cards.single()))
        assertTrue(utility.toString(), utility.containsAll(listOf("Get test USDC", "Network and settings")) && "Not saved" !in utility)
        val backup = words(all(ns.single { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-backup" }))
        assertTrue(backup.toString(), backup.containsAll(listOf("Backup", "Not saved")))
        assertFalse("старої назви Ще немає", words(ns).contains("Cash, people and settings"))
    }

    @Test fun theBackupRowOnlySaysWhatIsTrue() {
        val inside = words(all(screen(open()).first { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-backup" }))
        assertFalse("копію зроблено — «не збережено» немає", inside.contains("Not saved"))
        assertTrue("рядок не зникає, а каже, що зроблено: $inside", inside.contains("Recovery words saved"))
    }

    @Test fun savedCopySaysWhenAndWhereAndOpensItsActions() {
        val at = java.time.LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val a = open(state = ready.copy(backup = BackupStatus.Copy(at, "nightpost-wallet.backup", "Drive")))
        val inside = words(all(screen(a).first { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-backup" }))
        assertTrue(inside.toString(), inside.contains("Wallet copy · 18 Oct · Drive"))
        tap(screen(a)) { it == "Backup" }
        val sheet = words(dialog())
        assertTrue(sheet.toString(), sheet.containsAll(listOf("Check the copy", "Save again", "Restore from the copy")))
        shot(a, "backup-row-night")
        shotDialog("backup-sheet-night")
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-xhdpi")
    fun savedCopyRowInUkrainianNamesDayAndPlace() {
        val at = java.time.LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val a = open(spec = local.phantom.companion.core.design.theme.DayClear, state = ready.copy(backup = BackupStatus.Copy(at, "nightpost-wallet.backup", "Диск")))
        val inside = words(all(screen(a).first { it.config.getOrNull(SemanticsProperties.TestTag) == "wallet-backup" }))
        assertTrue(inside.toString(), inside.contains("Копія гаманця · 18 жовт. · Диск"))
        shot(a, "backup-row-day-uk")
        tap(screen(a)) { it == "Резервна копія" }
        assertTrue(words(dialog()).containsAll(listOf("Перевірити копію", "Зберегти ще раз", "Відновити з копії")))
        shotDialog("backup-sheet-day-uk")
    }

    @Test fun walletScreenFeedsThePendingChipFromThePaymentWatch() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "core-solana/src/main/java").isDirectory }
        val dir = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana")
        val act = File(dir, "WalletActivity.kt").readText()
        val screen = act.substringAfter("private fun WalletScreen(").substringBefore("internal fun WalletBody(")
        assertTrue("WalletScreen не передає pending: $screen", screen.contains("pending =") && screen.contains("SolanaLayer.paymentStatus") && screen.contains("SolanaLayer.revision"))
        assertTrue(File(dir, "SolanaLayer.kt").readText().contains("fun paymentStatus(signature: String)"))
    }

    @Test @Config(qualifiers = "uk-w411dp-h891dp-xhdpi")
    fun dayHeadersFollowTheUkrainianLocale() {
        val w = words(screen(open(history = listOf(ActivityLine("t", 1_000_000L, todayAt(14, 2), olena.address), ActivityLine("y", 1_000_000L, daysAgoAt(1, 10, 0), olena.address)))))
        assertTrue(w.toString(), w.contains("Сьогодні, 14:02") && w.contains("Вчора, 10:00"))
    }

    @Test @Config(qualifiers = "cs-w411dp-h891dp-xhdpi")
    fun dayHeadersFollowTheCzechLocale() {
        val w = words(screen(open(history = listOf(ActivityLine("t", 1_000_000L, todayAt(14, 2), olena.address), ActivityLine("y", 1_000_000L, daysAgoAt(1, 10, 0), olena.address)))))
        assertTrue(w.toString(), w.contains("Dnes, 14:02") && w.contains("Včera, 10:00"))
    }

    private fun toPage(a: ComponentActivity, index: Int) {
        val pager = screen(a).first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
        pager.config[SemanticsActions.ScrollToIndex].action!!.invoke(index)
        pump()
    }

    private fun addSheet(a: ComponentActivity) {
        toPage(a, 2)
        tap(screen(a)) { it == "Add a card" }
    }

    @Test fun plusCardOpensTheAddSheetAndConnectsAnOwnWallet() {
        val a = open()
        toPage(a, 2)
        shot(a, "add-card-night")
        tap(screen(a)) { it == "Add a card" }
        shotDialog("add-sheet-night")
        val sheet = words(dialog())
        assertTrue(sheet.toString(), sheet.containsAll(listOf("Token by address", "Watch an address", "Connect your own wallet", "Restore from words", "Restore from a copy file")))
        tap(dialog()) { it == "Connect your own wallet" }
        assertEquals("той самий шлях MWA, що й у «Ще»", 1, connects)
    }

    @Test fun addSheetRestoresFromTwelveOrTwentyFourWords() {
        val a = open()
        addSheet(a)
        tap(dialog()) { it == "Restore from words" }
        assertTrue(words(dialog()).contains("12 or 24 words separated by spaces"))
    }

    @Test fun tokenByAddressRefusesWhatIsNotAnAddress() {
        val a = open()
        addSheet(a)
        tap(dialog()) { it == "Token by address" }
        val field = dialog().first { it.config.getOrNull(SemanticsActions.SetText) != null }
        field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("not an address"))
        pump()
        tap(dialog()) { it == "Add card" }
        assertTrue(words(dialog()).contains("This is not an address. Check it and paste again."))
    }

    @Test fun builtInOnlyRowsStayAwayFromAnExternalWallet() {
        val a = open(state = ready.copy(kind = WalletKind.EXTERNAL))
        addSheet(a)
        val sheet = words(dialog())
        assertTrue(sheet.contains("Token by address"))
        assertFalse(sheet.contains("Restore from words"))
    }

    @Test fun heldTokenGetsACardThatShowsOnlyItsBalance() {
        val bonk = SolanaKey.of(ByteArray(32) { (it * 5 + 2).toByte() })!!
        val a = open(state = ready.copy(tokens = listOf(HeldToken(bonk, 500, 5))))
        assertTrue(words(screen(a)).contains("Card 1 of 4"))
        toPage(a, 2)
        shot(a, "token-card-night")
        val w = words(screen(a))
        assertTrue(w.toString(), w.contains("Token") && w.contains("This card shows the balance only."))
        assertFalse("з токена тут нічого не надсилаємо", w.contains("Send"))
    }

    @Test fun longPressArrangesTheCard() {
        val a = open()
        val card = screen(a).first { n -> n.config.getOrNull(SemanticsActions.OnLongClick) != null && n.words().any { it.startsWith("USDC card") } }
        card.config[SemanticsActions.OnLongClick].action!!.invoke()
        pump()
        val sheet = words(dialog())
        assertTrue(sheet.toString(), sheet.contains("Move later") && sheet.contains("Hide card"))
        assertFalse("перша картка раніше не посунеться", sheet.contains("Move earlier"))
    }

    @Test fun recoveryWordsStayReachableAfterBackup() {
        val a = open()
        assertFalse("копію вже зроблено — «не збережено» немає", words(screen(a)).contains("Not saved"))
        tap(screen(a)) { it == "Network and settings" }
        tap(screen(a)) { it == "Recovery words" }
        assertEquals("слова — лише через підтвердження", 1, wordAsks)
        assertTrue(words(dialog()).containsAll(words12))
    }

    @Test fun backupBannerOpensTheSameWords() {
        val a = open(state = ready.copy(backedUp = false))
        tap(screen(a)) { it == "Not saved" }
        assertEquals(1, wordAsks)
        assertTrue(words(dialog()).containsAll(words12))
    }

    @Test fun noPillWithoutPoints() = assertFalse(words(screen(open())).any { it.startsWith("Points:") })

    @Test fun dayShot() {
        val a = open(SunriseWarm)
        shot(a, "cards-front-day")
        tap(screen(a)) { it.startsWith("USDC card") }
        shot(a, "cards-back-day")
    }

    private fun shotDialog(name: String) = shotWindow(ShadowDialog.getLatestDialog()!!.window!!, name)

    private fun shot(a: ComponentActivity, name: String) = shotWindow(a.window, name)

    private fun shotWindow(window: android.view.Window, name: String) {
        val decor = window.decorView
        val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(window, bmp, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val out = File("build/wallet-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
