package local.phantom.companion.core.solana

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Збірка 18: частка рахунку платиться в групу і несе id рахунку
 * (`docs/scene/MEETUP_CONTRACT.md` §4). Кадр v2 = v1 + `;ref`, v1 читається
 * як був; картка йде в розмову, з якої людина прийшла платити, і нікуди —
 * коли відбиток скасовано.
 */
class PaymentFrameRefTest {

    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
    private val to = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"

    @Test
    fun v2FrameWithRefRoundTrips() {
        val f = PaymentFrame(Cluster.DEVNET, Coin.USDC, 7_500_000, to, sig, ref = bill)
        val wire = f.encode()
        assertEquals("PH-PAY:2;devnet;usdc;7500000;${to.base58};$sig;$bill", wire)
        assertEquals(f, PaymentFrame.decode(wire))
        assertEquals(bill, PaymentFrame.decode(wire)?.ref)
    }

    @Test
    fun v1StaysV1OnTheWireAndStillDecodesWithoutRef() {
        val v1 = PaymentFrame(Cluster.DEVNET, Coin.SOL, 1_000, to, sig)
        // Переказ без рахунку їде байт у байт як у збірці 17 — старі телефони його читають.
        assertEquals("PH-PAY:1;devnet;sol;1000;${to.base58};$sig", v1.encode())
        val back = PaymentFrame.decode(v1.encode())
        assertEquals(v1, back)
        assertNull(back?.ref)
    }

    @Test
    fun v2IgnoresUnknownTrailingFields() {
        val wire = PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, to, sig, ref = "bill_1").encode() + ";later;fields"
        assertEquals("bill_1", PaymentFrame.decode(wire)?.ref)
    }

    @Test
    fun v2WithoutValidRefIsRefused() {
        val base = "PH-PAY:2;devnet;usdc;1;${to.base58};$sig"
        assertNull("v2 без ref", PaymentFrame.decode(base))
        assertNull("порожній ref", PaymentFrame.decode("$base;"))
        assertNull("37 знаків", PaymentFrame.decode("$base;" + "a".repeat(37)))
        assertNull("чужі знаки", PaymentFrame.decode("$base;bill 1"))
        assertNull("невідома версія", PaymentFrame.decode("PH-PAY:3;devnet;usdc;1;${to.base58};$sig;$bill"))
        assertTrue(PaymentFrame.isRef("a".repeat(PaymentFrame.MAX_REF)))
        assertFalse(PaymentFrame.isRef("a".repeat(PaymentFrame.MAX_REF + 1)))
        assertFalse(PaymentFrame.isRef(null))
    }

    @Test
    fun aFrameCannotBeBuiltWithARefThatWouldBreakTheWire() {
        try {
            PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, to, sig, ref = "a;b")
            fail("ref з «;» розірвав би кадр")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── куди їде картка ──────────────────────────────────────────────────

    private class Wire {
        val sent = mutableListOf<Triple<String, String, String>>()
        var chatWorks = true
        var peerWorks = true
        val toPeer: suspend (String, String) -> Boolean = { id, body -> if (peerWorks) { sent += Triple("peer", id, body); true } else false }
        val toChat: suspend (String, String) -> Boolean = { id, body -> if (chatWorks) { sent += Triple("chat", id, body); true } else false }
    }

    private fun make(ref: String?) = { s: String -> PaymentFrame(Cluster.DEVNET, Coin.USDC, 7_500_000, to, s, ref) }

    @Test
    fun withAChatTheCardGoesToTheGroupNotTheOneToOne() = runTest {
        val w = Wire()
        val r = PaymentPost.after(WalletOutcome.Done(sig), make(bill)) { f -> PaymentPost.deliver(f, "olena", PayRoute("grp-cafe", bill), w.toPeer, w.toChat) }
        assertEquals(bill, (r as WalletOutcome.Done).value.frame.ref)
        assertTrue(r.value.posted)
        assertEquals(listOf(Triple("chat", "grp-cafe", r.value.frame.encode())), w.sent)
    }

    @Test
    fun withoutAChatTheCardGoesToThePayeeAsBefore() = runTest {
        val w = Wire()
        PaymentPost.after(WalletOutcome.Done(sig), make(null)) { f -> PaymentPost.deliver(f, "olena", PayRoute(), w.toPeer, w.toChat) }
        assertEquals(listOf("peer" to "olena"), w.sent.map { it.first to it.second })
        // Чат, що й є розмовою з отримувачем, — та сама 1:1.
        w.sent.clear()
        PaymentPost.deliver(make(null)(sig), "olena", PayRoute("olena", null), w.toPeer, w.toChat)
        assertEquals(listOf("peer" to "olena"), w.sent.map { it.first to it.second })
    }

    @Test
    fun moneyMovedButTheGroupIsGone_theCardStillReachesThePayee() = runTest {
        val w = Wire().apply { chatWorks = false }
        PaymentPost.deliver(make(bill)(sig), "olena", PayRoute("grp-cafe", bill), w.toPeer, w.toChat)
        assertEquals(listOf("peer" to "olena"), w.sent.map { it.first to it.second })
    }

    @Test
    fun venueAddressWithAChatGoesToTheGroup_withoutAChatNowhere() = runTest {
        val w = Wire()
        PaymentPost.deliver(make(bill)(sig), null, PayRoute("grp-cafe", bill), w.toPeer, w.toChat)
        assertEquals(listOf("chat" to "grp-cafe"), w.sent.map { it.first to it.second })
        w.sent.clear()
        PaymentPost.deliver(make(null)(sig), null, PayRoute(), w.toPeer, w.toChat)
        assertTrue(w.sent.isEmpty())
    }

    /**
     * Знахідка 5 рев'ю: гроші пішли, а картка — нікуди (з групи вийшов між перевіркою й
     * підписом). Екран не сміє казати «надіслано в розмову».
     */
    @Test
    fun aCardThatWentNowhereIsNotCalledPosted() = runTest {
        val gone = Wire().apply { chatWorks = false }
        assertFalse("заклад, групи вже нема — картку нікому показати", PaymentPost.deliver(make(bill)(sig), null, PayRoute("grp-cafe", bill), gone.toPeer, gone.toChat))
        assertTrue("групи нема, але є людина — картка в 1:1", PaymentPost.deliver(make(bill)(sig), "olena", PayRoute("grp-cafe", bill), gone.toPeer, gone.toChat))
        val dead = Wire().apply { chatWorks = false; peerWorks = false }
        assertFalse("ні групи, ні 1:1", PaymentPost.deliver(make(bill)(sig), "olena", PayRoute("grp-cafe", bill), dead.toPeer, dead.toChat))
        assertFalse("адреса вручну без розмови", PaymentPost.deliver(make(null)(sig), null, PayRoute(), Wire().toPeer, Wire().toChat))

        val r = PaymentPost.after(WalletOutcome.Done(sig), make(bill)) { f -> PaymentPost.deliver(f, null, PayRoute("grp-cafe", bill), gone.toPeer, gone.toChat) }
        assertFalse((r as WalletOutcome.Done).value.posted)
        assertEquals(R.string.sol_paid_unposted, PaymentPost.paidLine(r))
        assertEquals(R.string.sol_paid, PaymentPost.paidLine(WalletOutcome.Done(Paid(make(bill)(sig), posted = true))))
    }

    @Test
    fun cancelledFingerprintSendsNothing() = runTest {
        for (outcome in listOf(WalletOutcome.Declined, WalletOutcome.NoWallet, WalletOutcome.Failed("offline"))) {
            val w = Wire()
            var built = false
            val r = PaymentPost.after(outcome, { s -> built = true; make(bill)(s) }) { f -> PaymentPost.deliver(f, "olena", PayRoute("grp-cafe", bill), w.toPeer, w.toChat) }
            assertEquals(outcome, r)
            assertFalse("кадр без підпису не складається", built)
            assertTrue("нічого не поїхало після $outcome", w.sent.isEmpty())
        }
    }

    @Test
    fun theWholeGroupLearnsMyWalletSoMyCardCanBeChecked() {
        // Картку перевіряє кожен у групі: без моєї адреси чужий телефон бачить «платник невідомий».
        assertEquals(listOf("olena", "petro", "andrii"), PaymentPost.addressTo("olena", PayRoute("grp-cafe", bill), listOf("petro", "olena", "andrii")))
        assertEquals("заклад: адреса лише учасникам", listOf("petro", "andrii"), PaymentPost.addressTo(null, PayRoute("grp-cafe", bill), listOf("petro", "andrii")))
        assertEquals("1:1 як раніше — лише отримувачу", listOf("olena"), PaymentPost.addressTo("olena", PayRoute(), listOf("petro")))
        assertEquals(emptyList<String>(), PaymentPost.addressTo(null, PayRoute(), emptyList()))
    }

    // ── екран гаманця ────────────────────────────────────────────────────

    private fun opened(vararg extras: Pair<String, String>) = WalletActivity.Opened.read({ k -> extras.toMap()[k] })

    private val olena = SolanaContact("olena", "Олена", to)
    private val venue = SolanaKey.of(ByteArray(32) { 7 })!!

    private fun billForOlena() = opened(
        WalletActivity.EXTRA_PEER to "olena",
        WalletActivity.EXTRA_PAY_COIN to "usdc",
        WalletActivity.EXTRA_PAY_AMOUNT to "7.5",
        WalletActivity.EXTRA_PAY_CHAT to "grp-cafe",
        WalletActivity.EXTRA_PAY_REF to bill,
    )

    @Test
    fun theBillRouteRidesOnlyOnTheDialogThatOpenedItself() {
        val o = billForOlena()
        val petro = SolanaContact("petro", "Петро", SolanaKey.of(ByteArray(32) { 3 }))
        assertTrue(o.billArmed)
        assertEquals(PayRoute("grp-cafe", bill), o.routeFor(olena, cash = false, armed = true))
        assertEquals("інша людина — звичайний переказ", PayRoute(), o.routeFor(petro, cash = false, armed = true))
        assertEquals("готівка рахунку не платить", PayRoute(), o.routeFor(olena, cash = true, armed = true))
        assertFalse("звичайний переказ людині нічого не заряджає", opened(WalletActivity.EXTRA_PEER to "olena").billArmed)
    }

    /**
     * Знахідка 1 рев'ю: закрили аркуш частки, потім тим самим екраном переказали Олені
     * 10 USDC «за інше» — це НЕ частка й не в групу. А от скасований відбиток рахунок
     * не розряджає: друга спроба — та сама частка. Заплачена частка розряджає теж.
     */
    @Test
    fun aClosedBillDialogDisarmsTheRoute_aDeclinedFingerprintDoesNot() {
        val o = billForOlena()
        assertEquals("закрили аркуш — далі звичайний 1:1", PayRoute(), o.routeFor(olena, cash = false, armed = false))
        for (kept in listOf(WalletOutcome.Declined, WalletOutcome.NoWallet, WalletOutcome.Failed("offline"))) {
            assertTrue("після $kept гроші не пішли — частка лишається", WalletActivity.Opened.armedAfter(true, kept))
        }
        val paid = WalletOutcome.Done(Paid(PaymentFrame(Cluster.DEVNET, Coin.USDC, 7_500_000, to, sig, bill), posted = true))
        assertFalse("частку заплачено — наступний переказ Олені вже не частка", WalletActivity.Opened.armedAfter(true, paid))
        assertFalse("розряджене не заряджається знову", WalletActivity.Opened.armedAfter(false, WalletOutcome.Declined))
    }

    /** Знахідка 3 рев'ю: гаманець закладу — не «вписано вручну». */
    @Test
    fun venueWalletIsNotCalledTyped() {
        val o = opened(
            WalletActivity.EXTRA_PAY_VENUE to venue.base58,
            WalletActivity.EXTRA_PAY_CHAT to "grp-cafe",
            WalletActivity.EXTRA_PAY_REF to bill,
        )
        val (c, cash) = o.firstDialog(emptyList())!!
        assertFalse(cash)
        assertEquals(venue, c.address)
        assertEquals(AddressSource.VENUE, o.sourceFor(c))
        assertEquals(PayRoute("grp-cafe", bill), o.routeFor(c, cash, armed = o.billArmed))
        val notes = TransferGuard.check(venue, AddressSource.VENUE, Coin.USDC, Cluster.DEVNET, 1, emptyList())
        assertTrue(GuardNote.Venue in notes)
        assertFalse(GuardNote.Typed in notes)
        assertFalse("не з розмови", TransferGuard.view(venue, AddressSource.VENUE, Coin.USDC, Cluster.DEVNET, 1, emptyList())!!.fromChat)

        // Справді вписана руками адреса лишається вписаною руками.
        val typed = opened(WalletActivity.EXTRA_TO to venue.base58)
        assertEquals(AddressSource.TYPED, typed.sourceFor(typed.firstDialog(emptyList())!!.first))
        // Заклад без групи — картку нікому показати; такий екран нічого не відкриває сам.
        assertNull(opened(WalletActivity.EXTRA_PAY_VENUE to venue.base58).firstDialog(emptyList()))
    }

    /** Знахідка 6 рев'ю: кривий ref — не «картка в групу без ref», а звичайний переказ людині. */
    @Test
    fun aCrookedRefDropsTheWholeBillRoute() {
        val o = opened(WalletActivity.EXTRA_PEER to "olena", WalletActivity.EXTRA_PAY_CHAT to "grp-cafe", WalletActivity.EXTRA_PAY_REF to "a;b")
        assertNull(o.chat)
        assertNull(o.ref)
        assertFalse(o.billArmed)
        assertEquals(PayRoute(), o.routeFor(olena, cash = false, armed = true))
        val v = opened(WalletActivity.EXTRA_PAY_VENUE to venue.base58, WalletActivity.EXTRA_PAY_CHAT to "grp-cafe", WalletActivity.EXTRA_PAY_REF to "x".repeat(37))
        assertNull("заклад із кривим ref не відкривається", v.firstDialog(emptyList()))
    }

    /** Знахідка 4 рев'ю: підказка перемикача адреси каже правду про групу рахунку — обома мовами. */
    @Test
    fun theAddressSharingHintMentionsTheBillGroup() {
        fun hint(dir: String): String {
            val xml = File(moduleRoot(), "core-solana/src/main/res/$dir/strings.xml").readText()
            return Regex("""<string name="sol_share_all_hint">(.*?)</string>""").find(xml)!!.groupValues[1]
        }
        assertTrue(hint("values-uk"), hint("values-uk").contains("груп"))
        assertTrue(hint("values"), hint("values").contains("group"))
    }

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-solana").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь не знайдено") as Nothing
    }

    /** Досяжність: кнопка переказу на екрані гаманця справді віддає маршрут у [SolanaLayer.payPeer]. */
    @Test
    fun theWalletScreenPassesTheRouteIntoPayPeer() {
        val root = File(moduleRoot(), "core-solana/src/main/java/local/phantom/companion/core/solana")
        val screen = File(root, "WalletActivity.kt").readText()
        assertTrue(screen.contains("val route = open.routeFor(c, cash, pay.billArmed)"))
        assertTrue(Regex("""SolanaLayer\.payPeer\([^\n]*, route(, note)?\)""").containsMatchIn(screen))
        assertTrue("закритий аркуш розряджає", Regex("""onDismiss = \{[^\n]*billArmed = false""").containsMatchIn(screen))
        assertTrue("відповідь гаманця розряджає", screen.contains("pay.billArmed = WalletActivity.Opened.armedAfter(pay.billArmed, r)"))
        assertTrue("слова після переказу — за доставкою", screen.contains("PaymentPost.paidLine(r)"))
        assertTrue("сторож знає заклад", screen.contains("open.sourceFor(c)"))
        val layer = File(root, "SolanaLayer.kt").readText()
        assertTrue(layer.contains("PaymentPost.after(") && layer.contains("val posted = PaymentPost.deliver("))
        assertTrue(layer.contains("sendToChat"))
        assertTrue("адреса їде всій групі до картки", layer.contains("syncAddress(PaymentPost.addressTo("))
    }
}
