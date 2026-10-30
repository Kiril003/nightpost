package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A52, збірка 17: «what can you do» англійською — відповідь українською. Припис
 * казав «Спілкуйся виключно українською мовою» кожному ходу, хоч би якою мовою
 * питали. Мова відповіді — мова останньої репліки людини; неясно — мова застосунку.
 */
class SystemPromptLanguageTest {

    @Test
    fun `англійське питання дістає англійський припис навіть в українському застосунку`() {
        val rule = SystemPrompts.languageRule("what can you do", appLanguage = "uk")
        assertTrue(rule, rule.contains("English"))
        assertEquals("en", SystemPrompts.replyLanguage("what can you do", "uk"))
    }

    @Test
    fun `українське питання дістає українську навіть в англійському застосунку`() {
        assertEquals("uk", SystemPrompts.replyLanguage("що ти вмієш?", "en"))
        assertTrue(SystemPrompts.languageRule("що ти вмієш?", "en").contains("українською"))
    }

    @Test
    fun `без слів — мова застосунку`() {
        assertEquals("en", SystemPrompts.replyLanguage("👍 42", "en"))
        assertEquals("uk", SystemPrompts.replyLanguage("👍 42", "uk"))
        assertEquals("uk", SystemPrompts.replyLanguage(null, "uk"))
        assertEquals("uk", SystemPrompts.replyLanguage("ok", "uk"))
        assertEquals("uk", SystemPrompts.replyLanguage("", "de"))
        assertEquals("en", SystemPrompts.replyLanguage("hello", "uk"))
    }

    @Test
    fun `назва чи посилання латиницею не перемикають українську розмову`() {
        assertEquals("uk", SystemPrompts.replyLanguage("покажи Starbucks", "uk"))
        assertEquals("uk", SystemPrompts.replyLanguage("глянь https://example.invalid/a/very/long/english/path", "en"))
        assertEquals("en", SystemPrompts.replyLanguage("where is Хрещатик", "uk"))
    }

    @Test
    fun `сталий припис більше не наказує українську всупереч людині`() {
        assertFalse(SystemPrompts.DEFAULT_PHANTOM.contains("виключно українською"))
        assertTrue(SystemPrompts.DEFAULT_PHANTOM.contains("мовою останньої репліки"))
    }

    @Test
    fun `«hi» англійською привітання навіть в українському застосунку, а «ok» нічия`() {
        assertEquals("en", SystemPrompts.replyLanguage("hi", "uk"))
        assertEquals("en", SystemPrompts.replyLanguage("Hi!", "uk"))
        assertEquals("uk", SystemPrompts.replyLanguage("привіт", "en"))
        assertEquals("uk", SystemPrompts.replyLanguage("ok", "uk"))
        assertEquals("en", SystemPrompts.replyLanguage("ok", "en"))
    }

    @Test
    fun `коротка репліка йде мовою попередніх реплік людини, а не мовою застосунку`() {
        // Розмова англійською, людина пише «ok» — відповідь не стрибає в українську.
        assertEquals("en", SystemPrompts.replyLanguage("ok", "uk", earlier = listOf("що ти вмієш?", "what can you do")))
        assertEquals("uk", SystemPrompts.replyLanguage("ok", "en", earlier = listOf("what can you do", "що ти вмієш?")))
        // Нічого з попереднього не видно — мова застосунку.
        assertEquals("uk", SystemPrompts.replyLanguage("ok", "uk", earlier = listOf("👍", "42")))
        // Власні слова людини старші за історію.
        assertEquals("uk", SystemPrompts.replyLanguage("дякую", "en", earlier = listOf("what can you do")))
        assertTrue(SystemPrompts.languageRule("ok", "uk", earlier = listOf("what can you do")).contains("English"))
    }

    @Test
    fun `чеський застосунок без видимої мови відповідає чеською, а не українською`() {
        assertEquals("en", SystemPrompts.replyLanguage("👍 42", "cs"))
        assertEquals("en", SystemPrompts.replyLanguage(null, "cs"))
        val bare = SystemPrompts.languageRule("co umíš?", "cs")
        assertTrue(bare, bare.contains("Czech") && bare.contains("⟦NEXT⟧"))
        assertFalse(bare, bare.contains("українською"))
        assertTrue(SystemPrompts.languageRule("👍", "cs", earlier = listOf("😀")).contains("Czech"))
    }

    @Test
    fun `у чеському застосунку англійська й українська репліки лишаються мовою людини`() {
        assertEquals("en", SystemPrompts.replyLanguage("what can you do", "cs"))
        assertTrue(SystemPrompts.languageRule("what can you do", "cs").contains("English"))
        assertEquals("uk", SystemPrompts.replyLanguage("що ти вмієш?", "cs"))
        assertTrue(SystemPrompts.languageRule("що ти вмієш?", "cs").contains("українською"))
        val earlierEnglish = SystemPrompts.languageRule("ok", "cs", earlier = listOf("what can you do"))
        assertTrue(earlierEnglish, earlierEnglish.contains("English"))
    }
}
