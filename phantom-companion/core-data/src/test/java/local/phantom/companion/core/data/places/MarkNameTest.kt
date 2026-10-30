package local.phantom.companion.core.data.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkNameTest {

    @Test
    fun `placeholder names are not names`() {
        assertFalse(MarkName.isNamed("Точка на мапі"))
        assertFalse(MarkName.isNamed("  точка на мапі  "))
        assertFalse(MarkName.isNamed("місце без назви"))
        assertFalse(MarkName.isNamed("Point on map"))
        assertFalse(MarkName.isNamed(""))
        assertFalse(MarkName.isNamed(null))
        assertFalse(MarkName.isNamed("Д"))
    }

    @Test
    fun `bare coordinates are not a name`() {
        assertFalse(MarkName.isNamed("50.45000, 30.52000"))
        assertFalse(MarkName.isNamed("-50,45; 30,52"))
        assertTrue(MarkName.isNamed("Схрон 50.45, 30.52"))
    }

    @Test
    fun `real names pass`() {
        assertTrue(MarkName.isNamed("Дім"))
        assertTrue(MarkName.isNamed("Схрон біля мосту"))
        assertTrue(MarkName.isNamed("Точка 7"))
    }

    @Test
    fun `candidate prefers poi name then address head`() {
        assertEquals("Сільпо", MarkName.candidate("Сільпо", "вул. Хрещатик, 1"))
        assertEquals("вул. Хрещатик", MarkName.candidate("Точка на мапі", "вул. Хрещатик, 1"))
        assertEquals("", MarkName.candidate(null, null))
    }

    @Test
    fun `collision suffixes instead of overwriting`() {
        assertEquals("Дім", MarkName.deduplicate("Дім", emptySet()))
        assertEquals("Дім (2)", MarkName.deduplicate("Дім", setOf("дім")))
        assertEquals("Дім (3)", MarkName.deduplicate("Дім", setOf("дім", "дім (2)")))
    }
}
