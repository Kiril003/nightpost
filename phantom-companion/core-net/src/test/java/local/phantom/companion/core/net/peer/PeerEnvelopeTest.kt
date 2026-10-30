package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Те, що справді перетинає мережу.
 *
 * Кожен тест тут — про одну поразку, яку легко не помітити: слова, що поїхали
 * відкритим текстом; кадр, повернутий назад відправнику; кадр із чужої пари,
 * підписаний чужим імʼям. Підпис робить лист достовірним — приватним його
 * робить лише конверт.
 */
class PeerEnvelopeTest {

    private val key = randomBytes(32)

    private fun message(
        id: String = "m-1",
        from: String = "aaaa1111",
        to: String = "bbbb2222",
        body: String = "я на місці",
    ) = PeerMessage(
        id = id,
        fromId = from,
        toId = to,
        body = body,
        sentAtMs = 1_000L,
        signature = "cGlkcGlz",
    )

    private fun seal(message: PeerMessage): String = PeerEnvelope.seal(message, key)!!.frame

    @Test
    fun `a sealed message comes back exactly as it went in`() {
        val original = message()
        assertEquals(original, PeerEnvelope.open(seal(original)) { key })
    }

    @Test
    fun `the words never appear in the frame`() {
        // Це і є вся обіцянка хвилі: те, що людина написала, і те, що лежить у
        // байтах на дроті, не мають спільного тексту.
        val frame = seal(message(body = "я біля мосту, 50.45,30.52"))
        assertFalse(frame.contains("біля мосту"))
        assertFalse(frame.contains("50.45"))
        assertFalse(frame.contains("PH-LOC"))
    }

    @Test
    fun `a shared position is ciphertext like everything else`() {
        val place = PeerPayload.encodePlace(50.45010, 30.52340, 12)
        val frame = seal(message(body = place))
        assertFalse(frame.contains("PH-LOC"))
        assertFalse(frame.contains("30.523"))
        assertEquals(place, PeerEnvelope.open(frame) { key }?.body)
    }

    @Test
    fun `a group header and its member list are ciphertext too`() {
        val body = PeerGroup.encode(
            PeerGroup.Group(id = "g1", name = "Ланка", memberIds = listOf("aaaa1111", "bbbb2222")),
            "виходимо",
        )
        val frame = seal(message(body = body))
        assertFalse(frame.contains("Ланка"))
        assertFalse(frame.contains("виходимо"))
    }

    @Test
    fun `the signature itself is hidden`() {
        // Спостерігач у мережі не отримує навіть доказу авторства.
        val frame = seal(message())
        assertFalse(frame.contains("cGlkcGlz"))
        assertFalse(frame.contains("signature"))
    }

    @Test
    fun `the id of the message is not on the wire at all`() {
        // Він лежав відкрито, щоб ACK міг назвати лист. Поки він там лежав,
        // назвати лист міг і той, хто просто слухає Wi-Fi.
        val frame = seal(message(id = "m-таємний"))
        assertFalse(frame.contains("m-таємний"))
        assertFalse(frame.contains("\"id\""))
    }

    @Test
    fun `a wrong key opens nothing`() {
        assertNull(PeerEnvelope.open(seal(message())) { randomBytes(32) })
    }

    @Test
    fun `a tampered addressing field breaks the seal`() {
        // Адресування входить в AAD, тож перепідписати конверт не можна.
        val frame = seal(message())
        val moved = frame.replace("\"to\":\"bbbb2222\"", "\"to\":\"cccc3333\"")
        assertNotEquals(frame, moved)
        assertNull(PeerEnvelope.open(moved) { key })
    }

    @Test
    fun `a flipped ciphertext byte is refused, not half-read`() {
        val frame = seal(message())
        val env = PeerEnvelope.parse(frame)!!
        // Псується розкодований байт, а не символ base64: без набивки молодші
        // біти останнього символу незначущі, і його підміна на 'A'/'B' лишала
        // шифротекст незмінним у чверті запусків — тест проходив випадково.
        val raw = java.util.Base64.getDecoder().decode(env.ct)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val broken = frame.replace(
            env.ct,
            java.util.Base64.getEncoder().withoutPadding().encodeToString(raw),
        )
        assertNull(PeerEnvelope.open(broken) { key })
    }

    @Test
    fun `a truncated frame is refused`() {
        val frame = seal(message())
        assertNull(PeerEnvelope.open(frame.take(frame.length / 2)) { key })
        assertNull(PeerEnvelope.parse(frame.take(frame.length / 2)))
    }

    @Test
    fun `a frame from an older protocol version is refused, never guessed at`() {
        val frame = seal(message())
        assertNull(PeerEnvelope.parse(frame.replace("\"v\":3", "\"v\":2")))
        assertNull(PeerEnvelope.parse(frame.replace("\"v\":3", "\"v\":4")))
    }

    @Test
    fun `a plaintext message from the old build is refused`() {
        // Старий застосунок шле голий PeerMessage. Він мусить не відкритись —
        // навіть із бездоганним підписом усередині.
        val plain = kotlinx.serialization.json.Json.encodeToString(
            PeerMessage.serializer(),
            message(),
        )
        assertNull(PeerEnvelope.parse(plain))
        assertNull(PeerEnvelope.open(plain) { key })
    }

    @Test
    fun `the version field really travels`() {
        // kotlinx мовчки викидає значення за замовчуванням; версія, якої немає
        // в кадрі, помічається лише збіркою, що не може його прочитати.
        assertTrue(seal(message()).contains("\"v\":3"))
    }

    @Test
    fun `every attempt gets its own nonce`() {
        // Той самий лист, надісланий удруге, мусить дати інші байти: повторений
        // nonce під GCM віддає відкритий текст обох листів.
        val once = PeerEnvelope.seal(message(), key)!!
        val twice = PeerEnvelope.seal(message(), key)!!
        assertNotEquals(once.frame, twice.frame)
        assertNotEquals(once.nonceB64, twice.nonceB64)
        assertNotEquals(PeerEnvelope.parse(once.frame)!!.ct, PeerEnvelope.parse(twice.frame)!!.ct)
    }

    @Test
    fun `the nonce handed back is the nonce in the frame`() {
        // Квитанція привʼязана саме до нього; розійдуться — і жодна доставка
        // ніколи не підтвердиться, причому обидва боки вважатимуть себе правими.
        val sealed = PeerEnvelope.seal(message(), key)!!
        assertEquals(sealed.nonceB64, PeerEnvelope.parse(sealed.frame)!!.n)
    }

    @Test
    fun `a body too big to frame fails cleanly instead of being truncated`() {
        val huge = message(body = "я".repeat(PeerWire.MAX_BODY_BYTES))
        assertNull(PeerEnvelope.seal(huge, key))
    }

    @Test
    fun `a body that just fits still fits after base64`() {
        val big = message(body = "a".repeat(40_000))
        val sealed = PeerEnvelope.seal(big, key)
        assertNotNull(sealed)
        assertTrue(sealed!!.frame.toByteArray(Charsets.UTF_8).size <= PeerWire.MAX_BODY_BYTES)
        assertEquals(big, PeerEnvelope.open(sealed.frame) { key })
    }

    // — v4: той самий конверт під ключем однієї зустрічі ————————————————————

    private val sid = "a1b2c3d4e5f60718"
    private val sessionKey = randomBytes(32)

    private fun sealV4(message: PeerMessage): String =
        PeerEnvelope.sealV4(message, sid, sessionKey)!!.frame

    @Test
    fun `a v4 frame opens only with the key of its own meeting`() {
        val original = message()
        val frame = sealV4(original)
        assertEquals(4, PeerEnvelope.parse(frame)!!.v)
        assertEquals(sid, PeerEnvelope.parse(frame)!!.sid)
        assertEquals(original, PeerEnvelope.open(frame, sessionKey = { sessionKey }) { null })
        assertNull(PeerEnvelope.open(frame, sessionKey = { randomBytes(32) }) { null })
    }

    @Test
    fun `a v4 frame never falls back to the permanent pair key`() {
        // Кадр, ЗАПЕЧАТАНИЙ ключем пари, але позначений як кадр зустрічі. Хто
        // має довгий ключ — захоплений телефон, той самий співрозмовник
        // місяці тому — написав би такий сам. Тихий відкат на ключ пари
        // прийняв би його як свіжий, і «свіжий ключ» став би словом на екрані.
        val downgrade = PeerEnvelope.sealV4(message(), sid, key)!!.frame
        assertEquals(4, PeerEnvelope.parse(downgrade)!!.v)
        assertNull(
            "конверт v4 відкрито довгим ключем пари",
            PeerEnvelope.open(downgrade) { key },
        )
        assertNull(PeerEnvelope.open(downgrade, sessionKey = { null }) { key })
        // Ключ зустрічі — єдине, що його відкриває; і це не ключ пари.
        assertNotNull(PeerEnvelope.open(downgrade, sessionKey = { key }) { null })
    }

    @Test
    fun `an unknown meeting is refused, and the frame says which one it was`() {
        // Відмова мусить бути впізнаваною: відправник по ній розуміє, що треба
        // зустрітись наново, а не що лист загубився.
        val frame = sealV4(message())
        var asked: String? = null
        assertNull(PeerEnvelope.open(frame, sessionKey = { asked = it; null }) { key })
        assertEquals(sid, asked)
    }

    @Test
    fun `a frame moved to another meeting does not open`() {
        // Імʼя зустрічі лежить в AAD, тож переклеїти кадр на живу зустріч
        // не можна навіть маючи її ключ.
        val frame = sealV4(message())
        val moved = frame.replace("\"sid\":\"$sid\"", "\"sid\":\"0000000000000000\"")
        assertNotEquals(frame, moved)
        assertNull(PeerEnvelope.open(moved, sessionKey = { sessionKey }) { null })
    }

    @Test
    fun `a version and its meeting name must agree`() {
        // v3 із іменем зустрічі та v4 без нього — обидва відмовляються, бо
        // інакше довелось би вгадувати, під яким AAD читати.
        val v3 = seal(message())
        assertNull(PeerEnvelope.parse(v3.dropLast(1) + ",\"sid\":\"$sid\"}"))
        assertNull(PeerEnvelope.parse(sealV4(message()).replace("\"sid\":\"$sid\"", "\"sid\":\"\"")))
        assertNull(PeerEnvelope.parse(v3.replace("\"v\":3", "\"v\":4")))
    }

    @Test
    fun `a v3 frame is byte-identical to what the previous build wrote`() {
        // Співрозмовник зі старою збіркою мусить читати наші кадри без жодної
        // зміни; зайве поле в конверті зламало б це мовчки.
        val frame = seal(message())
        assertFalse("порожнього sid не має бути на дроті взагалі", frame.contains("sid"))
        assertEquals("", PeerEnvelope.parse(frame)!!.sid)
    }

    @Test
    fun `an absurd meeting name is refused before it becomes a cache key`() {
        assertNull(PeerEnvelope.sealV4(message(), "x".repeat(64), sessionKey))
        assertNull(PeerEnvelope.sealV4(message(), "", sessionKey))
        val frame = sealV4(message())
        assertNull(PeerEnvelope.parse(frame.replace("\"sid\":\"$sid\"", "\"sid\":\"${"x".repeat(64)}\"")))
    }

    @Test
    fun `the words are not in a v4 frame either`() {
        val frame = PeerEnvelope.sealV4(
            message(body = "я біля мосту, 50.45,30.52"), sid, sessionKey,
        )!!.frame
        assertFalse(frame.contains("біля мосту"))
        assertFalse(frame.contains("50.45"))
        assertTrue(frame.contains("\"v\":4"))
    }

    @Test
    fun `no key for the claimed sender means no decryption attempt`() {
        var askedFor: String? = null
        assertNull(PeerEnvelope.open(seal(message())) { from -> askedFor = from; null })
        assertEquals("aaaa1111", askedFor)
    }
}
