package local.phantom.companion.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 hotfix #2 — pin the [LOCATION:lat,lon] instruction added to
 * the system prompt and guard against regressions where literal
 * "Київ" / "50.45" / "30.52" sneak back into example payloads.
 *
 * Why this is its own file (separate from SystemPromptTest): the
 * Phase 1-F6 SystemPromptTest pins the four widget schema fragments —
 * those are stable. The hotfix-2 surface is about example *content*
 * vs. instructions that interpret runtime context, which is a
 * different invariant. Keeping them separate makes a future "we
 * renamed [LOCATION:] → [GEO:]" delta land in one targeted file.
 */
class SystemPromptLocationTest {

    private val prompt = SystemPrompts.DEFAULT_PHANTOM

    @Test
    fun `prompt contains LOCATION block instruction`() {
        assertTrue(
            "[LOCATION:lat,lon] format hint must be present",
            prompt.contains("[LOCATION:lat,lon]"),
        )
    }

    @Test
    fun `prompt instructs the AI to use runtime LOCATION over examples`() {
        // Bug #3 root cause: the AI was happily copying "Київ" from
        // the example. The new rule must explicitly tell it to prefer
        // the runtime [LOCATION:...] block over the prompt examples.
        assertTrue(
            "rule must reference using runtime LOCATION coords, not examples",
            prompt.contains("Використовуй ЦІ координати"),
        )
    }

    @Test
    fun `prompt instructs the AI to ask for a city when LOCATION is unknown`() {
        // Bug #3 mitigation when GPS is denied: ask, don't guess Kyiv.
        assertTrue(
            "[LOCATION:unknown] fallback rule must be present",
            prompt.contains("[LOCATION:unknown]"),
        )
        assertTrue(
            "fallback rule must mention asking the user (попроси)",
            prompt.contains("попроси користувача назвати місто"),
        )
    }

    @Test
    fun `prompt examples no longer hardcode literal Київ`() {
        // The old `"location":"Київ"` and `"label":"Київ"` example
        // values caused the AI to copy them verbatim and always show
        // weather for Kyiv regardless of the operator's real location.
        assertFalse(
            "literal \"Київ\" must not appear in example JSON values",
            prompt.contains("\"Київ\""),
        )
    }

    @Test
    fun `prompt examples no longer hardcode literal Kyiv coordinates`() {
        // 50.45 / 30.52 are the Київ centre — the AI was copying
        // these into route widgets even when the operator was in Lviv.
        assertFalse(
            "literal 50.45 (Kyiv lat) must not appear in example JSON",
            prompt.contains("50.45"),
        )
        assertFalse(
            "literal 30.52 (Kyiv lon) must not appear in example JSON",
            prompt.contains("30.52"),
        )
    }

    @Test
    fun `prompt uses placeholder syntax for coords in examples`() {
        // The replacement must be obviously a template, not a value
        // the model can mistake for a real coord.
        assertTrue(
            "<lat> placeholder present",
            prompt.contains("<lat>") || prompt.contains("<my_lat>"),
        )
        assertTrue(
            "<lon> placeholder present",
            prompt.contains("<lon>") || prompt.contains("<my_lon>"),
        )
    }
}
