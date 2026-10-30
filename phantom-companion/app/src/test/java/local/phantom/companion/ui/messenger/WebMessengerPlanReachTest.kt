package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K2 reach: `GET /messenger/conversations` (and the `?ids=` delta) builds every group row in
 * `groupConversation`, and that row must carry `plan` from [WebMessengerPlanRow].
 *
 * The rules are proven by [WebMessengerPlanRowTest]. `:app` cannot build the bridge on the JVM
 * (it needs an AppContainer), so only the glue is read as text, comment lines stripped, as in
 * `WebMessengerGroupReactReachTest`.
 */
class WebMessengerPlanReachTest {

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

    /** From `head` to the next member declaration at class indentation. */
    private fun block(head: String): String {
        val start = code.indexOf(head)
        if (start < 0) fail("no `$head` in WebMessengerBridge.kt")
        val rest = code.substring(start + head.length)
        val end = Regex("\n    (private |internal |override |suspend |fun |val |@)").find(rest)?.range?.first ?: rest.length
        return head + rest.substring(0, end)
    }

    private fun flat(s: String) = Regex("\\s+").replace(s, " ")

    @Test
    fun `the list route reaches groupConversation, which puts plan from WebMessengerPlanRow next to live_room`() {
        val route = flat(block("private suspend fun route("))
        assertTrue(
            "GET /messenger/conversations no longer answers with conversations()",
            route.contains("""seg[1] == "conversations" && seg.size == 2 && method == "GET" -> ok(idsOf(query)?.let { conversationsOf(it) } ?: conversations())"""),
        )
        assertTrue("the full list does not build group rows in groupConversation", flat(block("private fun conversations(")).contains("groupConversation(g)"))
        assertTrue("the ?ids= delta does not build group rows in groupConversation", flat(block("private fun conversationsOf(")).contains("groupConversation(it)"))

        val row = flat(block("private fun groupConversation("))
        assertTrue("group row has no plan field", row.contains(""".put("plan","""))
        assertTrue("plan is not built by WebMessengerPlanRow.of", row.contains("WebMessengerPlanRow.of(lines, group.memberIds)"))
        assertTrue("plan is not serialised by WebMessengerPlanRow.json", row.contains("WebMessengerPlanRow.json("))
        assertTrue("a group without a plan must say null", row.contains("?: JSONObject.NULL)"))
        assertTrue(
            "plan must sit right after live_room",
            row.indexOf(""".put("live_room",""") in 0 until row.indexOf(""".put("plan","""),
        )
        assertTrue(
            "the log must be folded lazily, only for plan groups",
            row.contains("{ WebMessengerCardMarks.fold(log) }"),
        )
        assertEquals("the feed is read twice per row", 1, Regex("""WebMessengerGroups\.feed\(""").findAll(row).count())
    }
}
