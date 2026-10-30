package local.phantom.companion.core.data.patterns

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 3 W4-7 — persistent pattern observed by [PatternDetector].
 *
 * Each row represents «PHANTOM noticed event of trigger_kind +
 * trigger_value happening N times in the last 7 days». Once
 * `occurrence_count` crosses the surface threshold (3 by default
 * per the prompt's «помітив: щовівторка о 9:00 ти готуєш каву» line)
 * the detector surfaces it as an InsightCard in Stream; the operator
 * can dismiss it («не зараз»), accept it as an automation seed
 * («так, нагадай»), or hush the pattern altogether («не пиши про
 * це більше» → flips `suppressed` to true).
 *
 * @param triggerKind  closed enum string: 'time' / 'app' / 'geo' /
 *                     'intent'
 * @param triggerValue trigger-specific JSON or normalised key (e.g.
 *                     `"tue@09"` for a Tuesday-9am time pattern,
 *                     `"com.spotify.music"` for an app-launch
 *                     pattern, `"grid:50.45,30.52"` for a geo
 *                     pattern). Stored as a string so the detector
 *                     stays agnostic to the kind's internal format.
 * @param suggestedAction operator-visible Ukrainian phrasing that
 *                     the InsightCard renders («нагадати про каву»).
 * @param confidenceScore 0..1 — currently
 *                     `min(1.0, occurrence_count / 5.0)`; future
 *                     phases can layer time-decay or sensor weight.
 * @param occurrenceCount monotonic counter of bucket hits; rolling
 *                     7-day window is enforced by the detector via
 *                     a TTL pass on `last_observed_at`.
 * @param lastObservedAt epoch-ms of the most recent ingest into
 *                     this bucket; the staleness pass deletes rows
 *                     where `now - last_observed_at > 7d`.
 * @param suppressed   operator hushed this pattern via «не пиши про
 *                     це більше». Detector skips suppressed buckets
 *                     when surfacing — they keep counting silently
 *                     so a future un-suppress shows accurate history.
 */
@Entity(
    tableName = "patterns",
    indices = [
        Index(value = ["trigger_kind", "trigger_value"], unique = true, name = "idx_patterns_trigger"),
        Index(value = ["last_observed_at"], name = "idx_patterns_last_observed"),
    ],
)
data class PatternEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "trigger_kind")
    val triggerKind: String,

    @ColumnInfo(name = "trigger_value")
    val triggerValue: String,

    @ColumnInfo(name = "suggested_action")
    val suggestedAction: String,

    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Double,

    @ColumnInfo(name = "occurrence_count", defaultValue = "0")
    val occurrenceCount: Int = 0,

    @ColumnInfo(name = "last_observed_at")
    val lastObservedAt: Long,

    @ColumnInfo(name = "suppressed", defaultValue = "0")
    val suppressed: Boolean = false,
) {
    object Kind {
        const val TIME = "time"
        const val APP = "app"
        const val GEO = "geo"
        const val INTENT = "intent"
    }
}
