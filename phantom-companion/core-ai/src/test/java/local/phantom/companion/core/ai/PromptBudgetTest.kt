package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brief is ~12 300 tokens and every one of them was sent for «котра
 * година». Trimming is only safe if it never removes what the turn needs.
 */
class PromptBudgetTest {

    private val brief = SystemPrompts.DEFAULT_PHANTOM

    @Test
    fun `a trivial question drops the heavy specs`() {
        val out = PromptBudget.trim(brief, "котра година")
        assertTrue("canvas schema survived a clock question", "\"type\":\"canvas\"" !in out)
        assertTrue("report schema survived", "\"type\":\"report\"" !in out)
        assertTrue("agent spec survived", "АГЕНТИ (sub-agents)" !in out)
        assertTrue("trim did nothing", out.length < brief.length * 0.9)
    }

    @Test
    fun `the core contract is never dropped`() {
        val out = PromptBudget.trim(brief, "котра година")
        // Response format and the tool protocol are what make ANY turn work.
        assertTrue("format contract lost", "КРИТИЧНО — ФОРМАТ ВІДПОВІДІ" in out)
        assertTrue("tool protocol lost", "ІНСТРУМЕНТИ (`tool-call`)" in out)
        assertTrue("rules lost", "ПРАВИЛА" in out)
    }

    @Test
    fun `asking for a drawing keeps the canvas spec`() {
        val out = PromptBudget.trim(brief, "намалюй схему маршруту")
        assertTrue("canvas schema dropped when asked for", "\"type\":\"canvas\"" in out)
    }

    @Test
    fun `an attachment keeps the image spec even with no cue words`() {
        val out = PromptBudget.trim(brief, "що це", hasAttachments = true)
        assertTrue("image schema dropped with a photo attached", "\"type\":\"photo-hero\"" in out)
    }

    @Test
    fun `a research request keeps the agent spec`() {
        val out = PromptBudget.trim(brief, "дослідь ринок дронів і збери порівняння")
        assertTrue("agent spec dropped for a research turn", "АГЕНТИ (sub-agents)" in out)
        assertTrue("report schema dropped for a research turn", "\"type\":\"report\"" in out)
    }

    @Test
    fun `no order outlives the schema it names`() {
        val out = PromptBudget.trim(brief, "котра година")
        assertTrue("report schema survived", "\"type\":\"report\"" !in out)
        assertTrue("the classifier still orders a report", "→ report" !in out)
        assertTrue("still orders a canvas", "поверни віджет 'canvas'" !in out)
        assertTrue("still orders a code-runner", "поверни віджет code-runner" !in out)
        assertTrue("the orphaned report heading stayed", "ЗВІТ (report)" !in out)
        // …and the classifier itself, plus its neighbours' orders, live on.
        assertTrue("the intent classifier was eaten", "одне число → stat-hero" in out)
        assertTrue("the markdown order was eaten", "поверни віджет 'markdown'" in out)
        assertTrue("the deep-search order was eaten", "research.deep(question" in out)
    }

    @Test
    fun `a cue matches a word, not the middle of one`() {
        // «код» must not fire on «закодував», or the gate is noise.
        val out = PromptBudget.trim(brief, "розкодуй це")
        assertTrue("code-runner survived an interior match", "\"type\":\"code-runner\"" !in out)
        assertTrue("a real cue stopped working", "\"type\":\"code-runner\"" in PromptBudget.trim(brief, "напиши код"))
    }

    @Test
    fun `an unknown brief is left untouched rather than cut blind`() {
        val other = "ЩОСЬ ІНШЕ:\nтіло\n"
        assertEquals(other, PromptBudget.trim(other, "котра година"))
    }

    @Test
    fun `neighbouring catalogue entries survive a cut`() {
        // The catalogue is numbered; a sloppy cut eats the entry after it.
        val out = PromptBudget.trim(brief, "котра година")
        assertTrue("the map widget was eaten", "\"type\":\"map\"" in out)
        assertTrue("the weather widget was eaten", "\"type\":\"weather\"" in out)
    }
}
