package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Найдорожча тиша, яку має цей продукт: у групі з шести один не сканував тебе,
 * його рядки не відкриваються саме в тебе — і зникають. Твоя копія розмови
 * розходиться з усіма іншими назавжди, а всі екрани лишаються правильними.
 */
class PeerStrangerTest {

    @Test
    fun `a drop leaves a mark instead of nothing`() {
        val out = PeerStranger.note(emptyList(), "ffff0000", inRoster = false, atMs = 100L)
        assertEquals(1, out.size)
        assertEquals("ffff0000", out.single().claimedId)
        assertEquals(1, out.single().count)
        assertFalse(out.single().inRoster)
    }

    @Test
    fun `the same sender repeating is a count, not a growing list`() {
        var seen = PeerStranger.note(emptyList(), "ffff0000", inRoster = false, atMs = 100L)
        seen = PeerStranger.note(seen, "ffff0000", inRoster = false, atMs = 200L)
        seen = PeerStranger.note(seen, "ffff0000", inRoster = false, atMs = 300L)
        assertEquals(1, seen.size)
        assertEquals(3, seen.single().count)
        assertEquals("перший раз має лишитись першим", 100L, seen.single().firstAtMs)
        assertEquals(300L, seen.single().lastAtMs)
    }

    @Test
    fun `a flood cannot grow without bound`() {
        // Будь-хто в тій самій Wi-Fi може слати кадри з будь-яким id у полі
        // відправника — це поле НЕ доведене. Список мусить бути стелею.
        var seen = emptyList<PeerStranger>()
        repeat(PeerStranger.MAX_KEPT * 3) { i ->
            seen = PeerStranger.note(seen, "id-$i", inRoster = false, atMs = i.toLong())
        }
        assertEquals(PeerStranger.MAX_KEPT, seen.size)
        assertEquals("найновіше мусить лишатись", "id-23", seen.first().claimedId)
    }

    @Test
    fun `a roster member whose frame will not open is not called a stranger`() {
        // Рядок, доданий до шифрування, або протухлий ключ — це не чужий, і
        // сказати про них «його немає у твоєму списку» було б неправдою.
        val out = PeerStranger.note(emptyList(), "a1b2", inRoster = true, atMs = 1L)
        assertTrue(out.single().inRoster)
    }

    @Test
    fun `an unnamed sender is not a row`() {
        assertEquals(emptyList<PeerStranger>(), PeerStranger.note(emptyList(), "  ", false, 1L))
    }

    @Test
    fun `adding the person answers their drops`() {
        var seen = PeerStranger.note(emptyList(), "ffff0000", inRoster = false, atMs = 100L)
        seen = PeerStranger.note(seen, "eeee1111", inRoster = false, atMs = 110L)
        seen = PeerStranger.forget(seen, "ffff0000")
        assertEquals(listOf("eeee1111"), seen.map { it.claimedId })
    }
}
