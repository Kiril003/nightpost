package local.phantom.companion.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «8 грн» came from the prompt, not the data: stat-hero used to say
 * «value — рядок, форматуй сам» over a list of world quantities — a written
 * licence to invent numbers. These tests go red through the INSTRUCTION
 * itself: a returned licence wording, a quantity entry that lost its source
 * clause, or the boundary falling out of the never-trimmed core.
 */
class SystemPromptNumberSourceTest {

    private val brief = SystemPrompts.DEFAULT_PHANTOM

    /** Quantities the world can contradict. */
    private val quantity =
        Regex("ціна|ціни|ціну|курс[,. )]|відстан|температур|кількіст|час прибуття|розклад")

    /** Wordings that hand authorship of a value to the model. */
    private val licence =
        Regex("форматуй сам|сформатуй сам|зроби сам|встав сам|запиши сам|оціни сам|підбери сам")

    /** Any admissible origin for a number: tool, result, operator, context. */
    private val source =
        Regex("інструмент|результат|оператор|джерел|source|geo\\.|weather\\.|web\\.|Open-Meteo|\\[LOCATION|розмов")

    @Test
    fun `no line licenses self-authored world quantities without a source`() {
        val offenders = (brief.lines() + SystemPrompts.LOCAL_COMPACT.lines())
            .filter { licence.containsMatchIn(it) && quantity.containsMatchIn(it) }
            .filterNot { source.containsMatchIn(it) }
        assertTrue(
            "дозвіл вигадати число про світ повернувся: ${offenders.map { it.take(80) }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every catalogue entry naming a world quantity carries a source clause`() {
        val offenders = brief.lines()
            .filter { Regex("^\\d+\\. ").containsMatchIn(it.trimStart()) }
            .filter { quantity.containsMatchIn(it) }
            .filterNot { source.containsMatchIn(it) }
        assertTrue(
            "запис каталогу говорить про величину без джерела: ${offenders.map { it.take(80) }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `quantity widgets each anchor their numbers to a source`() {
        listOf("stat-hero", "chart", "route", "weather").forEach { id ->
            val entry = brief.lines().firstOrNull { "\"type\":\"$id\"" in it }
            assertTrue("запис «$id» зник з каталогу", entry != null)
            assertTrue(
                "запис «$id» більше не каже, звідки беруться числа",
                source.containsMatchIn(entry!!),
            )
        }
    }

    @Test
    fun `the source boundary survives the trivial-turn trim`() {
        // A boundary the budget can silently cut is no boundary: it must sit
        // in the part of the brief every turn keeps.
        val trimmed = PromptBudget.trim(brief, "котра година")
        assertTrue("межу зрізано бюджетом", "ЧИСЛО ПРО СВІТ — ЛИШЕ З ДЖЕРЕЛА" in trimmed)
        // Its three escape hatches travel with it — a ban without an exit
        // breeds workarounds.
        assertTrue("вихід «виклич інструмент» втрачено", "виклич інструмент, що дасть число" in trimmed)
        assertTrue("вихід «не показуй картку» втрачено", "не показуй картку взагалі" in trimmed)
        assertTrue("вихід «порожнє поле» втрачено", "Порожнє поле чесніше" in trimmed)
    }

    @Test
    fun `the guess-anyway rule stays scoped to judgements`() {
        val lines = brief.lines().filter { "все одно дай найкращу відповідь" in it }
        assertTrue("правило про непевність зникло разом з межею", lines.isNotEmpty())
        lines.forEach {
            assertTrue(
                "«все одно відповідай» знову без обмеження на числа: ${it.take(80)}",
                "суджен" in it && "не знаю" in it,
            )
        }
    }

    @Test
    fun `the route order feeds from geo route, not estimation`() {
        val orders = brief.lines().filter { "куди до" in it }
        assertTrue("правило «куди до X» зникло", orders.isNotEmpty())
        orders.forEach {
            assertTrue("маршрутне правило знову без geo.route: ${it.take(80)}", "geo.route" in it)
        }
    }
}
