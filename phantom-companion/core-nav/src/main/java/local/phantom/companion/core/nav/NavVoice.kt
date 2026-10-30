package local.phantom.companion.core.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * ON-TIME VOICE — a navigation cue as data, not as a bare string.
 *
 * The field failure this repairs (operator drive, 2026-07-18): cues were
 * strings on a SharedFlow, spoken strictly in arrival order, each to
 * completion, by a synthesis pipeline that can take seconds per utterance.
 * A backlog formed behind one long phrase, and «через 200 метрів поверніть
 * праворуч» was still being SYNTHESIZED while the operator was already in
 * the next turn. A cue's words are only half of it; the other half is its
 * RIGHT TO BE SPOKEN AT ALL once time has passed — which is exactly what a
 * string cannot carry.
 */
data class NavCue(
    val text: String,
    val urgency: Urgency,
    /**
     * Supersede key: a new cue replaces every waiting cue with the same key.
     * Maneuver cues key on their step — when the 250 m tier is still waiting
     * as the 60 m tier fires, the operator needs the LATEST truth once, not
     * both in order.
     */
    val key: String? = null,
    val bornMs: Long,
    /** Past this age unspoken, the cue is a lie about the past — dropped. */
    val ttlMs: Long = DEFAULT_TTL_MS,
) {
    /** Declaration order IS priority order — the speaker sorts by ordinal. */
    enum class Urgency {
        /** The wheel moves on this. May preempt anything less urgent. */
        NOW,

        /** Ahead-of-the-turn preparation, reroute notices. */
        SOON,

        /** Digests, warnings, alarms — valuable, never worth a turn. */
        INFO,
    }

    fun expiredAt(nowMs: Long): Boolean = nowMs - bornMs > ttlMs

    companion object {
        const val DEFAULT_TTL_MS = 30_000L
    }
}

/**
 * ON-TIME VOICE — the speaker that owns the queue discipline.
 *
 * Three rules, each one a repair of the drive's observed failure:
 *
 *  1. **Stale cues are dropped, not spoken.** Every dequeue re-checks TTL;
 *     a maneuver cue that waited out its own turn dies in the queue.
 *  2. **Newest truth wins per maneuver.** A cue [NavCue.key] evicts every
 *     waiting cue with the same key — tiers of one turn never stack.
 *  3. **NOW preempts.** A final-tier cue arriving while something less
 *     urgent is mid-utterance stops that playback ([stopSpeaking]) and the
 *     turn is spoken immediately. A turn instruction spoken late is not
 *     politeness, it is a wrong instruction.
 *
 * Pure Kotlin: TTS reaches it as an injected suspend lambda (`urgent`
 * asks the router for its fastest-first-audio path), so every rule here is
 * a JVM unit test.
 */
class NavCueSpeaker(
    private val scope: CoroutineScope,
    /** Speak to completion. [urgent] = pick the fastest-starting engine. */
    private val speak: suspend (text: String, urgent: Boolean) -> Unit,
    /** Cancel the in-flight utterance; its [speak] call must then return. */
    private val stopSpeaking: () -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val pending = mutableListOf<NavCue>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** What is in the speaker's mouth right now, for the preemption rule. */
    @Volatile
    private var current: NavCue? = null

    private var worker: Job? = null

    fun submit(cue: NavCue) {
        var preempt = false
        synchronized(lock) {
            if (cue.key != null) pending.removeAll { it.key == cue.key }
            pending += cue
            val cur = current
            preempt = cue.urgency == NavCue.Urgency.NOW &&
                cur != null && cur.urgency != NavCue.Urgency.NOW
        }
        // Outside the lock: stop() reaches into an audio stack that must not
        // be entered while holding ours.
        if (preempt) stopSpeaking()
        signal.trySend(Unit)
    }

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            for (unused in signal) {
                while (true) {
                    val cue = takeNext() ?: break
                    current = cue
                    try {
                        speak(cue.text, cue.urgency == NavCue.Urgency.NOW)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        // A failed utterance must not kill the drive's voice.
                    } finally {
                        current = null
                    }
                }
            }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        stopSpeaking()
        synchronized(lock) { pending.clear() }
    }

    private fun takeNext(): NavCue? = synchronized(lock) {
        val now = clockMs()
        pending.removeAll { it.expiredAt(now) }
        val next = pending.minWithOrNull(compareBy({ it.urgency.ordinal }, { it.bornMs }))
        if (next != null) pending.remove(next)
        next
    }
}
