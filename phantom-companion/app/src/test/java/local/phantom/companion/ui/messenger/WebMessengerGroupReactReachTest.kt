package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K1 reach: the web's `POST /messenger/conversations/{groupId}/messages/{id}/reactions` must reach
 * [WebMessengerGroupReactions] through the bridge, and group rows must carry `reactions`.
 *
 * The door's behaviour is proven by [WebMessengerGroupReactionsTest]. `:app` cannot build the bridge
 * on the JVM (it needs an AppContainer), so only the glue lines are read as text, as in
 * `SceneBridgeReachTest`. Comment lines are stripped first.
 */
class WebMessengerGroupReactReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private val code by lazy {
        File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
            .readText().lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private val web by lazy {
        File(repoRoot(), "../phantom-os/src/frontend/src/services/messengerApi.ts").readText()
    }

    /** From `head` to the next member declaration at class indentation. */
    private fun block(head: String): String {
        val start = code.indexOf(head)
        if (start < 0) fail("no `$head` in WebMessengerBridge.kt")
        val rest = code.substring(start + head.length)
        val end = Regex("\n    (private |internal |override |suspend |fun |val |@)").find(rest)?.range?.first ?: rest.length
        return head + rest.substring(0, end)
    }

    private val ws = Regex("\\s+")
    private fun flat(s: String) = ws.replace(s, " ")

    @Test
    fun `the web sends group reactions on the same path as 1-to-1`() {
        assertTrue(
            "messengerApi.react no longer posts to /messenger/conversations/{id}/messages/{id}/reactions",
            web.contains("`/messenger/conversations/\${conversationId}/messages/\${messageId}/reactions`"),
        )
    }

    @Test
    fun `groupRoute has a reactions branch that calls groupReact`() {
        val route = flat(block("private suspend fun groupRoute("))
        assertTrue(
            "groupRoute has no literal POST …/messages/{id}/reactions branch",
            route.contains("""seg.size == 6 && seg[3] == "messages" && seg[5] == "reactions" && method == "POST" -> groupReact(group, seg[4], body)"""),
        )
    }

    @Test
    fun `groupReact asks the door and answers with the group row plus missed`() {
        val react = flat(block("private fun groupReact("))
        assertTrue("groupReact does not call the door", react.contains("groupReactions.handle(group, messageId, body)"))
        assertTrue("a refusal is not answered with fail(status, code, words)", react.contains("fail(r.status, r.code, say(r.why.res))"))
        assertTrue("Done is not answered with the group row", react.contains("ok(groupMessage(group, r.line,"))
        assertTrue("the reply does not name who missed the frame", react.contains(""".put("missed", JSONArray(r.missed))"""))
    }

    @Test
    fun `the door is wired to the real journal, roster keys and mesh send`() {
        val wiring = flat(block("private val groupReactions by lazy"))
        assertTrue(wiring.contains("WebMessengerGroupReactions("))
        assertTrue("log is not the mesh journal", wiring.contains("log = { container.mesh.outbox.value }"))
        assertTrue("me is not the mesh self id", wiring.contains("me = { container.mesh.selfIdFlow.value }"))
        assertTrue("keyed is not the roster with dh keys", wiring.contains("it.dhPubKey.isNotBlank()"))
        assertTrue("send does not go to the mesh", wiring.contains("send = { to, frame -> container.mesh.send(to, frame) }"))
        assertTrue(
            "the door must sit right above groupRoute",
            code.indexOf("private val groupReactions by lazy") < code.indexOf("private suspend fun groupRoute("),
        )
    }

    @Test
    fun `group rows carry reactions keyed by author and instant, members only`() {
        val row = flat(block("private fun groupMessage("))
        assertTrue("group row has no reactions field", Regex("""\.put\( ?"reactions",""").containsMatchIn(row))
        assertTrue(
            "reactionsOf must get the (author, sentAtMs) target so `mine` finds my frame",
            Regex("""reactionsOf\( ?WebMessengerCardMarks\.targetOf\(m\),""").containsMatchIn(row),
        )
        assertTrue("row reactions are not filtered to members", row.contains("WebMessengerGroupReactions.onLine("))
        assertTrue("row reactions use another member list", row.contains("group.memberIds"))
        val list = flat(block("private fun groupMessages("))
        assertTrue("the journal is not folded once per conversation", list.contains("PeerMark.foldAll(log, PeerMark.What.REACTION)"))
    }

    @Test
    fun `the 1-to-1 react contract is untouched`() {
        val react = flat(block("private fun react("))
        assertTrue(react.contains("PeerMark.holds(log, PeerMark.What.REACTION, messageId, emoji, me)"))
        assertTrue(react.contains("container.mesh.send(peerId, frame)"))
        assertTrue(react.contains("""fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))"""))
    }
}
