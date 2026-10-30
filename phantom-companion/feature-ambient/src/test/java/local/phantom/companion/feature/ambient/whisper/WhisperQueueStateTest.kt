package local.phantom.companion.feature.ambient.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E3 — pure JVM tests for the [WhisperQueueState] FIFO.
 *
 * The queue's contract: enqueue when current is null promotes
 * immediately; otherwise stacks behind. advance drops current and
 * pulls the next (or null when empty). Bounded at MAX_QUEUE_SIZE so
 * a notification burst doesn't grow unbounded.
 */
class WhisperQueueStateTest {

    @Test
    fun `enqueue when idle promotes to current immediately`() {
        val q = WhisperQueueState()
        assertNull(q.current.value)

        q.enqueue("PHANTOM OS чує тебе")

        assertEquals("PHANTOM OS чує тебе", q.current.value)
        assertEquals(0, q.buffered.size)
        assertEquals(1, q.size)
    }

    @Test
    fun `enqueue while busy queues behind current`() {
        val q = WhisperQueueState()
        q.enqueue("first")
        q.enqueue("second")
        q.enqueue("third")

        assertEquals("first", q.current.value)
        assertEquals(listOf("second", "third"), q.buffered)
        assertEquals(3, q.size)
    }

    @Test
    fun `advance promotes the next pending message`() {
        val q = WhisperQueueState()
        q.enqueue("first")
        q.enqueue("second")

        q.advance()

        assertEquals("second", q.current.value)
        assertEquals(0, q.buffered.size)
        assertEquals(1, q.size)
    }

    @Test
    fun `advance with no pending nulls current`() {
        val q = WhisperQueueState()
        q.enqueue("only")

        q.advance()

        assertNull(q.current.value)
        assertEquals(0, q.size)
    }

    @Test
    fun `advance on empty queue is a no-op`() {
        val q = WhisperQueueState()
        q.advance()
        assertNull(q.current.value)
        assertEquals(0, q.size)
    }

    @Test
    fun `clear empties everything`() {
        val q = WhisperQueueState()
        q.enqueue("a")
        q.enqueue("b")
        q.enqueue("c")
        assertEquals(3, q.size)

        q.clear()

        assertNull(q.current.value)
        assertEquals(0, q.size)
        assertEquals(emptyList<String>(), q.buffered)
    }

    @Test
    fun `blank messages are silently dropped`() {
        val q = WhisperQueueState()
        q.enqueue("")
        q.enqueue("   ")
        q.enqueue("\t\n")

        assertNull("blank enqueues must not promote", q.current.value)
        assertEquals(0, q.size)
    }

    @Test
    fun `queue caps at MAX_QUEUE_SIZE — newest in oldest out`() {
        val q = WhisperQueueState()
        q.enqueue("current") // promotes to current
        repeat(WhisperQueueState.MAX_QUEUE_SIZE) { i ->
            q.enqueue("queued-$i")
        }
        // current + MAX in buffer
        assertEquals("current", q.current.value)
        assertEquals(WhisperQueueState.MAX_QUEUE_SIZE, q.buffered.size)

        // Push one more — oldest in buffer drops
        q.enqueue("overflow")
        assertEquals(WhisperQueueState.MAX_QUEUE_SIZE, q.buffered.size)
        assertTrue("queue must contain newest", q.buffered.contains("overflow"))
        assertTrue("queue must drop oldest", !q.buffered.contains("queued-0"))
    }
}
