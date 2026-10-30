package local.phantom.companion.core.net.map

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * NAV-Ω1 — RouteRisk: an honest 0–100 risk figure per route variant,
 * built ONLY from data already fetched (RouteIntel corridor report +
 * alert crossings). Rules of honesty (plan §3.2):
 *
 *  - a component with no data contributes nothing and does not appear
 *    in [RouteRiskReport.factors] — absence of data ≠ absence of risk;
 *  - when there is no basis at all (unusable corridor AND no alert
 *    crossings) the assessment is null, never a confident-looking 0;
 *  - every point of the score is explained by a factor line — the UI
 *    must not render a bare number.
 */
data class RouteRiskReport(
    /** 0..100, higher = riskier. Capped sum of the factor contributions. */
    val score: Int,
    /** Ukrainian factor label → points it contributed, descending. */
    val factors: List<Pair<String, Int>>,
)

/** Risk tiers for tinting; thresholds shared by sheet and tool copy. */
enum class RiskTier { LOW, ELEVATED, HIGH }

fun riskTier(score: Int): RiskTier = when {
    score < 25 -> RiskTier.LOW
    score < 55 -> RiskTier.ELEVATED
    else -> RiskTier.HIGH
}

/**
 * Assess one variant. [intel] may be null or under-covered (both mean
 * «corridor unknown»); [alertCrossings] is the count of active-alert
 * oblasts the route crosses (0 is a real observed fact when the alert
 * feed is up — pass it as such). Null = no honest basis to score.
 */
fun assessRouteRisk(
    intel: RouteIntelReport?,
    alertCrossings: Int,
    profile: RouteProfile,
): RouteRiskReport? {
    val corridor = intel?.takeIf { it.coverage >= RISK_MIN_COVERAGE }
    if (corridor == null && alertCrossings <= 0) return null

    val factors = mutableListOf<Pair<String, Int>>()

    if (alertCrossings > 0) {
        val pts = min(30 + (alertCrossings - 1) * 15, 45)
        factors += "перетинає тривогу ×$alertCrossings" to pts
    }

    if (corridor != null) {
        // Cameras matter to the driver/rider; a walker passes them freely.
        if (profile != RouteProfile.FOOT && corridor.cameras.isNotEmpty()) {
            factors += "камери: ${corridor.cameras.size}" to min(corridor.cameras.size * 4, 16)
        }
        corridor.litFraction?.let { lit ->
            val unlit = 1.0 - lit
            if (unlit > 0.05) {
                factors += "неосвітлено ${(unlit * 100).roundToInt()}%" to (unlit * 15).roundToInt()
            }
        }
        corridor.surfaceShares.firstOrNull { it.first == "ґрунт/гравій" }?.let { (_, share) ->
            if (share > 0.05) {
                factors += "ґрунт ${(share * 100).roundToInt()}%" to (share * 12).roundToInt()
            }
        }
        if (corridor.barriers.isNotEmpty()) {
            val privateBarriers = corridor.barriers.count { it.access == "private" || it.access == "no" }
            val label = if (privateBarriers > 0) "шлагбауми/ворота: ${corridor.barriers.size} ($privateBarriers приват.)"
                        else "шлагбауми/барʼєри: ${corridor.barriers.size}"
            factors += label to min(corridor.barriers.size * 4 + privateBarriers * 3, 16)
        }
    }

    return RouteRiskReport(
        score = min(factors.sumOf { it.second }, 100),
        factors = factors.sortedByDescending { it.second },
    )
}

/**
 * NAV-Ω1 — «тихий хід» (ghost route): the least-surveilled variant.
 * Surveillance proxy = speed/enforcement cameras (dominant) + magistral
 * share (main arteries carry the cameras and the patrols). Honest gate:
 * null unless there are ≥2 variants and EVERY one has a usable corridor
 * report — picking «quietest» while blind to half the field would be a
 * guess dressed as a choice.
 */
fun ghostVariantIndex(variants: List<Pair<RouteVariant, RouteIntelReport?>>): Int? {
    if (variants.size < 2) return null
    val usable = variants.map { (_, r) -> r?.takeIf { it.coverage >= RISK_MIN_COVERAGE } }
    if (usable.any { it == null }) return null
    val scored = variants.mapIndexed { i, (v, _) ->
        val r = usable[i]!!
        val magistral = r.classShares.firstOrNull { it.first == "магістраль" }?.second ?: 0.0
        Triple(i, r.cameras.size * 10.0 + magistral * 5.0, v.durationSeconds)
    }
    return scored.sortedWith(compareBy({ it.second }, { it.third })).first().first
}

/** Same coverage gate as the intel display — below it, shares are noise. */
const val RISK_MIN_COVERAGE = 0.33
