package local.phantom.companion.core.nav

/**
 * ATLAS-2.5 §12 — the vocabulary [RouteCoastingEngine] speaks. The wiring
 * contract the next wave implements lives in that file's header; this one
 * holds only the types it passes across the seam.
 */

/** Which of the four things the engine is currently doing. */
enum class CoastMode { NORMAL, COASTING, MERGING, EXPIRED }

/** The honest trust badge — what the driver is told the puck currently is. */
enum class CoastBadge { LIVE, COASTING, MERGING, WAITING }

/** One believed measurement handed to [RouteCoastingEngine] (contract §3). */
data class CoastFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val bearingDeg: Double? = null,
)

/** A position the engine is responsible for, and everything needed to doubt it. */
data class CoastedFix(
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double,
    val speedMps: Double,
    /** 1.0 at the last measurement, 0.0 at the cap that freezes the puck. */
    val confidence: Float,
    val mode: CoastMode,
    /** Metres fabricated along the corridor since the last measurement. */
    val coastedM: Double,
    val coastedMs: Long,
    /** Radius to draw around it — it grows with every fabricated metre. */
    val uncertaintyM: Double,
) {
    /**
     * Contract §6 — navigation-grade so guidance keeps working through the
     * blackout, never trusted so off-route, reroute and arrival stay frozen.
     * That pair is exactly what [GuidanceSession.onFix] documents as dead
     * reckoning doing its job.
     */
    fun toNavFix(gait: NavGait = NavGait.UNKNOWN) = NavFix(
        lat = lat,
        lon = lon,
        speedMps = speedMps.toFloat(),
        bearingDeg = bearingDeg.toFloat(),
        navigationGrade = true,
        trusted = false,
        accuracyM = uncertaintyM.toFloat(),
        gait = gait,
    )
}

/** One tick's verdict. [puck] is non-null ONLY while the engine owns the puck. */
data class CoastState(
    val mode: CoastMode,
    val puck: CoastedFix?,
    val badge: CoastBadge,
    /** The returning fix was off the corridor — guidance's own reroute owns it. */
    val offRouteOnReturn: Boolean = false,
    /** The gap was handed over at once instead of being slid across. */
    val hardResync: Boolean = false,
)

/** Ukrainian badge text. Moves to `values/` when a UI module owns this (contract §9). */
fun coastBadgeUa(badge: CoastBadge): String? = when (badge) {
    CoastBadge.LIVE -> null
    CoastBadge.COASTING -> "GPS немає — веду по маршруту"
    CoastBadge.MERGING -> "Сигнал повернувся — вирівнююсь"
    CoastBadge.WAITING -> "Веду наосліп надто довго — чекаю сигнал"
}

/** English badge text. Moves to `values-en/` alongside [coastBadgeUa]. */
fun coastBadgeEn(badge: CoastBadge): String? = when (badge) {
    CoastBadge.LIVE -> null
    CoastBadge.COASTING -> "No GPS — following the route"
    CoastBadge.MERGING -> "Signal back — realigning"
    CoastBadge.WAITING -> "Blind too long — waiting for signal"
}
