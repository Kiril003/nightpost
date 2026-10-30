package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Холодний старт із повноекранного сповіщення: WebView підʼєднано раніше, ніж
 * бандл оголосив `__phantomCallSignal`. Кадр, відданий у цю мить, гинув.
 */
class PeerCallHubReadyTest {

    private val me = "aaaa1111bbbb2222"
    private val marta = "cccc3333dddd4444"
    private val callId = "call_9f31ac2b"

    private fun hub() = PeerCallHub(nowMs = { NOW }, send = { _, _ -> true }, selfId = { me })

    private fun frame(what: PeerCall.Signalling, payload: String) = PeerMessage(
        id = "m-${what.mark}-${payload.hashCode()}",
        fromId = marta,
        toId = me,
        body = PeerCall.encodeSignal(PeerCall.Signal(callId, what, payload))!!,
        sentAtMs = NOW,
    )

    private val offer = """{"sdp":"v=0 offer","media":"audio"}"""
    private val ice1 = """{"candidate":"c1"}"""
    private val ice2 = """{"candidate":"c2"}"""

    @Test
    fun `кадри до webReady тримаються і їдуть по порядку після нього`() {
        val hub = hub()
        val got = mutableListOf<String>()
        hub.attachWeb { got += it }

        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        hub.absorb(frame(PeerCall.Signalling.ICE, ice1))
        hub.absorb(frame(PeerCall.Signalling.ICE, ice2))
        assertTrue("веб ще не оголосив приймача, а кадри вже пішли в нікуди: $got", got.isEmpty())

        hub.webReady()
        assertEquals(listOf(offer, ice1, ice2), got)

        hub.absorb(frame(PeerCall.Signalling.ICE, """{"candidate":"c3"}"""))
        assertEquals(4, got.size)
    }

    @Test
    fun `прогрітий бандл сказав «готовий» раніше, ніж його підʼєднали`() {
        val hub = hub()
        hub.webReady()
        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        val got = mutableListOf<String>()
        hub.attachWeb { got += it }
        assertEquals(listOf(offer), got)
    }

    @Test
    fun `новий WebView знову чекає свого «готовий»`() {
        val hub = hub()
        hub.attachWeb { }
        hub.webReady()
        hub.detachWeb()

        val got = mutableListOf<String>()
        hub.attachWeb { got += it }
        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        assertTrue(got.isEmpty())
        hub.webReady()
        assertEquals(listOf(offer), got)
    }

    @Test
    fun `лінія відкрита, поки дзвонить, і закрита, коли поклали`() {
        val hub = hub()
        assertFalse(hub.lineOpen.value)
        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        assertTrue(hub.lineOpen.value)
        hub.absorb(frame(PeerCall.Signalling.HANGUP, """{"reason":"hangup"}"""))
        assertFalse(hub.lineOpen.value)
    }

    @Test
    fun `відеодзвінок видно з пропозиції`() {
        val hub = hub()
        hub.absorb(frame(PeerCall.Signalling.OFFER, """{"sdp":"v=0","media":"video"}"""))
        assertTrue(hub.video.value)
        hub.absorb(frame(PeerCall.Signalling.HANGUP, """{"reason":"hangup"}"""))
        hub.clearOver()
        assertFalse(hub.video.value)
    }

    @Test
    fun `голосовий дзвінок камери не просить`() {
        val hub = hub()
        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        assertFalse(hub.video.value)
    }

    @Test
    fun `відео ввімкнули посеред розмови — служба дізнається`() {
        val seen = mutableListOf<Boolean>()
        val hub = PeerCallHub(nowMs = { NOW }, send = { _, _ -> true }, selfId = { me }, onVideo = { seen += it })
        hub.absorb(frame(PeerCall.Signalling.OFFER, offer))
        assertTrue("голосовий дзвінок не міняє типу", seen.isEmpty())
        hub.absorb(frame(PeerCall.Signalling.OFFER, """{"sdp":"v=0 again","media":"video"}"""))
        assertEquals(listOf(true), seen)
        hub.absorb(frame(PeerCall.Signalling.OFFER, """{"sdp":"v=0 third","media":"video"}"""))
        assertEquals("той самий стан удруге — не новина", listOf(true), seen)
    }
}
