package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Картка 01 asst-os: ключ і сіль вкладення ніколи не стають текстом для моделі. */
class AssistantAttachGuardTest {

    private val me = "me"
    private val bot = AgencySessionManager.PHANTOM_AGENT_ID
    private val notices = AssistantNotices.of(listOf("Бачу лише текст і підпис"), emptyList())

    private val key = "0123456789abcdef".repeat(4)
    private val nonce = "fedcba987654321001234567"
    private val sha = "abcdef0123456789".repeat(4)
    private fun descriptor(extra: String = "") =
        """{"name":"IMG_1.jpg","size":204800,"mime":"image/jpeg","sha256":"$sha","blob_id":"b_7f3a9c","key_hex":"$key","nonce_hex":"$nonce"$extra}"""

    private val longHex = Regex("[0-9a-fA-F]{24,}")

    private fun line(from: String, body: String, at: Long) =
        PeerMessage(id = "$from@$at", fromId = from, toId = if (from == me) bot else me, body = body, sentAtMs = at)

    private fun leaks(text: String) = "key_hex" in text || "nonce_hex" in text || "blob_id" in text || longHex.containsMatchIn(text)

    @Test
    fun `an earlier photo is not replayed to the model as its descriptor`() {
        val log = listOf(
            line(me, descriptor(), 1),
            line(bot, "Бачу лише текст і підпис", 2),
            line(me, descriptor(""","caption":"що це за міст?""""), 3),
            line(bot, "Схоже на Карлів міст.", 4),
            line(me, "а коли його збудували?", 5),
        )

        val turns = DialogueTurns.history(log, me, "а коли його збудували?", notices) { it.body }

        assertFalse("history carries a descriptor: $turns", turns.any { leaks(it.content) })
        assertEquals(listOf("що це за міст?", "Схоже на Карлів міст."), turns.map { it.content })
    }

    @Test
    fun `a voice note just sent is asked once, by its transcript`() {
        val voice = descriptor(""","kind":"voice","duration":4,"waveform":[1,2],"transcript":"нагадай про хліб"""")
        val log = listOf(line(me, "привіт", 1), line(bot, "Привіт.", 2), line(me, voice, 3))

        val turns = DialogueTurns.history(log, me, "нагадай про хліб", notices) { it.body }

        assertEquals(listOf("привіт", "Привіт."), turns.map { it.content })
    }

    @Test
    fun `the guard keeps words and drops keys, failing closed`() {
        assertEquals("купи хліб", AssistantInput.promptOf("купи хліб"))
        assertEquals("""{"a":1}""", AssistantInput.promptOf("""{"a":1}"""))
        assertEquals("що це за міст?", AssistantInput.promptOf(descriptor(""","caption":"що це за міст?"""")))
        assertEquals(null, AssistantInput.promptOf(descriptor()))
        assertEquals(null, AssistantInput.promptOf(descriptor().take(200)))
        assertEquals(null, AssistantInput.promptOf("""{"__msg":${descriptor()}}"""))
    }

    private fun quoted(json: String) = "\"" + json.replace("\"", "\\\"") + "\""

    @Test
    fun `an escaped or pasted descriptor stays closed`() {
        assertEquals(null, AssistantInput.promptOf(quoted(descriptor())))
        assertEquals(null, AssistantInput.promptOf(descriptor(""","caption":${quoted(descriptor())}""")))
        assertEquals(null, AssistantInput.promptOf("перешли blob_id b_7f3a9c"))
    }

    @Test
    fun `only the first sheet of a batch speaks`() {
        assertTrue(AssistantInput.isBatchTail(descriptor(""","batch_id":"p1","batch_index":2,"batch_total":3""")))
        assertFalse(AssistantInput.isBatchTail(descriptor(""","batch_id":"p1","batch_index":0,"batch_total":3""")))
        assertFalse(AssistantInput.isBatchTail("""{"batch_index":2}"""))
    }

    private val blob = "0123456789abcdef".repeat(2)
    private fun sheet(extra: String = "") =
        """{"name":"IMG_1.jpg","size":204800,"mime":"image/jpeg","sha256":"$sha","blob_id":"$blob","key_hex":"$key","nonce_hex":"$nonce"$extra}"""

    @Test
    fun `the chat list names an attachment instead of printing its wire`() {
        assertEquals("image" to "IMG_1.jpg", AssistantInput.listLine(sheet()))
        assertEquals("image" to "міст", AssistantInput.listLine(sheet(""","caption":"міст"""")))
        assertEquals("voice" to null, AssistantInput.listLine(sheet(""","kind":"voice"""")))
        assertEquals("text" to "купи хліб", AssistantInput.listLine("  купи\n хліб "))
        assertEquals("text" to null, AssistantInput.listLine(descriptor().take(200)))
    }

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app").isDirectory && File(it, "core-ai").isDirectory }

    private fun src(path: String) = File(root(), path).readText()
    private val agency get() = src("app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt")
    private val bridge get() = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")

    @Test
    fun `every road to the model passes the guard first`() {
        val dialogue = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("asks.admit(")
        assertTrue("dispatchPhantomDialogue admits the raw body", "AssistantInput.promptOf(userText)" in dialogue)
        assertTrue("an attachment without words gets no honest line", "R.string.assistant_sees_text_only" in dialogue)
        assertTrue("the model is asked the raw body after the guard", "val prompt = asked.trim()" in agency.substringAfter("fun dispatchPhantomDialogue("))
        assertFalse("the model is asked the raw body after the guard", "val prompt = userText" in agency)

        val council = agency.substringAfter("fun dispatchAgencyCouncil(").substringBefore("ALL_AGENTS")
        assertTrue("the council takes the raw body", "AssistantInput.promptOf(userTask)" in council)
        assertTrue("the council drops an attachment without a word", "R.string.assistant_sees_text_only" in council)

        listOf("private fun phantomConversation(", "private fun agencyConversation(").forEach { fn ->
            val row = bridge.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue("$fn prints the last body raw into the chat list", "AssistantInput.listLine(" in row)
            assertFalse("$fn prints the last body raw into the chat list", "last?.body" in row)
        }

        val phantomBranch = bridge.substringAfter("if (peerId == AgencySessionManager.PHANTOM_AGENT_ID) {").substringBefore("if (peerId.startsWith(\"agency_\"))")
        assertFalse("the PC intent still carries the raw body", "question(text," in phantomBranch)
        assertTrue("the PC intent is not guarded", "AssistantInput.promptOf(text)" in phantomBranch)

        val call = src("app/src/main/java/local/phantom/companion/self/PhantomCall.kt")
            .substringAfter("override suspend fun readMessages(").substringBefore("\n    }\n")
        assertTrue("the call model reads raw bodies of other people's attachments", "AssistantInput.promptOf(m.body)" in call)
        assertFalse("the call model still reads m.body directly", "\${m.body" in call)
    }

    @Test
    fun `the honest line exists in every language and is not replayed`() {
        listOf("values-uk", "values", "values-cs").forEach {
            assertTrue("$it has no assistant_sees_text_only", "name=\"assistant_sees_text_only\"" in src("app/src/main/res/$it/strings.xml"))
        }
        val notices = src("app/src/main/java/local/phantom/companion/ui/messenger/AssistantNotices.kt")
        assertTrue("the line would be replayed as the model's words", "R.string.assistant_sees_text_only" in notices)
        assertTrue("Czech notices are not recognised and replay to the model", "Locale.forLanguageTag(\"cs\")" in notices)
    }
}
