package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAssistantStaysOnDeviceTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app").isDirectory && File(it, "core-ai").isDirectory }

    private fun src(path: String) = File(root(), path).readText()

    private val bridge get() = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
    private val agency get() = src("app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt")

    @Test
    fun `a call in a group is refused on the device, not sent to a model`() {
        val sendGroup = bridge.substringAfter("WebMessengerAi.mentioned(text)", "").substringBefore("\n        }\n")
        assertTrue("the group mention no longer reaches the refusal", "container.agencyManager.refuseGroupAsk(group.id)" in sendGroup)
        assertFalse("the bridge still builds group history for a model", "askPhantomInGroup" in bridge || "dispatchGroupReply" in bridge)
    }

    @Test
    fun `the group path has no model route and takes no group content`() {
        assertFalse("group reply to a model still exists", "fun dispatchGroupReply(" in agency)
        val refuse = agency.substringAfter("fun refuseGroupAsk(", "").substringBefore("\n    }\n")
        assertTrue("refuseGroupAsk is missing", refuse.isNotBlank())
        assertTrue("refuseGroupAsk takes more than the group id", refuse.startsWith("groupId: String)"))
        listOf("stream(", "aiRouter", "AIIntent", "history").forEach {
            assertFalse("refuseGroupAsk reaches a model: $it", it in refuse)
        }
        assertTrue("refuseGroupAsk shows no label", "R.string.ai_group_unavailable" in refuse)
    }

    @Test
    fun `the label exists in every language of the app`() {
        listOf("values-uk", "values", "values-cs").forEach {
            assertTrue("$it has no ai_group_unavailable", "name=\"ai_group_unavailable\"" in src("app/src/main/res/$it/strings.xml"))
        }
    }
}
