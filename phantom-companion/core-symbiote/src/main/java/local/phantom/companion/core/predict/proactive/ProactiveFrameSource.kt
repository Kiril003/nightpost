package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/**
 * Merges named string signal streams into one [ProactiveFrame] flow and stamps each frame
 * with a cheap change-magnitude [ProactiveFrame.salienceHint]. Inputs are abstract `String?`
 * flows on purpose — real typed sources get adapted upstream, keeping this pure-JVM testable.
 */
class ProactiveFrameSource(
    private val signalStreams: Map<String, Flow<String?>>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val criticalKeys: Set<String> = emptySet(),
    private val perSignalWeight: Int = 20,
    private val criticalBoost: Int = 80,
) {
    fun frames(): Flow<ProactiveFrame> {
        if (signalStreams.isEmpty()) return emptyFlow()
        val keys = signalStreams.keys.toList()
        // Leading null lets combine fire on the first real emission instead of stalling.
        val seeded = keys.map { signalStreams.getValue(it).onStart { emit(null) } }
        return flow {
            var prev: Map<String, String>? = null
            combine(seeded) { values ->
                val current = LinkedHashMap<String, String>()
                for (i in keys.indices) {
                    val v = values[i]
                    if (!v.isNullOrBlank()) current[keys[i]] = v
                }
                val salience = salienceOf(prev, current)
                prev = current
                ProactiveFrame(epochMs = clock(), signals = current, salienceHint = salience)
            }.collect { emit(it) }
        }
    }

    private fun salienceOf(prev: Map<String, String>?, current: Map<String, String>): Int {
        // First frame: everything present counts as changed. Otherwise diff the two snapshots.
        val changed: Set<String> =
            if (prev == null) current.keys.toSet()
            else (prev.keys + current.keys).filterTo(HashSet()) { prev[it] != current[it] }
        if (changed.isEmpty()) return 0
        val base = minOf(100, changed.size * perSignalWeight)
        val salience = if (changed.any { it in criticalKeys }) maxOf(base, criticalBoost) else base
        return salience.coerceIn(0, 100)
    }
}
