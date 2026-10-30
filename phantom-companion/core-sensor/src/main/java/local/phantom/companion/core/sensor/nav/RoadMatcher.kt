package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.exp

/**
 * ATLAS-2 — the third authority. [SpoofGate] judges physics, [DriftAudit]
 * judges the operator's own body; this judges *topology*: a position is more
 * believable when it lies on a road, connected by road to where the operator
 * just was. It is the classic online HMM map-matcher (Newson & Krumm 2009),
 * kept honest three ways:
 *
 *  - **Emission** — a candidate's likelihood falls as a Gaussian in its
 *    perpendicular distance, σ floored at [SIGMA_FLOOR_M] so a chip
 *    over-reporting its own accuracy cannot make the matcher cocky.
 *  - **Transition** — consecutive candidates are scored on how well their
 *    on-network distance agrees with the great-circle distance actually
 *    travelled. A candidate across the river scores near zero even when it
 *    is geometrically close — this is the whole advantage over naive
 *    nearest-road snapping.
 *  - **Refusal** — a fix beyond 2.5σ of every road returns null. Courtyards,
 *    parks, and fields exist; "off the network" is an answer, not a failure.
 *
 * The output is ADVISORY. It moves the *displayed* dot when confident; it
 * never rewrites the fused fix, and it does not (yet) testify to SpoofGate —
 * that promotion is a device-session decision (ATLAS masterplan §7).
 *
 * Pure Kotlin; the graph arrives from outside (Overpass-backed cache in the
 * app layer, fixtures in tests).
 */
class RoadMatcher {

    data class Match(
        val lat: Double,
        val lon: Double,
        val wayId: Long,
        val wayName: String?,
        /** Travel heading along the matched way, oriented by the fix bearing. */
        val headingDeg: Float,
        val perpM: Double,
        /** Posterior weight of the winning hypothesis, 0..1. */
        val confidence: Float,
    )

    private class Hypothesis(val cand: RoadGraph.Candidate, var logP: Double)

    private var hypotheses: List<Hypothesis> = emptyList()
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastFixMs = 0L

    /**
     * The graph the surviving [hypotheses] were computed against. A candidate
     * holds an index into its own graph, so carrying hypotheses across a
     * refetch pointed them at ways that no longer exist — an
     * IndexOutOfBounds on every fix once the new neighbourhood was smaller.
     */
    private var lastGraph: RoadGraph? = null

    fun reset() {
        hypotheses = emptyList()
        lastGraph = null
        lastFixMs = 0L
    }

    /**
     * One fused fix against [graph]. Call at fix rate (~1 Hz); a gap longer
     * than [CHAIN_BREAK_MS] starts a fresh chain — inference across a tunnel
     * would be inference over evidence that does not exist.
     */
    fun onFix(
        graph: RoadGraph,
        lat: Double,
        lon: Double,
        accuracyM: Float,
        bearingDeg: Float?,
        nowMs: Long,
    ): Match? {
        if (lastFixMs != 0L && nowMs - lastFixMs > CHAIN_BREAK_MS) reset()
        // A new graph invalidates every surviving hypothesis: their indices
        // addressed the old one. Start a fresh chain rather than compare
        // across two coordinate spaces.
        if (lastGraph !== graph) {
            hypotheses = emptyList()
            lastGraph = graph
        }

        val sigma = accuracyM.toDouble().coerceAtLeast(SIGMA_FLOOR_M)
        val radius = (SEARCH_SIGMA_MULT * sigma).coerceAtLeast(MIN_SEARCH_RADIUS_M)
        val cands = graph.near(lat, lon, radius, MAX_HYPOTHESES)
        if (cands.isEmpty()) {
            // Off the known network. The chain survives one such fix (a bad
            // sample mid-street happens); two in a row mean it is real.
            hypotheses = emptyList()
            lastFixMs = nowMs
            lastLat = lat
            lastLon = lon
            return null
        }

        val gcM = if (hypotheses.isEmpty()) 0.0 else haversineM(lastLat, lastLon, lat, lon)

        val next = cands.map { cand ->
            val emission = -0.5 * (cand.perpM / sigma) * (cand.perpM / sigma)
            val headingPenalty = headingPenalty(cand, bearingDeg)
            val logP = if (hypotheses.isEmpty()) {
                emission + headingPenalty
            } else {
                var best = -Double.MAX_VALUE
                for (h in hypotheses) {
                    val dPath = graph.pathDistanceM(h.cand, cand)
                    val transition = -abs(dPath - gcM) / BETA_M
                    val p = h.logP + transition
                    if (p > best) best = p
                }
                best + emission + headingPenalty
            }
            Hypothesis(cand, logP)
        }

        // Normalize so the chain never sinks into float underflow.
        val maxLog = next.maxOf { it.logP }
        next.forEach { it.logP -= maxLog }

        hypotheses = next.sortedByDescending { it.logP }.take(MAX_HYPOTHESES)
        lastLat = lat
        lastLon = lon
        lastFixMs = nowMs

        val best = hypotheses.first()
        if (best.cand.perpM > REFUSE_SIGMA_MULT * sigma) return null

        // Posterior of the winner among the survivors.
        val sum = hypotheses.sumOf { exp(it.logP) }
        val confidence = (exp(best.logP) / sum).toFloat()

        return Match(
            lat = best.cand.lat,
            lon = best.cand.lon,
            wayId = best.cand.way.id,
            wayName = best.cand.way.name,
            headingDeg = travelHeading(best.cand, bearingDeg),
            perpM = best.cand.perpM,
            confidence = confidence,
        )
    }

    /**
     * A candidate whose road runs crosswise to the operator's travel is
     * suspect — but only when the operator is moving fast enough for the fix
     * bearing to mean anything, and never as a hard veto (one-way service
     * roads meet avenues at odd angles).
     */
    private fun headingPenalty(cand: RoadGraph.Candidate, bearingDeg: Float?): Double {
        if (bearingDeg == null) return 0.0
        val diff = angleDiff(cand.headingDeg, bearingDeg)
        // Bidirectional ways can be travelled both directions; take the
        // closer orientation unless the way is oneway.
        val effective = if (cand.way.oneway) diff else minOf(diff, 180f - diff)
        return if (effective > HEADING_SOFT_LIMIT_DEG) -(effective - HEADING_SOFT_LIMIT_DEG) / HEADING_SCALE_DEG else 0.0
    }

    /** The way's heading flipped, if needed, to run with the operator. */
    private fun travelHeading(cand: RoadGraph.Candidate, bearingDeg: Float?): Float {
        if (bearingDeg == null || cand.way.oneway) return cand.headingDeg
        val forward = angleDiff(cand.headingDeg, bearingDeg)
        val backward = angleDiff((cand.headingDeg + 180f) % 360f, bearingDeg)
        return if (backward < forward) (cand.headingDeg + 180f) % 360f else cand.headingDeg
    }

    private fun angleDiff(a: Float, b: Float): Float =
        abs(((a - b + 540f) % 360f) - 180f)

    companion object {
        /** Newson–Krumm's β, tuned for 1 Hz urban fixes. */
        private const val BETA_M = 15.0

        private const val SIGMA_FLOOR_M = 8.0
        private const val SEARCH_SIGMA_MULT = 2.0
        private const val MIN_SEARCH_RADIUS_M = 35.0
        private const val REFUSE_SIGMA_MULT = 2.5
        private const val MAX_HYPOTHESES = 8
        private const val CHAIN_BREAK_MS = 10_000L

        private const val HEADING_SOFT_LIMIT_DEG = 45f
        private const val HEADING_SCALE_DEG = 25.0
    }
}
