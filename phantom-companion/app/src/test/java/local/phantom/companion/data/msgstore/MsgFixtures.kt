package local.phantom.companion.data.msgstore

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerOutboxStore

internal class MemSlot : PeerLogSlot {
    val cells = HashMap<String, String>()
    override fun read(key: String): String? = cells[key]
    override fun write(key: String, value: String?) {
        if (value == null) cells.remove(key) else cells[key] = value
    }
}

/** Телефон між запусками: той самий файл бази й той самий слот, щоразу нові зʼєднання. */
internal class Phone(val slot: MemSlot = MemSlot()) : AutoCloseable {
    val dir: File = Files.createTempDirectory("msgstore").toFile()
    val dbFile = File(dir, MsgBase.FILE_NAME)
    private val open = ArrayList<JdbcMsgSql>()
    var baseWorks = true

    fun boot(): PeerOutboxStore = PeerOutboxStore(slot, openBase = {
        if (!baseWorks) error("ключ недоступний")
        MsgBase(JdbcMsgSql(dbFile).also { open += it })
    })

    val lastSql: JdbcMsgSql get() = open.last()

    override fun close() {
        open.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }
}

internal fun line(
    i: Int,
    from: String = "me",
    to: String = "oksana",
    body: String = "рядок $i",
    state: PeerMessage.State = PeerMessage.State.DELIVERED,
) = PeerMessage(id = "m-$i", fromId = from, toId = to, body = body, sentAtMs = 1_700_000_000_000L + i * 1000L, state = state)

/** Та сама форма JSON, що лежить на телефонах: її пише справжній старий запис. */
internal fun legacyJson(rows: List<PeerMessage>): String {
    val slot = MemSlot()
    PeerOutboxStore(slot, bounded = false).save(rows)
    return slot.cells.getValue(PeerOutboxStore.KEY)
}
