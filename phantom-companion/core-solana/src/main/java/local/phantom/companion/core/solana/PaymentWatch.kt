package local.phantom.companion.core.solana

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Перевірки, на які мережа ще не відповіла остаточно. Купюра може прийти без
 * інтернету й чекати на нього годинами, тож перевірка не вмирає після першого
 * сплеску спроб: далі рідко, з паузами до [MAX_PAUSE_MS], і одразу — коли
 * повернулась мережа ([kick]) або людина відкрила картку ([watch]).
 */
class PaymentWatch(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChange: () -> Unit,
) {
    private class Entry(val bornMs: Long, val maxAgeMs: Long, val probe: suspend () -> PaymentStatus) {
        var job: Job? = null
    }

    private val statuses = ConcurrentHashMap<String, PaymentStatus>()
    private val waiting = ConcurrentHashMap<String, Entry>()

    fun status(key: String): PaymentStatus? = statuses[key]

    fun statusBySignature(signature: String): PaymentStatus? =
        statuses.entries.firstOrNull { it.key.substringBefore('|') == signature }?.value

    fun mark(key: String, status: PaymentStatus) {
        statuses[key] = status
    }

    val waitingCount: Int get() = waiting.size

    fun watch(key: String, maxAgeMs: Long, probe: suspend () -> PaymentStatus) {
        if (statuses[key]?.settled == true) return
        val e = waiting.computeIfAbsent(key) { Entry(now(), maxAgeMs, probe) }
        synchronized(e) {
            if (e.job?.isActive == true) return
            e.job = scope.launch { run(key, e) }
        }
    }

    fun kick() {
        for ((key, e) in waiting) {
            if (now() - e.bornMs > e.maxAgeMs) continue
            synchronized(e) {
                e.job?.cancel()
                e.job = scope.launch { run(key, e) }
            }
        }
    }

    private suspend fun run(key: String, e: Entry) {
        var pause = FIRST_PAUSE_MS
        var attempt = 0
        while (true) {
            val s = runCatching { e.probe() }.getOrDefault(PaymentStatus.UNKNOWN)
            if (s != PaymentStatus.UNKNOWN && statuses.put(key, s) != s) onChange()
            if (s.settled) {
                waiting.remove(key)
                return
            }
            if (now() - e.bornMs > e.maxAgeMs) return
            attempt++
            when {
                attempt < 4 -> delay(3_000L)
                attempt < BURST -> delay(15_000L)
                else -> {
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(MAX_PAUSE_MS)
                }
            }
        }
    }

    companion object {
        const val BURST = 12
        const val FIRST_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 30 * 60_000L
        /** Звичайний переказ або є в мережі за хвилини, або його вже не буде; перевіряти чужий підпис можна й пізніше. */
        const val PAY_MAX_AGE_MS = 7L * 24 * 3600_000L
        /** Купюра на durable nonce не старіє: її погасять, коли хтось вийде в мережу. */
        const val CASH_MAX_AGE_MS = 30L * 24 * 3600_000L
    }
}
