package local.phantom.companion.core.voice.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePersonaTest {

    @Test
    fun englishPersona_noUkrainianOnlyRule() {
        val text = LivePersona.text("", "en")
        assertTrue(text.contains("English"))
        assertFalse(text.lowercase().contains("україн"))
        assertFalse(text.lowercase().contains("лише"))
        assertTrue(text.contains("awaiting_confirmation"))
        assertTrue(text.contains("memory_recall"))
        assertTrue(text.contains("map_lookup"))
        assertTrue(text.contains("messages_read"))
    }

    @Test
    fun czechPersona_saysCzech() {
        val text = LivePersona.text("", "cs")
        val lower = text.lowercase()
        assertTrue(lower.contains("česky") || lower.contains("čeština"))
        assertFalse(lower.contains("україн"))
        assertTrue(text.contains("awaiting_confirmation"))
        assertTrue(text.contains("memory_recall"))
    }

    @Test
    fun ukrainianPersona_unchangedRules() {
        val text = LivePersona.text("", "uk")
        assertTrue(text.contains("українською") || text.contains("українська"))
        assertFalse(text.contains("лише українська"))
        assertTrue(text.contains("awaiting_confirmation"))
        assertTrue(text.contains("memory_recall"))
        assertTrue(text.contains("map_lookup"))
        assertTrue(text.contains("messages_read"))
    }

    @Test
    fun unknownLanguage_fallsBackToEnglish() {
        assertEquals(LivePersona.text("", "en"), LivePersona.text("", "de"))
    }

    @Test
    fun memoryHeadingFollowsLanguage() {
        val en = LivePersona.text("likes tea", "en")
        assertTrue(en.contains("What you already know about the owner:"))
        assertTrue(en.contains("likes tea"))

        val uk = LivePersona.text("любить чай", "uk")
        assertTrue(uk.contains("Що ти вже знаєш про власника:"))
        assertTrue(uk.contains("любить чай"))

        val cs = LivePersona.text("má rád čaj", "cs")
        assertTrue(cs.contains("Co už o majiteli víš:"))
        assertTrue(cs.contains("má rád čaj"))
    }
}
