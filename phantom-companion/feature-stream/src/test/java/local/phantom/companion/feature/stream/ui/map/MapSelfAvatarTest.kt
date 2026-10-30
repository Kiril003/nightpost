package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MapSelfAvatarTest {

    @Test
    fun `аватар пошуку бере ініціал з імені профілю, без імені нічого`() {
        assertEquals("I", selfInitialOf("  iva"))
        assertNull(selfInitialOf(null))
        assertNull(selfInitialOf("   "))
    }

    @Test
    fun `на мапі немає вшитих ініціалів`() {
        listOf("MapChrome.kt", "MapHomeControls.kt").forEach {
            val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$it").readText()
            assertFalse("$it містить вшите \"AN\"", Regex("\"AN\"").containsMatchIn(src))
        }
    }
}
