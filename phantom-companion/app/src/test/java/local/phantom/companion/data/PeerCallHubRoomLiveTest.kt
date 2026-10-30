package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRoomLive
import local.phantom.companion.ui.messenger.WebMessengerGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

class PeerCallHubRoomLiveTest {

    private val me = "aaaa1111bbbb2222"
    private val marta = "cccc3333dddd4444"
    private val taras = "eeee5555ffff6666"
    private val group = "a1b2c3d4e5f60718"
    private val callId = "call_9f31ac2b"

    private val sent = mutableListOf<Pair<String, String>>()

    private fun hub() = PeerCallHub(
        nowMs = { NOW },
        send = { to, frame -> sent += to to frame; true },
        selfId = { me },
        groupMembers = { if (it == group) listOf(me, marta, taras) else null },
    )

    private fun frame(from: String, body: String) =
        PeerMessage(id = "m-$from-${body.hashCode()}", fromId = from, toId = me, body = body, sentAtMs = NOW)

    private fun live(act: PeerRoomLive.Act, g: String = group) =
        PeerRoomLive.encode(PeerRoomLive.Note(callId, g, act, NOW - 60_000))!!

    @Test
    fun `учасник гілки сказав «я в розмові» — у гілці видно розмову, пішов — згасла`() {
        val hub = hub()
        hub.absorb(frame(marta, live(PeerRoomLive.Act.IN)))
        val seen = WebMessengerGroups.live(hub.live.value, group, me, NOW)!!
        assertEquals(listOf(marta), seen.faces)
        assertEquals(callId, seen.callId)
        assertFalse(seen.mine)

        hub.absorb(frame(marta, live(PeerRoomLive.Act.OUT)))
        assertNull(WebMessengerGroups.live(hub.live.value, group, me, NOW))
    }

    @Test
    fun `кадр кімнати не будить дзвінок`() {
        val hub = hub()
        hub.absorb(frame(marta, live(PeerRoomLive.Act.IN)))
        assertNull(hub.room.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `чужа гілка — нічого`() {
        val hub = hub()
        hub.absorb(frame(marta, live(PeerRoomLive.Act.IN, g = "ffff0000ffff0000")))
        assertTrue(hub.live.value.isEmpty())
    }

    @Test
    fun `покликали в гілку — кімната знає, що це гілка`() {
        val hub = hub()
        val offer = PeerCall.encodeSignal(
            PeerCall.Signal(callId, PeerCall.Signalling.OFFER, """{"sdp":"v=0","contact_id":"$group"}"""),
        )!!
        hub.absorb(frame(marta, offer))
        assertEquals(group, hub.room.value?.groupId)
    }

    @Test
    fun `дзвінок удвох гілкою не стає`() {
        val hub = hub()
        val offer = PeerCall.encodeSignal(
            PeerCall.Signal(callId, PeerCall.Signalling.OFFER, """{"sdp":"v=0","contact_id":"$me"}"""),
        )!!
        hub.absorb(frame(marta, offer))
        assertNull(hub.room.value?.groupId)
    }

    @Test
    fun `чужий гілці не може приписати свій дзвінок до неї`() {
        val hub = hub()
        val offer = PeerCall.encodeSignal(
            PeerCall.Signal(callId, PeerCall.Signalling.OFFER, """{"sdp":"v=0","contact_id":"$group"}"""),
        )!!
        hub.absorb(frame("9999aaaa8888bbbb", offer))
        assertNull(hub.room.value?.groupId)
    }

    @Test
    fun `себе веб обличчям не малює, але знає, що я там`() {
        var board = PeerRoomLive.absorb(emptyMap(), marta, PeerRoomLive.Note(callId, group, PeerRoomLive.Act.IN, NOW), listOf(me, marta), NOW)
        board = PeerRoomLive.absorb(board, me, PeerRoomLive.Note(callId, group, PeerRoomLive.Act.IN, NOW), listOf(me, marta), NOW)
        val seen = WebMessengerGroups.live(board, group, me, NOW)!!
        assertEquals(listOf(marta), seen.faces)
        assertTrue(seen.mine)
    }
}
