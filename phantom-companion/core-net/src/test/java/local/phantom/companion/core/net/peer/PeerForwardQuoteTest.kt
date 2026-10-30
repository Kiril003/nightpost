package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P03: автор пересланого і обраний шматок цитати їдуть у конверті; стара збірка їх просто не бачить. */
class PeerForwardQuoteTest {

    private val key = randomBytes(32)
    private val signer = Ed25519.generateKeypair()
    private val json = Json { ignoreUnknownKeys = true }

    /** Лист очима збірки, що ще не знає `fw`/`qt`: лише старі поля. */
    @Serializable
    private data class OldBuildLetter(
        val id: String,
        val fromId: String,
        val toId: String,
        val body: String,
        val sentAtMs: Long,
        val replyToId: String = "",
        val signature: String = "",
    )

    private fun letter(fw: PeerMessage.Forward? = null, qt: String = "", replyToId: String = "") =
        PeerSealing.seal(
            PeerMessage(
                id = "m-1", fromId = "aaaa1111", toId = "bbbb2222", body = "дивись",
                sentAtMs = 1_000L, replyToId = replyToId, fw = fw, qt = qt,
            ),
            signer.privateKey,
        )

    private fun plaintextOf(m: PeerMessage) = json.encodeToString(PeerMessage.serializer(), m)

    @Test
    fun `forward author and quoted part survive seal and open`() {
        val sent = letter(fw = PeerMessage.Forward("Оксана", "peer_oksana"), qt = "квитки в мене", replyToId = "m-0")
        val got = PeerEnvelope.open(PeerEnvelope.seal(sent, key)!!.frame) { key }
        assertNotNull(got)
        assertEquals(PeerMessage.Forward("Оксана", "peer_oksana"), got!!.fw)
        assertEquals("квитки в мене", got.qt)
        assertTrue(PeerSealing.verify(got, signer.publicKey, "aaaa1111"))
    }

    @Test
    fun `a plain letter carries no new keys, byte for byte as before`() {
        val plain = plaintextOf(letter())
        assertFalse(plain.contains("\"fw\""))
        assertFalse(plain.contains("\"qt\""))
    }

    @Test
    fun `an old build reads the letter and its signature still checks`() {
        val sent = letter(fw = PeerMessage.Forward("Оксана", "peer_oksana"), qt = "квитки", replyToId = "m-0")
        val raw = plaintextOf(sent)
        assertTrue(raw.contains("\"fw\""))
        val old = json.decodeFromString(OldBuildLetter.serializer(), raw)
        assertEquals("дивись", old.body)
        val asOldSeesIt = PeerMessage(
            id = old.id, fromId = old.fromId, toId = old.toId, body = old.body,
            sentAtMs = old.sentAtMs, replyToId = old.replyToId, signature = old.signature,
        )
        assertTrue(PeerSealing.verify(asOldSeesIt, signer.publicKey, "aaaa1111"))
    }

    @Test
    fun `wire values are clamped and a blank author is not a forward`() {
        assertNull(PeerMessage.forwardOf("   ", "x"))
        assertEquals(PeerMessage.FORWARD_NAME_MAX, PeerMessage.forwardOf("я".repeat(500), "")!!.name.length)
        assertEquals(PeerMessage.QUOTE_MAX, PeerMessage.quoteOf("ц".repeat(900)).length)
        assertEquals("шматок", PeerMessage.quoteOf("  шматок  "))
    }
}
