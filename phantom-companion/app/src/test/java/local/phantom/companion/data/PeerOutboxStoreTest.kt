package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один зіпсований байт стирав УСЮ переписку: розбір падав, [PeerOutboxStore.load]
 * мовчки віддавав нуль листів, а найближчий запис клав «[]» поверх диска.
 */
class PeerOutboxStoreTest {

    private class FakeSlot(seed: String? = null) : PeerLogSlot {
        val cells = HashMap<String, String>()

        init {
            seed?.let { cells[PeerOutboxStore.KEY] = it }
        }

        override fun read(key: String): String? = cells[key]

        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private fun row(id: String, state: String) =
        """{"id":"$id","fromId":"я","toId":"вони","body":"текст $id",""" +
            """"sentAtMs":$id,"state":"$state"}"""

    @Test
    fun `a state this build never heard of costs one field, not the whole log`() {
        // Новіша збірка дописала стан, людина відкотилась.
        val slot = FakeSlot("[${row("1", "QUEUED")},${row("2", "ARCHIVED")}]")
        val store = PeerOutboxStore(slot)

        val loaded = store.load()

        assertEquals(listOf("1", "2"), loaded.map { it.id })
        assertEquals(PeerMessage.State.QUEUED, loaded.last().state)

        store.save(loaded)
        val onDisk = PeerOutboxStore(FakeSlot(slot.cells.getValue(PeerOutboxStore.KEY))).load()
        assertEquals(listOf("1", "2"), onDisk.map { it.id })
    }

    @Test
    fun `a torn tail costs the torn letter only`() {
        val whole = "[${row("1", "QUEUED")},${row("2", "SENT")},${row("3", "QUEUED")}]"
        val torn = whole.substring(0, whole.indexOf(row("3", "QUEUED")) + 20)
        val slot = FakeSlot(torn)

        val loaded = PeerOutboxStore(slot).load()

        assertEquals(listOf("1", "2"), loaded.map { it.id })
        assertEquals(PeerMessage.State.SENT, loaded.last().state)
        assertEquals(torn, slot.cells[PeerOutboxStore.DAMAGED_KEY])
    }

    @Test
    fun `emptiness never goes over a log that would not parse`() {
        // Керівні знаки — назвами: написані собою, вони роблять файл двійковим
        // для git, і різниця перестає читатись. Значення те саме.
        val garbage = "\u0000\u0001не json взагалі"
        val slot = FakeSlot(garbage)
        val store = PeerOutboxStore(slot)

        assertTrue(store.load().isEmpty())
        store.save(emptyList())

        assertEquals(garbage, slot.cells[PeerOutboxStore.KEY])
        assertEquals(garbage, slot.cells[PeerOutboxStore.DAMAGED_KEY])
    }

    @Test
    fun `a whole log still round-trips and may still be emptied on purpose`() {
        val slot = FakeSlot()
        val store = PeerOutboxStore(slot)
        val one = PeerMessage(id = "1", fromId = "я", toId = "вони", body = "текст", sentAtMs = 1L)

        store.save(listOf(one))
        assertEquals(listOf("1"), store.load().map { it.id })

        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
