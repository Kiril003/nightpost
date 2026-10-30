package local.phantom.companion.core.sensor.nav

/**
 * ATLAS §7 — the third authority's seat at the reacquisition table.
 *
 * [SpoofGate] judges physics, [DriftAudit] judges the operator's own body;
 * both have a documented blind spot: after a long blackout the inertial
 * track's admitted error grows until "an adversary who plants the operator
 * within ~2.8 km of where he actually is cannot be distinguished from the
 * truth by inertia alone" ([CustomLocationEngine.REACQUIRE_ERROR_FRACTION]).
 * The engine's own comments name the answer — map-matching — and this is
 * its interface: *topology* as evidence. A returning sky that walks a
 * CONNECTED CHAIN of matched road positions is telling a story the map
 * corroborates; a transmitter that merely plants coordinates rarely
 * bothers to make them ride a road.
 *
 * The contract, stated so it survives tuning:
 *
 *  - **The witness may vouch, never veto.** «Off the road» is a courtyard,
 *    a park, a field — normal life, not an attack. Its testimony can only
 *    ACCELERATE acceptance of a returning sky (to DEGRADED, spoken out
 *    loud), never extend a lockout. The Kyiv drive (277 refusals of an
 *    honest sky over 4 h) is the failure this asymmetry guards.
 *  - **Honest limit:** an adversary who forges a road-coherent trajectory
 *    defeats it — the same limit the two-consistent-fixes rule already
 *    accepts and records. Corroboration yields DEGRADED, never TRUSTED.
 *
 * DORMANT until the Re-Drive QA passes: nothing constructs
 * [CustomLocationEngine] with a witness yet. Activation is one wiring
 * change in the app layer, made deliberately, after the field says the
 * baseline holds.
 */
fun interface RoadWitness {
    /**
     * One returning fix, straight from the chip (judged, not fused). Called
     * at fix rate while the engine is weighing a reacquisition; every call
     * feeds the witness's chain, including the ones the engine then refuses.
     * Null = the witness has no graph here and declines to testify.
     */
    fun testify(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        bearingDeg: Float?,
        nowMs: Long,
    ): RoadTestimony?
}

/** The road network's verdict on one returning fix. */
data class RoadTestimony(
    /** The fix lies on the known network (within the matcher's refusal bound). */
    val onRoad: Boolean,
    /** Posterior of the winning hypothesis, 0..1 — [RoadMatcher.Match.confidence]. */
    val confidence: Float,
    /** Consecutive fixes this chain has matched, including this one. */
    val chainLength: Int,
)

/**
 * The production witness: [RoadMatcher]'s HMM over whatever [RoadGraph] the
 * app layer can supply (Overpass tiles via RoadTruth, offline fixtures in
 * tests). Pure and clock-free like everything else in this package — the
 * graph provider owns its own staleness.
 */
class MatcherRoadWitness(
    /** Null = no tiles for this neighbourhood; the witness stands down. */
    private val graph: () -> RoadGraph?,
    private val matcher: RoadMatcher = RoadMatcher(),
) : RoadWitness {

    private var chain = 0
    private var lastMs = 0L

    override fun testify(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        bearingDeg: Float?,
        nowMs: Long,
    ): RoadTestimony? {
        val g = graph() ?: return null
        // A gap breaks the story: inference across it would be inference
        // over evidence that does not exist (same rule as the matcher's own
        // chain break, mirrored here for the count).
        if (lastMs != 0L && nowMs - lastMs > CHAIN_BREAK_MS) chain = 0
        lastMs = nowMs

        val match = matcher.onFix(g, lat, lon, accuracyM, bearingDeg, nowMs)
        if (match == null) {
            chain = 0
            return RoadTestimony(onRoad = false, confidence = 0f, chainLength = 0)
        }
        chain++
        return RoadTestimony(onRoad = true, confidence = match.confidence, chainLength = chain)
    }

    fun reset() {
        matcher.reset()
        chain = 0
        lastMs = 0L
    }

    private companion object {
        /** Mirrors [RoadMatcher]'s chain break, so the count never outlives
         *  the hypotheses it is counting. */
        const val CHAIN_BREAK_MS = 10_000L
    }
}
