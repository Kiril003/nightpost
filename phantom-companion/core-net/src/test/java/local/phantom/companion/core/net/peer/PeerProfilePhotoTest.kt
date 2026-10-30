package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProfilePhotoTest {

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    @Test
    fun `фото їде поруч із мініатюрою і повертається байт у байт`() {
        val photo = randomBytes(20_000)
        val thumb = randomBytes(4_000)
        val src = PeerProfile.Body(
            rev = 9,
            avatar = PeerProfile.Field.Set(thumb),
            photo = PeerProfile.Field.Set(photo),
        )
        val back = PeerProfile.decode(PeerProfile.encode(src)!!)!!
        assertEquals(src, back)
        assertTrue((back.photo as PeerProfile.Field.Set).value.contentEquals(photo))
    }

    @Test
    fun `фото понад стелю не складається в кадр`() {
        val over = PeerProfile.Body(rev = 1, photo = PeerProfile.Field.Set(ByteArray(PeerProfile.MAX_PHOTO_BYTES + 1)))
        assertNull(PeerProfile.encode(over))
        val at = PeerProfile.Body(rev = 1, photo = PeerProfile.Field.Set(ByteArray(PeerProfile.MAX_PHOTO_BYTES)))
        assertNotNull(PeerProfile.encode(at))
    }

    @Test
    fun `прибране фото — не те саме, що не приїхало`() {
        val cleared = PeerProfile.decode(PeerProfile.encode(PeerProfile.Body(rev = 2, photo = PeerProfile.Field.Cleared))!!)!!
        assertEquals(PeerProfile.Field.Cleared, cleared.photo)
        val absent = PeerProfile.decode(PeerProfile.encode(PeerProfile.Body(rev = 3))!!)!!
        assertEquals(PeerProfile.Field.Absent, absent.photo)
    }

    @Test
    fun `кадр, де кожне поле на стелі, влазить у справжній конверт`() {
        val body = PeerProfile.encode(
            PeerProfile.Body(
                rev = 0xFFFF_FFFFL,
                avatar = PeerProfile.Field.Set(randomBytes(PeerProfile.MAX_AVATAR_BYTES)),
                statusEmoji = PeerProfile.Field.Set("a".repeat(PeerProfile.MAX_STATUS_EMOJI_BYTES)),
                statusText = PeerProfile.Field.Set("a".repeat(PeerProfile.MAX_STATUS_TEXT_BYTES)),
                bio = PeerProfile.Field.Set("a".repeat(PeerProfile.MAX_BIO_BYTES)),
                name = PeerProfile.Field.Set("a".repeat(PeerProfile.MAX_NAME_BYTES)),
                handle = PeerProfile.Field.Set("abcdefghijklmnopqrstuvwxyz0123"),
                photo = PeerProfile.Field.Set(randomBytes(PeerProfile.MAX_PHOTO_BYTES)),
            ),
        )
        assertNotNull(body)
        val signing = Ed25519.generateKeypair()
        val letter = PeerSealing.seal(
            PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                fromId = b64(randomBytes(32)),
                toId = b64(randomBytes(32)),
                body = body!!,
                sentAtMs = System.currentTimeMillis(),
                replyToId = java.util.UUID.randomUUID().toString(),
            ),
            signing.privateKey,
        )
        val key = randomBytes(32)
        assertNotNull("профіль на стелі не влазить у кадр", PeerEnvelope.seal(letter, key))
        assertNotNull("профіль на стелі не влазить у кадр зустрічі", PeerEnvelope.sealV4(letter, "s".repeat(32), key))
    }

    @Test
    fun `доставлений профіль забувається з журналу, недоставлений — ні`() {
        val frame = PeerProfile.encode(PeerProfile.Body(rev = 1, photo = PeerProfile.Field.Set(ByteArray(10))))!!
        val delivered = PeerMessage("a", "me", "them", frame, 1, state = PeerMessage.State.DELIVERED)
        val queued = PeerMessage("b", "me", "them", frame, 2)
        val arrived = PeerMessage("c", "them", "me", frame, 3, state = PeerMessage.State.DELIVERED)
        val word = PeerMessage("d", "them", "me", "привіт", 4, state = PeerMessage.State.DELIVERED)
        val kept = PeerOutbox.prune(listOf(delivered, queued, arrived, word), "them").map { it.id }
        assertEquals(listOf("b", "d"), kept)
    }
}
