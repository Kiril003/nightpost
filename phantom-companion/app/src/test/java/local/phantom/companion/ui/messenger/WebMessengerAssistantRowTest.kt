package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-аудит №1 (09.10): помічник закріплений угорі списку, а його рядок
 * показував відмову «мій розум на цьому телефоні ще не ввімкнено» — перше,
 * що бачить суддя. Відмова лишається в розмові, але не стає рядком списку.
 */
class WebMessengerAssistantRowTest {

    private val bot = "phantom"
    private val notice = "I can't think this through right now: my mind isn't switched on on this phone yet."
    private val notices = AssistantNotices.of(listOf(notice), emptyList())

    private fun line(from: String, to: String, body: String, at: Long) =
        PeerMessage(id = "$from@$at", fromId = from, toId = to, body = body, sentAtMs = at)

    @Test
    fun `the refusal is not the list row - the person's own line is`() {
        val log = listOf(
            line("me", bot, "what can you do", 1_000L),
            line(bot, "me", notice, 2_000L),
            line("me", bot, "hi", 3_000L),
            line(bot, "me", "  $notice ", 4_000L),
        )
        assertEquals("hi", WebMessengerKinds.lastForList(log, bot, notices)?.body)
        // У розмові відмова лишається останньою — там вона чесна.
        assertEquals(4_000L, WebMessengerKinds.lastVisible(log, bot)?.sentAtMs)
    }

    @Test
    fun `only refusals - the row has no letter at all`() {
        assertNull(WebMessengerKinds.lastForList(listOf(line(bot, "me", notice, 2_000L)), bot, notices))
    }

    @Test
    fun `a real answer stays the row`() {
        val log = listOf(line("me", bot, "remember milk", 1_000L), line(bot, "me", "Remembered: milk.", 2_000L))
        assertEquals("Remembered: milk.", WebMessengerKinds.lastForList(log, bot, notices)?.body)
    }

    @Test
    fun `the bridge builds the assistant row from lastForList with both languages`() {
        val mono = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "phantom-companion/app").isDirectory }
        val bridge = File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val row = bridge.substringAfter("private fun phantomConversation(").take(300)
        assertTrue("рядок помічника бере відмову як останній лист", "lastForList(log, AgencySessionManager.PHANTOM_AGENT_ID, assistantNotices)" in row)
        assertTrue("AssistantNotices.load(" in bridge.substringAfter("private val assistantNotices").take(200))
        val set = File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/AssistantNotices.kt").readText()
        assertTrue("R.string.self_no_brain" in set && "R.string.self_ask_no_mind" in set)
        assertTrue("лише одна мова відмов", "\"uk\"" in set && "Locale.ENGLISH" in set)
    }
}
