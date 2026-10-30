package local.phantom.companion.core.ai.prompt

import local.phantom.companion.core.ai.SystemPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card A3: the Nightpost chat gets a short English prompt; DEFAULT_PHANTOM stays for the old screens. */
class NightpostPromptTest {

    private val tags = listOf("en", "uk", "cs")

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun `every language fits the 3 KB card and is a real prompt`() {
        assertEquals(3072, NightpostPrompt.MAX_BYTES)
        tags.forEach { tag ->
            val prompt = NightpostPrompt.forLanguage(tag)
            assertTrue("$tag: ${bytes(prompt)} bytes", bytes(prompt) in 1200..NightpostPrompt.MAX_BYTES)
        }
    }

    @Test
    fun `no JSON examples, no code fences, no Cyrillic`() {
        tags.forEach { tag ->
            val prompt = NightpostPrompt.forLanguage(tag)
            assertTrue("$tag: empty", prompt.isNotBlank())
            assertFalse("$tag: brace", '{' in prompt || '}' in prompt)
            assertFalse("$tag: fence", "```" in prompt)
            assertFalse("$tag: Cyrillic", prompt.any { it in 'Ѐ'..'ӿ' })
        }
    }

    @Test
    fun `the language line follows the app language`() {
        val names = mapOf("en" to "English", "uk" to "Ukrainian", "cs" to "Czech")
        names.forEach { (tag, name) ->
            val prompt = NightpostPrompt.forLanguage(tag)
            assertTrue("$tag names $name", name in prompt)
            names.values.filter { it != name }.forEach { other ->
                assertFalse("$tag also names $other", other in prompt)
            }
        }
        assertTrue("region tag", "Czech" in NightpostPrompt.forLanguage("cs-CZ"))
        assertTrue("unknown falls back to the default language", "English" in NightpostPrompt.forLanguage("de"))
        assertTrue("blank falls back to the default language", "English" in NightpostPrompt.forLanguage(""))
    }

    @Test
    fun `it carries the rules of the card`() {
        val prompt = NightpostPrompt.forLanguage("en")
        listOf(
            "Nightpost assistant",
            "own data",
            "Tools are the only way",
            "[TOOLS:",
            "tool-call",
            "[TOOL_RESULT",
            "confirm",
            "seed phrase",
            "PIN",
            "password",
            "one line",
        ).forEach { assertTrue("missing «$it»", it in prompt) }
    }

    @Test
    fun `copy rules and the old vocabulary stay out`() {
        val prompt = NightpostPrompt.forLanguage("en").lowercase()
        listOf("light", "blackout", "darkness", "bluetooth", "mesh", "symbiote", "widget", "agent", "json", "offline")
            .forEach { assertFalse("«$it» is in the prompt", it in prompt) }
    }

    @Test
    fun `the old screens keep the full prompt`() {
        assertTrue(bytes(SystemPrompts.DEFAULT_PHANTOM) > 50_000)
        assertTrue(SystemPrompts.DEFAULT_PHANTOM.contains("\"type\":\"tool-call\""))
        assertEquals(SystemPrompts.DEFAULT_PHANTOM, SystemPrompts.withCoreFacts(facts = emptyList()))
    }
}
