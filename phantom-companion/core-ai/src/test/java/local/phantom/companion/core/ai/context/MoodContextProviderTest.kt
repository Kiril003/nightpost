package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAP-AI-2 — pins the MOOD prompt block contract.
 *
 * The block is the only signal that gates AI suggestion tone for LOW /
 * CRITICAL mood arcs. Any regression in format silently changes how the
 * AI treats the operator, so the format is regression-pinned here.
 */
class MoodContextProviderTest {

    @Test
    fun `formatter emits unknown when state is null`() {
        assertEquals("[MOOD:unknown]", MoodContextFormatter.format(null))
    }

    @Test
    fun `formatter forwards verbatim ELEVATED band`() {
        assertEquals("[MOOD:state=ELEVATED]", MoodContextFormatter.format("ELEVATED"))
    }

    @Test
    fun `formatter forwards verbatim BALANCED band`() {
        assertEquals("[MOOD:state=BALANCED]", MoodContextFormatter.format("BALANCED"))
    }

    @Test
    fun `formatter forwards verbatim LOW band`() {
        assertEquals("[MOOD:state=LOW]", MoodContextFormatter.format("LOW"))
    }

    @Test
    fun `formatter forwards verbatim CRITICAL band`() {
        assertEquals("[MOOD:state=CRITICAL]", MoodContextFormatter.format("CRITICAL"))
    }

    @Test
    fun `formatter forwards future bands without translation`() {
        // If :core-health-mesh introduces a new band, :core-ai must NOT
        // shadow it with an outdated whitelist — the prompt must surface
        // the new label so the AI can be retrained on it.
        assertEquals("[MOOD:state=ECSTATIC]", MoodContextFormatter.format("ECSTATIC"))
    }

    @Test
    fun `provider lambda returns supplier snapshot`() {
        var snapshot: String? = null
        val provider = MoodContextProvider { snapshot }
        assertEquals(null, provider.current())

        snapshot = "LOW"
        assertEquals("LOW", provider.current())
    }
}
