package local.phantom.companion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleRulesTest {

    @Test
    fun `close and personal see location, others do not`() {
        assertTrue(CircleRules.locationDefault("personal"))
        assertTrue(CircleRules.locationDefault("close"))
        assertFalse(CircleRules.locationDefault("friends"))
        assertFalse(CircleRules.locationDefault("social"))
        assertFalse(CircleRules.locationDefault("work"))
        assertFalse(CircleRules.locationDefault("u:0123abcd"))

        assertTrue(CircleRules.canSeeLocation(setOf("work", "close")))
        assertTrue(CircleRules.canSeeLocation(setOf("personal")))
        assertFalse(CircleRules.canSeeLocation(setOf("friends", "social", "work", "u:0123abcd")))
        assertFalse(CircleRules.canSeeLocation(emptySet()))
    }

    @Test
    fun `social and work do not see moments, personal close friends and custom do`() {
        assertTrue(CircleRules.momentsDefault("personal"))
        assertTrue(CircleRules.momentsDefault("close"))
        assertTrue(CircleRules.momentsDefault("friends"))
        assertTrue(CircleRules.momentsDefault("u:0123abcd"))
        assertFalse(CircleRules.momentsDefault("social"))
        assertFalse(CircleRules.momentsDefault("work"))

        assertFalse(CircleRules.canSeeMoments(setOf("social", "work")))
        assertFalse(CircleRules.canSeeMoments(emptySet()))
        assertTrue(CircleRules.canSeeMoments(setOf("work", "friends")))
        assertTrue(CircleRules.canSeeMoments(setOf("work", "u:0123abcd")))
    }
}
