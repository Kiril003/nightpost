package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стирання доводиться лише прочитаним НАЗАД сховищем: «функцію викликано» тут
 * нічого не варте, бо помиляється саме вибір рядків, а не виклик.
 */
class PeerPurgeTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val taras = "cccc000000000003"

    private fun group(id: String = "abc123abc123", name: String = "Виїзд") =
        PeerGroup.Group(id = id, name = name, memberIds = listOf(me, olia, taras))

    private fun msg(
        toId: String,
        body: String,
        sentAtMs: Long,
        fromId: String = me,
        state: PeerMessage.State = PeerMessage.State.DELIVERED,
    ) = PeerMessage(
        id = "$fromId-$toId-$sentAtMs-${body.hashCode()}",
        fromId = fromId,
        toId = toId,
        body = body,
        sentAtMs = sentAtMs,
        state = state,
    )

    private fun log() = listOf(
        msg(olia, "привіт", 1_000L),
        msg(me, "привіт-привіт", 2_000L, fromId = olia),
        msg(taras, "де ти", 3_000L),
        msg(olia, PeerGroup.encode(group(), "виходимо"), 4_000L),
        msg(taras, PeerGroup.encode(group(), "виходимо"), 4_000L),
    )

    @Test
    fun `розмова на двох лічиться до дотику, з межами`() {
        val scope = PeerPurge.conversation(log(), olia)
        assertEquals(2, scope.count)
        assertEquals(1_000L, scope.fromMs)
        assertEquals(2_000L, scope.toMs)
    }

    /** Групова копія лежить у тому ж сховищі, але це не розмова цієї людини. */
    @Test
    fun `стерти розмову на двох не чіпає групових рядків`() {
        val before = log()
        val after = PeerOutbox.delete(before, PeerPurge.conversation(before, olia).ids)
        assertTrue(PeerOutbox.conversation(after, olia).isEmpty())
        assertEquals(2, after.count { PeerGroup.isGroup(it.body) })
        assertEquals(1, PeerGroup.known(after).size)
        // Розмова з Тарасом — чужа справа.
        assertEquals(1, PeerOutbox.conversation(after, taras).size)
    }

    @Test
    fun `група лічить сказане, а не копії`() {
        val scope = PeerPurge.group(log(), "abc123abc123")
        assertEquals(1, scope.count)
        assertEquals(2, scope.ids.size)
    }

    @Test
    fun `стерти історію групи не чіпає ні іншої групи, ні розмов на двох`() {
        val other = PeerGroup.Group("999999999999", "Дім", listOf(me, olia))
        val before = log() + msg(olia, PeerGroup.encode(other, "вдома"), 5_000L)
        val after = PeerOutbox.delete(before, PeerPurge.group(before, "abc123abc123").ids)
        assertEquals(listOf("999999999999"), PeerGroup.known(after).map { it.id })
        assertEquals(2, PeerOutbox.conversation(after, olia).size)
    }

    /** Група — це її листи, і зникнення групи зі списку треба сказати наперед. */
    @Test
    fun `останній стертий лист забирає й саму групу`() {
        val before = log()
        val scope = PeerPurge.group(before, "abc123abc123")
        assertTrue(PeerPurge.removesGroup(before, scope, "abc123abc123"))

        val oneLine = PeerPurge.line(before, before.first { PeerGroup.isGroup(it.body) })
        assertTrue(PeerPurge.removesGroup(before, oneLine, "abc123abc123"))

        val twoLines = before + msg(olia, PeerGroup.encode(group(), "уже тут"), 6_000L)
        assertFalse(
            PeerPurge.removesGroup(twoLines, PeerPurge.line(twoLines, twoLines[3]), "abc123abc123"),
        )
    }

    /** Одна репліка в групі — це весь її віяр, а не та копія, по якій тиснули. */
    @Test
    fun `стерти репліку групи забирає всі її копії`() {
        val before = log()
        val scope = PeerPurge.line(before, before[3])
        assertEquals(1, scope.count)
        val after = PeerOutbox.delete(before, scope.ids)
        assertTrue(after.none { PeerGroup.isGroup(it.body) })
        assertEquals(3, after.size)
    }

    @Test
    fun `стерти один лист на двох забирає рівно його`() {
        val before = log()
        val after = PeerOutbox.delete(before, PeerPurge.line(before, before[0]).ids)
        assertEquals(before.size - 1, after.size)
        assertTrue(after.none { it.id == before[0].id })
    }

    /**
     * Журнал і черга — один список, тож стертий рядок не має ані зʼявитись на
     * екрані, ані піти по мережі наступним тиком насоса.
     */
    @Test
    fun `стертий лист більше не проситься в мережу`() {
        val queued = msg(olia, "ще не пішло", 7_000L, state = PeerMessage.State.QUEUED)
        val before = log() + queued
        assertTrue(PeerOutbox.due(before, 9_000L).any { it.id == queued.id })
        val after = PeerOutbox.delete(before, setOf(queued.id))
        assertTrue(PeerOutbox.due(after, 9_000L).none { it.id == queued.id })
        assertTrue(after.none { it.id == queued.id })
    }

    @Test
    fun `порожній обсяг нічого не рухає`() {
        val before = log()
        assertTrue(PeerPurge.conversation(before, "нікого").isEmpty)
        assertEquals(before, PeerOutbox.delete(before, PeerPurge.NOTHING.ids))
    }

    /** Прибрати людину — забрати її листи; спільні групи це переживають. */
    @Test
    fun `прибрана людина лишає свої рядки в спільній групі`() {
        val before = log()
        assertTrue(PeerPurge.sharesGroup(before, olia))
        val after = PeerOutbox.delete(before, PeerPurge.conversation(before, olia).ids)
        assertTrue(PeerOutbox.conversation(after, olia).isEmpty())
        assertTrue(PeerGroup.known(after).single().memberIds.contains(olia))
    }

    @Test
    fun `вихід і видалення групи зберігає невідправлені кадри виходу в черзі, але прибирає групу зі списку`() {
        val g = group()
        val leaveGroup = PeerGroup.apply(g, me, PeerGroup.Act.Leave, g.name)
        val leaveFrame = msg(
            toId = olia,
            body = PeerGroup.encode(leaveGroup, PeerGroup.encodeAct(PeerGroup.Act.Leave)),
            sentAtMs = 8_000L,
            state = PeerMessage.State.QUEUED,
        )
        val before = log() + leaveFrame
        val scope = PeerPurge.group(before, g.id)
        val after = PeerOutbox.delete(before, scope.ids)
        assertTrue("кадр виходу мав залишитись у черзі для доставки", after.any { it.id == leaveFrame.id })
        assertTrue("група після видалення не має залишатись у списку відомих", PeerGroup.known(after).none { it.id == g.id })
    }

    @Test
    fun `стерти групу забирає й шматки її вкладень`() {
        val g = group()
        val blobId = "ab".repeat(PeerFile.BLOB_ID_CHARS / 2)
        val att = """{"name":"a.jpg","size":4,"mime":"image/jpeg","sha256":"${"cd".repeat(32)}","blob_id":"$blobId","key_hex":"${"11".repeat(32)}"}"""
        val chunk = msg(olia, PeerFile.encodeChunk(blobId, 0, 1, byteArrayOf(1, 2, 3, 4)), 5_001L)
        val before = log() + msg(olia, PeerGroup.encode(g, att), 5_000L) + chunk
        val scope = PeerPurge.group(before, g.id)
        assertTrue(chunk.id in scope.ids)
        assertEquals(2, scope.count)
    }
}
