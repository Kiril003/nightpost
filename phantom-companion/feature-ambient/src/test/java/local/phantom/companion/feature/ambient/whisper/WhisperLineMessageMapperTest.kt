package local.phantom.companion.feature.ambient.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E3 — verbatim mapping for the 8 supported event types.
 *
 * Acceptance from the prompt: «WhisperLineMessageMapperTest —
 * verbatim mapping for 8 event types». The 8 types are listed in
 * [WhisperLineMessageMapper.SUPPORTED_KINDS]; if any of them is
 * silently dropped or starts mapping to a different operator-visible
 * line, this test fails before the operator sees a regression.
 *
 * Unknown / out-of-set kinds map to null — the queue silently drops
 * them so a future server-side event type can't fill the ribbon
 * with garbage strings.
 */
class WhisperLineMessageMapperTest {

    private fun map(kind: String, text: String? = null, emotion: String? = null): String? =
        WhisperLineMessageMapper.mapEvent(WhisperEvent(kind, text, emotion))

    /* ---- 8 supported types ---- */

    @Test
    fun `proactive_cycle uses operator text when present`() {
        assertEquals("PHANTOM OS чує тебе", map("proactive.cycle", text = "PHANTOM OS чує тебе"))
    }

    @Test
    fun `proactive_cycle falls back to default when text is null or blank`() {
        assertEquals(WhisperLineMessageMapper.DEFAULT_PROACTIVE_CYCLE, map("proactive.cycle", text = null))
        assertEquals(WhisperLineMessageMapper.DEFAULT_PROACTIVE_CYCLE, map("proactive.cycle", text = ""))
        assertEquals(WhisperLineMessageMapper.DEFAULT_PROACTIVE_CYCLE, map("proactive.cycle", text = "   "))
    }

    @Test
    fun `proactive_pending_action uses operator text when present`() {
        assertEquals("олег чекає 23 хв", map("proactive.pending_action", text = "олег чекає 23 хв"))
    }

    @Test
    fun `proactive_pending_action falls back to default when text is null`() {
        assertEquals(WhisperLineMessageMapper.DEFAULT_PROACTIVE_PENDING, map("proactive.pending_action"))
    }

    @Test
    fun `proactive_action_fired uses operator text when present`() {
        assertEquals("надіслав звіт", map("proactive.action_fired", text = "надіслав звіт"))
    }

    @Test
    fun `proactive_action_fired falls back to default when text is null`() {
        assertEquals(WhisperLineMessageMapper.DEFAULT_PROACTIVE_FIRED, map("proactive.action_fired"))
    }

    @Test
    fun `emotion_updated formats emotion name with prefix`() {
        assertEquals("відчуття · joy", map("emotion.updated", emotion = "joy"))
        assertEquals("відчуття · sadness", map("emotion.updated", emotion = "sadness"))
    }

    @Test
    fun `emotion_updated drops when emotion is null or blank`() {
        // No emotion to display means nothing meaningful for the ribbon —
        // the queue must drop this event silently.
        assertNull(map("emotion.updated", emotion = null))
        assertNull(map("emotion.updated", emotion = ""))
        assertNull(map("emotion.updated", emotion = "   "))
    }

    @Test
    fun `task_report_acknowledged is a fixed string`() {
        // No operator text needed — the ack itself is the message.
        assertEquals(WhisperLineMessageMapper.DEFAULT_TASK_ACK, map("task.report_acknowledged"))
        // Even when text is provided, this kind shows the fixed string —
        // the ack is operator-agnostic.
        assertEquals(WhisperLineMessageMapper.DEFAULT_TASK_ACK, map("task.report_acknowledged", text = "ignored"))
    }

    @Test
    fun `inner_monologue thought formats with prefix when text present`() {
        assertEquals("думка · можливо переглянути контракт",
            map("inner_monologue.thought", text = "можливо переглянути контракт"))
    }

    @Test
    fun `inner_monologue observation formats with prefix when text present`() {
        assertEquals("помітив · ти не пив воду 2 години",
            map("inner_monologue.observation", text = "ти не пив воду 2 години"))
    }

    @Test
    fun `inner_monologue reflection formats with prefix when text present`() {
        assertEquals("роздум · вечір нагадує ту зиму",
            map("inner_monologue.reflection", text = "вечір нагадує ту зиму"))
    }

    @Test
    fun `inner_monologue events drop when text is null or blank`() {
        // The prefix alone («думка ·») has no value — drop the event.
        assertNull(map("inner_monologue.thought", text = null))
        assertNull(map("inner_monologue.observation", text = ""))
        assertNull(map("inner_monologue.reflection", text = "   "))
    }

    /* ---- unknown kind ---- */

    @Test
    fun `unknown kind returns null (drops from queue)`() {
        assertNull(map("unknown.something"))
        assertNull(map("proactive.unknown_subkind"))
        assertNull(map(""))
        // Even with text supplied — never invent a mapping for unknown kinds.
        assertNull(map("server.future_event_type", text = "hello"))
    }

    /* ---- supported set self-consistency ---- */

    @Test
    fun `every kind in SUPPORTED_KINDS yields a non-null mapping for the happy path`() {
        // Each declared kind must round-trip through the mapper for at
        // least one input shape — guards against adding an entry to
        // SUPPORTED_KINDS without a `when` branch (or vice versa).
        WhisperLineMessageMapper.SUPPORTED_KINDS.forEach { kind ->
            val happy = WhisperEvent(
                kind = kind,
                text = "test text",
                emotion = "joy",
            )
            assertTrue(
                "supported kind '$kind' must produce a non-null mapping",
                WhisperLineMessageMapper.mapEvent(happy) != null,
            )
        }
    }

    @Test
    fun `SUPPORTED_KINDS contains the prompt's eight verbatim types`() {
        // Pinning the exact set so a refactor that drops one of the
        // event types from the «proactive / emotion / monologue» triad
        // is caught loudly.
        val expected = setOf(
            "proactive.cycle",
            "proactive.pending_action",
            "proactive.action_fired",
            "emotion.updated",
            "task.report_acknowledged",
            "inner_monologue.thought",
            "inner_monologue.observation",
            "inner_monologue.reflection",
        )
        assertEquals(expected, WhisperLineMessageMapper.SUPPORTED_KINDS)
        assertEquals(8, WhisperLineMessageMapper.SUPPORTED_KINDS.size)
    }
}
