package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Nine full-width «ІНСАЙТ» cards with nothing in them and nothing to press.
 * The backend emits `proactive.cycle` on a timer whether or not it has
 * anything to say; the client rendered every one.
 */
class EmptyInsightTest {

    private fun raw(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a cycle with nothing in it is not shown`() {
        assertNull(ProactiveInsightParser.tryParse("proactive.cycle", null, raw("{}")))
        assertNull(ProactiveInsightParser.tryParse("proactive.cycle", "   ", raw("""{"id":"x"}""")))
    }

    @Test
    fun `a cycle with words is shown`() {
        val out = ProactiveInsightParser.tryParse("proactive.cycle", "мости перекрито", raw("{}"))
        assertNotNull(out)
        assertEquals("мости перекрито", out!!.body)
    }

    @Test
    fun `a title alone is enough to be worth a card`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.cycle", null, raw("""{"title":"РЕБ поруч"}"""),
        )
        assertEquals("РЕБ поруч", out?.title)
    }

    @Test
    fun `an empty body with a real action still shows — the button is the point`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.pending_action",
            null,
            raw("""{"actions":[{"label":"відкрити мапу","action_id":"map"}]}"""),
        )
        assertNotNull(out)
        assertEquals(1, out!!.actions.size)
    }

    @Test
    fun `an unknown kind is still refused`() {
        assertNull(ProactiveInsightParser.tryParse("chat.message", "текст", raw("{}")))
    }
}

/** «Чому зараз» — the line that separates an assistant from a notification. */
class InsightReasonTest {

    private fun raw(json: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject

    @Test
    fun `the reason and the confidence survive the wire`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.cycle",
            "Відкрити мапу?",
            raw("""{"why":"о цій порі ти йдеш до метро","confidence":"0.72"}"""),
        )
        assertEquals("о цій порі ти йдеш до метро", out?.why)
        assertEquals(0.72f, out?.confidence)
    }

    @Test
    fun `reason is also read under its other name`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.cycle", "текст", raw("""{"reason":"глушіння GPS"}"""),
        )
        assertEquals("глушіння GPS", out?.why)
    }

    @Test
    fun `an engine that cannot measure says nothing rather than inventing`() {
        val out = ProactiveInsightParser.tryParse("proactive.cycle", "текст", raw("{}"))
        assertNull(out?.why)
        assertNull(out?.confidence)
    }

    @Test
    fun `an absurd confidence is clamped, not shown as 900 percent`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.cycle", "текст", raw("""{"confidence":"9.0"}"""),
        )
        assertEquals(1f, out?.confidence)
    }

    @Test
    fun `a non-numeric confidence is dropped, not rendered as zero`() {
        val out = ProactiveInsightParser.tryParse(
            "proactive.cycle", "текст", raw("""{"confidence":"дуже"}"""),
        )
        assertNull(out?.confidence)
    }
}
