package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

class PeerRoomLiveTest {

    private val group = "a1b2c3d4e5f60718"
    private val members = listOf("me", "marta", "taras")
    private val callId = "call_9f31ac2b"

    private fun note(act: PeerRoomLive.Act, started: Long = NOW - 60_000, id: String = callId, g: String = group) =
        PeerRoomLive.Note(id, g, act, started)

    @Test
    fun `кадр туди й назад`() {
        val body = PeerRoomLive.encode(note(PeerRoomLive.Act.IN))!!
        assertTrue(PeerCall.isCall(body))
        assertTrue(PeerRoomLive.isLive(body))
        assertEquals(note(PeerRoomLive.Act.IN), PeerRoomLive.decode(body))
    }

    @Test
    fun `рушій дзвінка кадру не бачить і кімнати не чіпає`() {
        val body = PeerRoomLive.encode(note(PeerRoomLive.Act.IN))!!
        assertNull(PeerCall.decodeSignal(body))
        val verdict = PeerCallLine.apply(null, "marta", body, NOW)
        assertNull(verdict.room)
        assertNull(verdict.toWeb)
        assertTrue(verdict.out.isEmpty())
    }

    @Test
    fun `справжній кадр дзвінка не плутається з кімнатою`() {
        val offer = PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, "{}"))!!
        assertFalse(PeerRoomLive.isLive(offer))
        assertNull(PeerRoomLive.decode(offer))
    }

    @Test
    fun `увійшли двоє — двоє облич, вийшов один — лишився один`() {
        var board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN), members, NOW)
        board = PeerRoomLive.absorb(board, "taras", note(PeerRoomLive.Act.IN, NOW - 10_000), members, NOW)
        val room = PeerRoomLive.view(board, group, NOW)!!
        assertEquals(setOf("marta", "taras"), room.faces.keys)
        assertEquals(NOW - 60_000, room.startedAtMs)

        board = PeerRoomLive.absorb(board, "marta", note(PeerRoomLive.Act.OUT), members, NOW)
        assertEquals(setOf("taras"), PeerRoomLive.view(board, group, NOW)!!.faces.keys)

        board = PeerRoomLive.absorb(board, "taras", note(PeerRoomLive.Act.OUT), members, NOW)
        assertNull(PeerRoomLive.view(board, group, NOW))
        assertTrue(board.isEmpty())
    }

    @Test
    fun `чужий гілці не малює облич`() {
        val board = PeerRoomLive.absorb(emptyMap(), "stranger", note(PeerRoomLive.Act.IN), members, NOW)
        assertTrue(board.isEmpty())
        val unknownGroup = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN), null, NOW)
        assertTrue(unknownGroup.isEmpty())
    }

    @Test
    fun `той самий номер дзвінка з іншою гілкою не переписує кімнату`() {
        val board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN), members, NOW)
        val hijack = PeerRoomLive.absorb(board, "taras", note(PeerRoomLive.Act.IN, g = "ffff0000ffff0000"), members, NOW)
        assertEquals(board, hijack)
    }

    @Test
    fun `той, хто мовчки зник, гасне без поновлення`() {
        val board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN), members, NOW)
        assertNotNull(PeerRoomLive.view(board, group, NOW + PeerRoomLive.FACE_LIFE_MS - 1))
        assertNull(PeerRoomLive.view(board, group, NOW + PeerRoomLive.FACE_LIFE_MS))
        assertTrue(PeerRoomLive.expire(board, NOW + PeerRoomLive.FACE_LIFE_MS).isEmpty())
        assertTrue(PeerRoomLive.BEAT_MS * 2 < PeerRoomLive.FACE_LIFE_MS)
    }

    @Test
    fun `старт із майбутнього не приймається`() {
        val board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN, NOW + 3_600_000), members, NOW)
        assertEquals(NOW, PeerRoomLive.view(board, group, NOW)!!.startedAtMs)
    }

    @Test
    fun `дві розмови в одній гілці — показується людніша`() {
        var board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN, id = "call_a"), members, NOW)
        board = PeerRoomLive.absorb(board, "taras", note(PeerRoomLive.Act.IN, id = "call_b"), members, NOW)
        board = PeerRoomLive.absorb(board, "me", note(PeerRoomLive.Act.IN, id = "call_b"), members, NOW)
        assertEquals("call_b", PeerRoomLive.view(board, group, NOW)!!.callId)
    }

    private fun room(foot: PeerCallLine.Foot, g: String? = group, id: String = callId) =
        PeerCallLine.Room(id, g, listOf(PeerCallLine.Leg("marta", foot, NOW)))

    @Test
    fun `дзвонить — ще мовчу, взяв слухавку — кажу гілці, поклав — кажу, що вийшов`() {
        val ringing = PeerRoomLive.step(null, room(PeerCallLine.Foot.RINGING), emptyMap(), NOW)
        assertNull(ringing.mine)
        assertTrue(ringing.say.isEmpty())

        val joined = PeerRoomLive.step(null, room(PeerCallLine.Foot.TALKING), emptyMap(), NOW)
        assertEquals(listOf(PeerRoomLive.Note(callId, group, PeerRoomLive.Act.IN, NOW)), joined.say)

        val quiet = PeerRoomLive.step(joined.mine, room(PeerCallLine.Foot.TALKING), emptyMap(), NOW + 1_000)
        assertTrue(quiet.say.isEmpty())

        val left = PeerRoomLive.step(joined.mine, room(PeerCallLine.Foot.GONE), emptyMap(), NOW + 2_000)
        assertEquals(listOf(PeerRoomLive.Note(callId, group, PeerRoomLive.Act.OUT, NOW)), left.say)
        assertNull(left.mine)
        assertTrue(PeerRoomLive.step(null, null, emptyMap(), NOW + 3_000).say.isEmpty())
    }

    @Test
    fun `розмова вдвох без гілки гілці нічого не каже`() {
        assertTrue(PeerRoomLive.step(null, room(PeerCallLine.Foot.TALKING, g = null), emptyMap(), NOW).say.isEmpty())
    }

    @Test
    fun `поки в розмові — поновлюю себе раніше, ніж обличчя згасне`() {
        val joined = PeerRoomLive.step(null, room(PeerCallLine.Foot.TALKING), emptyMap(), NOW)
        val beat = PeerRoomLive.step(joined.mine, room(PeerCallLine.Foot.TALKING), emptyMap(), NOW + PeerRoomLive.BEAT_MS)
        assertEquals(PeerRoomLive.Act.IN, beat.say.single().act)
        assertEquals(NOW + PeerRoomLive.BEAT_MS, beat.mine!!.beatAtMs)
    }

    @Test
    fun `увійшов у вже живу — час старту її, а не мій`() {
        val board = PeerRoomLive.absorb(emptyMap(), "marta", note(PeerRoomLive.Act.IN), members, NOW)
        val joined = PeerRoomLive.step(null, room(PeerCallLine.Foot.TALKING), board, NOW)
        assertEquals(NOW - 60_000, joined.say.single().startedAtMs)
    }

    @Test
    fun `інший дзвінок — спершу вийшов зі старого, потім увійшов у новий`() {
        val joined = PeerRoomLive.step(null, room(PeerCallLine.Foot.TALKING), emptyMap(), NOW)
        val moved = PeerRoomLive.step(joined.mine, room(PeerCallLine.Foot.TALKING, id = "call_new"), emptyMap(), NOW + 1)
        assertEquals(listOf(PeerRoomLive.Act.OUT, PeerRoomLive.Act.IN), moved.say.map { it.act })
        assertEquals("call_new", moved.mine!!.callId)
    }

    @Test
    fun `гілку пропозиції читає з того, що склав рушій`() {
        assertEquals(group, PeerRoomLive.groupOfOffer("""{"sdp":"v=0","contact_id":"$group","media":"audio"}"""))
        assertNull(PeerRoomLive.groupOfOffer("""{"sdp":"v=0","contact_id":null}"""))
    }

    @Test
    fun `покалічений кадр — не кімната`() {
        assertNull(PeerRoomLive.decode(PeerCall.SIGNAL + "$callId;l;@@@"))
        assertNull(PeerRoomLive.encode(note(PeerRoomLive.Act.IN, g = "з пробілом")))
        assertNull(PeerRoomLive.encode(note(PeerRoomLive.Act.IN, id = "bad;id")))
    }
}
