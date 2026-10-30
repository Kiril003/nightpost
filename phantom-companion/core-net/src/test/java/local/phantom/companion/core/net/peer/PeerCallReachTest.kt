package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Дзвінок ДОЇЖДЖАЄ до другого апарата — і той дзвонить.
 *
 * Вада, заради якої цей сторож існує, не в кодувальнику й не в рушії: обидва
 * були написані й перевірені поодинці. Вона в тому, що між ними не було дороги.
 * `WebMessengerBridge.handleCallSignal` слав `POST` на
 * `http://<адреса>/api/v1/messenger/call/inbound`, а на тому кінці HTTP не
 * слухає ніхто: єдиний слухач телефона — [PeerLink], і він читає рядок
 * `PHANTOM/1 <verb> <len>`, а все інше кидає мовчки, «not ours — a port scan, a
 * browser, another app». Тобто кожен дзвінок гинув НА ТРАНСПОРТІ, ще до того,
 * як хтось згадає про звук, і жоден тест цього не ловив, бо перевіряли
 * наявність ручки, а не те, що вона кудись веде.
 *
 * Тому тут ДВА апарати, кожен зі справжнім сокетом на локальній петлі, і жодної
 * підміни: справжні ключі Ed25519 та X25519, справжній конверт, справжній
 * підпис, справжня квитанція. І доводиться не лише «кадр розібрався», а й те,
 * заради чого все це: лінія того, кому дзвонять, стає [PeerCallLine.Line.Ringing]
 * — саме нею [PeerCallHub] підіймає апарат, — а відмова зі шторки доїжджає
 * НАЗАД тим самим дротом і кладе лінію того, хто набрав.
 *
 * Останній випадок нижче бʼє в ту саму адресу старим способом — щоб
 * «не працювало» лишалось виміряним, а не згаданим.
 */
class PeerCallReachTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = mutableListOf<PeerLink>()

    @After
    fun tearDown() {
        links.forEach { it.stop() }
        scope.cancel()
    }

    /** Марта дзвонить, Кирило слухає. Ключі справжні, реєстри взаємні. */
    private class Pairing {
        val callerSign: Ed25519.Keypair = Ed25519.generateKeypair()
        val calleeSign: Ed25519.Keypair = Ed25519.generateKeypair()
        val callerDh: X25519.Keypair = dhKeypair()
        val calleeDh: X25519.Keypair = dhKeypair()
        val callerId = "marta-fp"
        val calleeId = "kyrylo-fp"

        /** Реєстр того, хто ПРИЙМАЄ: у ньому є той, хто дзвонить. */
        val calleeRoster = listOf(
            PeerIdentity(
                id = callerId,
                name = "Марта",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = b64(callerSign.publicKey),
                dhPubKey = b64(callerDh.publicKey),
            ),
        )

        /** І навпаки — інакше відмова не мала б куди повернутись. */
        val callerRoster = listOf(
            PeerIdentity(
                id = calleeId,
                name = "Кирило",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = b64(calleeSign.publicKey),
                dhPubKey = b64(calleeDh.publicKey),
            ),
        )

        val channel: ByteArray = PeerChannel.derive(
            callerDh.privateKey, b64(calleeDh.publicKey), callerId, calleeId,
        )!!
    }

    /**
     * Один апарат: слухач на петлі, свій реєстр, своя лінія.
     *
     * [receive] — точний ланцюг прийому з `PeerMesh.accept`, до останнього
     * кроку: відчинити ключем пари, звірити підпис, звірити адресата.
     * Квитанція пишеться лише тоді, коли всі три пройшли, — саме вона й
     * доводить, що кадр справді на тому телефоні. Далі кадр дзвінка йде туди,
     * куди його веде `PeerCallHub.absorb`, — у [PeerCallLine].
     */
    private class Phone(
        val id: String,
        val sign: Ed25519.Keypair,
        val dh: X25519.Keypair,
        val roster: List<PeerIdentity>,
    ) {
        var port = 0
        var room: PeerCallLine.Room? = null

        /** Усе, що відчинилось, — щоб судити ПРО ПРИЇХАЛЕ, а не про літерал. */
        val landed = mutableListOf<PeerMessage>()

        /** Тіла, віддані рушієві у вебі, у тому порядку, в якому приїхали. */
        val toWeb = mutableListOf<String>()

        fun receive(raw: String): String? {
            val env = PeerEnvelope.parse(raw) ?: return null
            val message = PeerEnvelope.open(raw) { fromId ->
                val peer = roster.firstOrNull { it.id == fromId } ?: return@open null
                PeerChannel.derive(dh.privateKey, peer.dhPubKey, id, peer.id)
            } ?: return null
            val sender = roster.firstOrNull { it.id == message.fromId } ?: return null
            val pub = runCatching { Base64.getDecoder().decode(sender.pubKey) }.getOrNull()
                ?: return null
            if (!PeerSealing.verify(message, pub, expectedFromId = sender.id)) return null
            if (message.toId != id) return null
            val key = PeerChannel.derive(dh.privateKey, sender.dhPubKey, id, sender.id)
                ?: return null
            landed += message
            if (PeerCall.isCall(message.body)) {
                val verdict = PeerCallLine.apply(room, message.fromId, message.body, NOW)
                room = verdict.room
                verdict.toWeb?.let { toWeb += it }
            }
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
    }

    private fun listen(phone: Phone): Phone = runBlocking {
        val link = PeerLink(
            port = 0,
            onMessage = { raw -> phone.receive(raw) },
            onBound = { phone.port = it },
        )
        links += link
        link.start(scope)
        repeat(50) { if (phone.port != 0) return@repeat else delay(20) }
        assertTrue("слухач ${phone.id} не піднявся", phone.port != 0)
        phone
    }

    /** Кадр від [from] до [to] тим самим запечатаним конвертом, що й слово. */
    private fun sealed(
        p: Pairing,
        from: Phone,
        to: Phone,
        id: String,
        body: String,
    ): Pair<String, String> {
        val message = PeerSealing.seal(
            PeerMessage(id = id, fromId = from.id, toId = to.id, body = body, sentAtMs = NOW),
            from.sign.privateKey,
        )
        val sealed = PeerEnvelope.seal(message, p.channel)!!
        val receipt = PeerReceipt.of(
            p.channel, message.id, message.fromId, message.toId, sealed.nonceB64,
        )!!
        return sealed.frame to receipt
    }

    private fun marta(p: Pairing) = Phone(p.callerId, p.callerSign, p.callerDh, p.callerRoster)

    private fun kyrylo(p: Pairing) = Phone(p.calleeId, p.calleeSign, p.calleeDh, p.calleeRoster)

    /**
     * НАСКРІЗЬ: пропозиція доїхала — апарат задзвонив — відмова доїхала назад.
     *
     * Кожен крок тут відбувається на справжньому сокеті, і жоден не підмінений.
     * Саме ця послідовність не працювала ЖОДНОГО разу: кадр не доходив, тож
     * лінія другого апарата ніколи не ставала [PeerCallLine.Line.Ringing], а
     * перший до останнього тримав «набираю».
     */
    @Test
    fun `пропозиція доїжджає, апарат дзвонить, а відмова доїжджає назад`() = runBlocking {
        val p = Pairing()
        val marta = listen(marta(p))
        val kyrylo = listen(kyrylo(p))

        val callId = "call_9f31ac2b-17"
        val offer = """{"call_id":"$callId","kind":"offer","from_node_id":"${p.callerId}",""" +
            """"sdp":"v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"}"""

        // 1. Марта набирає: кадр їде дротом, лінія йде слідом за рушієм.
        val (frame, receipt) = sealed(
            p, marta, kyrylo, "offer-1",
            PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, offer))!!,
        )
        assertTrue(
            "той апарат мусив підтвердити квитанцією, якої без ключа пари не скласти",
            PeerLink().send("127.0.0.1", kyrylo.port, receipt, frame) is PeerLink.Outcome.Acked,
        )
        marta.room = PeerCallLine.noteSent(
            marta.room, kyrylo.id, callId, PeerCall.Signalling.OFFER, NOW,
        ).room

        // 2. Апарат Кирила ДЗВОНИТЬ — і саме від цього стану PeerCallHub
        //    підіймає повноекранну плашку.
        assertEquals(
            "кадр доїхав, але апарат не задзвонив",
            PeerCallLine.Line.Ringing(callId, p.callerId, NOW),
            PeerCallLine.face(kyrylo.room),
        )
        // 3. Тіло дійшло до рушія СЛОВО В СЛОВО: телефон SDP не чіпав.
        assertEquals("рушій отримав не те, що склав рушій на тому боці", listOf(offer), kyrylo.toWeb)

        // 4. Кирило відхиляє зі шторки, не відкриваючи застосунку.
        val verdict = PeerCallLine.decline(kyrylo.room)!!
        kyrylo.room = verdict.room
        val out = verdict.out.single()
        val reply = out.reply
        assertEquals(p.callerId, out.toPeerId)
        val hangUp = """{"call_id":"${reply.callId}","kind":"${reply.what.webKind}",""" +
            """"reason":"${reply.reason}"}"""
        val (back, backReceipt) = sealed(
            p, kyrylo, marta, "hangup-1",
            PeerCall.encodeSignal(PeerCall.Signal(reply.callId, reply.what, hangUp))!!,
        )
        assertTrue(
            "відмова не доїхала назад — той, хто набрав, тримав би «набираю» до ранку",
            PeerLink().send("127.0.0.1", marta.port, backReceipt, back) is PeerLink.Outcome.Acked,
        )

        // 5. Лінія Марти лягла, і її рушій дізнався причину словом рушія.
        assertEquals(
            PeerCallLine.Line.Over(callId, p.calleeId, why = null),
            PeerCallLine.face(marta.room),
        )
        assertEquals(listOf(hangUp), marta.toWeb)
        assertTrue("рушій не побачив причини відмови", marta.toWeb.single().contains("declined"))
    }

    /**
     * Чужий кадр не кладе чужої розмови.
     *
     * Номер дзвінка мінить рушій, і він не таємниця: його видно в тілі, яке
     * їде до веба. Якби кадр не звірявся з ЖИВОЮ лінією, будь-хто з реєстру
     * клав би чужу розмову одним «hangup» із вгаданим номером.
     */
    @Test
    fun `сигнал на чужий номер не доходить до рушія`() = runBlocking {
        val p = Pairing()
        val kyrylo = listen(kyrylo(p))

        kyrylo.room = PeerCallLine.Room(
            "call_живий",
            null,
            listOf(PeerCallLine.Leg(p.callerId, PeerCallLine.Foot.TALKING, NOW)),
        )
        val body = """{"call_id":"call_чужий","kind":"hangup"}"""
        val (frame, receipt) = sealed(
            p, marta(p), kyrylo, "stray-1",
            PeerCall.encodeSignal(
                PeerCall.Signal("call_stray", PeerCall.Signalling.HANGUP, body),
            )!!,
        )
        assertTrue(
            PeerLink().send("127.0.0.1", kyrylo.port, receipt, frame) is PeerLink.Outcome.Acked,
        )
        assertEquals("кадр не доїхав — сторож перевіряє не те", 1, kyrylo.landed.size)
        assertEquals(
            "чужий номер поклав живу розмову",
            PeerCallLine.Line.Talking("call_живий", p.callerId, NOW),
            PeerCallLine.face(kyrylo.room),
        )
        assertEquals("тіло з чужим номером дійшло до рушія", emptyList<String>(), kyrylo.toWeb)
    }

    /**
     * Кадр дзвінка не має зʼявитись ані рядком у розмові, ані плашкою в шторці:
     * про долю дзвінка говорить екран дзвінка. Перевіряється на тому, що
     * СПРАВДІ приїхало, а не на літералі.
     */
    @Test
    fun `те, що приїхало дзвінком, не стає рядком розмови`() = runBlocking {
        val p = Pairing()
        val kyrylo = listen(kyrylo(p))
        val from = marta(p)

        val callId = "call_9f31ac2b-17"
        val frames = PeerCall.Signalling.entries.map { what ->
            PeerCall.encodeSignal(
                PeerCall.Signal(callId, what, """{"call_id":"$callId","kind":"${what.webKind}"}"""),
            )!!
        }
        frames.forEachIndexed { i, body ->
            val (frame, receipt) = sealed(p, from, kyrylo, "f-$i", body)
            PeerLink().send("127.0.0.1", kyrylo.port, receipt, frame)
        }

        assertEquals("не всі кадри дзвінка доїхали", frames.size, kyrylo.landed.size)
        kyrylo.landed.forEach {
            assertNotNull("кадр доїхав, але не розібрався сигналом", PeerCall.decodeSignal(it.body))
            assertFalse("кадр дзвінка став рядком розмови", PeerOutbox.isSpoken(it))
            assertNull(
                "кадр дзвінка загримів у шторці сирим дротом",
                PeerAnnounce.notice(it, visibleChat = null, visibleGroup = null, selfId = p.calleeId),
            )
        }
        assertEquals("кадр дзвінка порахувався листом, який чекає", 0, PeerOutbox.waiting(kyrylo.landed))
    }

    /**
     * Стара дорога, заміряна.
     *
     * Це не історична довідка: доки цей випадок зелений, «дзвінки не працюють»
     * лишається ВИМІРЯНИМ фактом про транспорт, а не здогадом. [PeerLink] на
     * `POST /api/v1/messenger/call/inbound` не відповідає нічим — рядок не
     * починається з `PHANTOM/1`, і слухач мовчки закриває зʼєднання.
     */
    @Test
    fun `POST на слухача телефона не отримує відповіді`() = runBlocking {
        var port = 0
        val callee = PeerLink(port = 0, onMessage = { null }, onBound = { port = it })
        links += callee
        callee.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        val body = """{"kind":"offer","call_id":"call_9f31ac2b-17"}"""
        val answer = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /api/v1/messenger/call/inbound HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${body.length}\r\n\r\n" + body
                    ).toByteArray(Charsets.UTF_8),
            )
            out.flush()
            runCatching {
                BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            }.getOrNull()
        }
        assertNull(
            "якби слухач на це відповідав, стара дорога не була б зламана — " +
                "і цей сторож перевіряв би не те",
            answer,
        )
    }

    private companion object {
        const val NOW = 1_788_517_702_898L
    }
}
