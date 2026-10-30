package local.phantom.companion.core.design.theme

import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomAccents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentChoiceTest {

    @Test
    fun `перший акцент - це сама палітра, невідоме імʼя падає на нього`() {
        assertEquals("amber", ACCENT_DEFAULT)
        assertEquals(NightColors, NightColors.withAccent("amber"))
        assertEquals(DayColors, DayColors.withAccent("no-such"))
        assertEquals(NightColors, NightColors.withAccent(null))
    }

    @Test
    fun `акцент міняє лише ролі акценту і бере тон своєї теми`() {
        val sage = PhantomAccents.first { it.id == "sage" }
        val night = NightColors.withAccent("sage")
        assertEquals(sage.night.accent, night.accent)
        assertEquals(sage.night.onAccent, night.onAccent)
        assertEquals(sage.day.accentText, DayColors.withAccent("sage").accentText)
        assertNotEquals(NightColors.accent, night.accent)
        assertEquals(NightColors.copy(
            accent = night.accent,
            accentHover = night.accentHover,
            accentPress = night.accentPress,
            accentText = night.accentText,
            accentLine = night.accentLine,
            onAccent = night.onAccent,
        ), night)
    }

    @Test
    fun `стара спека бере той самий акцент, що й токени`() {
        val c = DayColors.withAccent("sea")
        val spec = DayClear.withAccent(c)
        assertEquals(c.accent, spec.primary)
        assertEquals(c.accentText, spec.accentSharp)
        assertEquals(DayClear.surfaceBase, spec.surfaceBase)
    }

    @Test
    fun `шість спокійних акцентів, імена унікальні`() {
        assertEquals(listOf("amber", "sage", "sea", "iris", "rose", "graphite"), PhantomAccents.map { it.id })
    }
}
