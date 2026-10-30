package local.phantom.companion.core.net.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ пари: два телефони мусять вивести той самий, ніхто третій — жодного.
 *
 * Найтихіша поразка тут — не «не збіглось», а «збіглось не з тим»: віддати
 * старий ключ після переспарювання означає й далі шифрувати для того, кого
 * замінили.
 */
class PeerChannelTest {

    private val marta = dhKeypair()
    private val kyrylo = dhKeypair()
    private val martaId = "a1b2c3d4e5f60718"
    private val kyryloId = "f0e1d2c3b4a59687"

    @Test
    fun `both sides derive the same key without agreeing who goes first`() {
        val hers = PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        val his = PeerChannel.derive(kyrylo.privateKey, b64(marta.publicKey), kyryloId, martaId)
        assertNotNull(hers)
        assertArrayEquals(hers, his)
        assertEquals(32, hers!!.size)
    }

    @Test
    fun `a third person's pair gets a different key`() {
        val stranger = dhKeypair()
        val ours = PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)!!
        val theirs = PeerChannel.derive(marta.privateKey, b64(stranger.publicKey), martaId, "cccc")!!
        assertFalse(ours.contentEquals(theirs))
    }

    @Test
    fun `the same two keys under different names are a different channel`() {
        // Сіль — це відбитки, тож той самий ECDH у контексті іншої пари не дає
        // того самого ключа: шифротекст однієї розмови не відкриється в іншій.
        val a = PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)!!
        val b = PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, "9999")!!
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a peer with no encryption key gets no key, never an empty one`() {
        assertNull(PeerChannel.derive(marta.privateKey, "", martaId, kyryloId))
        assertNull(PeerChannel.derive(marta.privateKey, "   ", martaId, kyryloId))
    }

    @Test
    fun `garbage key material is refused rather than thrown`() {
        assertNull(PeerChannel.derive(marta.privateKey, "not-base64!!!", martaId, kyryloId))
        assertNull(PeerChannel.derive(marta.privateKey, b64(ByteArray(16)), martaId, kyryloId))
        assertNull(PeerChannel.derive(ByteArray(7), b64(kyrylo.publicKey), martaId, kyryloId))
    }

    @Test
    fun `a cached key is derived once`() {
        val cache = PeerChannel.Cache()
        var derivations = 0
        val first = cache.get(kyryloId, b64(kyrylo.publicKey)) {
            derivations++
            PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        }
        val second = cache.get(kyryloId, b64(kyrylo.publicKey)) {
            derivations++
            PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        }
        assertEquals(1, derivations)
        assertSame(first, second)
    }

    @Test
    fun `a re-paired peer can never be served the old key`() {
        // Кеш ключований самим публічним ключем, тож новий ключ — інший запис,
        // а не той самий запис із застарілим значенням.
        val cache = PeerChannel.Cache()
        val fresh = dhKeypair()
        val old = cache.get(kyryloId, b64(kyrylo.publicKey)) {
            PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        }!!
        val new = cache.get(kyryloId, b64(fresh.publicKey)) {
            PeerChannel.derive(marta.privateKey, b64(fresh.publicKey), martaId, kyryloId)
        }!!
        assertFalse(old.contentEquals(new))
    }

    @Test
    fun `dropping a peer drops their keys`() {
        val cache = PeerChannel.Cache()
        cache.get(kyryloId, b64(kyrylo.publicKey)) {
            PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        }
        assertEquals(1, cache.size())
        cache.forget(kyryloId)
        assertEquals(0, cache.size())
    }

    @Test
    fun `stopping the mesh leaves no keys behind`() {
        val cache = PeerChannel.Cache()
        cache.get(kyryloId, b64(kyrylo.publicKey)) {
            PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)
        }
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `a failed derivation is not remembered as a key`() {
        val cache = PeerChannel.Cache()
        assertNull(cache.get(kyryloId, "not-base64!!!") { null })
        assertEquals(0, cache.size())
    }

    @Test
    fun `what one pair sealed another pair cannot open`() {
        val stranger = dhKeypair()
        val ours = PeerChannel.derive(marta.privateKey, b64(kyrylo.publicKey), martaId, kyryloId)!!
        val theirs = PeerChannel.derive(
            stranger.privateKey,
            b64(marta.publicKey),
            "cccc4444",
            martaId,
        )!!
        val frame = PeerEnvelope.seal(
            PeerMessage(
                id = "m-1", fromId = martaId, toId = kyryloId,
                body = "координати", sentAtMs = 1L, signature = "x",
            ),
            ours,
        )!!.frame
        assertNull(PeerEnvelope.open(frame) { theirs })
        assertTrue(PeerEnvelope.open(frame) { ours } != null)
    }
}
