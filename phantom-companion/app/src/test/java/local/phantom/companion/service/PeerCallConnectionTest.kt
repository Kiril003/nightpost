package local.phantom.companion.service

import android.app.Application
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import local.phantom.companion.core.net.peer.PeerCallLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerCallConnectionTest {

    private class Door : CallDoor {
        val seen = mutableListOf<String>()
        override fun showRinging(line: PeerCallLine.Line.Ringing, name: String) { seen += "ring:${line.callId}:$name" }
        override fun answer() { seen += "answer" }
        override fun decline() { seen += "decline" }
        override fun hangUp() { seen += "hangup" }
    }

    private val door = Door()
    private val ringing = PeerCallLine.Line.Ringing("call_9f31ac2b", "cccc3333dddd4444", 1L)
    private fun connection() = PeerCallConnection(door, ringing, "Марта")

    @Test
    fun `з'єднання народжується дзвінком власного застосунку`() {
        val c = connection()
        assertEquals(Connection.STATE_RINGING, c.state)
        assertTrue(c.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0)
        assertEquals("Марта", c.callerDisplayName)
        assertNotNull(c.address)
    }

    @Test
    fun `система просить показати дзвінок - малюємо плашку`() {
        connection().onShowIncomingCallUi()
        assertEquals(listOf("ring:call_9f31ac2b:Марта"), door.seen)
    }

    @Test
    fun `кнопки системи доходять до застосунку`() {
        val c = connection()
        c.onAnswer()
        c.onAnswer(VideoProfile.STATE_BIDIRECTIONAL)
        c.onReject()
        c.onDisconnect()
        assertEquals(listOf("answer", "answer", "decline", "hangup"), door.seen)
    }

    @Test
    fun `система скасувала з'єднання - дзвінок гаситься і в застосунку`() {
        val c = connection()
        c.onAbort()
        assertEquals(listOf("hangup"), door.seen)
        assertEquals(Connection.STATE_DISCONNECTED, c.state)
        assertEquals(DisconnectCause.CANCELED, c.disconnectCause.code)
    }

    @Test
    fun `запізніле прохання показати плашку після відповіді не дзвонить знову`() {
        val c = connection()
        c.follow(PeerCallLine.Line.Talking(ringing.callId, ringing.peerId, 2L))
        c.onShowIncomingCallUi()
        assertEquals(emptyList<String>(), door.seen)
    }

    @Test
    fun `розмова почалась - з'єднання активне`() {
        val c = connection()
        c.follow(PeerCallLine.Line.Talking(ringing.callId, ringing.peerId, 2L))
        assertEquals(Connection.STATE_ACTIVE, c.state)
    }

    @Test
    fun `кінець розмови закриває з'єднання з причиною`() {
        fun endedWith(why: String?): Int {
            val c = connection()
            c.follow(PeerCallLine.Line.Over(ringing.callId, ringing.peerId, why))
            assertEquals(Connection.STATE_DISCONNECTED, c.state)
            return c.disconnectCause.code
        }
        assertEquals(DisconnectCause.MISSED, endedWith(PeerCallLine.MISSED))
        assertEquals(DisconnectCause.REJECTED, endedWith(PeerCallLine.DECLINED))
        assertEquals(DisconnectCause.REMOTE, endedWith(PeerCallLine.HUNG_UP))
        assertEquals(DisconnectCause.LOCAL, endedWith(null))
    }

    @Test
    fun `лінія стала вільною - з'єднання закрите`() {
        val c = connection()
        c.follow(PeerCallLine.Line.Idle)
        assertEquals(Connection.STATE_DISCONNECTED, c.state)
    }

    @Test
    fun `чужий дзвінок з'єднання не чіпає`() {
        val c = connection()
        c.follow(PeerCallLine.Line.Talking("call_other", "dddd", 2L))
        assertEquals(Connection.STATE_RINGING, c.state)
    }
}
