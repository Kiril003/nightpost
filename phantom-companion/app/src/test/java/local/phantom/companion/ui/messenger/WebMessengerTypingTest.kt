package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerTyping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMessengerTypingTest {

    private var on = true
    private val blocked = mutableSetOf<String>()
    private val sent = mutableListOf<Pair<String, String>>()
    private val crew = PeerGroup.Group(id = "g1", name = "Екіпаж", memberIds = listOf("me", "oksana", "pest"))

    private val typing = WebMessengerTyping(
        me = { "me" },
        enabled = { on },
        group = { if (it == crew.id) crew else null },
        canSend = { it !in blocked },
        nameOf = { mapOf("oksana" to "Оксана", "pest" to "Назойливий", "them" to "Тарас")[it] },
        send = { to, body -> sent += to to body },
    )

    private fun frame(from: String, t: PeerTyping.Typing) =
        PeerMessage("t", from, "me", PeerTyping.encode(t)!!, 1L)

    @Test
    fun `удвох — один кадр співрозмовнику`() {
        assertEquals(1, typing.signal("them", true))
        assertEquals(listOf("them" to PeerTyping.encode(PeerTyping.Typing(true))), sent)
    }

    @Test
    fun `у групі — кожному, крім себе й заблокованого`() {
        blocked += "pest"
        assertEquals(1, typing.signal("g1", false))
        assertEquals(listOf("oksana" to PeerTyping.encode(PeerTyping.Typing(false, "g1"))), sent)
    }

    @Test
    fun `вимкнені звіти про прочитання глушать друк в обидва боки`() {
        on = false
        assertEquals(0, typing.signal("them", true))
        assertEquals(emptyList<Pair<String, String>>(), sent)
        assertNull(typing.seen(frame("them", PeerTyping.Typing(true))))
    }

    @Test
    fun `чужий друк стає рядком розмови`() {
        assertEquals(
            WebMessengerTyping.Seen("them", "Тарас", true),
            typing.seen(frame("them", PeerTyping.Typing(true))),
        )
        assertEquals(
            WebMessengerTyping.Seen("g1", "Оксана", false),
            typing.seen(frame("oksana", PeerTyping.Typing(false, "g1"))),
        )
    }

    @Test
    fun `друк у чужу групу або від незнайомця не показується`() {
        assertNull(typing.seen(frame("them", PeerTyping.Typing(true, "g1"))))
        assertNull(typing.seen(frame("oksana", PeerTyping.Typing(true, "zz"))))
        assertNull(typing.seen(frame("nobody", PeerTyping.Typing(true))))
        assertNull(typing.seen(PeerMessage("w", "them", "me", "привіт", 1L)))
    }
}
