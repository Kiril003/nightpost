package local.phantom.companion.data.scheduled

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.ui.messenger.WebMessengerScheduled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Власник 20.10: «Запланувати» на телефоні не було зовсім. Відкладений лист
 * живе в зашифрованому файлі й іде будильником; тут — його список і доля.
 */
class ScheduledLettersTest {

    private var disk: String? = null
    private fun book() = ScheduledLetters(read = { disk }, write = { disk = it })

    @Test
    fun `a letter for the future is kept, survives a reload and a past time is refused`() {
        val a = book().add("peer_1", "  завтра о 9  ", dueAtMs = 5_000, quiet = true, nowMs = 1_000)!!
        assertEquals("завтра о 9", a.text)
        assertNull(book().add("peer_1", "вчора", dueAtMs = 900, quiet = false, nowMs = 1_000))
        assertNull(book().add("peer_1", "   ", dueAtMs = 5_000, quiet = false, nowMs = 1_000))
        assertEquals(listOf(a), book().all())
    }

    @Test
    fun `only due letters go, a sent one leaves the list, a refused one stays with the reason`() = runBlocking {
        val b = book()
        val ok = b.add("peer_1", "раз", 2_000, false, 1_000)!!
        val bad = b.add("peer_2", "два", 3_000, false, 1_000)!!
        val later = b.add("peer_1", "три", 9_000, false, 1_000)!!
        val sent = mutableListOf<String>()
        val went = b.fireDue(4_000) { l -> sent += l.id; if (l.id == bad.id) "ключ змінився" else null }
        assertEquals(1, went)
        assertEquals(listOf(ok.id, bad.id), sent)
        assertEquals(listOf(bad.id, later.id), b.all().map { it.id })
        assertEquals("ключ змінився", b.get(bad.id)!!.failure)
        assertEquals(later.id, b.next()!!.id)
    }

    @Test
    fun `the alarm and send now meeting at once send the letter once`() = runBlocking {
        val b = book()
        val l = b.add("peer_1", "раз", 2_000, false, 1_000)!!
        var sends = 0
        val slowSend: suspend (ScheduledLetters.Letter) -> String? = { sends++; delay(50); null }
        listOf(async { b.fireDue(3_000, slowSend) }, async { b.deliver(l.id, slowSend) }).awaitAll()
        assertEquals(1, sends)
        assertTrue(b.all().isEmpty())
    }

    @Test
    fun `the door refuses an unknown chat, sends overdue letters before listing and re-arms`() = runBlocking {
        val b = book()
        var rearms = 0
        val sent = mutableListOf<String>()
        var now = 1_000L
        val door = WebMessengerScheduled(
            letters = b,
            known = { it == "peer_1" },
            send = { sent += it.text; null },
            rearm = { rearms++ },
            now = { now },
        )
        assertEquals(WebMessengerScheduled.Out.Refused(404, "no_chat"), door.add("stranger", "привіт", 5_000, false))
        val one = door.add("peer_1", "привіт", 5_000, false) as WebMessengerScheduled.Out.One
        assertEquals(1, rearms)
        now = 6_000L
        assertTrue(door.list().isEmpty())
        assertEquals(listOf("привіт"), sent)
        assertEquals(2, rearms)
        assertEquals(WebMessengerScheduled.Out.Refused(404, "no_letter"), door.sendNow(one.letter.id))
    }

    @Test
    fun `send now on a refused letter carries the bridge's words`() = runBlocking {
        val b = book()
        val l = b.add("peer_1", "раз", 5_000, false, 1_000)!!
        val door = WebMessengerScheduled(b, { true }, { "контакт заблоковано" }, {}, { 2_000 })
        assertEquals(WebMessengerScheduled.Out.Refused(409, "send_failed", "контакт заблоковано"), door.sendNow(l.id))
        assertEquals("контакт заблоковано", b.get(l.id)!!.failure)
    }
}
