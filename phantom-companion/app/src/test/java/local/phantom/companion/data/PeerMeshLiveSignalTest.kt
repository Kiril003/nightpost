package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerEnvelope
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerSealing
import local.phantom.companion.core.net.peer.PeerTyping
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дзвінок, коли спільної мережі немає: сигнал іде живим каналом сховка в
 * двері адресата, а завеликий — лягає туди ж копією.
 */
class PeerMeshLiveSignalTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MemRelay()
    private val phones = mutableListOf<MemPhone>()

    @After
    fun tearDown() {
        phones.forEach { it.mesh.stop() }
        scope.cancel()
        server.shutdown()
    }

    private fun phone(name: String, transport: () -> local.phantom.companion.core.net.peer.PeerRelayTransport? = { server.Transport() }) =
        MemPhone(name, transport()).also { phones += it }

    private suspend fun friends(a: MemPhone, b: MemPhone) {
        a.mesh.accept(b.invite)
        b.mesh.accept(a.invite)
        a.startOn(scope)
        b.startOn(scope)
    }

    /** Обидва постукали одне одному в двері й прочитали відповіді: у сховку порожньо. */
    private suspend fun settled(a: MemPhone, b: MemPhone) = waitFor("знайомство обома боками, сховок порожній") {
        (a.row(b)?.introducedAtMs ?: 0L) > 0L && (b.row(a)?.introducedAtMs ?: 0L) > 0L && server.stored().isEmpty()
    }

    private fun offer(callId: String, sdp: String) =
        PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, sdp))!!

    @Test
    fun `no LAN but a subscribed live channel - the call frame rides it into their door`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)

        waitFor("А може дзвонити Б без спільної мережі") { a.mesh.canCall(b.id) }
        waitFor("Б слухає свої двері") { b.mesh.canCall(a.id) }
        settled(a, b)
        val frame = offer("call-1", "v=0\r\no=- 1 2 IN IP4 0.0.0.0\r\n")

        assertTrue(a.mesh.sendCallFrame(b.id, frame))

        val tag = b.door()
        assertEquals("у двері Б — один кадр", 1, server.liveFrames.count { it.first == tag })
        assertEquals("адресу слухає лише Б", 1, server.subscribedTo(tag))
        assertEquals("«почув» вернулось у двері А", 1, server.liveFrames.count { it.first == a.door() })
        waitFor("кадр дійшов до апарата Б") { b.calls.any { it.body == frame } }
        assertEquals(a.id, b.calls.single().fromId)
        assertTrue("сигнал не лягає копією", server.stored().values.none { it.first == tag })
        assertTrue("сигнал не лягає в журнал", b.mesh.outbox.value.none { it.body == frame })
    }

    @Test
    fun `typing rides the live channel too`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)
        waitFor("живий канал") { a.mesh.canCall(b.id) && b.mesh.canCall(a.id) }
        settled(a, b)

        assertTrue(a.mesh.sendTyping(b.id, PeerTyping.encode(PeerTyping.Typing(on = true))!!))

        waitFor("Б бачить «друкує»") { b.typing.isNotEmpty() }
        assertTrue(b.mesh.outbox.value.isEmpty())
    }

    @Test
    fun `an oversize frame is dropped into the door and still rings`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)
        waitFor("живий канал") { a.mesh.canCall(b.id) && b.mesh.canCall(a.id) }
        settled(a, b)
        val sdp = "a=candidate:" + "x".repeat(11_000)
        val frame = offer("call-2", sdp)

        assertTrue(a.mesh.sendCallFrame(b.id, frame))

        assertTrue("завеликий кадр живим каналом не йде", server.liveFrames.none { it.first == b.door() })
        waitFor("кадр дійшов до апарата Б зі сховка") { b.calls.any { it.body == frame } }
        waitFor("копію спалено") { server.stored().isEmpty() }
    }

    @Test
    fun `an offer nobody hears at the door is not ringing and leaves nothing to ring later`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        // Б вимкнений чи без мережі: його дверей не слухає ніхто.
        a.mesh.accept(b.invite)
        a.startOn(scope)
        waitFor("А має живий канал") { a.mesh.canCall(b.id) }
        waitFor("стук А вже в дверях Б") { server.stored().values.any { it.first == b.door() } }
        val before = server.stored().keys

        assertFalse("ніхто не почув — не «дзвонить»", a.mesh.sendCallFrame(b.id, offer("call-5", "v=0")))

        assertEquals("кадр живим каналом пішов", 1, server.liveFrames.count { it.first == b.door() })
        assertEquals("копії «на потім» немає", before, server.stored().keys)
    }

    @Test
    fun `a live call frame sent again by someone else does not ring twice`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)
        waitFor("живий канал") { a.mesh.canCall(b.id) && b.mesh.canCall(a.id) }
        settled(a, b)
        val frame = offer("call-7", "v=0")
        assertTrue(a.mesh.sendCallFrame(b.id, frame))
        waitFor("перший раз дзвонить") { b.calls.size == 1 }

        val heard = server.liveFrames.first { it.first == b.door() }
        server.sendLiveAs(heard.first, heard.second)
        delay(500)

        assertEquals("підслуханий кадр удруге не дзвонить", 1, b.calls.size)
    }

    @Test
    fun `a signed call frame from ten minutes ago does not ring through the door`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)
        waitFor("живий канал") { a.mesh.canCall(b.id) && b.mesh.canCall(a.id) }
        settled(a, b)
        val pair = PeerChannel.derive(a.dh.privateKey, b.dhPubB64, a.id, b.id)!!
        fun knock(id: String, sentAtMs: Long) {
            val signed = PeerSealing.seal(
                PeerMessage(id = id, fromId = a.id, toId = b.id, body = offer(id, "v=0"), sentAtMs = sentAtMs),
                a.signing.privateKey,
            )
            val sealed = PeerEnvelope.seal(signed, pair)!!.frame
            server.sendLiveAs(b.door(), PeerRelay.sealLive(sealed, b.dhPubB64, b.door())!!)
        }

        knock("call-old", System.currentTimeMillis() - 10 * 60_000L)
        knock("call-new", System.currentTimeMillis())

        waitFor("свіжий тим самим шляхом дзвонить") { b.calls.any { it.id == "call-new" } }
        delay(300)
        assertTrue("кадр десятихвилинної давнини не дзвонить", b.calls.none { it.id == "call-old" })
    }

    @Test
    fun `canCall is false when there is neither LAN nor live`() = runBlocking {
        val a = phone("Аня") { null }
        val b = phone("Богдан")
        friends(a, b)

        assertFalse(a.mesh.canCall(b.id))
        assertFalse(a.mesh.sendCallFrame(b.id, offer("call-3", "v=0")))
    }

    @Test
    fun `a relay without the live socket does not pretend calls work`() = runBlocking {
        val a = phone("Аня") { server.Transport(live = false) }
        val b = phone("Богдан")
        friends(a, b)
        waitFor("Б слухає свої двері") { b.mesh.canCall(a.id) }

        assertFalse(a.mesh.canCall(b.id))
        assertFalse(a.mesh.sendCallFrame(b.id, offer("call-4", "v=0")))
        assertTrue(server.liveFrames.isEmpty())
    }

    @Test
    fun `a contact seen on this Wi-Fi a minute ago whose port went quiet still rings on the first try`() = runBlocking {
        val a = phone("Аня")
        val b = phone("Богдан")
        friends(a, b)
        waitFor("живий канал") { a.mesh.canCall(b.id) && b.mesh.canCall(a.id) }
        settled(a, b)
        val quiet = java.net.ServerSocket(0).use { it.localPort }
        a.mesh.accept(b.invite.copy(host = "127.0.0.1", port = quiet))
        a.roster.sawNearby(b.id, System.currentTimeMillis())

        var attempts = 0
        var rang = false
        while (!rang && attempts < 3) {
            attempts += 1
            rang = a.mesh.sendCallFrame(b.id, offer("call-lan-$attempts", "v=0"))
        }

        assertEquals("спроб до першого дзвінка", 1, attempts)
        waitFor("Б дзвонить") { b.calls.any { it.body == offer("call-lan-1", "v=0") } }
    }
}
