package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.core.net.peer.PeerMessage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import local.phantom.companion.ui.messenger.WebMessengerPrivacy
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_788_517_702_898L

@RunWith(RobolectricTestRunner::class)
class PeerCallHubPrivacyTest {
    private val me = "aaaa1111bbbb2222"
    private val stranger = "eeee5555ffff6666"
    private val friend = "cccc3333dddd4444"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<Pair<String, String>>()
    private val web = mutableListOf<String>()

    @After fun done() = scope.cancel()

    private fun hub() = PeerCallHub(
        nowMs = { NOW },
        send = { to, frame -> sent += to to frame; true },
        selfId = { me },
        accepts = { it == friend },
    ).also {
        it.attach(scope)
        it.attachWeb { body -> web += body }
        it.webReady()
    }

    private fun offer(from: String, callId: String) = PeerMessage(
        id = "m-$from-$callId",
        fromId = from,
        toId = me,
        body = PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, """{"sdp":"v=0","media":"audio"}"""))!!,
        sentAtMs = NOW,
    )

    @Test
    fun `дзвінок від того, кому не дозволено, не дзвонить і чує «не приймає дзвінків»`() {
        val hub = hub()
        hub.absorb(offer(stranger, "call_a1"))
        assertNull(hub.room.value)
        assertEquals(PeerCallLine.Line.Idle, hub.line.value)
        assertTrue(web.isEmpty())
        val (to, frame) = sent.single()
        assertEquals(stranger, to)
        val signal = PeerCall.decodeSignal(frame)!!
        assertEquals(PeerCall.Signalling.HANGUP, signal.what)
        assertEquals("call_a1", signal.callId)
        assertEquals(PeerCallLine.CALLS_OFF, JSONObject(signal.payload).getString("reason"))
    }

    @Test
    fun `дозволений дзвонить, як і раніше`() {
        val hub = hub()
        hub.absorb(offer(friend, "call_b2"))
        assertEquals("call_b2", hub.room.value?.callId)
        assertEquals(1, web.size)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `міст віддає й приймає «Дзвінки»`() {
        var saved = VisibilityStore.Choice()
        val route = WebMessengerPrivacy({ true }, {}, { saved }, { saved = it })
        assertEquals("all", JSONObject(route.handle("GET", null)).getJSONObject("data").getString("calls"))
        val set = JSONObject(route.handle("PATCH", """{"calls":"people"}""")).getJSONObject("data")
        assertEquals("people", set.getString("calls"))
        assertEquals(VisibilityStore.Level.PEOPLE, saved.calls)
        assertTrue(saved.calls.allows(inCircle = true))
        assertFalse(saved.calls.allows(inCircle = false))
    }
}
