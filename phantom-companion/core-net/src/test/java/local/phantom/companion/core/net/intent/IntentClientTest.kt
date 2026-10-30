package local.phantom.companion.core.net.intent

import local.phantom.companion.core.net.peer.PeerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Клієнт Намірів: смуги за мозком v0 (розд. 07 §12.1 п. 5), журнал станів і
 * прийом відповіді будь-якою смугою. ПК тут — підробний виконавець, який
 * відчиняє Намір і запечатує відповідь ТИМИ САМИМИ функціями, що звірені з
 * Python-вектором (`PeerIntentVectorTest`).
 */
class IntentClientTest {

    private val pairKey = ByteArray(32) { (it + 1).toByte() }
    private val link = IntentClient.Pc(phoneId = "a1b2c3d4e5f60718", pcId = "0f1e2d3c4b5a6978", pairKey = pairKey)
    private var now = PeerIntent.EPOCH_MS + 400_000L * 60_000L

    private class FakeRelay : IntentClient.RelayPort {
        var up = true
        val sent = mutableListOf<Pair<String, String>>()
        val acked = mutableSetOf<String>()
        override fun send(pcId: String, body: String): String = "L${sent.size}".also { sent += pcId to body }
        override fun delivered(letterId: String) = letterId in acked
        override fun ready() = up
    }

    private class FakeSms(var policy: IntentBrain.SmsPolicy) : IntentClient.SmsPort {
        val sent = mutableListOf<String>()
        override fun policy() = policy
        override fun send(text: String): Boolean = sent.add(text)
    }

    private class MemSlot : IntentClient.JournalSlot {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private val relay = FakeRelay()
    private val gateway = FakeSms(IntentBrain.SmsPolicy(available = true, personalAllowed = true))
    private val slot = MemSlot()
    private var seed = 0

    private fun client(sms: IntentClient.SmsPort? = gateway, paired: Boolean = true) = IntentClient(
        pc = { if (paired) link else null },
        relay = relay,
        sms = sms,
        slot = slot,
        clock = { now },
        random = { n -> ByteArray(n) { (seed + it).toByte() }.also { seed += 17 } },
    )

    /** ПК: відчинити кадр, відповісти щаблем [rung]. */
    private fun pcAnswers(frame: ByteArray, rung: Int, body: ByteArray, status: Int = PeerIntent.Status.OK): ByteArray {
        val intent = PeerIntent.openIntent(frame, pairKey, link.phoneId, link.pcId)
        assertNotNull("ПК не відчинив Намір телефона", intent)
        val plain = byteArrayOf(((rung shl 4) or status).toByte()) + body
        return PeerIntent.sealAnswer(plain, pairKey, link.phoneId, link.pcId, intent!!.id, PeerIntent.counterOf(frame), rung)
    }

    private fun relayFrame(i: Int = 0): ByteArray =
        Base64.getDecoder().decode(relay.sent[i].second.removePrefix(PeerIntent.TAG))

    @Test fun `без спареного ПК прохання не створюється — і нічого не їде`() {
        assertNull(client(paired = false).question("що робити?"))
        assertTrue(relay.sent.isEmpty() && gateway.sent.isEmpty())
    }

    @Test fun `сховок першим, і ПК відчиняє той самий кадр`() {
        val c = client()
        val r = c.question("як зупинити кровотечу?")!!
        assertEquals(1, relay.sent.size)
        assertEquals(link.pcId, relay.sent[0].first)
        assertTrue(relay.sent[0].second.startsWith(PeerIntent.TAG))
        assertEquals(Phase.SENT, r.phase)
        assertEquals(listOf("relay"), r.tries.map { it.lane })
        assertTrue(gateway.sent.isEmpty())
        val intent = PeerIntent.openIntent(relayFrame(), pairKey, link.phoneId, link.pcId)!!
        assertEquals("як зупинити кровотечу?", String(intent.args, Charsets.UTF_8))
        assertEquals(PeerIntent.Lane.replyTo(smsParts = 2, relay = true), intent.replyTo)
    }

    @Test fun `без мережі — SMS одразу, той самий кадр байт у байт`() {
        relay.up = false
        val r = client().alerts("kharkiv")!!
        assertEquals(1, gateway.sent.size)
        assertEquals(listOf("relay", "sms"), r.tries.map { it.lane })
        val viaSms = IntentSms.decode(gateway.sent[0])!!
        assertTrue(viaSms.contentEquals(relayFrame()))
        assertTrue(gateway.sent[0].length <= IntentSms.SMS_CHARS)
    }

    @Test fun `мережа є — SMS лише після T_esc без квитанції ПК`() {
        val c = client()
        c.question("питання")
        now += IntentBrain.ESCALATE_MAX_MS - 1
        c.step()
        assertTrue(gateway.sent.isEmpty())
        now += 1
        c.step()
        assertEquals(1, gateway.sent.size)
        now += 60_000
        c.step()
        assertEquals("друга SMS на той самий Намір — гроші на вітер", 1, gateway.sent.size)
    }

    @Test fun `квитанція ПК на лист сховком скасовує SMS`() {
        val c = client()
        val r = c.question("питання")!!
        relay.acked += r.tries.single().letterId
        now += IntentBrain.ESCALATE_MAX_MS * 2
        c.step()
        assertTrue(gateway.sent.isEmpty())
        assertEquals(Phase.AT_PC, c.journal.value.single().phase)
    }

    @Test fun `особисте без дозволу і Тиха людина — SMS не йде ніколи`() {
        relay.up = false
        val closed = FakeSms(IntentBrain.SmsPolicy(available = true, personalAllowed = false))
        client(sms = closed).question("питання")
        assertTrue(closed.sent.isEmpty())
        // Публічне (тривоги) — можна і без дозволу на особисте…
        client(sms = closed).alerts("lviv")
        assertEquals(1, closed.sent.size)
        // …але не в режимі «Тиха людина».
        val quiet = FakeSms(IntentBrain.SmsPolicy(available = true, personalAllowed = true, quiet = true))
        client(sms = quiet).alerts("lviv")
        assertTrue(quiet.sent.isEmpty())
        // Збірка store: порту SMS немає взагалі, і reply_to його не просить.
        val store = client(sms = null).alerts("lviv")!!
        assertEquals(0, store.replyTo and PeerIntent.Lane.SMS)
    }

    @Test fun `Тиха людина, увімкнена ПІСЛЯ створення прохання, теж зупиняє SMS`() {
        val c = client()
        c.question("питання")
        gateway.policy = gateway.policy.copy(quiet = true)
        now += IntentBrain.ESCALATE_MAX_MS
        c.step()
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun `відповідь сховком і SMS — щабель 2 заступає 1, не навпаки`() {
        relay.up = false
        val c = client()
        val r = c.question("питання")!!
        val frame = relayFrame()
        // SMS (щабель 1) з коротким текстом.
        val short = IntentSms.encode(pcAnswers(frame, 1, "Тисніть рану…".toByteArray()))
        assertTrue(c.onSms(short))
        assertEquals(1, c.journal.value.single().answer!!.rung)
        assertFalse(c.journal.value.single().complete)
        // Сховком приходить повна (щабель 2).
        val full = PeerIntent.encodeAnswer(pcAnswers(frame, 2, "Тисніть рану чистою тканиною 10 хвилин.".toByteArray()))
        assertTrue(c.onLetter(link.pcId, full))
        val done = c.journal.value.single()
        assertEquals(2, done.answer!!.rung)
        assertEquals("relay", done.answer!!.lane)
        assertTrue(done.complete && done.idHex == r.idHex && done.phase == Phase.ANSWERED)
        // Запізніла SMS нічого не затирає.
        assertTrue(c.onSms(short))
        assertEquals(2, c.journal.value.single().answer!!.rung)
    }

    @Test fun `чужий ПК, чужий ключ і звичайна SMS — не відповідь`() {
        val c = client()
        c.question("питання")
        val good = pcAnswers(relayFrame(), 2, "так".toByteArray())
        assertFalse(c.onLetter("someone-else", PeerIntent.encodeAnswer(good)))
        val forged = PeerIntent.sealAnswer(
            byteArrayOf(0x20), ByteArray(32) { 9 }, link.phoneId, link.pcId, ByteArray(8), PeerIntent.counterOf(relayFrame()), 2,
        )
        assertFalse(c.onLetter(link.pcId, PeerIntent.encodeAnswer(forged)))
        assertFalse(c.onSms("Мама: я вдома, все добре"))
        assertFalse(c.onSms("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo"))
        assertNull(c.journal.value.single().answer)
    }

    @Test fun `дедлайн минув без відповіді — прострочено, і запис лишається видимим`() {
        val c = client()
        c.alerts("kharkiv")
        now += 3 * 60_000L
        c.step()
        assertEquals(Phase.EXPIRED, c.journal.value.single().phase)
    }

    @Test fun `журнал і лічильник переживають перезапуск, лічильник не повторюється`() {
        val first = client()
        val a = first.question("перше")!!
        val reborn = client()
        assertEquals(listOf(a.idHex), reborn.journal.value.map { it.idHex })
        val b = reborn.question("друге")!!
        assertTrue(b.counter > a.counter)
        // Навіть зі стертим журналом лічильник не відкотиться нижче секунд від епохи.
        slot.value = null
        val wiped = client().question("третє")!!
        assertTrue(wiped.counter >= (now - PeerIntent.EPOCH_MS) / 1000)
    }

    @Test fun `хто на звʼязку називає контакт лише тегом під ключем пари`() {
        val c = client()
        c.whoOnline("mama-node", "Мама")
        val intent = PeerIntent.openIntent(relayFrame(), pairKey, link.phoneId, link.pcId)!!
        assertTrue(intent.args.contentEquals(PeerIntent.contactTag(pairKey, "mama-node")))
        assertFalse(String(intent.args, Charsets.ISO_8859_1).contains("mama"))
    }

    @Test fun `питання з розмови PHANTOM знаходиться за листом людини і переживає перезапуск`() {
        val c = client()
        val r = c.question("тривоги в моєму районі?", anchor = "msg_abc")!!
        assertEquals(r.idHex, c.forAnchor("msg_abc")!!.idHex)
        assertNull(c.forAnchor("msg_other"))
        assertNull(c.forAnchor(""))
        assertEquals(r.idHex, client().forAnchor("msg_abc")!!.idHex)
    }

    @Test fun `маяк тривог — публічне, SMS дозволена без згоди на особисте, один SMS назад`() {
        relay.up = false
        val closed = FakeSms(IntentBrain.SmsPolicy(available = true, personalAllowed = false))
        val r = client(sms = closed).siren()!!
        assertEquals(1, closed.sent.size)
        assertEquals(PeerIntent.Lane.replyTo(smsParts = 1, relay = true), r.replyTo)
        assertEquals(PeerIntent.OP_SIREN, PeerIntent.openIntent(relayFrame(), pairKey, link.phoneId, link.pcId)!!.op)
    }

    @Test fun `довге питання ріжеться по літері, не посеред неї`() {
        val clipped = IntentClient.clip("ї".repeat(200), 281)
        assertEquals(280, clipped.size)
        String(clipped, Charsets.UTF_8).forEach { assertEquals('ї', it) }
    }
}
