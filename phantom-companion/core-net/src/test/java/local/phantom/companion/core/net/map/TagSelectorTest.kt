package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSelectorTest {

    @Test
    fun `multi-key or splits into clauses Overpass can actually ask`() {
        assertEquals(
            listOf("shelter_type=bomb_shelter", "bunker_type=bomb_shelter", "civil_shelter=yes"),
            TagSelector.parts("shelter_type=bomb_shelter|bunker_type=bomb_shelter|civil_shelter=yes"),
        )
    }

    @Test
    fun `a pipe inside quotes is a value regex, not a second key`() {
        assertEquals(listOf("""amenity~"cafe|restaurant""""), TagSelector.parts("""amenity~"cafe|restaurant""""))
        assertEquals(
            listOf("""generator:source~"diesel|solar"""", "power=generator"),
            TagSelector.parts("""generator:source~"diesel|solar"|power=generator"""),
        )
    }

    @Test
    fun `the joined shelter selector is an or only once it is split`() {
        // Нерозбитим цей рядок не «мовчав»: Overpass відповідав HTTP 400
        // `parse error: ']' expected - '|' found`, а на склі це виглядало як
        // «Джерело мовчить» — невідрізненно від обриву мережі.
        val joined = "shelter_type=bomb_shelter|bunker_type=bomb_shelter|civil_shelter=yes"
        val naive = TagSelector.Clause("shelter_type", setOf(joined.substringAfter('=')), regex = false)
        assertFalse(naive.matches("bomb_shelter"))

        val clauses = TagSelector.clauses(joined)
        assertTrue(TagSelector.matches(clauses, mapOf("shelter_type" to "bomb_shelter")))
        assertTrue(TagSelector.matches(clauses, mapOf("bunker_type" to "bomb_shelter")))
        assertTrue(TagSelector.matches(clauses, mapOf("civil_shelter" to "yes")))
        assertFalse(TagSelector.matches(clauses, mapOf("amenity" to "shelter")))
    }

    @Test
    fun `an anchored regex selector still matches its own values`() {
        val clauses = TagSelector.clauses("""highway~"^(motorway|trunk)$"""")
        assertTrue(TagSelector.matches(clauses, mapOf("highway" to "motorway")))
        assertFalse(TagSelector.matches(clauses, mapOf("highway" to "residential")))
    }

    @Test
    fun `cat and sub stay a fallback for places stored without full tags`() {
        val clauses = TagSelector.clauses("amenity=pharmacy")
        assertTrue(TagSelector.matches(clauses, emptyMap(), cat = "amenity", sub = "pharmacy"))
        assertFalse(TagSelector.matches(clauses, emptyMap(), cat = "amenity", sub = "cafe"))
    }

    @Test
    fun `an unparseable selector matches nothing, never everything`() {
        assertTrue(TagSelector.clauses("").isEmpty())
        assertFalse(TagSelector.matches(TagSelector.clauses(""), mapOf("amenity" to "shelter")))
    }
}
