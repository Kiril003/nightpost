package local.phantom.companion.core.nav

import java.util.Locale

/**
 * NAV-5 — the in-motion warning engine. Watches three honest sources as
 * the operator moves along an active route:
 *
 *  1. speed cameras from the corridor's RouteIntel report (OSM data);
 *  2. manual TTL marks (патруль/блокпост/ДТП — self-reports, never a
 *     crowd feed, see HazardMarks);
 *  3. entering an oblast whose air-raid alert is active right now.
 *
 * Pure Kotlin: threats are projected onto the route polyline once, each
 * fix is snapped and compared by along-route distance, every warning
 * speaks exactly once per threat (re-arming only if the operator passes
 * it). No Android, fully unit-tested.
 */
class ThreatWatch(
    routePoints: List<Pair<Double, Double>>,
    /** CAR gets earlier warnings than FOOT/BIKE — braking distance is real. */
    private val warnAheadM: Double,
    private val oblastAt: ((Double, Double) -> String?)? = null,
    private val alertActive: (String) -> Boolean = { false },
) {
    /** One threat projected onto the route. */
    private data class RouteThreat(
        val id: String,
        val kindUk: String,
        val alongM: Double,
    )

    sealed interface Cue {
        val spoken: String

        data class ThreatAhead(val kindUk: String, val distanceM: Int) : Cue {
            override val spoken: String
                get() = "Попереду за ${spokenDistanceUk(distanceM)} — $kindUk."
        }

        data class AlertZoneEntered(val oblast: String) : Cue {
            override val spoken: String
                get() = "Увага: вʼїжджаєте у $oblast — там зараз повітряна тривога."
        }
    }

    private val points = routePoints
    private val cumDistM: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
    }
    private val usable = points.size >= 2 && cumDistM.last() > 0.0

    private var threats: List<RouteThreat> = emptyList()
    private val announced = HashSet<String>()
    private var currentOblast: String? = null
    private var oblastInitialized = false

    /**
     * (Re)project the current threat set onto the route. Announced ids are
     * keyed on stable threat identity, so refreshing the set (a new mark, a
     * late intel report) never re-fires warnings already spoken.
     */
    fun setThreats(
        cameras: List<Pair<Double, Double>>,
        marks: List<Triple<Double, Double, String>>,
        barriers: List<Triple<Double, Double, String>> = emptyList(),
    ) {
        if (!usable) return
        val next = mutableListOf<RouteThreat>()
        cameras.forEach { (lat, lon) ->
            val snap = snapToPolyline(points, cumDistM, lat, lon)
            if (snap.offMeters <= CAMERA_ON_ROUTE_MAX_M) {
                next += RouteThreat(id = "cam:%.5f:%.5f".format(Locale.ROOT, lat, lon), kindUk = "камера швидкості", alongM = snap.alongM)
            }
        }
        marks.forEach { (lat, lon, kind) ->
            val snap = snapToPolyline(points, cumDistM, lat, lon)
            if (snap.offMeters <= MARK_ON_ROUTE_MAX_M) {
                next += RouteThreat(id = "mark:%.5f:%.5f:%s".format(Locale.ROOT, lat, lon, kind), kindUk = kind, alongM = snap.alongM)
            }
        }
        barriers.forEach { (lat, lon, kind) ->
            val snap = snapToPolyline(points, cumDistM, lat, lon)
            if (snap.offMeters <= BARRIER_ON_ROUTE_MAX_M) {
                val labelUk = when (kind.lowercase()) {
                    "checkpoint", "border_control" -> "пункт пропуску"
                    "toll_booth" -> "пункт оплати"
                    "lift_gate", "gate" -> "шлагбаум"
                    "bollard" -> "блокуючі стовпчики"
                    else -> "шлагбаум або барʼєр"
                }
                next += RouteThreat(id = "bar:%.5f:%.5f:%s".format(Locale.ROOT, lat, lon, kind), kindUk = labelUk, alongM = snap.alongM)
            }
        }
        threats = next.sortedBy { it.alongM }
    }

    /** One operator fix → the cues it earns (possibly empty). */
    fun onFix(lat: Double, lon: Double): List<Cue> {
        val cues = mutableListOf<Cue>()

        if (usable) {
            val along = snapToPolyline(points, cumDistM, lat, lon).alongM
            threats.forEach { t ->
                val delta = t.alongM - along
                when {
                    delta in 1.0..warnAheadM && announced.add(t.id) ->
                        cues += Cue.ThreatAhead(t.kindUk, delta.toInt())
                    // Passed well beyond → re-arm, so a loop route warns again.
                    delta < -REARM_BEHIND_M -> announced.remove(t.id)
                }
            }
        }

        // Alert-oblast crossing is route-independent: it fires off-route too.
        val lookup = oblastAt
        if (lookup != null) {
            val oblast = lookup(lat, lon)
            if (!oblastInitialized) {
                // The starting oblast is where the operator already IS —
                // entering it is not news.
                currentOblast = oblast
                oblastInitialized = true
            } else if (oblast != null && oblast != currentOblast) {
                currentOblast = oblast
                if (alertActive(oblast)) cues += Cue.AlertZoneEntered(oblast)
            } else if (oblast != null) {
                currentOblast = oblast
            }
        }

        return cues
    }

    companion object {
        /** A camera more than this off the polyline watches another road. */
        const val CAMERA_ON_ROUTE_MAX_M = 90.0

        /** Manual marks are hand-placed — a wider snap belt is honest. */
        const val MARK_ON_ROUTE_MAX_M = 150.0

        /** Barrier nodes (checkpoints, gates, tolls) mapped on the corridor. */
        const val BARRIER_ON_ROUTE_MAX_M = 35.0

        const val REARM_BEHIND_M = 300.0

        fun warnDistanceFor(profile: local.phantom.companion.core.net.map.RouteProfile): Double =
            when (profile) {
                local.phantom.companion.core.net.map.RouteProfile.CAR -> 700.0
                local.phantom.companion.core.net.map.RouteProfile.BIKE -> 300.0
                local.phantom.companion.core.net.map.RouteProfile.FOOT -> 200.0
                local.phantom.companion.core.net.map.RouteProfile.TRANSIT -> 200.0
            }
    }
}
