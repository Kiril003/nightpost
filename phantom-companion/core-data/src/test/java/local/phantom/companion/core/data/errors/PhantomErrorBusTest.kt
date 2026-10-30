package local.phantom.companion.core.data.errors

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QH4 — pin the central error bus contract: hot events + bounded
 * rolling history. The Settings → Діагностика screen depends on the
 * history StateFlow staying capped at 50 entries; a future refactor
 * that drops the trim would let a runaway loop OOM the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhantomErrorBusTest {

    @Test
    fun `emit appends to history`() = runTest {
        val bus = PhantomErrorBus()
        bus.emit(source = "VoiceManager", severity = PhantomError.Severity.Warn, message = "fell back to System TTS")
        val hist = bus.history.value
        assertEquals(1, hist.size)
        assertEquals("VoiceManager", hist[0].source)
        assertEquals(PhantomError.Severity.Warn, hist[0].severity)
    }

    @Test
    fun `events flow receives emitted errors`() = runTest {
        val bus = PhantomErrorBus()
        val received = mutableListOf<PhantomError>()
        val job = launch {
            bus.events.take(2).toList(received)
        }
        // Give the collector a moment to subscribe before emitting.
        kotlinx.coroutines.yield()
        bus.emit(source = "AIRouter", severity = PhantomError.Severity.Error, message = "cloud rejected")
        bus.emit(source = "PhantomLink", severity = PhantomError.Severity.Critical, message = "ws disconnected")
        job.join()
        assertEquals(2, received.size)
        assertEquals("AIRouter", received[0].source)
        assertEquals("PhantomLink", received[1].source)
    }

    @Test
    fun `history caps at maxHistory and keeps newest entries`() = runTest {
        val bus = PhantomErrorBus(maxHistory = 3)
        repeat(5) { i ->
            bus.emit(
                source = "Test",
                severity = PhantomError.Severity.Info,
                message = "msg $i",
            )
        }
        val hist = bus.history.value
        assertEquals(3, hist.size)
        assertEquals("msg 2", hist[0].message)
        assertEquals("msg 3", hist[1].message)
        assertEquals("msg 4", hist[2].message)
    }

    @Test
    fun `clearHistory empties the buffer`() = runTest {
        val bus = PhantomErrorBus()
        bus.emit(source = "x", severity = PhantomError.Severity.Info, message = "before")
        assertEquals(1, bus.history.value.size)
        bus.clearHistory()
        assertTrue("after clear, history is empty", bus.history.value.isEmpty())
    }

    @Test
    fun `create populates id and timestamp`() {
        val err = PhantomError.create(
            source = "X",
            severity = PhantomError.Severity.Info,
            message = "hello",
            nowMs = 12345L,
        )
        assertEquals(12345L, err.ts)
        assertTrue("id is non-blank UUID", err.id.isNotBlank())
        assertEquals("X", err.source)
    }

    @Test
    fun `cause is preserved but separate from message`() = runTest {
        val bus = PhantomErrorBus()
        val cause = RuntimeException("internal stack")
        bus.emit(
            source = "X",
            severity = PhantomError.Severity.Error,
            message = "оператор-видиме повідомлення",
            cause = cause,
        )
        val err = bus.history.value.first()
        assertEquals("оператор-видиме повідомлення", err.message)
        assertEquals(cause, err.cause)
    }
}
