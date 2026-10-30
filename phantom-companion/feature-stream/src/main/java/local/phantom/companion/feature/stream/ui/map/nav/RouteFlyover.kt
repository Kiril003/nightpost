package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.nav.bearingDeg
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPose
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds
import local.phantom.companion.feature.stream.ui.map.atlas.MapPort

/**
 * NAV-3 — the cinematic route preview: before the operator commits, the
 * camera swoops to the start, glides along the corridor bearing-forward
 * at the tactical tilt (through the Crystal City when it's up), then
 * pulls back to frame the whole route.
 *
 * ATLAS-1 — the legs now fly through [MapPort.animateAwait] instead of calling
 * MapLibre themselves, which is what let the camera ratchet's allow-list fall
 * to one name. Cancellation is unchanged: the port resolves `false` on
 * MapLibre's `onCancel`, and the flight yields on the first `false` — the
 * finger always wins, mid-leg.
 */
internal object RouteFlyover {

    suspend fun fly(port: MapPort, points: List<Pair<Double, Double>>) {
        if (points.size < 2) return
        val anchors = sampleAnchors(points, ANCHOR_COUNT)
        val zoom = flyZoom(points)

        val started = port.animateAwait(
            poseAt(
                anchors[0],
                bearingDeg(anchors[0].first, anchors[0].second, anchors[1].first, anchors[1].second),
                zoom,
            ),
            SWOOP_MS,
        )
        if (!started) return

        for (i in 1 until anchors.size) {
            val prev = anchors[i - 1]
            val next = anchors[i]
            val ok = port.animateAwait(
                poseAt(next, bearingDeg(prev.first, prev.second, next.first, next.second), zoom),
                legDurationMs(haversineM(prev, next)),
            )
            if (!ok) return // operator gesture — stop flying instantly
        }

        port.fit(boundsOf(points), OVERVIEW_PADDING, PULL_BACK_MS)
    }

    private fun poseAt(at: Pair<Double, Double>, bearing: Double, zoom: Double) = CameraPose(
        lat = at.first,
        lon = at.second,
        zoom = zoom,
        tiltDeg = FLY_TILT_DEG,
        bearingDeg = bearing,
    )

    internal fun boundsOf(points: List<Pair<Double, Double>>) = GeoBounds(
        southLat = points.minOf { it.first },
        westLon = points.minOf { it.second },
        northLat = points.maxOf { it.first },
        eastLon = points.maxOf { it.second },
    )

    /**
     * The whole flight's length, so `CameraIntent.Flyover` can declare how long
     * the map is busy. The service holds lower-priority intents off for exactly
     * this window; the operator's own hand still cuts it mid-leg.
     */
    internal fun estimateDurationMs(points: List<Pair<Double, Double>>): Int {
        if (points.size < 2) return 0
        val anchors = sampleAnchors(points, ANCHOR_COUNT)
        val legs = (1 until anchors.size).sumOf { i ->
            legDurationMs(haversineM(anchors[i - 1], anchors[i]))
        }
        return SWOOP_MS + legs + PULL_BACK_MS
    }

    /** ~evenly spaced anchor points by distance along the route, endpoints included. */
    internal fun sampleAnchors(
        points: List<Pair<Double, Double>>,
        count: Int,
    ): List<Pair<Double, Double>> {
        if (points.size <= 2 || count <= 2) return listOf(points.first(), points.last())
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
        val total = cum.last()
        if (total <= 0.0) return listOf(points.first(), points.last())
        val out = mutableListOf(points.first())
        var idx = 0
        for (k in 1 until count - 1) {
            val target = total * k / (count - 1)
            while (idx < points.size - 1 && cum[idx + 1] < target) idx++
            out += points[(idx + 1).coerceAtMost(points.size - 1)]
        }
        out += points.last()
        return out
    }

    /** Constant flight pace: longer legs take longer, inside sane bounds. */
    internal fun legDurationMs(legMeters: Double): Int =
        (legMeters * 1.8).toInt().coerceIn(MIN_LEG_MS, MAX_LEG_MS)

    /** Далекий маршрут — вищий обліт, ближній — впритул до дахів. */
    internal fun flyZoom(points: List<Pair<Double, Double>>): Double {
        val totalM = points.zipWithNext().sumOf { (a, b) -> haversineM(a, b) }
        return when {
            totalM > 20_000 -> 13.0
            totalM > 5_000 -> 14.5
            else -> 16.4
        }
    }

    private const val ANCHOR_COUNT = 9
    private const val FLY_TILT_DEG = 60.0
    private const val SWOOP_MS = 1_500
    private const val MIN_LEG_MS = 900
    private const val MAX_LEG_MS = 2_600
    private const val PULL_BACK_MS = 1_400
    private val OVERVIEW_PADDING = EdgeInsets(96, 96, 96, 96)
}
