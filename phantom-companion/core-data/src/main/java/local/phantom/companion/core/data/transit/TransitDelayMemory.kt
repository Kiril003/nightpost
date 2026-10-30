package local.phantom.companion.core.data.transit

import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Як запізнюється лінія у цей час тижня — вивчено на пристрої з того, що
 * табло бачило живим. Нікуди не відправляється.
 */
class TransitDelayMemory(
    private val file: File?,
    private val maxKeys: Int = 4_000,
) {
    private class Stat(var meanSec: Double, var count: Int)

    private val stats = LinkedHashMap<String, Stat>(256, 0.75f, true)
    private val seenTrips = LinkedHashSet<String>()
    private var dirty = 0
    private var loaded = false

    fun observe(lineKey: String, tripKey: String?, scheduledMs: Long, delaySec: Int, zone: ZoneId) = synchronized(this) {
        load()
        if (lineKey.isBlank() || delaySec !in -600..7_200) return@synchronized
        if (tripKey != null) {
            if (!seenTrips.add(tripKey)) return@synchronized
            if (seenTrips.size > 2_000) seenTrips.remove(seenTrips.first())
        }
        val key = bucketKey(lineKey, scheduledMs, zone)
        val s = stats.getOrPut(key) { Stat(0.0, 0) }
        s.count++
        val alpha = maxOf(ALPHA, 1.0 / s.count)
        s.meanSec += alpha * (delaySec - s.meanSec)
        while (stats.size > maxKeys) stats.remove(stats.keys.first())
        if (++dirty >= FLUSH_EVERY) flush()
    }

    /** Звичне запізнення, секунди; null — бачили замало, щоб казати. */
    fun typicalDelaySec(lineKey: String, atMs: Long, zone: ZoneId): Int? = synchronized(this) {
        load()
        val s = stats[bucketKey(lineKey, atMs, zone)] ?: return@synchronized null
        if (s.count < MIN_SEEN) null else s.meanSec.toInt()
    }

    fun warm() = synchronized(this) { load() }

    fun flush() = synchronized(this) {
        val f = file ?: return@synchronized
        dirty = 0
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                stats.forEach { (k, s) -> w.append(k).append('\t').append(s.meanSec.toInt().toString()).append('\t').append(s.count.toString()).append('\n') }
            }
            tmp.renameTo(f)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file?.takeIf { it.exists() } ?: return
        runCatching {
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val mean = p[1].toDoubleOrNull() ?: return@forEachLine
                    val n = p[2].toIntOrNull() ?: return@forEachLine
                    stats[p[0]] = Stat(mean, n)
                }
            }
        }
    }

    companion object {
        /** Одна памʼять на процес; застосунок підміняє її файловою при старті. */
        @Volatile
        var shared: TransitDelayMemory = TransitDelayMemory(null)

        const val MIN_SEEN = 3
        private const val ALPHA = 0.2
        private const val FLUSH_EVERY = 12

        fun lineKey(label: String, mode: String, operator: String?): String =
            "${mode.lowercase()}|${label.trim().lowercase()}|${operator?.trim()?.lowercase().orEmpty()}"

        internal fun bucketKey(lineKey: String, atMs: Long, zone: ZoneId): String {
            val t = Instant.ofEpochMilli(atMs).atZone(zone)
            val day = when (t.dayOfWeek) {
                DayOfWeek.SATURDAY -> "sat"
                DayOfWeek.SUNDAY -> "sun"
                else -> "wd"
            }
            return "$lineKey|$day|${t.hour}"
        }
    }
}
