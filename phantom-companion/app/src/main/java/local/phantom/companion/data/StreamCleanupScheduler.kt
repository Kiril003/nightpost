package local.phantom.companion.data

import local.phantom.companion.core.data.stream.ChatDecay
import local.phantom.companion.core.data.stream.StreamRepository

/**
 * W2-5 — daily TTL cleanup for the persisted `stream_items` table.
 *
 * Pre-DA-5 this class also ran its own coroutine loop. DA-5 ripped
 * the loop out: scheduling is now WorkManager's job
 * ([StreamCleanupWorker] + [WorkScheduling.cleanupRequest]). What
 * remains here is the gating logic, which both the Worker AND the
 * Settings → Діагностика «очистити зараз» button hit through the
 * same [runOnceIfDue] entry point.
 *
 * Behaviour:
 *   - retentionDays = 0 short-circuits (operator opted out).
 *   - Otherwise check `prefs.lastCleanupAtMs` — if more than 24 h
 *     has passed (or never ran), invoke
 *     [StreamRepository.cleanupBefore] with `now - retentionDays *
 *     86_400_000L` and mark the run.
 */
class StreamCleanupScheduler(
    private val repository: StreamRepository,
    /** Live retention setting in days. 0 means «cleanup disabled». */
    private val retentionDaysProvider: () -> Int,
    /** Reads/writes the «last run» timestamp via the prefs layer. */
    private val lastRunMsGetter: () -> Long,
    private val lastRunMsSetter: (Long) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Convenience constructor for AppContainer's wiring path —
     * threads the [VoicePreferences] callables through automatically.
     */
    constructor(
        repository: StreamRepository,
        voicePrefs: VoicePreferences,
        nowMs: () -> Long = System::currentTimeMillis,
    ) : this(
        repository = repository,
        retentionDaysProvider = { voicePrefs.cleanupAfterDays.value },
        lastRunMsGetter = { voicePrefs.lastCleanupAtMs() },
        lastRunMsSetter = { ts -> voicePrefs.markCleanupRan(ts) },
        nowMs = nowMs,
    )

    /**
     * Run a single cleanup pass *if* it's been more than 24 h since
     * the last run. Public so a Settings → Діагностика «очистити
     * зараз» button can force-fire it AND so [StreamCleanupWorker]
     * can drive it on the WorkManager schedule.
     */
    suspend fun runOnceIfDue(force: Boolean = false): Int {
        val days = retentionDaysProvider()
        val now = nowMs()
        val lastRun = lastRunMsGetter()
        if (!force && (now - lastRun) < ONE_DAY_MS) return 0
        // «Назавжди» (days <= 0) stops deleting the operator's content — but the
        // plumbing trace decays on its own fixed 21-day clock regardless, or the
        // copy that names that one exception («службовий слід зникає через 21
        // день») becomes a lie the moment someone picks forever. So the two
        // clocks are separated here: forever keeps the full plumbing window
        // instead of collapsing it to zero, and runs no content sweep at all.
        val policy = if (days <= 0) ChatDecay.Policy() else decayPolicy(days)
        val decayed = runCatching { repository.decay(now, policy) }.getOrNull()
        val deleted = if (days <= 0) {
            0
        } else {
            val cutoff = now - days * ONE_DAY_MS
            runCatching { repository.cleanupBefore(cutoff) }.getOrDefault(0)
        }
        lastRunMsSetter(now)
        return deleted + (decayed?.dropped ?: 0)
    }

    /**
     * The operator's retention is the only clock over their content. This one
     * covers plumbing, is fixed rather than derived, and is clamped so it can
     * never outlive the number they chose.
     */
    internal fun decayPolicy(retentionDays: Int): ChatDecay.Policy =
        ChatDecay.Policy(plumbingDays = ChatDecay.PLUMBING_DAYS.coerceAtMost(retentionDays))

    companion object {
        const val ONE_DAY_MS: Long = 86_400_000L
    }
}
