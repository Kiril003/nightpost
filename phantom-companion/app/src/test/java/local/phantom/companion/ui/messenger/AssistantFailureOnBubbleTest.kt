package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поразка помічника показується на бульбашці ПИТАННЯ людини («Помічник недоступний · Ще раз»),
 * а не листом від імені помічника, і «Ще раз» шле те саме питання тим самим шляхом.
 * Мапу причин тримає [FailureWordsTest]; тут — слова й двері.
 */
class AssistantFailureOnBubbleTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища")

    private fun src(rel: String) = File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    private fun failStrings(dir: String): Map<String, String> {
        val xml = File(root, "app/src/main/res/$dir/strings.xml").readText()
        return Regex("""<string name="(ai_fail_[a-z_]+)">([^<]*)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    @Test
    fun `every failure line is a short capitalised label in both languages`() {
        for (dir in listOf("values-uk", "values")) {
            val lines = failStrings(dir)
            assertTrue("$dir: ai_fail_* не знайдено", lines.size >= 14)
            lines.forEach { (name, text) ->
                assertTrue("$dir/$name починається не з великої: «$text»", text.first().isUpperCase())
                assertTrue("$dir/$name задовге для ярлика: «$text»", text.length <= 32)
                assertFalse("$dir/$name — речення з тире: «$text»", " — " in text)
                assertFalse("$dir/$name закінчується крапкою: «$text»", text.endsWith("."))
            }
        }
    }

    @Test
    fun `a failed question is marked on its own row and carries the reason`() {
        val agency = src("ui/messenger/AgencySessionManager.kt")
        val ask = agency.substringAfter("private fun failAsk(").substringBefore("fun retryAsk(")
        assertTrue("питання не стає FAILED", "markDirect(questionId, PeerMessage.State.FAILED)" in ask)
        assertTrue("причина не запамʼятана для бульбашки", "failedAsks[questionId] = reason" in ask)
        assertTrue("знак «не пішло» має лягти раніше за живий стан", ask.indexOf("markDirect(") < ask.indexOf("failLive(messageId, reason)"))
        val onBubble = ask.substringAfter("failedAsks[questionId] = reason")
        assertFalse("поразка питання з листом усе ще йде листом помічника", "meId" in onBubble)

        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        val line = bridge.substringAfter("// Питання без відповіді: причина").substringBefore(".let { relayInto(it, m) }")
        assertTrue("міст не віддає причину вебові", "put(\"fail_reason\", it)" in line && "failureOf(m.id)" in line)
    }

    @Test
    fun `retry resends the same question through the same dispatch, without a second row`() {
        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        val branch = bridge.substringAfter("if (peerId == AgencySessionManager.PHANTOM_AGENT_ID) {").substringBefore("if (peerId.startsWith(\"agency_\"))")
        val retry = branch.indexOf("agencyManager.retryAsk(known, me)")
        val fresh = branch.indexOf("val userMsg = PeerMessage(")
        assertTrue("«Ще раз» не доходить до retryAsk", retry in 0 until fresh)
        assertTrue("повтор шукає лист за client_id у журналі", "o.optString(\"client_id\")" in branch.substring(0, retry))
        assertTrue("повтор піднімає лише питання, що справді не пішло", "known.state == PeerMessage.State.FAILED" in branch.substring(0, retry))
        assertTrue("знайомий client_id ніколи не дає другого питання", "return ok(message(known" in branch.substring(retry, fresh))
        assertTrue("звичайне питання не передає id свого листа", "dispatchPhantomDialogue(text, me, questionId = userMsg.id)" in branch)

        val agency = src("ui/messenger/AgencySessionManager.kt")
        val again = agency.substringAfter("fun retryAsk(").substringBefore("\n    }\n")
        assertTrue(again, "dispatchPhantomDialogue(question.body, meId, questionId = question.id)" in again && "markDirect(question.id, PeerMessage.State.DELIVERED)" in again)
        assertFalse("повтор кладе новий лист від людини", "ingestDirect(" in again)
    }

    @Test
    fun `a spoken question fails on its bubble too`() {
        val voice = src("self/PhantomVoice.kt")
        val calls = Regex("""dispatchPhantomDialogue\(said, asked\.fromId, \w+, asked\.id\)""").findAll(voice).count()
        assertTrue("кожен голосовий хід (ask і heard) має нести id свого питання, а їх $calls", calls == 2)
    }
}
