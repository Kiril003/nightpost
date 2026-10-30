package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_788_517_702_898L

// Збірка 18: вхідний підписувався «Невідомий вузол» — кадр сітки не несе імені.
@RunWith(RobolectricTestRunner::class)
class PeerCallHubCallerNameTest {

    private val me = "aaaa1111bbbb2222"
    private val marta = "cccc3333dddd4444"
    private val callId = "call_9f31ac2b"

    private fun hub(names: Map<String, String>) = PeerCallHub(
        nowMs = { NOW },
        send = { _, _ -> true },
        selfId = { me },
        nameOf = { names[it] },
    )

    private fun frame(what: PeerCall.Signalling, payload: String) = PeerMessage(
        id = "m-${what.mark}-${payload.hashCode()}",
        fromId = marta,
        toId = me,
        body = PeerCall.encodeSignal(PeerCall.Signal(callId, what, payload))!!,
        sentAtMs = NOW,
    )

    private fun delivered(hub: PeerCallHub, what: PeerCall.Signalling, payload: String): List<JSONObject> {
        val got = mutableListOf<String>()
        hub.attachWeb { got += it }
        hub.webReady()
        hub.absorb(frame(what, payload))
        return got.map(::JSONObject)
    }

    @Test
    fun `пропозиція доїжджає до вебу з іменем людини зі списку`() {
        val got = delivered(
            hub(mapOf(marta to "Марта")),
            PeerCall.Signalling.OFFER,
            """{"sdp":"v=0 offer","media":"audio","from_node_id":"$marta"}""",
        )
        assertEquals(1, got.size)
        assertEquals("Марта", got.single().optString("display_name"))
        assertEquals("v=0 offer", got.single().getString("sdp"))
    }

    @Test
    fun `імʼя зі свого списку важить більше за кадр, а невідомому не вигадується`() {
        val unknown = delivered(
            hub(emptyMap()),
            PeerCall.Signalling.OFFER,
            """{"sdp":"v=0 offer","media":"audio"}""",
        )
        assertEquals("", unknown.single().optString("display_name"))

        val local = delivered(
            hub(mapOf(marta to "Марта")),
            PeerCall.Signalling.OFFER,
            """{"sdp":"v=0 offer","display_name":"Марта Коваль"}""",
        )
        assertEquals("Марта", local.single().optString("display_name"))
    }
}
