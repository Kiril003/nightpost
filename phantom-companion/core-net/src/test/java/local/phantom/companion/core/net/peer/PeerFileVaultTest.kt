package local.phantom.companion.core.net.peer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скільки коштує файл у черзі ПІСЛЯ того, як байти пішли з журналу.
 *
 * [PeerFileCeilingTest] виміряв стару ціну й нею пояснив стелю 4 МіБ: журнал
 * серіалізується в ОДИН рядок і переписується цілком на кожну зміну, тож поки
 * шматки в ньому, телефон тримає файл у купі двічі. Тут міряється нова ціна —
 * рядок із ПОСИЛАННЯМ замість байтів, — і з неї виводиться нова стеля.
 *
 * Жодне число нижче не прикинуте: усі — з тієї самої серіалізації, якою пише
 * `PeerOutboxStore`.
 */
class PeerFileVaultTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val blobId = "0".repeat(PeerFile.BLOB_ID_CHARS)

    private fun letter(body: String, i: Int) = PeerMessage(
        id = "msg_" + "%012d".format(i),
        fromId = "a".repeat(16),
        toId = "b".repeat(16),
        body = body,
        sentAtMs = 1_788_517_702_898L,
        signature = "s".repeat(88),
        state = PeerMessage.State.QUEUED,
    )

    // ── посилання поводиться як шматок скрізь, де це важливо ─────────────

    @Test
    fun `посилання лишається шматком для журналу, але без байтів`() {
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }
        val real = PeerFile.encodeChunk(blobId, 7, 171, full)
        val ref = PeerFile.vaultedChunk(blobId, 7, 171)

        // Те, на чому стоїть уся решта журналу, мусить бачити їх однаково.
        assertTrue("посилання перестало бути шматком", PeerFile.isChunk(ref))
        assertEquals("голова посилання розійшлась із головою шматка", PeerFile.head(real), PeerFile.head(ref))
        assertFalse("посилання пролізло в розмову", PeerOutbox.isSpoken(letter(ref, 0)))
        assertEquals("рід посилання не CHUNK", PeerFrames.Kind.CHUNK, PeerFrames.kindOf(ref))

        assertTrue(PeerFile.isVaulted(ref))
        assertFalse("справжній шматок прийняли за посилання", PeerFile.isVaulted(real))
    }

    /**
     * Найважливіше: посилання НЕ читається як шматок із байтами.
     *
     * Зірочки в абетці base64 немає, тож розбір падає, і [PeerFile.decodeChunk]
     * віддає null. Якби він віддавав порожні байти, приймач записав би на диск
     * нуль байтів і файл ніколи б не зійшовся за відбитком — мовчазна втрата
     * найгіршого ґатунку.
     */
    @Test
    fun `посилання не вдає шматка з байтами`() {
        assertNull(PeerFile.decodeChunk(PeerFile.vaultedChunk(blobId, 0, 1)))
    }

    @Test
    fun `порожній хвіст і сміття посиланням не вважаються`() {
        assertFalse(PeerFile.isVaulted("PH-FCH:$blobId:0:1:"))
        assertFalse(PeerFile.isVaulted("PH-FCH:$blobId:0:1:**"))
        assertFalse(PeerFile.isVaulted("не кадр узагалі"))
    }

    // ── ціна, виміряна ───────────────────────────────────────────────────

    /**
     * Ціна рядка з посиланням проти рядка з байтами — на тій самій серіалізації.
     */
    @Test
    fun `рядок із посиланням дешевший за рядок із байтами — у стільки разів`() {
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }
        val heavy = json.encodeToString(listOf(letter(PeerFile.encodeChunk(blobId, 0, 171, full), 0)))
        val light = json.encodeToString(listOf(letter(PeerFile.vaultedChunk(blobId, 0, 171), 0)))

        val ratio = heavy.length.toDouble() / light.length
        assertTrue(
            "рядок з байтами $heavy.length знаків, з посиланням ${light.length} — виграш лише ${ratio.toInt()}x",
            ratio > 80,
        )
        // Посилання важить одиниці сотень знаків, а не десятки тисяч.
        assertTrue("рядок із посиланням розповнів до ${light.length} знаків", light.length < 400)
    }

    /**
     * ЧИСЛА, ЯКІ СТОЯТЬ У KDoc, — тут і ніде більше.
     *
     * Решта вимірів у цьому файлі перевіряють ВІДНОШЕННЯ («дешевше», «менше»),
     * і цього досить, щоб рішення лишалось правильним. Але над
     * [PeerFile.MAX_BYTES] і [PeerOutbox.MAX_PER_PEER] написані КОНКРЕТНІ числа
     * — 33 033, 267, 366 345, 1 366, — і поки їх ніщо не тримає, вони старіють
     * мовчки: перше ж поле, додане в [PeerMessage], зробить коментар брехнею, а
     * всі assertTrue вище лишаться зеленими.
     *
     * Тому кожне число з коментаря прибите тут. Почервоніє — коментар треба
     * переписати, а не тест послабити.
     */
    @Test
    fun `числа з коментаря над стелею — саме ці`() {
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }
        val heavy = json.encodeToString(listOf(letter(PeerFile.encodeChunk(blobId, 0, 171, full), 0)))
        val light = json.encodeToString(listOf(letter(PeerFile.vaultedChunk(blobId, 0, 1366), 0)))

        assertEquals("рядок із байтами важить не 33 033 знаки", 33_033, heavy.length)
        assertEquals("рядок із посиланням важить не 267 знаків", 267, light.length)
        assertEquals("стеля дає не 1366 шматків", 1_366, PeerFile.chunkCount(PeerFile.MAX_BYTES))
        assertEquals("черга вміщує не 2000 рядків", 2_000, PeerOutbox.MAX_PER_PEER)

        val chunks = PeerFile.chunkCount(PeerFile.MAX_BYTES)
        val journal = json.encodeToString((0 until chunks).map { i ->
            letter(PeerFile.vaultedChunk(blobId, i, chunks), i)
        })
        assertEquals("журнал на новій стелі важить не 366 345 знаків", 366_345, journal.length)
    }

    /**
     * НОВА СТЕЛЯ, і звідки взялося саме це число.
     *
     * Дві межі, і обидві мусять зійтися:
     *
     * 1) ВАГА ЖУРНАЛУ в найдорожчу мить — усі шматки в черзі, журнал
     *    переписується. Стара стеля коштувала 5,6-6,2 млн знаків
     *    ([PeerFileCeilingTest]); нова не сміє коштувати більше.
     *
     * 2) РЯДКИ ЧЕРГИ: [PeerOutbox.MAX_PER_PEER]. Файл, що дає більше шматків,
     *    ніж уміщує черга розмови, виштовхує сам себе — і частина шматків
     *    зникає, не поїхавши. Плюс місце для самих РОЗМОВ: черга, зайнята
     *    одним файлом під завʼязку, це розмова, у якій не лишилось слів.
     */
    @Test
    fun `нова стеля тримається обох меж — і ваги журналу, і рядків черги`() {
        val chunks = PeerFile.chunkCount(PeerFile.MAX_BYTES)
        val journal = json.encodeToString((0 until chunks).map { i ->
            letter(PeerFile.vaultedChunk(blobId, i, chunks), i)
        })

        // 1) Вага: не дорожче за стару стелю в її найдорожчу мить.
        assertTrue(
            "журнал на новій стелі важить ${journal.length} знаків — дорожче за стару (6 200 000)",
            journal.length < 6_200_000,
        )

        // 2) Рядки: файл мусить уміститись, ще й лишити місце словам.
        assertTrue(
            "стеля файла дає $chunks шматків при черзі на ${PeerOutbox.MAX_PER_PEER} рядків",
            chunks < PeerOutbox.MAX_PER_PEER,
        )
        val roomForWords = PeerOutbox.MAX_PER_PEER - chunks
        assertTrue(
            "на стелі файл лишає лише $roomForWords рядків на саму розмову",
            roomForWords >= 500,
        )
    }

    /**
     * Ціна ПЕРЕПИСУВАННЯ, а не лише ваги: саме вона росте квадратом.
     *
     * Журнал переписується цілком на кожне [PeerOutbox.markSent] і
     * [PeerOutbox.markDelivered], тобто двічі на шматок. N шматків коштують
     * N x 2 x (вага журналу), і вага теж росте з N. Цей вимір не дає підняти
     * стелю в місце, де телефон ляже на самому переписуванні.
     */
    @Test
    fun `переписування журналу на новій стелі дешевше, ніж було на старій`() {
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }

        // Було: 171 шматок із байтами.
        val oldChunks = 171
        val oldRow = json.encodeToString(listOf(letter(PeerFile.encodeChunk(blobId, 0, oldChunks, full), 0))).length
        val oldChurn = oldChunks.toLong() * 2 * oldChunks * oldRow

        // Стало: стільки шматків, скільки дає нова стеля, з посиланнями.
        val newChunks = PeerFile.chunkCount(PeerFile.MAX_BYTES)
        val newRow = json.encodeToString(listOf(letter(PeerFile.vaultedChunk(blobId, 0, newChunks), 0))).length
        val newChurn = newChunks.toLong() * 2 * newChunks * newRow

        assertTrue(
            "нова стеля ($newChunks шматків) коштує $newChurn знаків переписування проти " +
                "старих $oldChurn — телефон платив би більше, ніж до підняття",
            newChurn <= oldChurn,
        )
    }

    /**
     * Ціна «безмежно» тим самим приладом.
     *
     * Власник просив «по лімітам безмежно». Вага журналу цьому більше не
     * заважає — заважає ЧЕРГА: 100 МБ це 4267 рядків при стелі
     * [PeerOutbox.MAX_PER_PEER]. Число лишається в тесті, щоб ніхто не
     * написав «безмежно» там, де його немає.
     */
    @Test
    fun `сто мегабайтів досі не вміщуються — і ось у скільки разів`() {
        val chunksFor100 = PeerFile.chunkCount(100 * 1024 * 1024)
        assertEquals("100 МБ — це стільки шматків", 4_267, chunksFor100)
        assertTrue(
            "100 МБ це $chunksFor100 рядків проти ${PeerOutbox.MAX_PER_PEER} — черга зʼїла б власний файл",
            chunksFor100 > PeerOutbox.MAX_PER_PEER,
        )
    }

    /**
     * ЧЕТВЕРТА МЕЖА: стеля того боку, а не цього.
     *
     * [PeerFile.head] і [PeerFile.decodeChunk] відкидають кадр, чий `total`
     * більший за `chunkCount(MAX_BYTES)` ВЛАСНОЇ збірки. Це правильно саме по
     * собі — сміттю не місце в сховищі, — але має ціну, яку треба тримати
     * названою: телефон зі старою стелею не збере файл, нарізаний новою.
     *
     * Тут ця ціна відтворюється буквально: кадр із `total` понад стелю не
     * читається ЖОДНИМ зі шляхів. Якщо колись зʼявиться рукостискання про
     * стелю різальника, цей тест доведеться переписати — і це буде добре, бо
     * означатиме, що межі більше немає.
     */
    @Test
    fun `кадр понад стелю не читається — ціна, яку платить старий бік`() {
        val tooMany = PeerFile.chunkCount(PeerFile.MAX_BYTES) + 1
        val full = ByteArray(PeerFile.CHUNK_BYTES) { (it and 0x7f).toByte() }

        assertNull(
            "кадр із total=$tooMany розібрався, хоча стеля ${PeerFile.chunkCount(PeerFile.MAX_BYTES)}",
            PeerFile.decodeChunk(PeerFile.encodeChunk(blobId, 0, tooMany, full)),
        )
        assertNull(
            "голова кадру понад стелю прочиталась",
            PeerFile.head(PeerFile.vaultedChunk(blobId, 0, tooMany)),
        )
    }

    @Test
    fun `посилання будується лише на справжніх числах`() {
        val ref = PeerFile.vaultedChunk(blobId, 170, 171)
        val head = PeerFile.head(ref)
        assertNotNull(head)
        assertEquals(170, head!!.index)
        assertEquals(171, head.total)
        assertEquals(blobId, head.blobId)
    }
}
