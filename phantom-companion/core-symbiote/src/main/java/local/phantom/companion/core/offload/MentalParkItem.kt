package local.phantom.companion.core.offload

/**
 * Tier 23-A — a single unparked idea. `ripeness` is the 0..100 score
 * the park uses to decide whether phantom should surface this item
 * during a low-cognitive-load window. Stored as Int so the field can
 * cross to `:core-data` without precision drift.
 */
data class MentalParkItem(
    val id: String,
    val text: String,
    val createdAtMs: Long,
    val lastTouchedMs: Long = createdAtMs,
    val status: Status = Status.LIVE,
    val ripeness: Int = 0,
    val tags: Set<String> = emptySet(),
) {
    enum class Status { LIVE, ARCHIVED, FORGOTTEN }

    val isActive: Boolean get() = status == Status.LIVE
}
