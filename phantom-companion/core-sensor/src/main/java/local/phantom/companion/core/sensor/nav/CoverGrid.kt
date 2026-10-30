package local.phantom.companion.core.sensor.nav

import kotlin.math.floor
import kotlin.math.max

/**
 * УКРИТТЯ — kinetic-cover prior from real OSM building footprints.
 * A cell touching a building wall scores full cover (weighted by height:
 * a 9-storey slab shields, a shed barely); the adjacent ring gets partial
 * credit («вздовж стіни», not inside). Everything else is open ground.
 * Pure Kotlin — built and consumed on the JVM, fetch stays in the host.
 */
class CoverGrid private constructor(
    private val cells: HashMap<Long, Float>,
) {
    val cellCount: Int get() = cells.size

    fun coverAt(lat: Double, lon: Double): Double =
        cells[key(lat, lon)]?.toDouble() ?: 0.0

    /** LocalRouter cost shape: open ground costs (1 + avoidance), wall-hugging ~1. */
    fun costFactorAt(lat: Double, lon: Double, openAvoidance: Double): Double =
        1.0 + openAvoidance * (1.0 - coverAt(lat, lon))

    /** Fraction of the route spent on open ground (cover < [OPEN_THRESHOLD]). */
    fun openFractionAlong(points: List<Pair<Double, Double>>): Double {
        if (points.isEmpty()) return 1.0
        val sampled = CorridorPlanner.sample(points, stepM = 40.0)
        var open = 0
        for ((lat, lon) in sampled) if (coverAt(lat, lon) < OPEN_THRESHOLD) open++
        return open.toDouble() / sampled.size
    }

    companion object {
        const val CELL_DEG = 0.0009 // ~100 m of latitude
        const val OPEN_THRESHOLD = 0.3
        const val FULL_COVER_HEIGHT_M = 12f
        const val ADJACENT_FACTOR = 0.6f
        const val EDGE_STEP_M = 35.0

        data class Footprint(val ring: List<Pair<Double, Double>>, val heightM: Float)

        fun build(footprints: List<Footprint>): CoverGrid {
            val cells = HashMap<Long, Float>()
            for (f in footprints) {
                if (f.ring.size < 3) continue
                val w = (f.heightM / FULL_COVER_HEIGHT_M).coerceIn(0.35f, 1f)
                val marked = HashSet<Long>()
                for (i in f.ring.indices) {
                    val a = f.ring[i]
                    val b = f.ring[(i + 1) % f.ring.size]
                    val d = haversineM(a.first, a.second, b.first, b.second)
                    val steps = max(1, (d / EDGE_STEP_M).toInt())
                    for (s in 0..steps) {
                        val t = s.toDouble() / steps
                        marked.add(key(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t))
                    }
                }
                for (k in marked) {
                    cells.merge(k, w, ::maxOf)
                    val la = k shr 32
                    val lo = k.toInt().toLong()
                    for (dla in -1..1) for (dlo in -1..1) {
                        if (dla == 0 && dlo == 0) continue
                        cells.merge(pack(la + dla, lo + dlo), w * ADJACENT_FACTOR, ::maxOf)
                    }
                }
            }
            return CoverGrid(cells)
        }

        internal fun key(lat: Double, lon: Double): Long =
            pack(floor(lat / CELL_DEG).toLong(), floor(lon / CELL_DEG).toLong())

        private fun pack(la: Long, lo: Long): Long = (la shl 32) xor (lo and 0xFFFFFFFFL)
    }
}
