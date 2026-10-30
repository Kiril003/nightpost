package local.phantom.companion.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1-F6 — pin the schema strings the AI is told it can emit.
 *
 * The DEFAULT_PHANTOM prompt is what teaches the model that
 * timer/weather/route/code-runner widgets exist + what JSON shape
 * to return. Drift here means the model emits the wrong shape and
 * the renderer's JSON-detect path silently falls back to plain
 * text — operator sees a description of a timer instead of an
 * actual ticking widget.
 *
 * Tests pin the schema fragments that the parser depends on.
 */
class SystemPromptTest {

    private val prompt = SystemPrompts.DEFAULT_PHANTOM

    /* ---- the four Phase 2 widgets ---- */

    @Test
    fun `prompt advertises Phase 1-F2 timer schema fields`() {
        assertTrue("type:timer present", prompt.contains("\"type\":\"timer\""))
        assertTrue("duration_seconds present", prompt.contains("duration_seconds"))
        assertTrue("started_at present", prompt.contains("started_at"))
        assertTrue("pause action present", prompt.contains("\"id\":\"pause\""))
        assertTrue("cancel action present", prompt.contains("\"id\":\"cancel\""))
    }

    @Test
    fun `R1_2 prompt advertises the hydrate-only weather contract`() {
        // R1.2 (kill fake weather): the model is told to request
        // hydration ({"request":"current"}) and NEVER author hours[]
        // itself — numbers come only from weather.now/weather.forecast.
        assertTrue("type:weather present", prompt.contains("\"type\":\"weather\""))
        assertTrue("hydration request present", prompt.contains("\"request\":\"current\""))
        assertTrue("weather.now tool named", prompt.contains("weather.now"))
        assertTrue("weather.forecast tool named", prompt.contains("weather.forecast"))
        assertTrue(
            "no-self-authored-hours rule present",
            prompt.contains("НІКОЛИ не пиши hours[]"),
        )
    }

    @Test
    fun `prompt advertises Phase 1-F3 route geo schema fields`() {
        assertTrue("type:route present", prompt.contains("\"type\":\"route\""))
        assertTrue("from object present", prompt.contains("\"from\""))
        assertTrue("to object present", prompt.contains("\"to\""))
        assertTrue("distance_m present", prompt.contains("distance_m"))
        assertTrue("duration_sec present", prompt.contains("duration_sec"))
        assertTrue("navigate action present", prompt.contains("\"id\":\"navigate\""))
    }

    @Test
    fun `prompt advertises Phase 1-F5 code-runner schema fields`() {
        assertTrue("type:code-runner present", prompt.contains("\"type\":\"code-runner\""))
        assertTrue("lang field present", prompt.contains("\"lang\""))
        assertTrue("code field present", prompt.contains("\"code\""))
        assertTrue("env_hint mentioned", prompt.contains("env_hint"))
        assertTrue("run-on-pc action present", prompt.contains("\"id\":\"run-on-pc\""))
    }

    /* ---- legacy widgets still advertised (no regressions) ---- */

    @Test
    fun `prompt still advertises pre-Phase 2 widgets (no regressions)`() {
        assertTrue("map widget still present", prompt.contains("\"type\":\"map\""))
        assertTrue("task-list still present", prompt.contains("\"type\":\"task-list\""))
        assertTrue("command verb still present", prompt.contains("\"type\":\"command\""))
    }

    /* ---- behavior rules ---- */

    @Test
    fun `prompt includes the Phase 1-F6 verbatim trigger rules`() {
        // Verbatim from the Phase 1-F6 spec: «коли користувач
        // просить таймер / погоду / маршрут / запустити код —
        // відповідай JSON {type:..., payload:..., actions:...}
        // замість тексту»
        assertTrue(
            "core trigger rule present",
            prompt.contains("Коли користувач просить таймер / погоду / маршрут / запустити код"),
        )
    }

    @Test
    fun `prompt includes example trigger phrases for each widget`() {
        assertTrue(
            "timer example present",
            prompt.contains("встанови таймер на 2 хвилини"),
        )
        assertTrue(
            // R1.2 — the weather rule was rewritten around weather.now;
            // the anchor phrase is now the hard "real data only" header.
            "weather rule present",
            prompt.contains("ПОГОДА — ТІЛЬКИ РЕАЛЬНІ ДАНІ"),
        )
        assertTrue(
            "route example present",
            prompt.contains("куди до"),
        )
        assertTrue(
            "code-runner example present",
            prompt.contains("запусти hello world Python"),
        )
    }

    @Test
    fun `prompt is non-blank and contains the persona statement`() {
        assertTrue("non-blank", prompt.isNotBlank())
        assertTrue("persona statement present",
            prompt.contains("Ти — Nightpost") && prompt.contains("симбіонт"))
    }
}
