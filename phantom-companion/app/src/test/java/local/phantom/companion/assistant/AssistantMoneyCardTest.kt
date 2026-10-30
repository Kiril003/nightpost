package local.phantom.companion.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.ui.messenger.WebMessengerKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMoneyCardTest {

    private val olyaKey = SolanaKey.of(ByteArray(32) { 1 })!!
    private val petroKey = SolanaKey.of(ByteArray(32) { 2 })!!
    private val sig = "5" + "1".repeat(86)

    private fun msg(id: String, from: String, to: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at, state = PeerMessage.State.DELIVERED)

    @Test
    fun `money overview questions get the money card, transfer commands and unrelated asks do not`() {
        for (ask in listOf(
            "Скільки я відправив цього тижня?",
            "скільки я переказав за тиждень",
            "Хто мені винен?",
            "кому я винен",
            "Мій баланс",
            "Який у мене баланс?",
            "Скільки в мене на рахунку?",
            "Nightpost, що там по грошах?",
            "Мої гроші",
            "Гроші за тиждень",
            "How much did I send this week?",
            "Who owes me?",
            "My balance",
            "Kolik jsem poslal tento týden?",
            "Kdo mi dluží?",
        )) assertTrue(ask, AssistantMoneyCard.asks(ask))

        for (no in listOf(
            "Скинь Олі 5 USDC",
            "Передай Петру 10 USDC",
            "Що в мене сьогодні?",
            "Що там з Олею?",
            "Нагадай завтра о 9:00 про рахунок",
            "",
        )) assertFalse(no, AssistantMoneyCard.asks(no))
    }

    @Test
    fun `summary holds balances, last 7 days sends by person, open bill splits and test flag`() {
        val me = "node_me"
        val olya = "node_olya"
        val petro = "node_petro"
        val now = 10 * 86_400_000L

        val billToMe = PeerCard.encode(
            PeerCard.Body(
                PeerCard.Card.BILL,
                """{"v":1,"id":"bill_1","meetupId":null,"venueId":"request","title":"Кава","token":"USDC","items":[],"payee":{"kind":"peer","id":"$me"},"people":[{"id":"$olya","name":"Оля Коваль"},{"id":"$petro","name":"Петро"}],"asks":[{"id":"$olya","amount":"4"},{"id":"$petro","amount":"6"}]}""",
            ),
        )!!
        val billFromOlya = PeerCard.encode(
            PeerCard.Body(
                PeerCard.Card.BILL,
                """{"v":1,"id":"bill_2","meetupId":null,"venueId":"request","title":"Обід","token":"USDC","items":[],"payee":{"kind":"peer","id":"$olya"},"people":[{"id":"$me","name":"Кирило"}],"asks":[{"id":"$me","amount":"9.5"}]}""",
            ),
        )!!
        val petroPaidRef = PaymentFrame(Cluster.DEVNET, Coin.USDC, 6_000_000, olyaKey, sig, ref = "bill_1").encode()

        val log = listOf(
            // Старіший за 7 днів — не входить у тиждень
            msg("old", me, olya, PaymentFrame(Cluster.DEVNET, Coin.USDC, 20_000_000, olyaKey, sig).encode(), now - 8 * 86_400_000L),
            // Цього тижня Олі двічі і Петру раз
            msg("w1", me, olya, PaymentFrame(Cluster.DEVNET, Coin.USDC, 5_000_000, olyaKey, sig).encode(), now - 2 * 86_400_000L),
            msg("w2", me, olya, PaymentFrame(Cluster.DEVNET, Coin.USDC, 1_500_000, olyaKey, sig).encode(), now - 86_400_000L),
            msg("w3", me, petro, PaymentFrame(Cluster.DEVNET, Coin.SOL, 200_000_000, petroKey, sig).encode(), now - 3_600_000L),
            // Рахунки: Петро вже сплатив свою частку bill_1, Оля винна 4, а я винен Олі 9.5 -> разом я винен Олі 5.5 USDC
            msg("b1", me, olya, billToMe, now - 50_000L),
            msg("p1", petro, me, petroPaidRef, now - 40_000L),
            msg("b2", olya, me, billFromOlya, now - 30_000L),
        )

        val s = AssistantMoneyCard.summary(
            me = me,
            people = listOf(olya to "Оля Коваль", petro to "Петро"),
            walletOf = { id -> if (id == olya) olyaKey else if (id == petro) petroKey else null },
            log = log,
            walletReady = true,
            balances = mapOf(Coin.USDC to 42_500_000L, Coin.SOL to 1_250_000_000L),
            cluster = Cluster.DEVNET,
            nowMs = now,
        )

        assertTrue(s.wallet)
        assertTrue(s.test)
        assertEquals(
            listOf(AssistantMoneyCard.Balance("USDC", "42.5"), AssistantMoneyCard.Balance("SOL", "1.25")),
            s.balances,
        )
        assertEquals(
            listOf(
                AssistantMoneyCard.Sent(petro, "Петро", "SOL", "0.2"),
                AssistantMoneyCard.Sent(olya, "Оля Коваль", "USDC", "6.5"),
            ),
            s.week,
        )
        assertEquals(
            listOf(AssistantMoneyCard.Split(olya, olya, "Оля Коваль", "USDC", "5.5", owesMe = false)),
            s.splits,
        )

        val wire = AssistantMoneyCard.body(s)
        val card = PeerCard.decode(wire)!!
        assertEquals(PeerCard.Card.MONEY, card.what)
        val json = Json.parseToJsonElement(card.payload).jsonObject
        assertEquals(1, json["v"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["balances"]!!.jsonArray.size)
        assertEquals(2, json["week"]!!.jsonArray.size)
        assertEquals(1, json["splits"]!!.jsonArray.size)
        assertEquals("true", json["test"]!!.jsonPrimitive.content)

        val fields = WebMessengerKinds.fields(wire)
        assertEquals("widget:money", fields[WebMessengerKinds.KIND])
        assertEquals("moneyData", fields[WebMessengerKinds.CARD_FIELD])
        assertTrue(WebMessengerKinds.isVisible(wire))
    }

    @Test
    fun `empty state on mainnet has no balances, no week sends, no splits and test is false`() {
        val s = AssistantMoneyCard.summary(
            me = "node_me",
            people = emptyList(),
            walletOf = { null },
            log = emptyList(),
            walletReady = false,
            balances = null,
            cluster = Cluster.MAINNET,
            nowMs = 1_000L,
        )
        assertFalse(s.wallet)
        assertTrue(s.balances.isEmpty())
        assertTrue(s.week.isEmpty())
        assertTrue(s.splits.isEmpty())
        assertFalse(s.test)
    }
}
