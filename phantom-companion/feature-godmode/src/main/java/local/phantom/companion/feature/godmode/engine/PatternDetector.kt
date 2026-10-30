package local.phantom.companion.feature.godmode.engine

import local.phantom.companion.core.data.patterns.PatternEntity
import local.phantom.companion.core.data.patterns.PatternRepository
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Phase 3 W4-7 — Tasker pattern detector light.
 *
 * Promotes v1 [TaskerEngine]'s in-memory hard-coded Waze branch into
 * a Room-backed observation pipeline. Every operator-driven event
 * the engine cares about (timer creation with a noun trigger,
 * app-launch transitions, geo dwell) flows through [observe] which
 * normalises a `(triggerKind, triggerValue)` bucket key, upserts the
 * occurrence count + timestamp into [PatternRepository], and asks
 * the repo for any patterns that crossed the surface threshold (3
 * within the rolling 7-day window).
 *
 * The detector itself is pure-Kotlin; the Android-touching event
 * sources (TaskerEngine subscribing to GodModeEventBus) plug
 * orthogonally. Tests drive the detector with synthetic
 * [PatternEvent]s + a fake [PatternRepository] to verify threshold
 * logic, suppression, and the per-week roll-off.
 */
class PatternDetector(
    private val repository: PatternRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val surfaceThreshold: Int = SURFACE_THRESHOLD,
    private val rollingWindowMs: Long = ROLLING_WINDOW_MS,
) {

    /**
     * Single observation tick — call from the engine's
     * GodModeEventBus / Stream-Tasker callbacks. Returns any pattern
     * whose count just crossed the surface threshold (caller
     * surfaces it as an InsightCard); returns `null` for buckets
     * still below threshold or already surfaced + suppressed.
     */
    suspend fun observe(event: PatternEvent): PatternEntity? {
        val now = event.atMs ?: nowMs()
        val triggerValue = event.normaliseKey()
        val existing = repository.findByTrigger(event.kind, triggerValue)
        val patternId: String
        val newOccurrenceCount: Int
        if (existing == null) {
            patternId = UUID.randomUUID().toString()
            newOccurrenceCount = 1
            repository.insert(
                PatternEntity(
                    id = patternId,
                    triggerKind = event.kind,
                    triggerValue = triggerValue,
                    suggestedAction = event.suggestedAction,
                    confidenceScore = (1.0 / 5.0).coerceAtMost(1.0),
                    occurrenceCount = 1,
                    lastObservedAt = now,
                ),
            )
        } else {
            patternId = existing.id
            newOccurrenceCount = existing.occurrenceCount + 1
            repository.bumpOccurrence(existing.id, now)
        }
        // Stale-roll cleanup is best-effort — failure here doesn't
        // block the surface decision.
        runCatching { repository.cleanupStale(now - rollingWindowMs) }

        if (newOccurrenceCount < surfaceThreshold) return null
        // Pull the freshly-bumped row so the caller sees the
        // post-bump state (occurrenceCount + confidence + the
        // suppressed flag — which the upsert preserves).
        val row = repository.findByTrigger(event.kind, triggerValue)
        if (row == null || row.suppressed) return null
        return row
    }

    /** Mark a pattern bucket «не пиши про це більше». */
    suspend fun suppress(patternId: String) {
        repository.suppress(patternId)
    }

    /** Periodic: drop rows older than the rolling window. */
    suspend fun cleanupStale(): Int = repository.cleanupStale(nowMs() - rollingWindowMs)

    companion object {
        const val SURFACE_THRESHOLD: Int = 3
        const val ROLLING_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L

        /**
         * Build a TIME-of-day key — operator's local DOW + hour
         * («tue@09» for Tuesday 9 am). The detector treats this
         * key as the bucket id, so re-observation on a different
         * day-of-week / hour produces a different row.
         */
        fun timeKey(epochMs: Long): String {
            val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
            // ISO DOW (1=Mon..7=Sun) → 3-letter Ukrainian-ish code.
            // We use English-only short codes to keep the row stable
            // across locale changes (Ukrainian DOW formatting depends
            // on the Java Locale Calendar driver).
            val dow = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "mon"
                Calendar.TUESDAY -> "tue"
                Calendar.WEDNESDAY -> "wed"
                Calendar.THURSDAY -> "thu"
                Calendar.FRIDAY -> "fri"
                Calendar.SATURDAY -> "sat"
                Calendar.SUNDAY -> "sun"
                else -> "?"
            }
            val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
            return "$dow@$hh"
        }
    }
}

/**
 * Single observation the detector ingests. The shape stays kind-
 * agnostic so app-launch / timer / geo / intent paths share the
 * upsert plumbing.
 *
 * @param kind  one of [PatternEntity.Kind] string values
 * @param normaliseKey  bucket-key extractor. The detector calls
 *                       this once per ingest; key collisions across
 *                       events with the same (kind, key) feed the
 *                       same row.
 * @param suggestedAction operator-visible Ukrainian phrasing
 *                       («нагадати про каву») rendered on the
 *                       eventual InsightCard
 * @param atMs  optional event timestamp; defaults to `nowMs()` when
 *              null
 */
data class PatternEvent(
    val kind: String,
    val keyValue: String,
    val suggestedAction: String,
    val atMs: Long? = null,
) {
    /** Normalised key as stored in `patterns.trigger_value`. */
    fun normaliseKey(): String = keyValue
}
