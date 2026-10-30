package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2 — плашка режиму: рівно одна на вхід, і зі стелею на повтор.
 *
 * Той самий бюджет уваги, що вже виміряний на чипі довіри: власник дивився на
 * два рядкові написи, поки телефон лежав, і сказав, що це «злить». Плашка, яка
 * клацає щоразу, коли класифікатор перечепився, злитиме так само.
 */
class SceneAnnounceTest {

    private val t0 = 500_000L

    @Test
    fun `перша побачена сцена не оголошується`() {
        val opened = SceneAnnounceState().observe(SceneMode.THREAT, t0)
        assertFalse("те, що мапу відкрили, не новина", opened.announcing(t0))
        assertEquals(SceneMode.THREAT, opened.mode)
    }

    @Test
    fun `вхід за кермо оголошується рівно один раз`() {
        var s = SceneAnnounceState().observe(SceneMode.EXPLORE, t0)
        s = s.observe(SceneMode.DRIVE, t0 + 1_000L)
        assertTrue(s.announcing(t0 + 1_000L))

        // Поки режим тримається, оголошувати нема чого — вікно просто спливає.
        val same = s.observe(SceneMode.DRIVE, t0 + 2_000L)
        assertEquals(s, same)
        assertFalse(s.announcing(t0 + 1_000L + ANNOUNCE_MS))
    }

    @Test
    fun `тихі режими не кажуть нічого`() {
        var s = SceneAnnounceState().observe(SceneMode.DRIVE, t0)
        for (quiet in listOf(SceneMode.CALM, SceneMode.EXPLORE, SceneMode.WALK, SceneMode.TRANSIT, SceneMode.GUIDE)) {
            s = s.observe(quiet, t0 + 60_000L)
            assertFalse("$quiet заговорив", s.announcing(t0 + 60_000L))
        }
    }

    /** Режим, що мигає на межі витримки, не має права клацати плашкою. */
    @Test
    fun `стеля переоголошення тримає режим, що смикається`() {
        var s = SceneAnnounceState().observe(SceneMode.EXPLORE, t0)
        s = s.observe(SceneMode.DRIVE, t0 + 1_000L)
        assertTrue(s.announcing(t0 + 1_000L))

        val flapAt = t0 + 1_000L + REANNOUNCE_FLOOR_MS / 2
        s = s.observe(SceneMode.EXPLORE, flapAt)
        s = s.observe(SceneMode.DRIVE, flapAt + 1)
        assertFalse("друге «за кермом» за пів хвилини", s.announcing(flapAt + 1))
    }

    @Test
    fun `після стелі той самий режим має право сказати про себе знову`() {
        var s = SceneAnnounceState().observe(SceneMode.EXPLORE, t0)
        s = s.observe(SceneMode.DRIVE, t0 + 1_000L)
        val later = t0 + 1_000L + REANNOUNCE_FLOOR_MS
        s = s.observe(SceneMode.EXPLORE, later - 1)
        s = s.observe(SceneMode.DRIVE, later)
        assertTrue(s.announcing(later))
    }

    /** Дві різні новини — дві плашки; стеля рахується ПО РЕЖИМУ, не глобально. */
    @Test
    fun `загроза не мовчить через недавнє «за кермом»`() {
        var s = SceneAnnounceState().observe(SceneMode.EXPLORE, t0)
        s = s.observe(SceneMode.DRIVE, t0 + 1_000L)
        s = s.observe(SceneMode.THREAT, t0 + 2_000L)
        assertTrue(s.announcing(t0 + 2_000L))
    }

    @Test
    fun `оголошуються тільки ті режими, що ЗАБИРАЮТЬ з екрана`() {
        assertEquals(setOf(SceneMode.DRIVE, SceneMode.THREAT), ANNOUNCED_MODES)
    }
}
