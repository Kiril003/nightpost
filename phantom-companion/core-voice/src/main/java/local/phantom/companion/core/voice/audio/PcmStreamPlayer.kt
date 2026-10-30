package local.phantom.companion.core.voice.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Gapless streaming playback of fragmented PCM chunks.
 *
 * One utterance = one [Session]. The synthesis pipeline calls
 * [Session.enqueue] as each segment's PCM materialises; a dedicated
 * feeder thread drains the queue into the [PcmSink] with blocking
 * writes. The producer never touches the audio HAL, the feeder never
 * waits on the model — the queue is the only meeting point, which is
 * the whole "play fragmented chunks on the fly" requirement.
 *
 * [Session.finish] marks the end of input; [Session.awaitCompletion]
 * blocks until the sink has drained (or the session was [cancel]led).
 * [cancel] is the stop() path: it tears the sink down mid-buffer, so
 * the voice halts in tens of milliseconds, not at the next chunk edge.
 */
class PcmStreamPlayer(private val sinkFactory: (isNavigation: Boolean) -> PcmSink) {

    private val active = AtomicReference<Session?>(null)

    /** Begin an utterance at [sampleRate]. Any previous session is cancelled. */
    fun startSession(sampleRate: Int, isNavigation: Boolean = false): Session {
        active.getAndSet(null)?.cancel()
        val session = Session(sinkFactory(isNavigation), sampleRate)
        active.set(session)
        return session
    }

    /** Cancel whatever is playing right now (idempotent, thread-safe). */
    fun cancelActive() {
        active.getAndSet(null)?.cancel()
    }

    /** ЖИВА РОЗМОВА — стишити живу репліку, не обриваючи її. */
    fun setGain(gain: Float) {
        active.get()?.setGain(gain)
    }

    /** Кадри, які залізо вже відтворило в поточній репліці. */
    fun framesRendered(): Long = active.get()?.framesRendered ?: 0L

    class Session internal constructor(
        private val sink: PcmSink,
        sampleRate: Int,
    ) {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        /** Poison pill: zero-length array marks end-of-stream in the queue. */
        private val eos = FloatArray(0)

        private val feeder = Thread({
            try {
                sink.start(sampleRate)
                while (true) {
                    val chunk = queue.take()
                    if (chunk === eos || cancelled.get()) break
                    sink.write(chunk, 0, chunk.size)
                }
                if (!cancelled.get()) sink.awaitDrained()
            } catch (_: InterruptedException) {
                // cancel() interrupted a blocking take/write — fall through
            } finally {
                sink.release()
                done.countDown()
            }
        }, "phantom-voice-feeder").apply {
            // Real-time-ish: losing the CPU here is an audible underrun.
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }

        /** Hand one synthesized segment to playback. Non-blocking. */
        fun enqueue(samples: FloatArray) {
            if (samples.isEmpty() || finished.get() || cancelled.get()) return
            queue.put(samples)
        }

        /** 1f — на повну; ~0.25f — поступився словом, але ще не замовк. */
        fun setGain(gain: Float) = sink.setGain(gain)

        /**
         * Кадри, які залізо СПРАВДІ віддало в динамік.
         *
         * Після обриву віддається знімок, зроблений у мить обриву: сам
         * [cancel] розносить доріжку, і той, хто спитає після нього, дістав
         * би нуль — тобто «людина не почула нічого», що є неправдою і рівно
         * тим, чого позначка перехоплення має не допустити.
         */
        val framesRendered: Long
            get() = renderedAtCancel.get().takeIf { it >= 0L } ?: sink.framesRendered()

        private val renderedAtCancel = java.util.concurrent.atomic.AtomicLong(-1L)

        /** No more chunks will come; play out what is queued, then drain. */
        fun finish() {
            if (finished.compareAndSet(false, true)) queue.put(eos)
        }

        /**
         * Block until the utterance has fully played (or was cancelled).
         * Returns false on [timeoutMs] expiry — a stuck audio route must
         * not wedge the caller forever.
         */
        fun awaitCompletion(timeoutMs: Long): Boolean =
            done.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** Immediate stop: drop the queue, tear down the sink mid-buffer. */
        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            // Знімок ДО flush(): після нього головка відтворення вже нічого
            // не значить, а це і є точка, на якій людину перестали слухати.
            renderedAtCancel.set(sink.framesRendered())
            queue.clear()
            finished.set(true)
            sink.flush() // Instantly halt playback
            queue.offer(eos) // wake a feeder blocked on take()
            feeder.interrupt() // wake a feeder blocked inside sink.write()
        }

        val isCancelled: Boolean get() = cancelled.get()
    }
}
