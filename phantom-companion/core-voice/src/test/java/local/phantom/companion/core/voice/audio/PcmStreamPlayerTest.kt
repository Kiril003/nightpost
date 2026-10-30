package local.phantom.companion.core.voice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class FakeSink : PcmSink {
    val written = CopyOnWriteArrayList<FloatArray>()
    val started = AtomicInteger(0)
    val released = AtomicInteger(0)
    val flushed = AtomicInteger(0)
    val drained = AtomicBoolean(false)
    var startedRate = 0
    /** When set, write() blocks on this latch — simulates a full HAL buffer. */
    var writeGate: CountDownLatch? = null

    override fun start(sampleRate: Int) {
        started.incrementAndGet(); startedRate = sampleRate
    }

    override fun write(samples: FloatArray, offset: Int, length: Int) {
        writeGate?.await()
        written.add(samples.copyOfRange(offset, offset + length))
    }

    override fun awaitDrained() { drained.set(true) }
    override fun flush() { flushed.incrementAndGet() }
    override fun release() { released.incrementAndGet() }
}

class PcmStreamPlayerTest {

    @Test
    fun `chunks play in order and session drains on finish`() {
        val sink = FakeSink()
        val player = PcmStreamPlayer { sink }
        val session = player.startSession(44100)
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(3f)
        val c = floatArrayOf(4f, 5f, 6f)
        session.enqueue(a); session.enqueue(b); session.enqueue(c)
        session.finish()
        assertTrue(session.awaitCompletion(2_000))
        assertEquals(3, sink.written.size)
        assertArrayEquals(a, sink.written[0], 0f)
        assertArrayEquals(b, sink.written[1], 0f)
        assertArrayEquals(c, sink.written[2], 0f)
        assertTrue("finish must drain, not cut", sink.drained.get())
        assertEquals(44100, sink.startedRate)
        assertEquals(1, sink.released.get())
    }

    @Test
    fun `enqueue while playing keeps feeding — the streaming property`() {
        val sink = FakeSink()
        val player = PcmStreamPlayer { sink }
        val session = player.startSession(24000)
        session.enqueue(floatArrayOf(1f))
        // Wait for the feeder to consume chunk 1, then feed chunk 2 —
        // proves the session accepts chunks after playback has begun.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (sink.written.size < 1 && System.nanoTime() < deadline) Thread.yield()
        assertEquals(1, sink.written.size)
        session.enqueue(floatArrayOf(2f))
        session.finish()
        assertTrue(session.awaitCompletion(2_000))
        assertEquals(2, sink.written.size)
    }

    @Test
    fun `cancel stops mid-stream without draining`() {
        val sink = FakeSink()
        val gate = CountDownLatch(1)
        sink.writeGate = gate
        val player = PcmStreamPlayer { sink }
        val session = player.startSession(44100)
        session.enqueue(floatArrayOf(1f, 2f, 3f))
        session.enqueue(floatArrayOf(4f))
        session.cancel()
        gate.countDown()
        assertTrue(session.awaitCompletion(2_000))
        assertTrue(session.isCancelled)
        assertFalse("cancel must not wait for drain", sink.drained.get())
        assertEquals("sink must still be released", 1, sink.released.get())
        assertTrue("queued chunk after cancel must not play", sink.written.size <= 1)
    }

    @Test
    fun `starting a new session cancels the previous one`() {
        val sinks = mutableListOf<FakeSink>()
        val player = PcmStreamPlayer { FakeSink().also { sinks.add(it) } }
        val first = player.startSession(44100)
        first.enqueue(floatArrayOf(1f))
        val second = player.startSession(44100)
        assertTrue(first.awaitCompletion(2_000))
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        second.finish()
        assertTrue(second.awaitCompletion(2_000))
        assertEquals(2, sinks.size)
        assertEquals(1, sinks[0].released.get())
    }

    @Test
    fun `enqueue after finish is ignored`() {
        val sink = FakeSink()
        val player = PcmStreamPlayer { sink }
        val session = player.startSession(44100)
        session.enqueue(floatArrayOf(1f))
        session.finish()
        session.enqueue(floatArrayOf(9f))
        assertTrue(session.awaitCompletion(2_000))
        assertEquals(1, sink.written.size)
    }

    @Test
    fun `cancelActive is safe with no session`() {
        PcmStreamPlayer { FakeSink() }.cancelActive() // must not throw
    }
}
