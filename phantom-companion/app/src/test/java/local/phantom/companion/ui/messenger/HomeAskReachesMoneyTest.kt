package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «передай Олені 5 USDC» з капсули «Спитати Nightpost» доходить до MoneyAssistant:
 * askNightpost (services/askNightpost.ts) → sendMessage → міст (гілка PHANTOM_AGENT_ID) →
 * dispatchPhantomDialogue → money.hear раніше за модель.
 */
class HomeAskReachesMoneyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `home ask goes the composer road to the money reader before the model`() {
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
        val hear = dispatch.indexOf("money.hear(prompt)")
        val model = dispatch.indexOf("phantomSelf.answer(prompt)")
        assertTrue("dispatchPhantomDialogue не питає MoneyAssistant", hear >= 0)
        assertTrue("гроші мусять читатися раніше за модель", model < 0 || hear < model)
        assertTrue("MoneyAssistant без справжніх рук застосунку", "MoneyAssistant(container.moneyHands())" in agency)
        val landed = dispatch.indexOf("container.mesh.ingestDirect(")
        val opens = dispatch.indexOf("money.act(heard)")
        assertTrue("екран переказу має відкриватися лише після того, як відповідь лягла в розмову", landed in 0 until opens)
    }
}
