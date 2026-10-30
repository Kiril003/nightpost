package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * parity-1: кожна з фіч справді доходить до веба через міст — і веб кличе
 * саме ті двері, які міст відчиняє.
 */
class ParityWiringReachTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        dir ?: fail("корінь не знайдено") as Nothing
    }

    private val bridge by lazy {
        File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
    }

    private fun web(path: String): String? =
        File(root.parentFile, "phantom-os/src/frontend/src/$path").takeIf { it.isFile }?.readText()

    @Test fun `зникаючі — двері строку і строк у розмові`() {
        assertTrue(bridge.contains("""seg[3] == "timer" && method == "POST" -> setTimer(seg[2], body)"""))
        assertTrue(bridge.contains("container.disappear.setTimer(peer.id, seconds)"))
        assertTrue(bridge.contains(""".put("disappear_s", container.disappear.current(p.id)?.seconds ?: 0L)"""))
        web("services/messengerApi.ts")?.let { assertTrue(it.contains("/messenger/conversations/\${conversationId}/timer")) }
    }

    @Test fun `прочитання — одна збірка умов для мосту й шторки`() {
        assertTrue(bridge.contains("private val reads by lazy { WebMessengerReads.of(container) }"))
    }

    @Test fun `зміна ключа — поля розмови, «Продовжити» і пауза ДО відправки`() {
        assertTrue(bridge.contains(""".put("contact_key_hold", p.sendHeld)"""))
        assertTrue(bridge.contains("\"contact_key_changed_at\""))
        assertTrue(bridge.contains("roster.continueAfterKeyChange(seg[2])"))
        val hold = bridge.indexOf("if (peer.sendHeld) return fail(409, \"key_hold\"")
        val send = bridge.indexOf("container.mesh.sendTyped(peerId, text, replyToId")
        assertTrue("відправка не питає паузу", hold in 1 until send)
        web("services/messengerApi.ts")?.let { assertTrue(it.contains("/continue`")) }
    }

    @Test fun `превʼю — картка слідом за словом і поле листа`() {
        val send = bridge.indexOf("container.mesh.sendTyped(peerId, text, replyToId")
        val follow = bridge.indexOf("container.linkPreviews.launch(container.scope, peerId, sent)")
        assertTrue("картка не їде слідом за листом", follow > send)
        assertTrue(bridge.contains("WebMessengerPreview.json(container.mesh.outbox.value, m)?.let { o.put(\"link_preview\", it) }"))
    }

    @Test fun `кола — рядок розмови несе справжнє коло, веб ставить його одними дверима`() {
        assertTrue(bridge.contains("""seg[3] == "circle" && method == "PATCH" -> setCircle(seg[2], body)"""))
        assertTrue(bridge.contains("container.peers.setCircle(id, circle)"))
        val person = bridge.substringAfter("private fun conversation(p: PeerIdentity").substringBefore("private fun ")
        assertTrue("рядок людини без кола", person.contains(""".put("circle", container.peers.circleOf(p.id))"""))
        val group = bridge.substringAfter("private fun groupConversation(").substringBefore("private fun ")
        assertTrue("рядок групи без кола", group.contains(""".put("circle", container.peers.circleOf(group.id))"""))
        web("services/messengerApi.ts")?.let { assertTrue(it.contains("/messenger/conversations/\${conversationId}/circle")) }
    }

    @Test fun `сторінки «Сьогодні» — порядок і сховане живуть на телефоні під своєю особою`() {
        assertTrue(bridge.contains("""seg[1] == "today" && seg.getOrNull(2) == "tabs" && method == "GET" -> ok(todayTabs())"""))
        assertTrue(bridge.contains("""seg[1] == "today" && seg.getOrNull(2) == "tabs" && method == "PUT" -> putTodayTabs(body)"""))
        assertTrue(bridge.contains("container.todayTabs.get(container.mesh.selfIdFlow.value)"))
        assertTrue(bridge.contains("container.todayTabs.put(container.mesh.selfIdFlow.value, "))
        web("services/messengerApi.ts")?.let { assertTrue(it.contains("'/messenger/today/tabs'")) }
    }
}
