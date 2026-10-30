package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Test

/** A computer paired under another language keeps the default name in that language; the row reads it in today's. */
class PairedPcNameTest {

    private val defaults = setOf("My computer", "Мій компʼютер", "Můj počítač")

    @Test
    fun `a default name follows the app language`() {
        for (old in defaults) assertEquals(old, "Мій компʼютер", shownDeviceName(old, defaults, "Мій компʼютер"))
        assertEquals("Můj počítač", shownDeviceName(" My computer ", defaults, "Můj počítač"))
    }

    @Test
    fun `a name the owner set stays as it is`() {
        assertEquals("Робочий ноут", shownDeviceName("Робочий ноут", defaults, "Мій компʼютер"))
        assertEquals("My computer 2", shownDeviceName("My computer 2", defaults, "Мій компʼютер"))
    }
}
