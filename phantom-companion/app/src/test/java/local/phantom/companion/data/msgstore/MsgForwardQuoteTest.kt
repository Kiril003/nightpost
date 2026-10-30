package local.phantom.companion.data.msgstore

import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P03: автор пересланого і обраний шматок цитати переживають перезапуск; база v1 переїжджає без втрат. */
class MsgForwardQuoteTest {

    private val rows = listOf(
        line(1, from = "oksana", to = "me").copy(fw = PeerMessage.Forward("Оксана", "peer_oksana")),
        line(2).copy(replyToId = "m-1", qt = "квитки в мене"),
        line(3),
    )

    @Test
    fun `forward author and quoted part come back after a restart`() {
        Phone().use { phone ->
            phone.boot().apply { load() }.save(rows)
            val after = phone.boot().load()
            assertEquals(rows, after)
            assertEquals("Оксана", after[0].fw?.name)
            assertEquals("квитки в мене", after[1].qt)
            assertNull(after[2].fw)
        }
    }

    @Test
    fun `search finds a forwarded line with its author and quote`() {
        Phone().use { phone ->
            val found = listOf(
                line(1, from = "oksana", to = "me", body = "квитки на суботу").copy(fw = PeerMessage.Forward("Оксана", "peer_oksana")),
                line(2, body = "беру квитки").copy(replyToId = "m-1", qt = "квитки на суботу"),
            )
            val store = phone.boot()
            store.load()
            store.save(found)
            val hits = store.history!!.fts.search("квитк").associateBy { it.id }
            assertEquals("Оксана", hits["m-1"]?.fw?.name)
            assertEquals("квитки на суботу", hits["m-2"]?.qt)
        }
    }

    @Test
    fun `a v1 base without the new columns opens and keeps every row`() {
        Phone().use { phone ->
            val plain = rows.map { it.copy(fw = null, qt = "") }
            phone.boot().apply { load() }.save(plain)
            JdbcMsgSql(phone.dbFile).use { sql ->
                listOf("fw_name", "fw_id", "qt").forEach { sql.exec("ALTER TABLE line DROP COLUMN $it") }
                sql.exec("UPDATE meta SET v = '1' WHERE k = '${MsgBase.VERSION_KEY}'")
            }

            val store = phone.boot()
            assertEquals(plain, store.load())
            store.save(rows)
            assertEquals(rows, phone.boot().load())
        }
    }
}
