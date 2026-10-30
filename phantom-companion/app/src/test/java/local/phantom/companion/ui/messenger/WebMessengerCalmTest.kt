package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val NOW = 1_790_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

/**
 * Числа, з яких веб складає спокійний потік: коли писала людина, коли писали
 * їй, скільки листів за 30 днів. Помилка тут тихо перекидає розмову з
 * «Важливого» у згорнутий рядок — тобто ховає людину, з якою говорять.
 */
class WebMessengerCalmTest {

    private fun line(from: String, daysAgo: Long) =
        PeerMessage(id = "$from$daysAgo", fromId = from, toId = "x", body = "b", sentAtMs = NOW - daysAgo * DAY)

    @Test
    fun `свій і чужий останні листи розрізняються за автором`() {
        val p = WebMessengerCalm.pulse(
            listOf(line("me", 40), line("me", 3), line("petro", 10), line("olha", 1)),
            me = "me",
            nowMs = NOW,
        )
        assertEquals(NOW - 3 * DAY, p.selfAtMs)
        assertEquals(NOW - 1 * DAY, p.peerAtMs)
    }

    @Test
    fun `лист старший за 30 днів у вікно не входить`() {
        val p = WebMessengerCalm.pulse(
            listOf(line("a", 31), line("a", 30), line("a", 29), line("me", 100)),
            me = "me",
            nowMs = NOW,
        )
        assertEquals(2, p.letters30d)
    }

    @Test
    fun `людина, що ніколи не писала, має порожній власний час`() {
        val p = WebMessengerCalm.pulse(listOf(line("a", 1), line("b", 2)), me = "me", nowMs = NOW)
        assertNull(p.selfAtMs)
        assertEquals(2, p.letters30d)
    }

    @Test
    fun `порожня розмова — нуль і дві порожнечі`() {
        val p = WebMessengerCalm.pulse(emptyList(), me = "me", nowMs = NOW)
        assertNull(p.selfAtMs)
        assertNull(p.peerAtMs)
        assertEquals(0, p.letters30d)
    }
}
