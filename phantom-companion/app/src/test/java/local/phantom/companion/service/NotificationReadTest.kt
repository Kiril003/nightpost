package local.phantom.companion.service

import java.io.File
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRead
import local.phantom.companion.ui.messenger.WebMessengerReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** «Прочитано» зі шторки: та сама межа, що з чату, і той самий звіт співрозмовнику. */
class NotificationReadTest {

    private val t0 = 1_790_000_000_000L
    private val log = mutableListOf<PeerMessage>()
    private val sent = mutableListOf<PeerMessage>()
    private val dm = mutableMapOf<String, Long>()
    private val groups = mutableMapOf<String, Long>()
    private var receiptsOn = true

    private val read = NotificationRead(
        log = { log },
        me = { "me" },
        markDm = { peer, upto -> dm[peer] = upto },
        markGroup = { g, upto -> groups[g] = upto },
        receipts = WebMessengerReads(
            log = { log },
            me = { "me" },
            enabled = { receiptsOn },
            canSend = { true },
            send = { to, body -> PeerMessage("r${sent.size}", "me", to, body, t0 + 1_000).also { sent += it; log += it } },
        ),
    )

    @Test fun `розмова вдвох — межа своя і звіт PH-RDU до найновішого її листа`() {
        log += PeerMessage("a", "her", "me", "привіт", t0)
        log += PeerMessage("b", "her", "me", "ти тут?", t0 + 10)
        log += PeerMessage("c", "me", "her", "так", t0 + 20)
        assertTrue(read.advance("her", null))
        assertEquals(t0 + 10, dm["her"])
        assertEquals(PeerRead.Read(t0 + 10), PeerRead.decode(sent.single().body))
        assertEquals("her", sent.single().toId)
        // Повторне «Прочитано» без нових листів звіту не дублює.
        assertTrue(read.advance("her", null))
        assertEquals(1, sent.size)
    }

    @Test fun `звіти вимкнено або текст ховав замок — межа рухається, звіту немає`() {
        log += PeerMessage("a", "her", "me", "привіт", t0)
        receiptsOn = false
        assertTrue(read.advance("her", null))
        assertEquals(t0, dm["her"])
        assertTrue(sent.isEmpty())
        receiptsOn = true
        log += PeerMessage("b", "her", "me", "ще", t0 + 5)
        assertTrue(read.advance("her", null, sawText = false))
        assertEquals(t0 + 5, dm["her"])
        assertTrue("людина бачила «Нове повідомлення», а не лист", sent.isEmpty())
    }

    @Test fun `група — звіт кожному автору, чиї рядки щойно прочитано`() {
        val g = PeerGroup.Group(id = "g1", name = "Бригада", memberIds = listOf("me", "ann", "bob"))
        log += PeerMessage("a", "ann", "me", PeerGroup.encode(g, "раз"), t0)
        log += PeerMessage("b", "bob", "me", PeerGroup.encode(g, "два"), t0 + 7)
        assertTrue(read.advance("g1", "g1"))
        assertEquals(t0 + 7, groups["g1"])
        val byTo = sent.associate { it.toId to PeerRead.decode(it.body) }
        assertEquals(PeerRead.Read(t0, "g1"), byTo["ann"])
        assertEquals(PeerRead.Read(t0 + 7, "g1"), byTo["bob"])
    }

    @Test fun `нема чого читати — нічого не рухається`() {
        assertFalse(read.advance("her", null))
        assertTrue(dm.isEmpty() && sent.isEmpty())
    }

    @Test fun `шторка справді кличе це прочитання — і з «Прочитано», і після відповіді`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val base = File(dir ?: fail("корінь не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/service")
        val mark = File(base, "PeerMarkReadReceiver.kt").readText()
        assertTrue(mark.contains("NotificationRead.of(container).advance(conversation, groupId, sawText)"))
        assertTrue(mark.contains("hidesNotificationContent()"))
        assertTrue(File(base, "PeerReplyReceiver.kt").readText().contains("NotificationRead.of(container).advance(conversation, groupId)"))
    }
}
