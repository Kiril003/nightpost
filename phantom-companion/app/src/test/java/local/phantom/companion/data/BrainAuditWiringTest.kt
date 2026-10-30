package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит розуму 14.10: поведінку доводять тести :core-ai і чисті помічники
 * месенджера; тут — що контейнер і помічник справді їх кличуть.
 */
class BrainAuditWiringTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "feature-stream").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    private val agency get() = src("ui/messenger/AgencySessionManager.kt")
    private val container get() = src("data/AppContainer.kt")

    @Test
    fun `the dialogue lists the tools, folds every call and keeps notices out of the model's history`() {
        val dispatch = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("\n    private suspend fun stream(")
        assertTrue("DialogueTurns.messengerIntent(" in dispatch && "messengerTools," in dispatch)
        assertTrue("messengerToolset(container.toolRegistry.tools.value)" in agency)
        assertTrue("DialogueTurns.history(" in dispatch && "assistantNotices" in dispatch)
        assertTrue("DialogueTurns.followUp(" in src("ui/messenger/ToolRounds.kt"))
        assertTrue("SpokenFold()" in src("ui/messenger/AssistantLiveBoard.kt"))
        assertFalse("лише останній виклик ходу", "toolCall = chunk" in dispatch)
    }

    @Test
    fun `one door runs the tool rounds, and hands-free voice listens through it`() {
        val dispatch = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("\n    private suspend fun stream(")
        assertTrue(dispatch, "ToolRounds(" in dispatch && "voice = voice" in dispatch && "rounds.answer(intent)" in dispatch)
        assertFalse("другий цикл інструментів поза ToolRounds", "while (" in dispatch)
        assertFalse("місток поза ToolRounds", "acting()" in agency)
        val turn = src("self/PhantomVoice.kt").substringAfter("private inner class Turn")
        assertTrue("SpeakingSink(" in turn && "override fun nextRound() = sink.nextRound()" in turn)
    }

    @Test
    fun `failure words come from one place`() {
        assertTrue("FailureWords.of(" in agency.substringAfter("private fun words("))
        assertFalse("AIError.Broken ->" in agency)
    }

    @Test
    fun `the council speaks the same failure words as the dialogue`() {
        val council = agency.substringAfter("fun dispatchAgencyCouncil(").substringBefore("\n    fun ")
        assertFalse("сирий рядок винятку в чаті", "cause.message" in council || "it.message" in council)
        assertTrue("words(" in council)
    }

    @Test
    fun `every failure line is a notice`() {
        val notices = src("ui/messenger/AssistantNotices.kt")
        val strings = File(root, "app/src/main/res/values-uk/strings.xml").readText()
        val ids = Regex("""name="(ai_fail_[a-z_]+)"""").findAll(strings).map { it.groupValues[1] }.toList() + "agency_council_halted"
        assertTrue(ids.size > 5)
        ids.forEach { assertTrue("$it не відсіюється з історії моделі", "R.string.$it" in notices) }
        assertTrue("WebMessengerBridge.kt: міст бере той самий перелік", "AssistantNotices.load(" in src("ui/messenger/WebMessengerBridge.kt"))
    }

    @Test
    fun `offline own key says no network and the phone rules still get their turn`() {
        val gemini = container.substringAfter("val geminiRoute: GeminiCloudRoute by lazy {").substringBefore("\n    }\n")
        assertFalse("черга офлайн-запитів стрічки ковтає питання месенджера", "offlineQueueSink" in gemini)
        assertTrue("ResumableCloud(geminiRoute" in container)
        assertFalse("emitAll(cloud.complete(" in container)
    }

    @Test
    fun `the own key falls back once to the lite model, never on a 429`() {
        val own = container.substringAfter("private val cloudUnlessLocalOnly").substringBefore("\n    }\n")
        assertTrue(own, "fallback = local.phantom.companion.core.resilience.ResumableCloud(geminiLite)" in own)
        val lite = container.substringAfter("val geminiLite").substringBefore("\n    }\n")
        assertTrue(lite, "fixedProfile = local.phantom.companion.core.ai.BrainProfile.CHAT" in lite && "aiKeyStore.geminiKey.value" in lite)
    }

    @Test
    fun `the paired PC yields tool turns only when the cloud is actually reachable`() {
        val pc = container.substringAfter("yieldToolTurns = {").substringBefore("},")
        assertTrue(pc, "InternetPath.has(" in pc)
    }

    @Test
    fun `background distillation never spends the server's daily quota`() {
        val generate = container.substringAfter("private val localGenerate").substringBefore("val memoryConsolidator")
        assertTrue("ownBrains.complete(" in generate)
        assertFalse("aiRouter" in generate)
        val own = container.substringAfter("val ownBrains").substringBefore("\n    }\n")
        assertFalse("nightpostBrain" in own)
    }

    @Test
    fun `one device identity for the brain, and phone asks pass memory`() {
        assertFalse("друга особа пристрою", "= DeviceIdentityStore(" in src("data/NightpostBrain.kt"))
        assertTrue("phoneAsk = rules::asksPhone" in src("self/PhantomSelfAndroid.kt"))
    }
}
