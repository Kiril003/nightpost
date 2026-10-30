package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Голосове й кружок ЛЮДИНІ — справжнім конвертом пари, тим самим кошиком, що й
 * фото: опис (з родом) і шматки PH-FCH; на прийомі — рід із опису, байти цілі,
 * відбиток зійшовся. А на вузькій дорозі коротке голосове їде смугою слів.
 */
class VoiceNoteRoundTripTest {

    private val key = randomBytes(32)
    private val signing = Ed25519.generateKeypair()

    private fun describe(blobId: String, data: ByteArray, kind: String?, name: String, mime: String): String =
        """{"name":"$name","size":${data.size},"mime":"$mime","sha256":"${PeerFile.sha256Hex(data)}",""" +
            """"blob_id":"$blobId","key_hex":"${"ab".repeat(32)}","nonce_hex":"${"cd".repeat(12)}"""" +
            (kind?.let { ""","kind":"$it"""" } ?: "") + ""","duration":4,"waveform":[0.1,0.8,0.4]}"""

    /** Лист через справжній конверт: підписати, запечатати, відчинити, звірити підпис. */
    private fun travel(body: String, i: Int): String {
        val letter = PeerSealing.seal(
            PeerMessage(id = "m-$i", fromId = "aaaa1111", toId = "bbbb2222", body = body, sentAtMs = 1_000L + i),
            signing.privateKey,
        )
        val opened = PeerEnvelope.open(PeerEnvelope.seal(letter, key)!!.frame) { key }!!
        assertTrue(PeerSealing.verify(opened, signing.publicKey))
        return opened.body
    }

    private fun roundTrip(kind: String, name: String, mime: String, size: Int) {
        val data = randomBytes(size)
        val blobId = PeerFile.newBlobId()
        val total = PeerFile.chunkCount(data.size)
        val bodies = listOf(describe(blobId, data, kind, name, mime)) +
            (0 until total).map { PeerFile.encodeChunk(blobId, it, total, PeerFile.sliceAt(data, it)!!) }

        val arrived = bodies.mapIndexed { i, b -> travel(b, i) }
        val att = PeerFile.describe(arrived.first())!!
        assertEquals(kind, att.kind)
        val parts = arrived.drop(1).map { PeerFile.decodeChunk(it)!! }.associate { it.index to it.bytes }
        val joined = PeerFile.join(parts, total)!!
        assertArrayEquals(data, joined)
        assertTrue("відбиток з опису", PeerFile.matches(joined, att.sha256))
    }

    @Test fun `голосове людині — опис із родом і шматки доїжджають цілими`() =
        roundTrip(PeerFile.VOICE, "voice_1790000000.webm", "audio/webm", 50_000)

    @Test fun `кружок людині — так само`() =
        roundTrip(PeerFile.VIDEO_NOTE, "video_note_1790000000.webm", "video/webm", 300_000)

    @Test fun `рід — з опису, а без нього — з імені й типу, як у вебі та на ПК`() {
        assertEquals(PeerFile.VOICE, PeerFile.kindOf("voice", "x.bin", "application/octet-stream"))
        assertEquals(PeerFile.VIDEO_NOTE, PeerFile.kindOf(null, "video_note_1.webm", "video/webm"))
        assertEquals(PeerFile.VOICE, PeerFile.kindOf(null, "voice_1.webm", "video/webm"))
        assertEquals(PeerFile.VOICE, PeerFile.kindOf(null, "запис.ogg", "audio/ogg"))
        assertEquals(PeerFile.IMAGE, PeerFile.kindOf(null, "IMG.jpg", "image/jpeg"))
        assertEquals(PeerFile.FILE, PeerFile.kindOf("щось інше", "звіт.pdf", "application/pdf"))
    }

    // ── вузька дорога ────────────────────────────────────────────────────

    private fun line(id: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = "me", toId = "them", body = body, sentAtMs = at)

    private fun voiceLines(size: Int, kind: String = PeerFile.VOICE): List<PeerMessage> {
        val data = randomBytes(size)
        val blobId = PeerFile.newBlobId()
        val total = PeerFile.chunkCount(size)
        return listOf(line("d-$blobId", describe(blobId, data, kind, "voice_1.webm", "audio/webm"), 1)) +
            (0 until total).map { line("c-$blobId-$it", PeerFile.vaultedChunk(blobId, it, total), 2L + it) }
    }

    @Test fun `вузько — коротке голосове їде, довге й фото чекають`() {
        val short = voiceLines(PeerLane.NARROW_VOICE_BYTES)
        val long = voiceLines(PeerLane.NARROW_VOICE_BYTES + 1)
        val photo = voiceLines(20_000, kind = PeerFile.IMAGE)
        val log = short + long + photo
        // Описи вже поїхали — у черзі лишились шматки.
        val due = log.filter { PeerFile.isChunk(it.body) }

        val speech = PeerLane.speechBlobs(log)
        val went = PeerLane.order(due, narrow = true, nowMs = 10_000, speech = speech).map { it.id }.toSet()
        assertEquals("коротке голосове — усі шматки", short.drop(1).map { it.id }.toSet(), went)

        // Широко — їде все, і голос усе одно попереду фото.
        val wide = PeerLane.order(due, narrow = false, nowMs = 10_000, speech = speech)
        assertEquals(due.size, wide.size)
        assertTrue(wide.indexOfFirst { it.id.startsWith("c-") && it in short } < wide.indexOfFirst { it in photo })
    }

    @Test fun `без описів у журналі вузька дорога не вгадує — шматки чекають`() {
        val short = voiceLines(1_000)
        val due = short.filter { PeerFile.isChunk(it.body) }
        assertEquals(emptyList<PeerMessage>(), PeerLane.order(due, narrow = true, nowMs = 0, speech = emptySet()))
    }
}
