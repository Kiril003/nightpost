package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Файл у черзі — і те, чого він не сміє зробити з розмовою.
 *
 * Шматки їдуть тими самими рядками, що й слова, і це навмисно. Але звідси
 * випливають дві поразки, які тестами й закриті: сотні рядків base64 у самій
 * розмові замість фотографії, і одне фото, що виштовхує з розмови стільки ж
 * живих речень — тихо, бо [PeerOutbox.prune] нікому не звітує.
 */
class PeerFileOutboxTest {

    private val me = "aaaa1111"
    private val you = "bbbb2222"
    private val blob = PeerFile.newBlobId()

    private fun line(id: String, body: String, at: Long, state: PeerMessage.State = PeerMessage.State.DELIVERED) =
        PeerMessage(id = id, fromId = me, toId = you, body = body, sentAtMs = at, state = state)

    /**
     * [total] названо явно й завжди дорівнює справжній кількості шматків.
     * Кадр із номером поза межами [total] не читається зовсім, тож фікстура,
     * що бреше про це, тихо перевіряла б не те, що написано в назві тесту.
     */
    private fun chunk(
        i: Int,
        at: Long,
        total: Int,
        state: PeerMessage.State = PeerMessage.State.DELIVERED,
    ) = line("c-$i", PeerFile.encodeChunk(blob, i, total, ByteArray(16) { i.toByte() }), at, state)

    @Test
    fun `шматки не показуються як рядки розмови`() {
        val log = listOf(
            line("m-1", "привіт", 100L),
            chunk(0, 200L, total = 2),
            chunk(1, 201L, total = 2),
            line("m-2", "як ти", 300L),
        )
        val seen = PeerOutbox.conversation(log, you)
        assertEquals(listOf("m-1", "m-2"), seen.map { it.id })
    }

    @Test
    fun `останній рядок розмови — не шматок`() {
        val log = listOf(line("m-1", "привіт", 100L), chunk(0, 900L, total = 2))
        assertEquals("m-1", PeerOutbox.lastLine(log, you)?.id)
    }

    @Test
    fun `шматки не рахуються непрочитаними`() {
        // Інакше одне вхідне фото показало б «171» над розмовою.
        val log = (0 until 8).map {
            PeerMessage(
                id = "c-$it",
                fromId = you,
                toId = me,
                body = PeerFile.encodeChunk(blob, it, 8, ByteArray(4)),
                sentAtMs = 500L + it,
                state = PeerMessage.State.DELIVERED,
            )
        } + PeerMessage(
            id = "m-1",
            fromId = you,
            toId = me,
            body = "глянь",
            sentAtMs = 600L,
            state = PeerMessage.State.DELIVERED,
        )
        assertEquals(1, PeerOutbox.unread(log, you, readUpToMs = 0L))
    }

    @Test
    fun `фото не витирає розмову, коли черга переповнюється`() {
        // Файл ЩЕ В ДОРОЗІ: усі шматки, крім останнього, доїхали, а він чекає —
        // тож правило «доставлений файл забувається одразу» тут не спрацьовує,
        // і черга впирається у стелю по-справжньому. Забути треба шматки: вони
        // своє вже відвезли, а речення не відвезло нічого, воно і є розмова.
        //
        // Числа рахуються ВІД [PeerOutbox.MAX_PER_PEER], а не вписані: стеля
        // піднялась 16.09 разом із виносом байтів шматків із журналу, і тест,
        // прибитий до 500, перевіряв би переповнення, якого більше немає.
        // Складаємо рівно на 50 рядків більше за стелю.
        val wordCount = PeerOutbox.MAX_PER_PEER - 100
        val chunkCount = 150
        val words = (0 until wordCount).map { line("w-$it", "речення $it", 1_000L + it) }
        val chunks = (0 until chunkCount - 1).map { chunk(it, 500L + it, total = chunkCount) } +
            chunk(chunkCount - 1, 649L, total = chunkCount, state = PeerMessage.State.QUEUED)
        val pruned = PeerOutbox.prune(words + chunks, you)

        assertEquals(PeerOutbox.MAX_PER_PEER, pruned.size)
        val survivingWords = pruned.count { !PeerFile.isChunk(it.body) }
        assertEquals("жодне речення не сміло зникнути", wordCount, survivingWords)
        assertEquals(
            "мали лишитись лише найновіші шматки",
            100,
            pruned.count { PeerFile.isChunk(it.body) },
        )
        assertTrue(
            "недовезений шматок викинули",
            pruned.any { it.state == PeerMessage.State.QUEUED },
        )
    }

    @Test
    fun `недоставлений шматок не викидається ніколи`() {
        // Те, за що ще не відзвітували, [prune] не має права забути — інакше
        // файл «надіслано» без половини себе.
        val words = (0 until 495).map { line("w-$it", "речення $it", 1_000L + it) }
        val owed = (0 until 20).map { chunk(it, 500L + it, total = 20, state = PeerMessage.State.QUEUED) }
        val pruned = PeerOutbox.prune(words + owed, you)
        assertEquals(20, pruned.count { PeerFile.isChunk(it.body) })
    }

    /**
     * Найдорожча вада цієї хвилі, якби її не полагодити: журнал шифрується й
     * переписується ЦІЛКОМ на кожну зміну. Фото, що лишається в ньому назавжди,
     * робить кожен наступний рядок дорожчим — і через десяток світлин найдешевше
     * «привіт» коштує секунди.
     */
    @Test
    fun `доставлений файл не лишається в журналі назавжди`() {
        val log = listOf(line("m-1", "ось воно", 100L)) +
            (0 until 8).map { chunk(it, 200L + it, total = 8) }
        val pruned = PeerOutbox.prune(log, you)
        assertEquals("шматки доставленого файла мали зникнути", 1, pruned.size)
        assertEquals("m-1", pruned.first().id)
    }

    @Test
    fun `недовезений файл не чіпають узагалі`() {
        // Сім із восьми доставлених — це нуль доставлених файлів. Викинути сім
        // означало б зробити восьмий недосилаємим.
        val log = (0 until 7).map { chunk(it, 200L + it, total = 8) } +
            chunk(7, 207L, total = 8, state = PeerMessage.State.QUEUED)
        assertEquals(8, PeerOutbox.prune(log, you).size)
    }

    @Test
    fun `файли різних фото не плутаються між собою`() {
        val other = PeerFile.newBlobId()
        val done = (0 until 8).map { chunk(it, 200L + it, total = 8) }
        val going = (0 until 4).map {
            line("o-$it", PeerFile.encodeChunk(other, it, 4, ByteArray(8)), 300L + it, PeerMessage.State.QUEUED)
        }
        val pruned = PeerOutbox.prune(done + going, you)
        assertEquals("друге фото постраждало від першого", 4, pruned.size)
        assertTrue(pruned.all { PeerFile.head(it.body)?.blobId == other })
    }

    @Test
    fun `фото в дорозі — це одна річ, що чекає, а не сто сімдесят одна`() {
        val log = listOf(line("m-1", "привіт", 100L, PeerMessage.State.QUEUED)) +
            (0 until 8).map { chunk(it, 200L + it, total = 8, state = PeerMessage.State.QUEUED) }
        // Одне слово плюс одне фото. «9» тут було б не докладніше, а неправдиве.
        assertEquals(2, PeerOutbox.waiting(log))
    }

    private fun manifest(id: String, at: Long, state: PeerMessage.State = PeerMessage.State.QUEUED) =
        line(
            id,
            """{"name":"a.jpg","size":10,"mime":"image/jpeg","sha256":"ab",""" +
                """"blob_id":"$blob","key_hex":"aa","nonce_hex":"bb"}""",
            at,
            state,
        )

    /**
     * «Стерти, але дослати» тут не існує як варіант — так каже [PeerOutbox.delete],
     * і файл не має бути винятком. Шматків на екрані немає, тож обсяг стирання
     * легко склався б лише з видимого, і фотографія їхала б до людини, яка
     * щойно стерла розмову.
     */
    @Test
    fun `стерта розмова забирає й шматки, яких не було видно`() {
        val log = listOf(manifest("m-1", 100L)) +
            (0 until 8).map { chunk(it, 200L + it, total = 8, state = PeerMessage.State.QUEUED) }
        val scope = PeerPurge.conversation(log, you)
        assertEquals("людині показують одну річ, а не девʼять", 1, scope.count)
        assertEquals(9, scope.ids.size)
        assertTrue(PeerOutbox.delete(log, scope.ids).isEmpty())
    }

    @Test
    fun `стерта фотографія забирає свої шматки`() {
        val other = PeerFile.newBlobId()
        val mine = manifest("m-1", 100L)
        val log = listOf(mine, line("m-2", "слово", 150L)) +
            (0 until 8).map { chunk(it, 200L + it, total = 8, state = PeerMessage.State.QUEUED) } +
            (0 until 3).map {
                line("x-$it", PeerFile.encodeChunk(other, it, 3, ByteArray(4)), 400L + it, PeerMessage.State.QUEUED)
            }
        val scope = PeerPurge.line(log, mine)
        assertEquals(1, scope.count)
        val left = PeerOutbox.delete(log, scope.ids)
        assertEquals("забрало зайве", listOf("m-2", "x-0", "x-1", "x-2"), left.map { it.id })
    }

    @Test
    fun `заголовок шматка читається без розбору тіла`() {
        val body = PeerFile.encodeChunk(blob, 3, 8, ByteArray(64) { 7 })
        val head = PeerFile.head(body)!!
        assertEquals(blob, head.blobId)
        assertEquals(3, head.index)
        assertEquals(8, head.total)
        assertEquals(null, PeerFile.head("звичайне слово"))
        assertEquals(null, PeerFile.head("PH-FCH:../..:0:1:AA"))
    }

    @Test
    fun `шматок не буде дзвонити в шторку`() {
        // 171 сповіщення на одну фотографію — це вимкнений канал назавжди.
        assertEquals(
            null,
            PeerAnnounce.notice(chunk(0, 100L, total = 2), visibleChat = null, visibleGroup = null),
        )
        // А сам лист-опис — дзвонить, бо його людина й чекає.
        val manifest = line(
            "m-1",
            """{"name":"a.jpg","size":10,"mime":"image/jpeg","sha256":"ab",""" +
                """"blob_id":"$blob","key_hex":"aa","nonce_hex":"bb"}""",
            100L,
        )
        assertTrue(PeerAnnounce.notice(manifest, null, null) != null)
    }
}
