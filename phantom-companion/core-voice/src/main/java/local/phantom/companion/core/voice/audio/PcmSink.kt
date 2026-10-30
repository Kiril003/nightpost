package local.phantom.companion.core.voice.audio

/**
 * Blocking low-level PCM output. The one production implementation is
 * [AudioTrackPcmSink]; tests substitute a fake so the streaming pipeline
 * above it ([PcmStreamPlayer]) is verifiable on the JVM.
 *
 * Contract: [start] → any number of [write]s (blocking, backpressured by
 * the hardware buffer) → [awaitDrained] → [release]. [release] must be
 * safe at any point and idempotent.
 */
interface PcmSink {

    /** Open the output at [sampleRate] Hz, mono float PCM. */
    fun start(sampleRate: Int)

    /**
     * Blocking write of [length] samples from [samples] at [offset].
     * Returns when the samples are handed to the output buffer.
     */
    fun write(samples: FloatArray, offset: Int, length: Int)

    /** Block until everything written has actually been *heard*. */
    fun awaitDrained()

    /** Instantly pause and flush the hardware buffer to stop stale audio. */
    fun flush()

    /** Stop immediately, drop queued audio, free the output. */
    fun release()

    /**
     * ЖИВА РОЗМОВА — приглушити, не вбиваючи. Перехоплення слова тепер
     * двоступеневе: спершу PHANTOM стишується (людина чує поступку за
     * мілісекунди), і лише коли розпізнавач підтвердить справжнє слово,
     * репліка обривається. Кашель коштує короткого просідання гучності,
     * а не втраченої фрази.
     */
    fun setGain(gain: Float) = Unit

    /**
     * Скільки кадрів залізо СПРАВДІ відтворило. Це і є точка, на якій
     * людина перебила: усе після неї не було почуте, хай що ми синтезували.
     */
    fun framesRendered(): Long = 0L
}
