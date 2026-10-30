package local.phantom.companion.data

import local.phantom.companion.core.data.notifications.NotificationDigestEntity
import local.phantom.companion.core.data.notifications.NotificationDigestRepository
import java.util.Calendar

/**
 * Phase 3 W4-5 — daily 9 am digest scheduler. Drains routine
 * notifications classified by [NotificationClassifier] from the
 * `notification_digest` Room table and emits one consolidated Notice
 * line for Stream to render («ранок · вчорашня доба: 3 нові від Олі
 * · 2 нагадування · 1 email від адвоката»).
 *
 * Pre-DA-5 this class also ran its own coroutine loop. DA-5 ripped
 * the loop out: scheduling is now WorkManager's job
 * ([NotificationDigestWorker] + [WorkScheduling.digestRequest]).
 * What remains here is the gating logic — exposed via [runOnceIfDue]
 * — which both the Worker AND a Settings → Діагностика «надіслати
 * дайджест зараз» force-fire button hit through the same entry
 * point.
 *
 * Per-pass behaviour:
 *   0. Every pass, due or not: drop every row older than
 *      [PRUNE_AFTER_MS]. Це єдина межа життя рядка, і вона не
 *      залежить ні від девʼятої, ні від доставки.
 *   1. Once per local-day, when the wall-clock hour crosses 9 am
 *      (gated by [shouldEmit]), pull the last 24 h of pending
 *      routine notifications.
 *   2. Format them through [DigestFormatter], pass the resulting
 *      line to the [emitter] callback (AppContainer wires this into
 *      a StreamItem.Notice push).
 *   3. Mark the picked rows delivered.
 *
 * No-op when there are zero pending rows.
 */
class NotificationDigestScheduler(
    private val repository: NotificationDigestRepository,
    private val emitter: suspend (digestText: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Local-day hour at which the digest fires. Default 9. */
    private val emitHour: Int = DEFAULT_EMIT_HOUR,
    /** Persistent «last-emit» timestamp (defaults to in-memory). */
    private val lastEmitMsGetter: () -> Long = { lastEmitInMemory },
    private val lastEmitMsSetter: (Long) -> Unit = { lastEmitInMemory = it },
    /** Справжня назва застосунку від системи; null — назвати нічим. */
    private val appLabel: (String) -> String? = { null },
) {

    /**
     * Single check-and-emit pass. Public so a Settings →
     * Діагностика «надіслати дайджест зараз» button can force-fire
     * (pass `force = true` to bypass the once-per-day guard) AND so
     * [NotificationDigestWorker] can drive it on the WorkManager
     * schedule.
     */
    suspend fun runOnceIfDue(force: Boolean = false): Int {
        val now = nowMs()
        // До брами часу і до вибірки: рядок, який уже не потрапить у жоден
        // дайджест, не сміє чекати на девʼяту, якої може не статись.
        runCatching { repository.purgeOlderThan(now - PRUNE_AFTER_MS) }
        if (!force && !shouldEmit(now)) return 0

        val cutoff = now - LOOKBACK_MS
        val pending = runCatching { repository.pickPendingSince(cutoff) }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) {
            // Still mark today as «processed» so the scheduler
            // doesn't keep running pickPending every tick.
            lastEmitMsSetter(now)
            return 0
        }

        val digest = DigestFormatter.format(pending, appLabel)
        runCatching { emitter(digest) }
            .onSuccess {
                runCatching { repository.markDelivered(pending.map { it.id }) }
                lastEmitMsSetter(now)
            }
        return pending.size
    }

    private fun shouldEmit(now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (cal.get(Calendar.HOUR_OF_DAY) < emitHour) return false
        val lastEmit = lastEmitMsGetter()
        if (lastEmit == 0L) return true
        val lastCal = Calendar.getInstance().apply { timeInMillis = lastEmit }
        // Same Y/M/D → already emitted today.
        return cal.get(Calendar.YEAR) != lastCal.get(Calendar.YEAR) ||
            cal.get(Calendar.DAY_OF_YEAR) != lastCal.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        const val LOOKBACK_MS: Long = 24L * 60L * 60L * 1000L       // 24 h
        const val PRUNE_AFTER_MS: Long = 7L * 24L * 60L * 60L * 1000L  // 7 days
        const val DEFAULT_EMIT_HOUR: Int = 9

        @Volatile
        private var lastEmitInMemory: Long = 0L
    }
}

/**
 * Phase 3 W4-5 — pure formatter for the digest Notice line.
 * Pulled out as a separate object so the renderer logic can be
 * tested without spinning up a Room database.
 */
object DigestFormatter {

    fun format(
        rows: List<NotificationDigestEntity>,
        appLabel: (String) -> String? = { null },
    ): String {
        if (rows.isEmpty()) return "ранок · нічого нового за добу"
        val grouped = rows.groupBy { it.packageName }
        val named = grouped.entries
            .mapNotNull { (pkg, group) -> friendlySender(pkg, appLabel)?.let { it to group.size } }
            .sortedByDescending { it.second }
        val shown = named.take(MAX_GROUPS)
        val rest = rows.size - shown.sumOf { it.second }
        val tail = if (rest > 0) " · ще $rest" else ""
        if (shown.isEmpty()) return "ранок · вчорашня доба: ${rows.size} сповіщень. Розгорнути?"
        val phrases = shown.joinToString(" · ") { (name, count) -> "$count від $name" }
        return "ранок · вчорашня доба: $phrases$tail. Розгорнути?"
    }

    /**
     * Null means «нема чим це назвати» — такі групи їдуть у хвіст «ще N».
     * Раніше тут стояв хвіст імені пакета, і оператор читав «620 від music»,
     * «108 від vpn»: уламок ідентифікатора, а не назва застосунку.
     */
    private fun friendlySender(pkg: String, appLabel: (String) -> String?): String? = when {
        pkg.contains("telegram", ignoreCase = true) -> "Telegram"
        pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        pkg.contains("signal", ignoreCase = true) || pkg.contains("securesms") -> "Signal"
        pkg.contains("gm") || pkg.contains("mail") -> "пошти"
        pkg.contains("viber") -> "Viber"
        pkg.contains("messenger") -> "Messenger"
        else -> appLabel(pkg)?.takeIf { it.isNotBlank() && it != pkg }
    }

    private const val MAX_GROUPS: Int = 3
}
