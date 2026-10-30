package local.phantom.companion.core.net.intent

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.peer.PeerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Домашній шлюз: бере SMS лише від моїх номерів, несе кадр до ПК (LAN, а без
 * неї — сховком), повертає щабель 1 SMS і рахує SMS чесно. Ключа пари шлюз не
 * має: ПК тут — підробний виконавець із ключем, якого шлюз не бачить.
 */
class IntentGatewayTest {

    private val pairKey = ByteArray(32) { (it * 3).toByte() }
    private val phone = "a1b2c3d4e5f60718"
    private val pc = "0f1e2d3c4b5a6978"
    private val me = "+380671234567"
    private var now = PeerIntent.EPOCH_MS + 500_000L * 60_000L
    private var counter = 100L

    private fun intentSms(): Pair<String, ByteArray> {
        val intent = PeerIntent.Intent(ByteArray(8) { (counter + it).toByte() }, PeerIntent.OP_ALERTS, 0, 5, 0,
            PeerIntent.Lane.replyTo(smsParts = 1, relay = true), byteArrayOf(7))
        val frame = PeerIntent.sealIntent(intent, pairKey, phone, pc, counter++)
        return IntentSms.encode(frame) to frame
    }

    /** ПК: відчиняє Намір своїм ключем і відповідає щаблем [rung]. */
    private fun pcAnswer(frame: ByteArray, rung: Int = 1, body: Int = 8): ByteArray {
        val intent = PeerIntent.openIntent(frame, pairKey, phone, pc)!!
        val plain = byteArrayOf((rung shl 4).toByte()) + ByteArray(body)
        return PeerIntent.sealAnswer(plain, pairKey, phone, pc, intent.id, PeerIntent.counterOf(frame), rung)
    }

    private class Mem : IntentGateway.LedgerSlot {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private val sent = mutableListOf<Pair<String, String>>()
    private val letters = mutableListOf<String>()
    private val lanSeen = mutableListOf<ByteArray>()
    private var lanMode = "answer"
    private val slot = Mem()

    private fun gateway(cap: Int = IntentGateway.DAILY_CAP, lanOn: Boolean = true) = IntentGateway(
        allowed = { setOf(me) },
        lan = if (!lanOn) null else IntentGateway.LanPort { frame ->
            lanSeen += frame
            when (lanMode) {
                "answer" -> IntentGateway.Lan.Answer(pcAnswer(frame))
                "none" -> IntentGateway.Lan.NoAnswer
                else -> IntentGateway.Lan.Unreachable
            }
        },
        relay = IntentGateway.RelayPort { body -> letters += body; "L${letters.size}" },
        sms = IntentGateway.SmsOut { n, t -> sent += n to t; true },
        ledger = slot,
        clock = { now },
        dailyCap = cap,
    )

    @Test fun `моя SMS через LAN — відповідь щаблем 1 тому, хто питав`() = runTest {
        val g = gateway()
        val (text, frame) = intentSms()
        assertEquals(IntentGateway.Outcome.ANSWERED_LAN, g.onSms("0671234567", text))
        assertTrue("шлюз мусить нести кадр байт у байт", lanSeen.single().contentEquals(frame))
        val (to, back) = sent.single()
        assertEquals(me, to)
        val answer = PeerIntent.openAnswer(IntentSms.decode(back)!!, pairKey, phone, pc, PeerIntent.openIntent(frame, pairKey, phone, pc)!!.id)
        assertEquals(1, answer!!.rung)
        assertTrue(back.length <= IntentSms.SMS_CHARS)
        val l = g.ledger()
        assertEquals(listOf(1, 1, 1, 0), listOf(l.received, l.forwarded, l.smsSent, l.refused))
    }

    @Test fun `чужий номер і звичайна SMS — тиша, ПК нічого не бачить`() = runTest {
        val g = gateway()
        assertEquals(IntentGateway.Outcome.STRANGER, g.onSms("+380501112233", intentSms().first))
        assertEquals(IntentGateway.Outcome.NOT_OURS, g.onSms(me, "Мама: я вдома"))
        assertTrue(lanSeen.isEmpty() && sent.isEmpty() && letters.isEmpty())
        assertEquals(1, g.ledger().refused)
    }

    @Test fun `без LAN — сховком PH-ING, і відповідь листом іде SMS правильному номеру`() = runTest {
        lanMode = "down"
        val g = gateway()
        val (text, frame) = intentSms()
        assertEquals(IntentGateway.Outcome.VIA_RELAY, g.onSms(me, text))
        assertTrue(letters.single().startsWith(PeerIntent.GATEWAY_TAG))
        assertTrue(IntentSms.decode(letters.single().removePrefix(PeerIntent.GATEWAY_TAG))!!.contentEquals(frame))
        assertFalse("чужа відповідь не йде нікому", g.onLetter(PeerIntent.encodeAnswer(pcAnswer(intentSms().second))))
        assertTrue(g.onLetter(PeerIntent.encodeAnswer(pcAnswer(frame))))
        assertEquals(me, sent.single().first)
        assertFalse("та сама відповідь удруге — друга SMS", g.onLetter(PeerIntent.encodeAnswer(pcAnswer(frame))))
        assertEquals(1, sent.size)
    }

    @Test fun `щабель 2 і задовгий кадр SMS не їдуть ніколи`() = runTest {
        lanMode = "down"
        val g = gateway()
        val (text, frame) = intentSms()
        g.onSms(me, text)
        assertFalse(g.onLetter(PeerIntent.encodeAnswer(pcAnswer(frame, rung = 2))))
        val (text2, frame2) = intentSms()
        g.onSms(me, text2)
        assertFalse(g.onLetter(PeerIntent.encodeAnswer(pcAnswer(frame2, body = 600))))
        assertTrue(sent.isEmpty())
    }

    @Test fun `ПК сказав, що SMS-відповіді не буде — шлюз не шле нічого й не йде сховком`() = runTest {
        lanMode = "none"
        val g = gateway()
        assertEquals(IntentGateway.Outcome.NO_ANSWER, g.onSms(me, intentSms().first))
        assertTrue(sent.isEmpty() && letters.isEmpty())
    }

    @Test fun `темп на відправника — пʼяте прохання за хвилину відхилено, за хвилину знов можна`() = runTest {
        val g = gateway()
        repeat(IntentGateway.PER_SENDER_PER_MINUTE) { assertEquals(IntentGateway.Outcome.ANSWERED_LAN, g.onSms(me, intentSms().first)) }
        assertEquals(IntentGateway.Outcome.RATE_LIMITED, g.onSms(me, intentSms().first))
        now += 60_000
        assertEquals(IntentGateway.Outcome.ANSWERED_LAN, g.onSms(me, intentSms().first))
    }

    @Test fun `денна стеля SMS з чесним лічильником, новий день — нові числа`() = runTest {
        val g = gateway(cap = 2)
        g.onSms(me, intentSms().first)
        now += 61_000
        g.onSms(me, intentSms().first)
        now += 61_000
        assertEquals(IntentGateway.Outcome.CAPPED, g.onSms(me, intentSms().first))
        assertEquals(2, g.ledger().smsSent)
        assertEquals(2, sent.size)
        // Лічильник живе на диску: перезапуск шлюзу його не обнуляє.
        assertEquals(2, gateway(cap = 2).ledger().smsSent)
        now += 86_400_000L
        assertEquals(0, g.ledger().smsSent)
        assertEquals(IntentGateway.Outcome.ANSWERED_LAN, g.onSms(me, intentSms().first))
    }
}
