package local.phantom.companion.data

import local.phantom.companion.core.solana.AddressSource
import local.phantom.companion.core.solana.CashGap
import local.phantom.companion.core.solana.CashOnHand
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.GuardNote
import local.phantom.companion.core.solana.PaymentDraft
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaContact
import local.phantom.companion.core.solana.SolanaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAssistantTest {

    private val olenaKey = SolanaKey.of(ByteArray(32) { 1 })!!
    private val olena2Key = SolanaKey.of(ByteArray(32) { 2 })!!
    private val kyryloKey = SolanaKey.of(ByteArray(32) { 3 })!!
    private val sig = "5" + "1".repeat(86)

    private class FakeWallet(
        val contacts: List<SolanaContact>,
        val history: List<Pair<String, PaymentFrame>> = emptyList(),
        val ready: Boolean = true,
        val net: Boolean = true,
        val pocket: CashOnHand = CashOnHand(null, 0, builtIn = true),
    ) : MoneyAssistant.Hands {
        val drafts = ArrayList<PaymentDraft>()
        val cashDrafts = ArrayList<PaymentDraft>()
        var sealPickers = 0

        override fun people() = contacts
        override fun sent() = history
        override fun cluster() = Cluster.DEVNET
        override fun walletReady() = ready
        override fun online() = net
        override fun cashOnHand() = pocket
        override fun openDraft(draft: PaymentDraft, cash: Boolean) { if (cash) cashDrafts += draft else drafts += draft }
        override fun openSealPicker() { sealPickers++ }
    }

    private val people = listOf(
        SolanaContact("p1", "Олена Коваль", olenaKey),
        SolanaContact("p3", "Кирило", kyryloKey),
        SolanaContact("p4", "Тарас", null),
    )

    @Test
    fun `phrase only opens a filled draft`() {
        val wallet = FakeWallet(people)
        val cases = listOf(
            Triple("скинь Олені 5 USDC", "p1", 5_000_000L to Coin.USDC),
            Triple("передай Олені 5 USDC", "p1", 5_000_000L to Coin.USDC),
            Triple("переказати Кирилу 0.1 SOL", "p3", 100_000_000L to Coin.SOL),
            Triple("скинь Олені 5,5 usdc", "p1", 5_500_000L to Coin.USDC),
            Triple("скинь Олені п'ять USDC", "p1", 5_000_000L to Coin.USDC),
            Triple("переказ Кирилу 0.000001 USDC", "p3", 1L to Coin.USDC),
            Triple("скинь Олені 5 євро", "p1", 5_000_000L to Coin.EURC),
            Triple("переказати Кирилу €2,5", "p3", 2_500_000L to Coin.EURC),
        )
        for ((phrase, peer, money) in cases) {
            val assistant = MoneyAssistant(wallet)
            val r = assistant.hear(phrase) as? MoneyAssistant.Reply.Drafted ?: error("no draft: $phrase")
            assertEquals(phrase, peer, r.draft.peerId)
            assertEquals(phrase, money.first, r.draft.units)
            assertEquals(phrase, money.second, r.draft.coin)
            assertEquals(phrase, AddressSource.CONVERSATION, r.draft.source)
            assistant.act(r)
        }
        assertEquals(cases.size, wallet.drafts.size)
        assertEquals(0, wallet.sealPickers)
    }

    @Test
    fun `hearing opens nothing — the screen comes only after the reply`() {
        val wallet = FakeWallet(people, net = false, pocket = CashOnHand(Coin.USDC, 5_000_000, builtIn = true))
        val assistant = MoneyAssistant(wallet)
        val r = assistant.hear("передай Олені 5 USDC")!!
        val seal = assistant.hear("засвідчи останнє фото")!!
        assertTrue(wallet.drafts.isEmpty() && wallet.cashDrafts.isEmpty() && wallet.sealPickers == 0)
        assistant.act(r)
        assistant.act(seal)
        assertEquals(1, wallet.cashDrafts.size)
        assertEquals(1, wallet.sealPickers)
    }

    @Test
    fun `below the smallest unit is refused and nothing opens`() {
        val wallet = FakeWallet(people)
        val r = MoneyAssistant(wallet).hear("переказ Кирилу 0.0000001 USDC")
        assertEquals(MoneyAssistant.Reply.BadAmount("0.0000001", Coin.USDC, "0.000001"), r)
        assertTrue(wallet.drafts.isEmpty())
    }

    @Test
    fun `two Olenas is a question`() {
        val wallet = FakeWallet(people + SolanaContact("p2", "Олена Шевчук", olena2Key))
        assertEquals(MoneyAssistant.Reply.WhichOne(listOf("Олена Коваль", "Олена Шевчук")), MoneyAssistant(wallet).hear("скинь Олені 5 USDC"))
        assertTrue(wallet.drafts.isEmpty())
    }

    @Test
    fun `no wallet and nobody are said honestly`() {
        val wallet = FakeWallet(people)
        assertEquals(MoneyAssistant.Reply.NoWallet(listOf("Тарас")), MoneyAssistant(wallet).hear("скинь Тарасу 5 USDC"))
        assertEquals(MoneyAssistant.Reply.Nobody("ігорю"), MoneyAssistant(wallet).hear("скинь Ігорю 5 USDC"))
        assertEquals(MoneyAssistant.Reply.WhoMissing, MoneyAssistant(wallet).hear("скинь 5 USDC"))
        assertEquals(MoneyAssistant.Reply.NoOwnWallet, MoneyAssistant(FakeWallet(people, ready = false)).hear("скинь Олені 5 USDC"))
        assertTrue(wallet.drafts.isEmpty())
    }

    @Test
    fun `ordinary talk is not taken`() {
        val wallet = FakeWallet(people)
        assertNull(MoneyAssistant(wallet).hear("скинь мені посилання на карту"))
        assertNull(MoneyAssistant(wallet).hear("яка погода"))
    }

    @Test
    fun `guard speaks from the chat journal`() {
        val past = listOf(4L, 5L, 6L).map { "p1" to PaymentFrame(Cluster.DEVNET, Coin.USDC, it * 1_000_000, olenaKey, sig) }
        val big = MoneyAssistant(FakeWallet(people, past)).hear("скинь Олені 50 USDC") as MoneyAssistant.Reply.Drafted
        assertEquals(listOf(GuardNote.Unusual("6", Coin.USDC)), big.notes)
        val first = MoneyAssistant(FakeWallet(people)).hear("скинь Кирилу 1 USDC") as MoneyAssistant.Reply.Drafted
        assertEquals(listOf(GuardNote.FirstTime), first.notes)
    }

    @Test
    fun `typed address is a draft with a warning`() {
        val stranger = SolanaKey.of(ByteArray(32) { 9 })!!
        val wallet = FakeWallet(people)
        val r = MoneyAssistant(wallet).hear("скинь 1 USDC на ${stranger.base58}") as MoneyAssistant.Reply.Drafted
        assertEquals(null, r.draft.peerId)
        assertEquals(AddressSource.TYPED, r.draft.source)
        assertTrue(GuardNote.Typed in r.notes)
        val known = MoneyAssistant(wallet).hear("скинь 1 USDC на ${kyryloKey.base58}") as MoneyAssistant.Reply.Drafted
        assertEquals("p3", known.draft.peerId)
        assertEquals(AddressSource.CONVERSATION, known.draft.source)
    }

    @Test
    fun `seal opens the photo picker only`() {
        val wallet = FakeWallet(people)
        val assistant = MoneyAssistant(wallet)
        assertEquals(MoneyAssistant.Reply.SealPicker, assistant.hear("засвідчи останнє фото"))
        assistant.act(MoneyAssistant.Reply.SealPicker)
        assertEquals(1, wallet.sealPickers)
        assertTrue(wallet.drafts.isEmpty())
    }

    @Test
    fun `assistant has no hand that signs, pays or sends`() {
        val hands = MoneyAssistant.Hands::class.java.methods + MoneyAssistant::class.java.declaredMethods
        val forbidden = Regex("sign|pay|send|transfer|seal\\b", RegexOption.IGNORE_CASE)
        hands.forEach { m ->
            assertTrue(m.name, !forbidden.containsMatchIn(m.name) || m.name == "sent")
            m.parameterTypes.forEach { t -> assertTrue("${m.name}(${t.simpleName})", t.simpleName != "WalletHost") }
        }
    }

    @Test
    fun `online opens a transfer even with cash in the pocket`() {
        val wallet = FakeWallet(people, pocket = CashOnHand(Coin.USDC, 20_000_000, builtIn = true))
        val assistant = MoneyAssistant(wallet)
        val r = assistant.hear("передай Олені 5 USDC") as MoneyAssistant.Reply.Drafted
        assistant.act(r)
        assertEquals(false, r.cash)
        assertEquals(1, wallet.drafts.size)
        assertTrue(wallet.cashDrafts.isEmpty())
    }

    @Test
    fun `offline with enough notes opens the cash dialog for the same person, coin and amount`() {
        val wallet = FakeWallet(people, net = false, pocket = CashOnHand(Coin.USDC, 5_000_000, builtIn = true))
        val assistant = MoneyAssistant(wallet)
        val r = assistant.hear("передай Олені 5 USDC") as MoneyAssistant.Reply.Drafted
        assistant.act(r)
        assertEquals(true, r.cash)
        assertTrue(wallet.drafts.isEmpty())
        val d = wallet.cashDrafts.single()
        assertEquals("p1", d.peerId)
        assertEquals(Coin.USDC, d.coin)
        assertEquals(5_000_000L, d.units)
        assertEquals(olenaKey, d.to)
    }

    @Test
    fun `offline without fitting cash is one honest sentence and nothing opens`() {
        fun offline(pocket: CashOnHand, phrase: String = "передай Олені 5 USDC"): Pair<MoneyAssistant.Reply?, FakeWallet> {
            val w = FakeWallet(people, net = false, pocket = pocket)
            val a = MoneyAssistant(w)
            return a.hear(phrase)?.also(a::act) to w
        }
        val cases = listOf(
            offline(CashOnHand(null, 0, builtIn = true)) to CashGap.Empty,
            offline(CashOnHand(Coin.USDC, 0, builtIn = true)) to CashGap.Empty,
            offline(CashOnHand(Coin.SOL, 900_000_000, builtIn = true)) to CashGap.OtherCoin(Coin.SOL, Coin.USDC),
            offline(CashOnHand(Coin.USDC, 4_999_999, builtIn = true)) to CashGap.Short(4_999_999, Coin.USDC),
            offline(CashOnHand(Coin.USDC, 50_000_000, builtIn = false)) to CashGap.External,
            offline(CashOnHand(Coin.USDC, 50_000_000, builtIn = true), "скинь 1 USDC на ${SolanaKey.of(ByteArray(32) { 9 })!!.base58}") to CashGap.NotAPerson,
        )
        for ((got, gap) in cases) {
            assertEquals(MoneyAssistant.Reply.OfflineNoCash(gap), got.first)
            assertTrue(got.second.drafts.isEmpty() && got.second.cashDrafts.isEmpty())
        }
    }
}
