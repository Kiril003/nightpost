package local.phantom.companion.data.msgstore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.data.PeerOutboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переїзд журналу власника з JSON у базу: жоден рядок не губиться, стара копія
 * живе, доки база не відчинилась удруге, а збій посередині не лишає півпереїзду.
 */
class MsgMigrationTest {

    private val rich = listOf(
        line(1, body = "привіт \"у лапках\" і \\ скісна"),
        line(2, from = "oksana", to = "me", body = "емодзі 🙂 і\nновий рядок"),
        line(3, state = PeerMessage.State.HELD).copy(heldNonce = "n-1", heldUntilMs = 99L, sealing = PeerMessage.Sealing.PAIR_KEY),
        line(4, state = PeerMessage.State.FAILED).copy(attempts = 8, nextAttemptMs = 5L),
        line(5).copy(replyToId = "m-1", signature = "sig", editedAtMs = 7L),
        line(6).copy(body = "", deletedAtMs = 8L),
        line(7, to = "taras", state = PeerMessage.State.QUEUED),
    )

    @Test
    fun `the real JSON shape moves across row for row with every field`() {
        Phone().use { phone ->
            phone.slot.cells[PeerOutboxStore.KEY] = legacyJson(rich)
            assertEquals(rich, phone.boot().load())
            assertEquals(rich, phone.boot().load())
        }
    }

    @Test
    fun `a torn tail moves exactly what the old salvage recovered and keeps the damaged copy`() {
        val full = legacyJson(rich)
        val torn = full.substring(0, full.indexOf("\"m-6\"") + 12)
        Phone().use { phone ->
            phone.slot.cells[PeerOutboxStore.KEY] = torn
            val salvaged = PeerOutboxStore(MemSlot().also { it.cells[PeerOutboxStore.KEY] = torn }).load()
            assertEquals(rich.take(5), salvaged)

            assertEquals(salvaged, phone.boot().load())
            assertEquals(torn, phone.slot.cells[PeerOutboxStore.DAMAGED_KEY])
            phone.boot().load()
            phone.boot().load()
            assertEquals("битий оригінал не стирається ніколи", torn, phone.slot.cells[PeerOutboxStore.DAMAGED_KEY])
        }
    }

    @Test
    fun `the old copy survives the first open and goes only after a second one sees the rows`() {
        Phone().use { phone ->
            val raw = legacyJson(rich)
            phone.slot.cells[PeerOutboxStore.KEY] = raw

            phone.boot().load()
            assertEquals(raw, phone.slot.cells[PeerOutboxStore.KEY])

            assertEquals(rich, phone.boot().load())
            assertNull(phone.slot.cells[PeerOutboxStore.KEY])

            assertEquals(rich, phone.boot().load())
        }
    }

    @Test
    fun `a crash halfway through the copy leaves nothing half-done and the next boot finishes it`() {
        Phone().use { phone ->
            val rows = (1..300).map { line(it) }
            val raw = legacyJson(rows)
            phone.slot.cells[PeerOutboxStore.KEY] = raw
            val crashing = PeerOutboxStore(phone.slot, openBase = {
                MsgBase(CrashAfter(JdbcMsgSql(phone.dbFile), rowsBeforeCrash = 150))
            })

            assertEquals("впав переїзд — телефон живе на старих дверях", rows, crashing.load())
            assertEquals(raw, phone.slot.cells[PeerOutboxStore.KEY])
            assertEquals(0L, MsgBase(JdbcMsgSql(phone.dbFile)).lines.count())

            assertEquals(rows, phone.boot().load())
            assertEquals(rows, phone.boot().load())
            assertEquals(300L, phone.lastSql.let(::MsgBase).lines.count())
        }
    }

    @Test
    fun `running the copy twice does not double a single row`() {
        Phone().use { phone ->
            val raw = legacyJson(rich)
            val base = MsgBase(JdbcMsgSql(phone.dbFile))
            val parse = { s: String -> PeerOutboxStore(MemSlot().also { it.cells[PeerOutboxStore.KEY] = s }).load() }
            MsgMigration.run(base, raw, parse) {}
            base.setMeta(MsgMigration.SEEN, "інший")
            MsgMigration.run(base, raw, parse) {}
            assertEquals(rich.size.toLong(), base.lines.count())
        }
    }

    @Test
    fun `letters an older build wrote after a rollback are picked up, not ignored`() {
        Phone().use { phone ->
            phone.slot.cells[PeerOutboxStore.KEY] = legacyJson(rich)
            phone.boot().load()
            phone.boot().load()
            assertNull(phone.slot.cells[PeerOutboxStore.KEY])

            val fromOldBuild = line(50, from = "oksana", to = "me", body = "написано старою збіркою")
            phone.slot.cells[PeerOutboxStore.KEY] = legacyJson(rich + fromOldBuild)

            assertEquals(rich + fromOldBuild, phone.boot().load())
        }
    }

    @Test
    fun `when the base cannot open the words come from the old copy and it is left alone`() {
        Phone().use { phone ->
            val raw = legacyJson(rich)
            phone.slot.cells[PeerOutboxStore.KEY] = raw
            phone.baseWorks = false

            val store = phone.boot()
            assertEquals(rich, store.load())
            assertNull(store.history)
            assertEquals(raw, phone.slot.cells[PeerOutboxStore.KEY])

            phone.baseWorks = true
            val later = line(60, body = "поки бази не було")
            store.save(rich + later)
            assertEquals(rich + later, phone.boot().load())
        }
    }

    @Test
    fun `a location frame never reaches the base even if an old log held one`() {
        val where = line(70, body = PeerWhere.TAG + "{\"lat\":50.45}")
        Phone().use { phone ->
            phone.slot.cells[PeerOutboxStore.KEY] =
                legacyJson(rich).removeSuffix("]") + "," + Json.encodeToString(where) + "]"
            val loaded = phone.boot().load()
            assertFalse(loaded.any { PeerWhere.isWhere(it.body) })
            assertEquals(rich, loaded)
        }
    }

    @Test
    fun `a state this build does not know reads as the default instead of losing the row`() {
        Phone().use { phone ->
            phone.slot.cells[PeerOutboxStore.KEY] =
                legacyJson(listOf(line(1))).replace("\"state\":\"DELIVERED\"", "\"state\":\"ARCHIVED\"")
                    .let { if ("ARCHIVED" in it) it else it.replace("\"id\":\"m-1\"", "\"id\":\"m-1\",\"state\":\"ARCHIVED\"") }
            val loaded = phone.boot().load().single()
            assertEquals("m-1", loaded.id)
            assertNotNull(loaded.state)
            assertTrue(phone.boot().load().isNotEmpty())
        }
    }
}

/** База, що падає посеред пакета, — як процес, убитий під час переїзду. */
private class CrashAfter(private val inner: JdbcMsgSql, private val rowsBeforeCrash: Int) : MsgSql by inner {
    override fun batch(sql: String, rows: List<Array<Any?>>) {
        if (rows.size > rowsBeforeCrash) {
            inner.batch(sql, rows.take(rowsBeforeCrash))
            error("процес убито")
        }
        inner.batch(sql, rows)
    }
}
