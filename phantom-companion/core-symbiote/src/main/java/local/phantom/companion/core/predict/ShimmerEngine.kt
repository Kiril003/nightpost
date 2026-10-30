package local.phantom.companion.core.predict

import kotlin.math.max

/**
 * Tier 15-C — Markov-chain over (state, hour, location-bucket,
 * last-N-actions). The engine keeps a transition table and exposes
 * a `suggest()` that returns the most-likely next action *only* if
 * its probability passes [shimmerThreshold]. That gating is the
 * difference between "you might want X" and "we promised never to
 * nag" — under the threshold, the shimmer stays invisible.
 */
class ShimmerEngine(
    private val shimmerThreshold: Double = DEFAULT_THRESHOLD,
) {

    data class Bucket(
        val state: String,
        val hour: Int,
        val location: String,
        val lastAction: String?,
    ) {
        init {
            require(hour in 0..23) { "Hour must be 0..23, got $hour" }
        }
    }

    private val counts: MutableMap<Bucket, MutableMap<String, Int>> = mutableMapOf()
    private val totals: MutableMap<Bucket, Int> = mutableMapOf()

    fun observe(bucket: Bucket, nextAction: String) {
        val row = counts.getOrPut(bucket) { mutableMapOf() }
        row[nextAction] = (row[nextAction] ?: 0) + 1
        totals[bucket] = (totals[bucket] ?: 0) + 1
    }

    fun probability(bucket: Bucket, action: String): Double {
        val total = totals[bucket] ?: return 0.0
        val hit = counts[bucket]?.get(action) ?: return 0.0
        return hit.toDouble() / max(total, 1)
    }

    fun suggest(bucket: Bucket): Suggestion? {
        val row = counts[bucket] ?: return null
        val total = totals[bucket] ?: return null
        if (total == 0) return null
        val best = row.maxByOrNull { it.value } ?: return null
        val p = best.value.toDouble() / total
        return if (p >= shimmerThreshold) Suggestion(best.key, p) else null
    }

    data class Suggestion(val action: String, val probability: Double)

    companion object {
        /** Vision text says 0.4. */
        const val DEFAULT_THRESHOLD = 0.4
    }
}
