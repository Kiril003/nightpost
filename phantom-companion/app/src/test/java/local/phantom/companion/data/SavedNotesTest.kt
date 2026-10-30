package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedNotesTest {

    private class Slot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private fun open(slot: Slot) = SavedNotes(PeerOutboxStore(slot, bounded = false))

    private fun note(id: String, at: Long, body: String = "нотатка $id") =
        PeerMessage(id = id, fromId = "я", toId = SavedNotes.ID, body = body, sentAtMs = at)

    @Test
    fun `нотатки переживають перезапуск у тому самому порядку`() {
        val slot = Slot()
        open(slot).apply {
            add(note("a", 10))
            add(note("b", 20, "друга"))
            add(note("c", 30))
        }

        val reopened = open(slot).all()

        assertEquals(listOf("a", "b", "c"), reopened.map { it.id })
        assertEquals("друга", reopened[1].body)
        assertTrue(reopened.all { it.state == PeerMessage.State.DELIVERED && it.toId == SavedNotes.ID })
    }

    @Test
    fun `новий лист не перезаписує старих`() {
        val slot = Slot()
        val notes = open(slot)
        notes.add(note("a", 10, "перша"))
        notes.add(note("b", 20, "друга"))
        val before = open(slot).all()

        notes.add(note("c", 5, "третя, годинник відстав"))

        val after = open(slot).all()
        assertEquals(before, after.take(2))
        assertEquals(listOf("a", "b", "c"), after.map { it.id })
    }

    @Test
    fun `той самий id удруге не подвоює і не підміняє`() {
        val notes = open(Slot())
        notes.add(note("a", 10, "оригінал"))

        val again = notes.add(note("a", 99, "підміна"))

        assertEquals("оригінал", again.body)
        assertEquals(1, notes.all().size)
    }

    @Test
    fun `межа журналу сітки нотаток не обрізає`() {
        val slot = Slot()
        val many = (1..PeerOutbox.MAX_PER_PEER + 50).map { note("n$it", it.toLong()) }
        open(slot).adopt(many)

        assertEquals(many.size, open(slot).all().size)
    }

    @Test
    fun `перенесене стає за часом, наявні між собою не рухаються`() {
        val notes = open(Slot())
        notes.add(note("b", 20))
        notes.add(note("d", 40))

        val fresh = notes.adopt(listOf(note("c", 30), note("a", 10), note("b", 999, "підміна")))

        assertEquals(listOf("c", "a"), fresh.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), notes.all().map { it.id })
        assertEquals("нотатка b", notes.find("b")?.body)
    }
}
