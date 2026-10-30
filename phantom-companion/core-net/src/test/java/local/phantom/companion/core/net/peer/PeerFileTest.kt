package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фото, що доїхало, — і фото, що не доїхало й сказало про це.
 *
 * Файл їде сотнями звичайних листів, тож усе, що вже доведене на тексті —
 * підпис, конверт, черга — тут не перевіряється вдруге. Перевіряється рівно
 * те, що додала ця хвиля: чи склеюється розібране назад, чи ловиться підміна
 * в одному шматку з сотень, чи пролізає граничний шматок у справжній кадр і
 * чи відмова звучить уголос замість тиші.
 */
class PeerFileTest {

    private val key = randomBytes(32)

    private fun bytes(n: Int): ByteArray = randomBytes(n)

    private fun manifestOf(blobId: String, data: ByteArray, mime: String = "image/jpeg"): String =
        """{"name":"IMG_0042.jpg","size":${data.size},"mime":"$mime",""" +
            """"sha256":"${PeerFile.sha256Hex(data)}","blob_id":"$blobId",""" +
            """"key_hex":"aa","nonce_hex":"bb"}"""

    // ── дорога туди й назад ──────────────────────────────────────────────

    @Test
    fun `файл переживає розбирання на шматки і складання назад`() {
        // Навмисне не кратний шматку: остання порція коротша, і саме на ній
        // ламається будь-яке складання, писане в припущенні рівних частин.
        val data = bytes(PeerFile.CHUNK_BYTES * 3 + 977)
        val blobId = PeerFile.newBlobId()
        val total = PeerFile.chunkCount(data.size)
        assertEquals(4, total)

        val wire = (0 until total).map {
            PeerFile.encodeChunk(blobId, it, total, PeerFile.sliceAt(data, it)!!)
        }
        val parts = wire.map { PeerFile.decodeChunk(it)!! }
            .associate { it.index to it.bytes }

        val joined = PeerFile.join(parts, total)!!
        assertArrayEqualsMsg("файл склався не тим, чим був", data, joined)
        assertTrue(PeerFile.matches(joined, PeerFile.sha256Hex(data)))
    }

    @Test
    fun `порядок шматків не має значення`() {
        val data = bytes(PeerFile.CHUNK_BYTES * 2 + 5)
        val blobId = PeerFile.newBlobId()
        val total = PeerFile.chunkCount(data.size)
        // Черга пробує рядки незалежно, і жодне місце не обіцяє порядку.
        val parts = (0 until total).shuffled()
            .map { PeerFile.decodeChunk(PeerFile.encodeChunk(blobId, it, total, PeerFile.sliceAt(data, it)!!))!! }
            .associate { it.index to it.bytes }
        assertArrayEqualsMsg("файл зібрався у хибному порядку", data, PeerFile.join(parts, total)!!)
    }

    @Test
    fun `весь шлях — запечатано, відкрито, складено`() {
        // Той самий конверт і той самий підпис, що возять слова. Якби файл
        // потребував власного каналу, саме цей тест довелось би писати заново.
        val data = bytes(PeerFile.CHUNK_BYTES + 100)
        val blobId = PeerFile.newBlobId()
        val total = PeerFile.chunkCount(data.size)
        val signing = Ed25519.generateKeypair()

        val parts = (0 until total).associate { i ->
            val letter = PeerSealing.seal(
                PeerMessage(
                    id = "chunk-$i",
                    fromId = "aaaa1111",
                    toId = "bbbb2222",
                    body = PeerFile.encodeChunk(blobId, i, total, PeerFile.sliceAt(data, i)!!),
                    sentAtMs = 1_000L + i,
                ),
                signing.privateKey,
            )
            val frame = PeerEnvelope.seal(letter, key)!!.frame
            val opened = PeerEnvelope.open(frame) { key }!!
            assertTrue("підпис шматка не перевірився", PeerSealing.verify(opened, signing.publicKey))
            val chunk = PeerFile.decodeChunk(opened.body)!!
            chunk.index to chunk.bytes
        }
        assertArrayEqualsMsg("наскрізь пройшло не те", data, PeerFile.join(parts, total)!!)
    }

    /**
     * Стеля кадру — 64 КіБ, і байт іде до неї через ДВА base64. Арифметику в
     * коментарі перевірити нічим; цей тест перевіряє її справжнім конвертом,
     * справжнім підписом і найдовшими правдоподібними ідентифікаторами.
     */
    @Test
    fun `найбільший шматок пролізає у справжній конверт`() {
        val signing = Ed25519.generateKeypair()
        val full = PeerFile.sliceAt(bytes(PeerFile.CHUNK_BYTES * 2), 0)!!
        assertEquals(PeerFile.CHUNK_BYTES, full.size)
        val letter = PeerSealing.seal(
            PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                // Відбитки ключів у base64 — найдовше, що тут буває.
                fromId = b64(randomBytes(32)),
                toId = b64(randomBytes(32)),
                body = PeerFile.encodeChunk(PeerFile.newBlobId(), 170, 171, full),
                sentAtMs = System.currentTimeMillis(),
                replyToId = java.util.UUID.randomUUID().toString(),
            ),
            signing.privateKey,
        )
        val sealed = PeerEnvelope.seal(letter, key)
        assertNotNull("граничний шматок не вліз у кадр — стеля або шматок помиляються", sealed)
        val size = sealed!!.frame.toByteArray(Charsets.UTF_8).size
        assertTrue("кадр $size байтів проти стелі ${PeerWire.MAX_BODY_BYTES}", size <= PeerWire.MAX_BODY_BYTES)
    }

    // ── підміна ─────────────────────────────────────────────────────────

    @Test
    fun `один зіпсований шматок валить увесь файл`() {
        val data = bytes(PeerFile.CHUNK_BYTES * 3)
        val total = PeerFile.chunkCount(data.size)
        val parts = (0 until total).associate { it to PeerFile.sliceAt(data, it)!! }.toMutableMap()
        // Довжина та сама, змінений один біт: спійматись це може лише
        // відбитком усього файла, і саме тому він у описі.
        val spoiled = parts.getValue(1).copyOf()
        spoiled[7] = (spoiled[7].toInt() xor 0x01).toByte()
        parts[1] = spoiled

        val joined = PeerFile.join(parts, total)!!
        assertEquals("довжина навмисне збіглася", data.size, joined.size)
        assertFalse(
            "підмінений шматок пройшов як справжній файл",
            PeerFile.matches(joined, PeerFile.sha256Hex(data)),
        )
    }

    @Test
    fun `неповний файл не склеюється взагалі`() {
        val data = bytes(PeerFile.CHUNK_BYTES * 3)
        val total = PeerFile.chunkCount(data.size)
        val parts = (0 until total).associate { it to PeerFile.sliceAt(data, it)!! } - 1
        assertEquals(listOf(1), PeerFile.missing(parts.keys, total))
        // Половина файла, видана за файл, гірша за відсутній файл.
        assertNull(PeerFile.join(parts, total))
    }

    @Test
    fun `кадр шматка не читається, коли в ньому щось не так`() {
        val ok = PeerFile.encodeChunk(PeerFile.newBlobId(), 0, 1, bytes(10))
        assertNotNull(PeerFile.decodeChunk(ok))
        assertNull("чужа абетка в ідентифікаторі", PeerFile.decodeChunk("PH-FCH:../../etc:0:1:AAAA"))
        assertNull("номер поза межами", PeerFile.decodeChunk(PeerFile.encodeChunk(PeerFile.newBlobId(), 5, 3, bytes(10))))
        assertNull("не наш кадр", PeerFile.decodeChunk("просто слова"))
        assertNull("нема тіла", PeerFile.decodeChunk("PH-FCH:" + PeerFile.newBlobId() + ":0:1:"))
    }

    // ── межі, сказані вголос ────────────────────────────────────────────

    @Test
    fun `порожній і завеликий файли отримують відмову, а не тишу`() {
        assertEquals(PeerFile.Refusal.EMPTY, PeerFile.refuse(0))
        assertEquals(PeerFile.Refusal.TOO_BIG, PeerFile.refuse(PeerFile.MAX_BYTES + 1))
        assertNull("файл рівно по межі мусить їхати", PeerFile.refuse(PeerFile.MAX_BYTES))
        assertNull(PeerFile.refuse(1))
    }

    @Test
    fun `файл на межі не зʼїдає всю розмову`() {
        // Шматки — рядки тієї самої черги. Якби найбільший файл важив більше
        // за [PeerOutbox.MAX_PER_PEER], одне фото витерло б розмову цілком.
        val chunks = PeerFile.chunkCount(PeerFile.MAX_BYTES)
        assertTrue("$chunks шматків проти межі ${PeerOutbox.MAX_PER_PEER}", chunks < PeerOutbox.MAX_PER_PEER)
    }

    // ── опис ────────────────────────────────────────────────────────────

    @Test
    fun `опис вкладення читається просто з тіла листа`() {
        val data = bytes(1234)
        val blobId = PeerFile.newBlobId()
        val att = PeerFile.describe(manifestOf(blobId, data))!!
        assertEquals(blobId, att.blobId)
        assertEquals("IMG_0042.jpg", att.name)
        assertEquals("image/jpeg", att.mime)
        assertEquals(data.size, att.size)
        assertEquals(PeerFile.sha256Hex(data), att.sha256)
    }

    @Test
    fun `звичайний лист не стає вкладенням`() {
        assertNull(PeerFile.describe("привіт"))
        assertNull(PeerFile.describe(""))
        // Людина процитувала JSON — це все ще речення, а не фотографія.
        assertNull(PeerFile.describe("""{"blob_id":"тут","key_hex":"aa"}"""))
        assertNull("без ключа це не вкладення", PeerFile.describe("""{"blob_id":"${PeerFile.newBlobId()}"}"""))
        assertNull(PeerFile.describe(PeerFile.encodeChunk(PeerFile.newBlobId(), 0, 1, bytes(4))))
    }

    @Test
    fun `шматок і опис ніколи не плутаються`() {
        val chunk = PeerFile.encodeChunk(PeerFile.newBlobId(), 0, 1, bytes(4))
        val manifest = manifestOf(PeerFile.newBlobId(), bytes(4))
        assertTrue(PeerFile.isChunk(chunk))
        assertFalse(PeerFile.isChunk(manifest))
        assertTrue(PeerFile.isAttachment(manifest))
        assertFalse(PeerFile.isAttachment(chunk))
    }

    @Test
    fun `ідентифікатор блоба не вміє виразити обхід каталогу`() {
        repeat(50) {
            val id = PeerFile.newBlobId()
            assertTrue(id, PeerFile.isBlobId(id))
            assertFalse(id.contains('.'))
            assertFalse(id.contains('/'))
        }
        assertFalse(PeerFile.isBlobId(".."))
        assertFalse(PeerFile.isBlobId("../../secret"))
        assertFalse(PeerFile.isBlobId(""))
        assertFalse("велика літера — не наша абетка", PeerFile.isBlobId("A".repeat(32)))
    }

    private fun assertArrayEqualsMsg(message: String, expected: ByteArray, actual: ByteArray) {
        assertTrue(message, expected.contentEquals(actual))
    }
}
