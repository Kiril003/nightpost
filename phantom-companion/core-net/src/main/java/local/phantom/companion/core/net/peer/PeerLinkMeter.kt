package local.phantom.companion.core.net.peer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Яка зараз дорога до сховка. Оцінка обертання — як у TCP (RFC 6298: SRTT,
 * RTTVAR, RTO), плюс частка провалів серед останніх подій. З неї — строки, що
 * ростуть разом з ефіром, і [narrow]: «слабкий звʼязок — текст іде першим».
 *
 * Провал — це не втрачений пакет (його латає TCP), а те, що бачить застосунок:
 * квитанція не прийшла вчасно, набір не встиг, сокет замовк, запит упав.
 */
class PeerLinkMeter(private val nowMs: () -> Long = System::currentTimeMillis) {

    @Volatile var srttMs: Long? = null
        private set

    @Volatile var rttvarMs: Long = 0L
        private set

    private val outcomes = ArrayDeque<Boolean>()
    private val recent = ArrayDeque<Long>()
    private var lastBadMs = Long.MIN_VALUE / 2
    private val lock = Any()

    private val _narrow = MutableStateFlow(false)
    val narrowState: StateFlow<Boolean> = _narrow.asStateFlow()
    val narrow: Boolean get() = _narrow.value

    fun rtt(sampleMs: Long) {
        if (sampleMs < 0) return
        synchronized(lock) {
            val s = srttMs
            if (s == null) {
                srttMs = sampleMs
                rttvarMs = sampleMs / 2
            } else {
                rttvarMs = (3 * rttvarMs + kotlin.math.abs(s - sampleMs)) / 4
                srttMs = (7 * s + sampleMs) / 8
            }
            recent.addLast(sampleMs)
            while (recent.size > RECENT) recent.removeFirst()
            if (sampleMs >= NARROW_RTT_MS) lastBadMs = nowMs()
            noteLocked(true)
        }
    }

    fun ok() = synchronized(lock) { noteLocked(true) }

    fun fail() = synchronized(lock) {
        lastBadMs = nowMs()
        noteLocked(false)
    }

    /** Строк повтору, як у TCP; без жодного виміру — обережні 3 с, не оптимістична 1. */
    val rtoMs: Long
        get() {
            val s = srttMs ?: return INITIAL_RTO_MS
            return (s + maxOf(MIN_VAR_MS, 4 * rttvarMs)).coerceIn(MIN_RTO_MS, MAX_RTO_MS)
        }

    /** Скільки чекати відповіді на кадр розміром [bytes]: два RTO плюс ефір найповільнішої смуги. */
    fun replyTimeoutMs(bytes: Int, floorMs: Long): Long =
        (2 * rtoMs + bytes * 1000L / SLOW_BYTES_PER_S).coerceIn(floorMs, MAX_WAIT_MS)

    /** Набір з нуля: TCP, TLS, апгрейд, підписка — пʼять обертань і 4 КБ ефіру. */
    fun dialTimeoutMs(): Long = (5 * rtoMs + DIAL_BYTES * 1000L / SLOW_BYTES_PER_S).coerceIn(MIN_DIAL_MS, MAX_WAIT_MS)

    /** Перевірити вихід із вузького режиму й без нових подій — час теж свідок. */
    fun recheck() = synchronized(lock) { decideLocked() }

    private fun noteLocked(good: Boolean) {
        outcomes.addLast(good)
        while (outcomes.size > WINDOW) outcomes.removeFirst()
        decideLocked()
    }

    private fun decideLocked() {
        // SRTT з вагою 1/8 повзе за ефіром вісім вимірів, а виміри рідкі (лист,
        // підписка, пінг раз на дві хвилини). Медіана трьох останніх ловить
        // підвал із першого ж листа й не здригається від одного викиду.
        val median = recent.sorted().let { if (it.size >= 2) it[it.size / 2] else 0L }
        val slow = (srttMs ?: 0L) >= NARROW_RTT_MS || median >= NARROW_RTT_MS
        val failing = outcomes.count { !it } >= NARROW_FAILS
        val bad = slow || failing
        val now = nowMs()
        _narrow.value = when {
            bad -> true
            // Вихід — лише після хвилини без жодної прикмети: підвал мигає.
            _narrow.value -> now - lastBadMs < EXIT_HOLD_MS || (srttMs ?: 0L) >= CALM_RTT_MS
            else -> false
        }
    }

    companion object {
        const val INITIAL_RTO_MS = 3_000L
        const val MIN_RTO_MS = 1_000L
        const val MAX_RTO_MS = 30_000L
        const val MIN_VAR_MS = 200L
        const val MAX_WAIT_MS = 120_000L
        const val MIN_DIAL_MS = 20_000L
        const val DIAL_BYTES = 4_096

        /** 20 кбіт/с — нижній край «підвалу». */
        const val SLOW_BYTES_PER_S = 2_500

        const val NARROW_RTT_MS = 1_000L
        const val CALM_RTT_MS = 700L
        const val WINDOW = 10
        const val RECENT = 3
        const val NARROW_FAILS = 2
        const val EXIT_HOLD_MS = 60_000L
    }
}
