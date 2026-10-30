package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Блокування переживає перезапуск.
 *
 * Засув, що живе в памʼяті екрана, — це не блокування: людина закриває
 * застосунок, телефон засинає, процес убиває Android за памʼять — і листи
 * знову заходять, а сказати їй про це нікому. Тому «перезапуск» тут
 * справжній: НОВИЙ [PeerBlockStore] над тими самими байтами, рівно так, як
 * його будує холодний старт.
 */
class PeerBlockStoreTest {

    /** Диск. Те, що в [cells], — це те, що лежить у зашифрованому файлі. */
    private class FakeSlot(seed: String? = null) : PeerLogSlot {
        val cells = HashMap<String, String>()
        var writes = 0

        init {
            seed?.let { cells[PeerBlockStore.KEY] = it }
        }

        override fun read(key: String): String? = cells[key]

        override fun write(key: String, value: String?) {
            writes++
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    /** Холодний старт: нове сховище, той самий диск. */
    private fun restart(slot: FakeSlot) = PeerBlockStore(slot)

    @Test
    fun `заблокований лишається заблокованим після перезапуску`() {
        val slot = FakeSlot()
        val before = PeerBlockStore(slot)
        before.block("abc123")
        assertTrue(before.blocks("abc123"))

        val after = restart(slot)

        assertTrue("засув не дожив до холодного старту", after.blocks("abc123"))
        assertEquals(setOf("abc123"), after.blocked.value)
    }

    @Test
    fun `розблокування теж переживає перезапуск`() {
        // Інакше «розблокував» було б виставою в інший бік: людина зняла
        // засув, застосунок перезапустився — і людина знову не отримує листів.
        val slot = FakeSlot()
        PeerBlockStore(slot).apply {
            block("abc123")
            unblock("abc123")
        }

        val after = restart(slot)

        assertFalse(after.blocks("abc123"))
        assertEquals(emptySet<String>(), after.blocked.value)
    }

    @Test
    fun `свіже сховище нікого не блокує`() {
        val store = PeerBlockStore(FakeSlot())
        assertEquals(emptySet<String>(), store.blocked.value)
        assertFalse(store.blocks("abc123"))
    }

    @Test
    fun `двічі заблокований не пише диск двічі`() {
        val slot = FakeSlot()
        val store = PeerBlockStore(slot)
        store.block("abc123")
        val once = slot.writes
        store.block("abc123")
        assertEquals("той самий засув писався вдруге", once, slot.writes)
    }

    @Test
    fun `кілька заблокованих доживають усі`() {
        val slot = FakeSlot()
        val store = PeerBlockStore(slot)
        listOf("a1", "b2", "c3").forEach(store::block)
        store.unblock("b2")

        val after = restart(slot)

        assertEquals(setOf("a1", "c3"), after.blocked.value)
        assertTrue(after.blocks("a1"))
        assertFalse(after.blocks("b2"))
        assertTrue(after.blocks("c3"))
    }

    @Test
    fun `зіпсований рядок не стає порожнім списком поверх диска`() {
        // Найтихіший спосіб зняти всі засуви: розбір упав, у памʼяті нуль, і
        // перший же запис кладе «[]» поверх того, що ще лежить на диску.
        val slot = FakeSlot("[\"a1\",\"b2")
        val store = PeerBlockStore(slot)

        store.unblock("нікого")

        assertEquals(
            "нерозібране перекрито порожнечею",
            "[\"a1\",\"b2",
            slot.cells[PeerBlockStore.KEY],
        )
        assertEquals("[\"a1\",\"b2", slot.cells[PeerBlockStore.DAMAGED_KEY])

        // Свідома дія людини знімає заборону — і диск знову правдивий.
        store.block("c3")
        assertEquals(setOf("c3"), restart(slot).blocked.value)
    }

    @Test
    fun `сховище не має другої правди про те, хто заблокований`() {
        // Рішення живе в [PeerBlock] і більше ніде: друга реалізація «чи
        // заблокований» — це саме те, що розходиться мовчки.
        val slot = FakeSlot()
        val store = PeerBlockStore(slot)
        store.block("abc123")
        assertEquals(
            PeerBlock.blocks(store.blocked.value, "abc123"),
            store.blocks("abc123"),
        )
        assertEquals(
            PeerBlock.blocks(store.blocked.value, "інший"),
            store.blocks("інший"),
        )
    }
}
