package local.phantom.companion.data.linkpreview

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Картка посилання: складає відправник, вимикається в Приватності, на вузькій дорозі не їде. */
class LinkPreviewsTest {

    private val t0 = 1_790_000_000_000L
    private var on = true
    private var narrow = false
    private val fetched = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String, String>>()
    private var page: PeerPreview.Preview? = null

    private val previews = LinkPreviews(
        enabled = { on },
        narrow = { narrow },
        fetch = { url -> fetched += url; page ?: PeerPreview.Preview(url, "Новина дня", "Опис", "news.example") },
        send = { to, body -> sent += to to body; PeerMessage("f${sent.size}", "me", to, body, t0 + 1) },
    )

    private val letter = PeerMessage("m-1", "me", "her", "дивись https://news.example/a.", t0)

    @Test fun `лист із посиланням — картка слідом, привʼязана до листа`() = runBlocking {
        assertTrue(previews.follow("her", letter))
        assertEquals(listOf("https://news.example/a"), fetched)
        val (to, body) = sent.single()
        assertEquals("her", to)
        val (target, p) = PeerPreview.decode(body)!!
        assertEquals("m-1", target)
        assertEquals("Новина дня", p.title)
        // І журнал співрозмовника віддає її саме цьому листу.
        val log = listOf(letter, PeerMessage("f1", "me", "her", body, t0 + 1))
        assertEquals("Новина дня", PeerPreview.of(log, letter)!!.title)
    }

    @Test fun `вимкнено в Приватності — сторінку не читаємо взагалі`() = runBlocking {
        on = false
        assertFalse(previews.follow("her", letter))
        assertTrue(fetched.isEmpty() && sent.isEmpty())
    }

    @Test fun `вузька дорога — ні читання, ні кадру, навіть якщо звузилась під час читання`() = runBlocking {
        narrow = true
        assertFalse(previews.follow("her", letter))
        assertTrue(fetched.isEmpty())
        narrow = false
        val slow = LinkPreviews(
            enabled = { true },
            narrow = { narrow },
            fetch = { url -> narrow = true; PeerPreview.Preview(url, "x") },
            send = { to, body -> sent += to to body; null },
        )
        assertFalse(slow.follow("her", letter))
        assertTrue(sent.isEmpty())
    }

    @Test fun `без посилання чи службовий кадр — нічого`() = runBlocking {
        assertFalse(previews.follow("her", letter.copy(body = "просто слово")))
        assertFalse(previews.follow("her", letter.copy(body = "PH-LOC:50.45,30.52 https://x.example/")))
        assertTrue(fetched.isEmpty())
    }
}
