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
import java.util.Base64

/**
 * Two people, end to end, over a real socket.
 *
 * Everything below the UI: an invite is read, the peer lands in a roster, a
 * message is signed, sealed, crosses the wire, is opened with the key only
 * that pair holds, verified against the key that was proved — and only then
 * counted as delivered. Two phones in a room is the only thing this cannot
 * stand in for, and it is the only part left.
 *
 * The frames are captured as they arrive, so «нічого не поїхало відкритим
 * текстом» is asserted against the bytes the listener really read, not against
 * what the sender intended to write.
 */
class PeerEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: PeerLink? = null

    @After
    fun tearDown() {
        link?.stop()
        scope.cancel()
    }

    private fun edB64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `an invite becomes a peer who can be believed`() = runBlocking {
        // Марта makes a code; Кирило reads it.
        val marta = Ed25519.generateKeypair()
        val martaDh = dhKeypair()
        val invite = PeerInvite.Invite(
            peerId = "marta-key-fp",
            name = "Марта",
            pubKey = edB64(marta.publicKey),
            dhPubKey = b64(martaDh.publicKey),
            host = "127.0.0.1",
            port = 1,
        )
        val decoded = inviteIn(PeerInvite.encode(invite))
        assertNotNull("the code must survive being passed along", decoded)

        var roster = PeerRoster.upsert(
            emptyList(),
            PeerIdentity(
                id = decoded!!.peerId,
                name = decoded.name,
                kind = PeerIdentity.Kind.PERSON,
                pubKey = decoded.pubKey,
                dhPubKey = decoded.dhPubKey,
            ),
        )
        assertEquals(1, roster.size)
        assertEquals(invite.dhPubKey, roster.single().dhPubKey)

        val said = PeerSealing.seal(
            PeerMessage(
                id = "m1", fromId = "marta-key-fp", toId = "kyrylo",
                body = "я на місці", sentAtMs = 1_000L,
            ),
            marta.privateKey,
        )
        val known = roster.first { it.id == said.fromId }
        assertTrue(
            PeerSealing.verify(said, Base64.getDecoder().decode(known.pubKey), known.id),
        )

        // Somebody else saying the same words with their own key is not Марта.
        val impostor = Ed25519.generateKeypair()
        val forged = PeerSealing.seal(said.copy(signature = ""), impostor.privateKey)
        assertFalse(
            PeerSealing.verify(forged, Base64.getDecoder().decode(known.pubKey), known.id),
        )

        roster = PeerRoster.sawIt(roster, known.id, 2_000L)
        assertTrue(roster.first().isLive(2_500L))
    }

    @Test
    fun `a message crosses the wire sealed, and the words are not in the bytes`() = runBlocking {
        val words = "виходь, я біля метро, PH2-PROOF-9f31ac2b"
        val sender = Ed25519.generateKeypair()
        val senderDh = dhKeypair()
        val meDh = dhKeypair()
        val senderId = "sender-fp"
        val meId = "me-fp"
        val roster = listOf(
            PeerIdentity(
                id = senderId, name = "Марта", kind = PeerIdentity.Kind.PERSON,
                pubKey = edB64(sender.publicKey), dhPubKey = b64(senderDh.publicKey),
            ),
        )
        val received = mutableListOf<PeerMessage>()
        val onWire = mutableListOf<String>()
        var port = 0

        val receiver = PeerLink(
            port = 0,
            onMessage = { raw ->
                onWire += raw
                receiptFor(raw, roster, meDh.privateKey, meId) { received += it }
            },
            onBound = { port = it },
        )
        link = receiver
        receiver.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        var outbox = emptyList<PeerMessage>()
        val message = PeerSealing.seal(
            PeerMessage(
                id = "m-real", fromId = senderId, toId = meId,
                body = words, sentAtMs = 5_000L,
            ),
            sender.privateKey,
        )
        outbox = PeerOutbox.enqueue(outbox, message)
        assertEquals(1, PeerOutbox.waiting(outbox))

        val channel = PeerChannel.derive(
            senderDh.privateKey, b64(meDh.publicKey), senderId, meId,
        )!!
        val sealed = PeerEnvelope.seal(message, channel)!!
        val expected = PeerReceipt.of(
            channel, message.id, message.fromId, message.toId, sealed.nonceB64,
        )!!
        outbox = PeerOutbox.markSent(outbox, message.id)
        val landed = PeerLink().send(
            host = "127.0.0.1", peerPort = port, receipt = expected, json = sealed.frame,
        )

        assertTrue(
            "the peer confirmed with a receipt only it could write",
            landed is PeerLink.Outcome.Acked,
        )
        assertEquals(words, received.single().body)

        // Те, заради чого хвиля: байти, які справді прочитав слухач, не містять
        // жодного слова з листа — і не називають самого листа.
        val bytes = onWire.single().toByteArray(Charsets.UTF_8)
        assertFalse(String(bytes, Charsets.UTF_8).contains("PH2-PROOF-9f31ac2b"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("метро"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("m-real"))

        outbox = PeerOutbox.markDelivered(outbox, message.id)
        assertEquals(0, PeerOutbox.waiting(outbox))
        assertEquals(PeerMessage.State.DELIVERED, outbox.single().state)
    }

    @Test
    fun `a frame played back at its own sender is refused`() = runBlocking {
        // Ключ пари той самий в обидва боки, тож кадр Марта→Кирило справді
        // розшифрується в Марти. Вбиває його адресат усередині підпису.
        val marta = Ed25519.generateKeypair()
        val martaDh = dhKeypair()
        val kyryloDh = dhKeypair()
        val martaId = "marta-fp"
        val kyryloId = "kyrylo-fp"
        val channel = PeerChannel.derive(
            martaDh.privateKey, b64(kyryloDh.publicKey), martaId, kyryloId,
        )!!
        val message = PeerSealing.seal(
            PeerMessage(
                id = "m-echo", fromId = martaId, toId = kyryloId,
                body = "я на позиції", sentAtMs = 1L,
            ),
            marta.privateKey,
        )
        val frame = PeerEnvelope.seal(message, channel)!!.frame
        val martasRoster = listOf(
            PeerIdentity(
                id = martaId, name = "Марта", kind = PeerIdentity.Kind.PERSON,
                pubKey = edB64(marta.publicKey), dhPubKey = b64(martaDh.publicKey),
            ),
        )

        assertNotNull("the pair key does open it", PeerEnvelope.open(frame) { channel })
        assertNull(
            "but it was never addressed here",
            accept(frame, martasRoster, kyryloDh.privateKey, martaId),
        )
    }

    @Test
    fun `a plaintext frame from the old build is refused even when signed`() = runBlocking {
        val sender = Ed25519.generateKeypair()
        val senderDh = dhKeypair()
        val meDh = dhKeypair()
        val roster = listOf(
            PeerIdentity(
                id = "sender-fp", name = "Марта", kind = PeerIdentity.Kind.PERSON,
                pubKey = edB64(sender.publicKey), dhPubKey = b64(senderDh.publicKey),
            ),
        )
        var port = 0
        val receiver = PeerLink(
            port = 0,
            onMessage = { raw -> receiptFor(raw, roster, meDh.privateKey, "me-fp") },
            onBound = { port = it },
        )
        link = receiver
        receiver.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        val signed = PeerSealing.seal(
            PeerMessage(
                id = "m-legacy", fromId = "sender-fp", toId = "me-fp",
                body = "старий застосунок", sentAtMs = 1L,
            ),
            sender.privateKey,
        )
        val plain = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .encodeToString(PeerMessage.serializer(), signed)
        val landed = PeerLink().send("127.0.0.1", port, "будь-що", plain)
        assertFalse(
            "cleartext must never be acked, whatever it is signed with",
            landed is PeerLink.Outcome.Acked,
        )
    }

    @Test
    fun `an unsigned message is refused inside the envelope and stays owed`() = runBlocking {
        val senderDh = dhKeypair()
        val meDh = dhKeypair()
        val senderId = "sender-fp"
        val meId = "me-fp"
        val roster = listOf(
            PeerIdentity(
                id = senderId, name = "Марта", kind = PeerIdentity.Kind.PERSON,
                pubKey = edB64(Ed25519.generateKeypair().publicKey),
                dhPubKey = b64(senderDh.publicKey),
            ),
        )
        var port = 0
        val receiver = PeerLink(
            port = 0,
            onMessage = { raw -> receiptFor(raw, roster, meDh.privateKey, meId) },
            onBound = { port = it },
        )
        link = receiver
        receiver.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        val naked = PeerMessage(
            id = "m-naked", fromId = senderId, toId = meId,
            body = "не я це писав", sentAtMs = 1L,
        )
        val channel = PeerChannel.derive(
            senderDh.privateKey, b64(meDh.publicKey), senderId, meId,
        )!!
        val sealed = PeerEnvelope.seal(naked, channel)!!
        val landed = PeerLink().send(
            "127.0.0.1",
            port,
            PeerReceipt.of(channel, naked.id, senderId, meId, sealed.nonceB64)!!,
            sealed.frame,
        )
        assertFalse("a sealed frame is not a believed frame", landed is PeerLink.Outcome.Acked)

        // Refused means owed: it goes back into the queue, not into the bin.
        var outbox = PeerOutbox.enqueue(emptyList(), naked)
        outbox = PeerOutbox.markFailed(outbox, naked.id, 1_000L)
        assertEquals(1, PeerOutbox.waiting(outbox))
    }

    @Test
    fun `the same frame arriving twice is announced once`() {
        val sender = Ed25519.generateKeypair()
        val senderDh = dhKeypair()
        val meDh = dhKeypair()
        val senderId = "sender-fp"
        val meId = "me-fp"
        val roster = listOf(
            PeerIdentity(
                id = senderId, name = "Марта", kind = PeerIdentity.Kind.PERSON,
                pubKey = edB64(sender.publicKey), dhPubKey = b64(senderDh.publicKey),
            ),
        )
        val message = PeerSealing.seal(
            PeerMessage(
                id = "m-twice", fromId = senderId, toId = meId,
                body = "повтор", sentAtMs = 1L,
            ),
            sender.privateKey,
        )
        val channel = PeerChannel.derive(
            senderDh.privateKey, b64(meDh.publicKey), senderId, meId,
        )!!
        val frame = PeerEnvelope.seal(message, channel)!!.frame

        var outbox = emptyList<PeerMessage>()
        var announced = 0
        repeat(2) {
            val opened = accept(frame, roster, meDh.privateKey, meId)!!
            val known = outbox.any { it.id == opened.id }
            outbox = PeerOutbox.enqueue(outbox, opened)
            if (!known) announced++
        }
        assertEquals(1, outbox.size)
        assertEquals("a re-sent frame must not ring twice", 1, announced)
    }

    /**
     * Той єдиний випадок, якого тут ніколи не будували: реєстр приймача
     * ПОРОЖНІЙ. Кожен випадок вище починався з того, що відправника вже мають —
     * саме тому однобічне додавання й доїхало до людей.
     */
    @Test
    fun `one code read fills both rosters, and the shower touches nothing`() = runBlocking {
        val marta = Ed25519.generateKeypair()
        val martaDh = dhKeypair()
        val martaId = PeerIntro.fingerprint(edB64(marta.publicKey))
        val kyrylo = Ed25519.generateKeypair()
        val kyryloDh = dhKeypair()
        val kyryloId = PeerIntro.fingerprint(edB64(kyrylo.publicKey))

        var martasRoster = emptyList<PeerIdentity>()
        var port = 0
        val hersOnAir = PeerLink(
            port = 0,
            onMessage = { raw -> receiptFor(raw, martasRoster, martaDh.privateKey, martaId) },
            onIntro = { raw ->
                PeerIntro.open(raw, martaId, martaDh.privateKey)?.let { card ->
                    martasRoster = PeerRoster.upsert(
                        martasRoster,
                        PeerIdentity(
                            id = card.peerId,
                            name = card.name,
                            kind = PeerIdentity.Kind.PERSON,
                            pubKey = card.pubKey,
                            dhPubKey = card.dhPubKey,
                            keySource = PeerIdentity.KeySource.SHARED,
                            introducedAtMs = 1L,
                        ),
                    )
                    PeerIntro.answer(
                        me = martasCode(marta, martaDh, martaId, 1),
                        to = card,
                        mySigningKey = marta.privateKey,
                        myDhPrivate = martaDh.privateKey,
                    )
                }
            },
            onBound = { port = it },
        )
        link = hersOnAir
        hersOnAir.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        // Кирило читає код. Це вся дія, яку роблять двоє людей.
        val decoded = PeerInvite.decode(
            PeerInvite.encode(martasCode(marta, martaDh, martaId, port)),
        ) as PeerInvite.Decoded.Ph2
        val hers = decoded.invite
        PeerRoster.upsert(
            emptyList(),
            PeerIdentity(
                id = hers.peerId, name = hers.name, kind = PeerIdentity.Kind.PERSON,
                pubKey = hers.pubKey, dhPubKey = hers.dhPubKey,
            ),
        )
        assertTrue("до знайомства це рівно та однобічність, що ламала продукт", martasRoster.isEmpty())

        val mine = PeerInvite.Invite(
            peerId = kyryloId, name = "Кирило", pubKey = edB64(kyrylo.publicKey),
            dhPubKey = b64(kyryloDh.publicKey), host = "127.0.0.1", port = 2,
        )
        val offer = PeerIntro.offer(
            me = mine, toId = hers.peerId, theirDhPubB64 = hers.dhPubKey,
            mySigningKey = kyrylo.privateKey, myDhPrivate = kyryloDh.privateKey,
        )!!
        val back = PeerLink().intro("127.0.0.1", port, offer.frame)
        assertTrue("знайомство мусить повернути картку", back is PeerLink.Outcome.Met)
        assertNotNull(
            "і саме Мартину, під нашим-таки нонсом",
            PeerIntro.open(
                (back as PeerLink.Outcome.Met).json,
                kyryloId, kyryloDh.privateKey, echoOf = offer.nonceB64,
            ),
        )
        assertEquals(
            "одне читання коду мусить наповнити ОБИДВА реєстри",
            kyryloId,
            martasRoster.single().id,
        )

        // І аж тепер лист доходить — раніше не було кому написати квитанцію.
        val letter = PeerSealing.seal(
            PeerMessage(
                id = "m-both", fromId = kyryloId, toId = martaId,
                body = "я вийшов", sentAtMs = 9_000L,
            ),
            kyrylo.privateKey,
        )
        val channel = PeerChannel.derive(
            kyryloDh.privateKey, b64(martaDh.publicKey), kyryloId, martaId,
        )!!
        val sealed = PeerEnvelope.seal(letter, channel)!!
        assertTrue(
            "після знайомства лист доходить, і Марта для цього не робила нічого",
            PeerLink().send(
                "127.0.0.1",
                port,
                PeerReceipt.of(channel, letter.id, kyryloId, martaId, sealed.nonceB64)!!,
                sealed.frame,
            ) is PeerLink.Outcome.Acked,
        )
    }

    /**
     * Межа, яку цей верб не має права перейти. Маячок називає peerId відкрито,
     * тож окрема відмова «тебе немає в моєму реєстрі» зробила б з кожного
     * телефона на спільній кавʼярняній мережі довідник «хто кого знає».
     *
     * Тому відмова того, хто НЕ впустив, мусить бути тим самим рядком, що й у
     * збірки, яка про це слово ніколи не чула.
     */
    @Test
    fun `a refusal to be introduced is the same words as a build that never heard the verb`() =
        runBlocking {
            var oldPort = 0
            val oldBuild = PeerLink(port = 0, onBound = { oldPort = it })
            link = oldBuild
            oldBuild.start(scope)
            repeat(50) { if (oldPort != 0) return@repeat else delay(20) }

            var newPort = 0
            // Говорить INT, але цього не впускає — стороннього, чийого коду не бачив.
            val declining = PeerLink(port = 0, onIntro = { null }, onBound = { newPort = it })
            declining.start(scope)
            repeat(50) { if (newPort != 0) return@repeat else delay(20) }

            val nonsense = """{"v":1,"from":"a","to":"b","dh":"x","n":"y","ct":"z"}"""
            val fromOld = PeerLink().intro("127.0.0.1", oldPort, nonsense)
            val fromNew = PeerLink().intro("127.0.0.1", newPort, nonsense)
            declining.stop()

            assertTrue(fromOld is PeerLink.Outcome.Refused)
            assertTrue(fromNew is PeerLink.Outcome.Refused)
            assertEquals(
                "інакше кожен на тій самій мережі дізнається, хто кого знає",
                (fromOld as PeerLink.Outcome.Refused).reason,
                (fromNew as PeerLink.Outcome.Refused).reason,
            )
            assertEquals(PeerWire.unknownVerb(PeerWire.VERB_INTRO), fromNew.reason)
        }

    private fun martasCode(
        signing: Ed25519.Keypair,
        dh: X25519.Keypair,
        id: String,
        port: Int,
    ) = PeerInvite.Invite(
        peerId = id,
        name = "Марта",
        pubKey = edB64(signing.publicKey),
        dhPubKey = b64(dh.publicKey),
        host = "127.0.0.1",
        port = port,
    )

    /** Точний ланцюг прийому з [local.phantom.companion.data.PeerMesh]. */
    private fun accept(
        raw: String,
        roster: List<PeerIdentity>,
        myDhPrivate: ByteArray,
        meId: String,
    ): PeerMessage? {
        val message = PeerEnvelope.open(raw) { fromId ->
            val peer = roster.firstOrNull { it.id == fromId } ?: return@open null
            PeerChannel.derive(myDhPrivate, peer.dhPubKey, meId, peer.id)
        } ?: return null
        val sender = roster.firstOrNull { it.id == message.fromId } ?: return null
        val pub = runCatching { Base64.getDecoder().decode(sender.pubKey) }.getOrNull()
            ?: return null
        if (!PeerSealing.verify(message, pub, expectedFromId = sender.id)) return null
        if (message.toId != meId) return null
        return message
    }

    /** І те, чим цей ланцюг відповідає: квитанція під ключем пари, або нічого. */
    private fun receiptFor(
        raw: String,
        roster: List<PeerIdentity>,
        myDhPrivate: ByteArray,
        meId: String,
        onOpened: (PeerMessage) -> Unit = {},
    ): String? {
        val env = PeerEnvelope.parse(raw) ?: return null
        val message = accept(raw, roster, myDhPrivate, meId) ?: return null
        val peer = roster.firstOrNull { it.id == message.fromId } ?: return null
        val channel = PeerChannel.derive(myDhPrivate, peer.dhPubKey, meId, peer.id) ?: return null
        onOpened(message)
        return PeerReceipt.of(channel, message.id, message.fromId, message.toId, env.n)
    }
}
