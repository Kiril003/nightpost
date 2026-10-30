package local.phantom.companion.data.msgstore

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.data.PeerOutboxHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запис різницею: одна зміна — один рядок на диску, а не весь журнал. І межа
 * 2000 більше не стирає початок довгої дружби.
 */
class MsgDiffWriteTest {

    @Test
    fun `2500 lines with one person survive a restart`() {
        Phone().use { phone ->
            val rows = (1..2500).map { line(it, from = if (it % 2 == 0) "oksana" else "me", to = if (it % 2 == 0) "me" else "oksana") }
            phone.boot().apply { load() }.save(rows)

            val after = phone.boot().load()
            assertEquals(2500, after.size)
            assertEquals("рядок 1", after.first().body)
            assertEquals(rows, after)
        }
    }

    @Test
    fun `one change writes one row, not the log`() {
        Phone().use { phone ->
            val rows = (1..1000).map { line(it, state = PeerMessage.State.SENT) }
            val store = phone.boot()
            store.load()
            store.save(rows)
            val sql = phone.lastSql
            val before = sql.rowWrites.get()

            store.save(PeerOutbox.markDelivered(rows, "m-500"))
            assertEquals(1, sql.rowWrites.get() - before)

            val later = phone.boot().load()
            assertEquals(PeerMessage.State.DELIVERED, later.single { it.id == "m-500" }.state)
            assertEquals(PeerMessage.State.SENT, later.single { it.id == "m-499" }.state)
        }
    }

    @Test
    fun `a delete leaves the base, the index and the summary together`() {
        Phone().use { phone ->
            val rows = listOf(line(1, body = "привіт Оксано"), line(2, body = "зустрінемось о шостій"), line(3, to = "taras", body = "інша розмова"))
            val store = phone.boot()
            store.load()
            store.save(rows)
            val base = store.history!!

            assertEquals(listOf("m-1"), base.fts.search("приві").map { it.id })
            assertEquals(listOf("m-2"), base.fts.search("шост").map { it.id })
            val conv = convOf(rows[0])
            assertEquals(2L, base.summaries.of(conv)!!.lines)
            assertEquals("m-2", base.summaries.of(conv)!!.lastId)

            store.save(PeerOutbox.delete(rows, setOf("m-2")))

            assertTrue(base.fts.search("шост").isEmpty())
            assertEquals(1L, base.summaries.of(conv)!!.lines)
            assertEquals("m-1", base.summaries.of(conv)!!.lastId)
            assertEquals(listOf("m-1", "m-3"), phone.boot().load().map { it.id })
        }
    }

    @Test
    fun `an edit is found by its new words and not by the old ones`() {
        Phone().use { phone ->
            val rows = listOf(line(1, body = "купи хліб"))
            val store = phone.boot()
            store.load()
            store.save(rows)
            store.save(listOf(rows[0].copy(body = "купи молоко", editedAtMs = 5L)))

            val base = store.history!!
            assertTrue(base.fts.search("хліб").isEmpty())
            assertEquals("m-1", base.fts.search("молоко").single().id)
            assertEquals("купи молоко", base.summaries.of(convOf(rows[0]))!!.snippet)
        }
    }

    @Test
    fun `a conversation reads in pages from the newest and counts unread from the base`() {
        Phone().use { phone ->
            val rows = (1..300).map { line(it, from = "oksana", to = "me") } + line(1000, to = "taras")
            val store = phone.boot()
            store.load()
            store.save(rows)
            val base = store.history!!
            val conv = convOf(rows[0])

            val first = base.lines.page(conv, limit = 50)
            assertEquals((300 downTo 251).map { "m-$it" }, first.map { it.id })
            val second = base.lines.page(conv, beforeMs = first.last().sentAtMs, limit = 50)
            assertEquals((250 downTo 201).map { "m-$it" }, second.map { it.id })
            assertEquals(10L, base.lines.unread(conv, "oksana", rows[289].sentAtMs))
            assertEquals(2, base.summaries.all().size)
        }
    }

    @Test
    fun `through the holder the disk ends up exactly where the screen is`() {
        Phone().use { phone ->
            val store = phone.boot()
            val holder = PeerOutboxHolder(store.load(), save = store::save)
            (1..200).forEach { i -> holder.publish { PeerOutbox.enqueue(it, line(i, state = PeerMessage.State.QUEUED)) } }
            holder.publish { PeerOutbox.markSent(it, "m-7") }
            holder.publish { PeerOutbox.delete(it, setOf("m-9")) }
            assertTrue(holder.flush())

            assertEquals(holder.messages.value, phone.boot().load())
        }
    }
}
