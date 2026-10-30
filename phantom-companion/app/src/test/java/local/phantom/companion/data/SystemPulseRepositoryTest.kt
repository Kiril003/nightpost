package local.phantom.companion.data

import local.phantom.companion.core.context.SystemState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for [SystemPulseRepository].
 *
 * Pure-JVM tests — neither [ContextEngine] nor [MobileSensorAdapter] is
 * constructed (both pull Android framework deps for sensors / Wi-Fi);
 * we pass nulls and exercise the bits that survive without those flows.
 *
 * The aggregator's full behaviour (state-transition emission, mic-rms
 * normalisation, sensor-staleness fade) needs Android-context-bound
 * upstream classes and is covered by the manual E2E checklist
 * (docs/E2E_MANUAL_CHECKLIST.md).
 */
class SystemPulseRepositoryTest {

    // ── initial pulse ───────────────────────────────────────────────────

    @Test fun `initial pulse before start has all signals OFF`() {
        val repo = SystemPulseRepository(
            contextEngine = null,
            sensorAdapter = null,
            aiAvailable = { false },
            linkConnected = { false },
        )
        val p = repo.pulse.value
        assertEquals("default state must be FOCUS", SystemState.FOCUS, p.state)
        assertEquals(0L, p.dwellMs)
        assertEquals("ініціалізація", p.narrative)
        assertEquals(0f, p.confidence, 0.0001f)
        assertEquals(PulseSignal.Status.OFF, p.ai.status)
        assertEquals(PulseSignal.Status.OFF, p.voice.status)
        assertEquals(PulseSignal.Status.OFF, p.sensor.status)
        assertEquals(PulseSignal.Status.OFF, p.link.status)
        assertEquals(0f, p.ai.brightness, 0.0001f)
        assertEquals(0f, p.link.brightness, 0.0001f)
    }

    // ── PulseSignal data class shape ─────────────────────────────────────

    @Test fun `PulseSignal Status enum has the documented three values`() {
        // Locked because the SystemPulseHeader UI keys off these names —
        // dropping/renaming a status would break the Compose rendering.
        val statuses = PulseSignal.Status.entries.map { it.name }.toSet()
        assertEquals(setOf("LIVE", "IDLE", "OFF"), statuses)
    }

    // ── label invariants — used by the constellation rendering ───────────

    @Test fun `initial pulse exposes the four canonical signal labels`() {
        // Vitals constellation expects exactly AI / VOICE / SENSOR / LINK —
        // SystemPulseHeader.kt iterates over these four signals in order.
        // Renaming a label silently breaks the corresponding dot.
        val repo = SystemPulseRepository(
            contextEngine = null,
            sensorAdapter = null,
            aiAvailable = { false },
            linkConnected = { false },
        )
        val p = repo.pulse.value
        assertEquals("AI", p.ai.label)
        assertEquals("VOICE", p.voice.label)
        assertEquals("SENSOR", p.sensor.label)
        assertEquals("LINK", p.link.label)
    }
}
