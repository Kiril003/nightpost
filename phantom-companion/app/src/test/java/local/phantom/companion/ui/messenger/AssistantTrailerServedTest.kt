package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лист помічника, записаний до того, як трейлер ⟦NEXT⟧ став зніматися при записі, лежить
 * у журналі з ним, а міст віддає журнал як є. Сторінка бере слова з трьох місць — стрічка,
 * рядок переліку, пошук — і в кожному з них слова помічника проходять через [SpokenFold.forPage].
 * Слова людини лишаються як набрані.
 */
class AssistantTrailerServedTest {

    private val bot = AgencySessionManager.PHANTOM_AGENT_ID
    private val me = "me"
    private val notices = AssistantNotices.of(listOf("not a real notice"), emptyList())

    private val words = "Sunsets look red because the shorter wavelengths scatter away and the longer wavelengths reach you."
    private val stored = "$words\n⟦NEXT⟧ why are sunsets red | why not violet | what color is space"

    private fun line(from: String, to: String, body: String, at: Long) =
        PeerMessage(id = "$from@$at", fromId = from, toId = to, body = body, sentAtMs = at)

    private val ask = line(me, bot, "why is the sky red at dusk", 1_000L)
    private val oldReply = line(bot, me, stored, 2_000L)

    @Test
    fun `the message list gives the page an old assistant line without its trailer`() {
        val log = listOf(ask, oldReply)

        val served = WebMessengerKinds.feed(log).map { SpokenFold.forPage(it) }

        assertEquals(listOf(ask.body, words), served.map { it.body })
        assertEquals(listOf(ask.id, oldReply.id), served.map { it.id })
        assertEquals(listOf(1_000L, 2_000L), served.map { it.sentAtMs })
        assertEquals(WebMessengerKinds.TEXT, WebMessengerKinds.fields(served[1].body)[WebMessengerKinds.KIND])
    }

    @Test
    fun `the chat list snippet of the assistant row is the words`() {
        val log = listOf(ask, oldReply)

        val last = WebMessengerKinds.lastForList(log, bot, notices)?.let { SpokenFold.forPage(it) }

        assertEquals(words, last?.body)
        assertEquals(2_000L, last?.sentAtMs)
    }

    @Test
    fun `search finds the words of an old assistant line and never its trailer`() {
        val source = WebMessengerSearch.Source(bot, listOf(ask, oldReply).map { SpokenFold.forPage(it) })

        val byWords = WebMessengerSearch.search("longer wavelengths", listOf(source))
        assertEquals(listOf(oldReply.id), byWords.hits.map { it.messageId })
        assertEquals(words, byWords.hits.single().text)

        val byTrailer = WebMessengerSearch.search("why not violet", listOf(source))
        assertTrue("a hint from the service line must not be a hit", byTrailer.hits.isEmpty())
    }

    @Test
    fun `a line of a person that holds the literal marker is not altered`() {
        val typed = line(me, bot, "the model ends replies with ⟦NEXT⟧ keep it | literally | in my words", 3_000L)
        val fromFriend = line("friend", me, "⟦NEXT⟧ a | b | c", 4_000L)

        assertSame(typed, SpokenFold.forPage(typed))
        assertSame(fromFriend, SpokenFold.forPage(fromFriend))

        val log = listOf(ask, oldReply, typed)
        val served = WebMessengerKinds.feed(log).map { SpokenFold.forPage(it) }
        assertEquals(listOf(ask.body, words, typed.body), served.map { it.body })

        val last = WebMessengerKinds.lastForList(log, bot, notices)?.let { SpokenFold.forPage(it) }
        assertEquals(typed.body, last?.body)

        val source = WebMessengerSearch.Source(bot, log.map { SpokenFold.forPage(it) })
        val hit = WebMessengerSearch.search("keep it | literally", listOf(source)).hits.single()
        assertEquals(typed.id, hit.messageId)
        assertEquals(typed.body, hit.text)
    }

    @Test
    fun `an assistant line without a trailer is the same line`() {
        val clean = line(bot, me, words, 5_000L)
        assertSame(clean, SpokenFold.forPage(clean))
    }

    @Test
    fun `the bridge serves the assistant conversation through the fold in all three places`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("companion root not found")
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()

        val list = bridge.substringAfter("private fun messages(peerId: String, afterSeq: Int)").substringBefore("private fun reactionsOf(")
        assertTrue("message list does not fold", "WebMessengerKinds.feed(allMessages).map { SpokenFold.forPage(it) }" in list)

        val row = bridge.substringAfter("private fun phantomConversation(").substringBefore("private fun agencyConversation(")
        assertTrue(
            "list row does not fold",
            "lastForList(log, AgencySessionManager.PHANTOM_AGENT_ID, assistantNotices)?.let { SpokenFold.forPage(it) }" in row,
        )

        val search = bridge.substringAfter("private fun searchAll(").substringBefore("private fun report(")
        assertTrue(
            "search does not fold",
            "container.mesh.conversation(AgencySessionManager.PHANTOM_AGENT_ID).map { SpokenFold.forPage(it) }" in search,
        )
    }
}
