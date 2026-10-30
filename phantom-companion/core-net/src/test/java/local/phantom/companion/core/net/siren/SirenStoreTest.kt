package local.phantom.companion.core.net.siren

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.intent.AnswerRecord
import local.phantom.companion.core.net.intent.IntentRecord
import local.phantom.companion.core.net.intent.IntentView
import local.phantom.companion.core.net.intent.Phase
import local.phantom.companion.core.net.peer.PeerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Сховок останнього перевіреного знімка і його показ у журналі Намірів. */
class SirenStoreTest {

    private val seed = ByteArray(32) { (it + 40).toByte() }
    private val pub = Ed25519.publicKeyFromPrivate(seed)
    private val keys = mapOf(3 to pub)
    private var now = 1_790_000_000L

    /** Знімок, складений так само, як сервер (siren.py): тут — для підстановок. */
    private fun snapshot(seq: Long, observed: Long, oblasts: Map<Int, Int>, signer: ByteArray = seed): ByteArray {
        val body = ByteArray(SirenSnapshot.BODY_BYTES)
        body[0] = 1
        body[1] = 3
        fun put(at: Int, v: Long) { for (i in 0 until 4) body[at + i] = (v ushr (8 * (3 - i))).toByte() }
        put(2, seq)
        put(6, observed)
        var bitmap = 0L
        oblasts.forEach { (i, code) ->
            bitmap = bitmap or (1L shl i)
            val shift = if (i % 2 == 0) 4 else 0
            body[14 + i / 2] = (body[14 + i / 2].toInt() or (code shl shift)).toByte()
        }
        put(10, bitmap)
        return body + Ed25519.sign(signer, SirenSnapshot.DOMAIN + body)
    }

    private class Mem : SirenStore.Slot {
        var v: String? = null
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private val slot = Mem()
    private fun store() = SirenStore(slot, { keys }, { now })

    @Test fun `береться лише підписаний і лише новіший знімок, і він переживає перезапуск`() {
        val s = store()
        val first = snapshot(seq = now - 100, observed = now - 30, oblasts = mapOf(7 to 1))
        assertTrue(s.offer(first))
        assertFalse("чужий підпис", s.offer(snapshot(now, now, mapOf(0 to 1), signer = ByteArray(32) { 9 })))
        assertFalse("старий знімок від перевізника", s.offer(snapshot(now - 200, now - 300, emptyMap())))
        assertEquals(mapOf("kharkiv" to 1), s.current()!!.alerts)
        val newer = snapshot(seq = now - 10, observed = now - 5, oblasts = mapOf(7 to 1, 20 to 2))
        assertTrue(s.offer(newer))
        assertEquals(setOf("kharkiv", "sumy"), store().current()!!.alerts.keys)
    }

    @Test fun `у журналі Намірів знімок перевірено — свіжий, старий або не наш`() {
        fun record(body: ByteArray) = IntentRecord(
            idHex = "01", op = PeerIntent.OP_SIREN, title = "Маяк тривог", createdMs = 0, deadlineMs = 1, counter = 1,
            prio = 3, replyTo = PeerIntent.Lane.replyTo(smsParts = 1, relay = true), frameB64 = "AA", phase = Phase.ANSWERED,
            answer = AnswerRecord(1, 0, Base64.getEncoder().encodeToString(body), "sms", 0),
        )
        val fresh = IntentView.row(record(snapshot(now - 600, now - 120, mapOf(7 to 1))), keys, now).answer!!.siren!!
        assertTrue(fresh.verified && fresh.fresh)
        assertEquals(2L, fresh.ageMin)
        assertEquals(listOf("kharkiv"), fresh.oblasts)
        val stale = IntentView.row(record(snapshot(now - 900, now - 900, mapOf(7 to 1))), keys, now).answer!!.siren!!
        assertTrue(stale.verified && !stale.fresh)
        val foreign = IntentView.row(record(snapshot(now, now, mapOf(7 to 1), signer = ByteArray(32) { 5 })), keys, now).answer!!.siren!!
        assertFalse(foreign.verified)
        assertTrue(foreign.oblasts.isEmpty())
        // Без закріпленого ключа (сьогоднішня збірка) — нічого не перевірено.
        assertFalse(IntentView.row(record(snapshot(now, now, mapOf(7 to 1)))).answer!!.siren!!.verified)
        assertNull(SirenSnapshot.verify(snapshot(now, now, emptyMap()), SirenKeys.PINNED))
    }
}
