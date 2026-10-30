package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFloatingBarsTest {
    private val dir = "src/main/java/local/phantom/companion/feature/stream/ui/map"
    private fun src(name: String) = File("$dir/$name").readText()
    private fun section(file: String, from: String): String {
        val s = src(file)
        val a = s.indexOf(from)
        assertTrue("$from missing in $file", a >= 0)
        val b = s.indexOf("\n@Composable", a + from.length).let { if (it < 0) s.length else it }
        return s.substring(a, b)
    }
    private fun person(name: String) = MapTrayPerson(id = name, name = name, status = "", fresh = true)

    @Test
    fun `the invite names two people and counts the rest`() {
        assertNull(inviteNames(emptyList()))
        assertEquals("Олена, Марко", inviteNames(listOf(person("Олена"), person("Марко"))))
        assertEquals("Олена, Марко +2", inviteNames(listOf("Олена", "Марко", "Ія", "Ден").map(::person)))
    }

    @Test
    fun `map controls are rounded squares, not ovals`() {
        assertFalse(section("MapControls.kt", "internal fun MapControlOrb(").contains("CircleShape"))
        val locate = section("MapHomeControls.kt", "internal fun MapLocateFab(")
        assertFalse(locate.contains("CircleShape"))
        assertFalse("following must not paint a second amber", locate.contains("c.accent else c.raised"))
    }

    @Test
    fun `search and categories share one card with flat tabs`() {
        assertTrue(section("MapHomeControls.kt", "internal fun MapHomeTopBand(").contains("inCard = true"))
        val bar = section("MapHomeControls.kt", "internal fun MapHomeCategoryBar(")
        assertFalse(bar.contains("CircleShape"))
        assertFalse(bar.contains("c.accent"))
    }

    @Test
    fun `people sit in one invite strip with one amber primary`() {
        val peek = section("MapHomeControls.kt", "internal fun MapHomePeekBar(")
        assertTrue(peek.contains("MapInviteStrip("))
        assertFalse(peek.contains("R.string.map_tray_meet"))
        val strip = src("MapInviteStrip.kt")
        assertEquals(1, Regex("""c\.accent\b""").findAll(strip).count())
        assertTrue(strip.contains("R.string.map_invite_empty"))
        for (d in listOf("values", "values-uk", "values-cs")) {
            assertTrue(d, File("src/main/res/$d/strings_map_home.xml").readText().contains("name=\"map_invite_empty\""))
        }
    }
}
