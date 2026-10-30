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
import local.phantom.companion.self.Commitment
import local.phantom.companion.ui.messenger.WebMessengerKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPersonCardTest {

    private val key = SolanaKey.of(ByteArray(32) { 1 })!!
    private val sig = "5" + "1".repeat(86)
    private fun msg(id: String, from: String, to: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at, state = PeerMessage.State.DELIVERED)
    private fun promise(id: String, peerId: String?, name: String, what: String, due: Long?) =
        Commitment(id = id, text = "пообіцяв $what", personName = name, peerId = peerId, what = what, dueMs = due, createdMs = 1L, sourceMessageId = "s$id")

    @Test
    fun `asks about a person give the name words, other questions do not`() {
        assertEquals(listOf("олею"), AssistantPersonCard.who("Що там з Олею?"))
        assertEquals(listOf("олею"), AssistantPersonCard.who("що з Олею"))
        assertEquals(listOf("петром"), AssistantPersonCard.who("Nightpost, як там з Петром?"))
        assertEquals(listOf("олею", "коваль"), AssistantPersonCard.who("А що нового з Олею Коваль"))
        assertEquals(listOf("olya"), AssistantPersonCard.who("What's up with Olya?"))
        assertEquals(listOf("olou"), AssistantPersonCard.who("Co je s Olou?"))
        for (no in listOf("Скинь Олі 5 USDC", "Що в мене сьогодні?", "Хто з моїх друзів поруч?", "що там з Олею і Петром і Іваном", "Розкажи анекдот", "")) {
            assertNull(no, AssistantPersonCard.who(no))
        }
    }

    @Test
    fun `only a person from my people is picked, the one I talked to last`() {
        val people = listOf("o1" to "Оля Коваль", "o2" to "Оля Бондар", "p" to "Петро", "g" to "Олег")
        val last = mapOf("o1" to 10L, "o2" to 50L, "p" to 99L, "g" to 120L)
        fun pick(said: List<String>) = AssistantPersonCard.pick(said, people, { it.second }) { last.getValue(it.first) }?.first
        assertEquals("o2", pick(listOf("олею")))
        assertEquals("o1", pick(listOf("олею", "коваль")))
        assertEquals("p", pick(listOf("петром")))
        assertNull(pick(listOf("погодою")))
        assertNull(pick(listOf("посилкою")))
    }

    @Test
    fun `the glance holds the last words, my open promises to her and money both ways`() {
        val me = "node_me"
        val olya = "node_olya"
        val fromHer = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_000_000, key, sig).encode()
        val log = listOf(
            msg("1", me, olya, "Привіт", 100),
            msg("2", olya, me, fromHer, 200),
            msg("3", olya, me, "Ти   встигаєш\nна 10?", 300),
            msg("4", olya, me, "PH-CARD:xx", 400),
        )
        val g = AssistantPersonCard.glance(
            peerId = olya,
            name = "Оля Коваль",
            conversation = log,
            isText = { !it.startsWith("PH") },
            open = listOf(
                promise("a", olya, "Олі", "скинути фото", 900),
                promise("b", null, "Олі", "повернути книгу", null),
                promise("c", "node_petro", "Петру", "звіт", 50),
            ),
            sent = listOf(PaymentFrame(Cluster.DEVNET, Coin.USDC, 5_000_000, key, sig), PaymentFrame(Cluster.DEVNET, Coin.USDC, 1_500_000, key, sig)),
            cluster = Cluster.DEVNET,
            nowMs = 1_000,
        )
        assertEquals(AssistantPersonCard.Said("Ти встигаєш на 10?", 300, mine = false), g.last)
        assertEquals(listOf("скинути фото", "повернути книгу"), g.promises.map { it.what })
        assertEquals(listOf(AssistantPersonCard.Flow("USDC", "6.5", "2")), g.money)
        assertTrue(g.test)

        val body = AssistantPersonCard.body(g)
        val card = PeerCard.decode(body)!!
        assertEquals(PeerCard.Card.GLANCE, card.what)
        val json = Json.parseToJsonElement(card.payload).jsonObject
        assertEquals("node_olya", json["peer"]!!.jsonPrimitive.content)
        assertEquals("false", json["last"]!!.jsonObject["mine"]!!.jsonPrimitive.content)
        assertEquals(2, json["promises"]!!.jsonArray.size)
        val fields = WebMessengerKinds.fields(body)
        assertEquals("widget:glance", fields[WebMessengerKinds.KIND])
        assertEquals("glanceData", fields[WebMessengerKinds.CARD_FIELD])
        assertTrue(WebMessengerKinds.isVisible(body))
    }

    @Test
    fun `nothing between us is an honest empty glance`() {
        val g = AssistantPersonCard.glance("p", "Петро", emptyList(), { true }, emptyList(), emptyList(), Cluster.MAINNET, 5)
        assertNull(g.last)
        assertTrue(g.promises.isEmpty())
        assertTrue(g.money.isEmpty())
        assertFalse(g.test)
    }
}
