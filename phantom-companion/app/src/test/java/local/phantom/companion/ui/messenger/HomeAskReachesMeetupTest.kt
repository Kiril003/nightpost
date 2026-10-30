package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «збери Олену й Петра в Cafe X о 19:00» з капсули «Спитати Nightpost» доходить до
 * чернетки в месенджері:
 *
 *   askNightpost (services/askNightpost.ts) → sendMessage → міст (гілка PHANTOM_AGENT_ID) →
 *   dispatchPhantomDialogue → правила/модель кличуть інструмент із РЕЄСТРУ →
 *   runGuarded → MeetupCreateTool → WebMessengerMeetupDraft.asked →
 *   хост → window.__phantomOpenMeetupDraft.
 *
 * Поведінку по шматках доводять `MeetupAssistantReachTest` (правила → інструмент
 * → черга), `MeetupAskLeftToToolsTest` (памʼять не перехоплює) і
 * `SceneBridgeReachTest` (хост → веб). Тут — що шматки з'єднані.
 */
class HomeAskReachesMeetupTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `home ask reaches the meetup draft through the tool registry`() {
        // Збірка 18: одна капсула «Пошук або спитати Nightpost»; питання — перший рядок пошуку.
        val web = File(root.parentFile, "phantom-os/src/frontend/src")
        val home = File(web, "services/askNightpost.ts")
        assertTrue("дороги «Спитати Nightpost» немає: $home", home.isFile)
        val ask = home.readText().substringAfter("export const askNightpost = (text: string)").substringBefore("export const talkToNightpost")
        assertTrue("питання з капсули більше не йде дорогою композера", "sendMessage(q)" in ask)
        assertTrue("капсула над списком не кличе askNightpost", "askNightpost(q)" in File(web, "components/messenger/PhoneChatList.tsx").readText())

        val bridge = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
        val phantomBranch = bridge.substringAfter("if (peerId == AgencySessionManager.PHANTOM_AGENT_ID) {").substringBefore("if (peerId.startsWith(\"agency_\"))")
        assertTrue("міст не віддає питання PHANTOM у dispatchPhantomDialogue", "agencyManager.dispatchPhantomDialogue(text, me, questionId = userMsg.id)" in phantomBranch)

        val agency = src("app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt")
        val dispatch = agency.substringAfter("fun dispatchPhantomDialogue(")
        assertTrue("модель і правила не бачать реєстру інструментів", "messengerTools," in dispatch && "messengerToolset(container.toolRegistry.tools.value)" in agency)
        assertTrue("виклик інструмента йде повз ворота", "runGuarded(tool, parsedArgs)" in dispatch)
        assertTrue("інструмент шукається не в реєстрі", "messengerTools.firstOrNull { it.id == activeToolCall.name }" in dispatch)

        val factory = src("app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt")
        assertTrue("meetup.create не в реєстрі", "core.tool.impl.MeetupCreateTool(" in factory)
        assertTrue("bill.split не в реєстрі", "core.tool.impl.BillSplitTool(" in factory)

        val door = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerMeetupDraft.kt")
        assertTrue("двері помічника не кладуть чернетку в чергу", Regex("""fun asked\(ask: MeetupDraftAsk\) \{\s+open\(""").containsMatchIn(door))
        val host = src("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt")
        assertTrue("хост не слухає черги чернеток", host.contains("WebMessengerMeetupDraft.pending"))
    }
}
