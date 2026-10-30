package local.phantom.companion.core.sensor.nav.replay

import local.phantom.companion.core.sensor.nav.RoadWay
import local.phantom.companion.core.sensor.nav.latToMeters
import local.phantom.companion.core.sensor.nav.lonToMeters
import local.phantom.companion.core.sensor.nav.metersToLat
import local.phantom.companion.core.sensor.nav.metersToLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

internal const val ORIGIN_LAT = 50.4501
internal const val ORIGIN_LON = 30.5234

internal data class En(val e: Double, val n: Double) {
    val lat: Double get() = ORIGIN_LAT + metersToLat(n)
    val lon: Double get() = ORIGIN_LON + metersToLon(e, ORIGIN_LAT)
    operator fun plus(o: En) = En(e + o.e, n + o.n)
    operator fun minus(o: En) = En(e - o.e, n - o.n)
    operator fun times(k: Double) = En(e * k, n * k)
    fun norm(): Double = hypot(e, n)
}

internal fun enOf(lat: Double, lon: Double) =
    En(lonToMeters(lon - ORIGIN_LON, ORIGIN_LAT), latToMeters(lat - ORIGIN_LAT))

internal fun compassOf(v: En): Double = atan2(v.e, v.n).let { if (it < 0) it + 2 * PI else it }

internal fun wrapPi(a: Double): Double {
    var d = (a + PI) % (2 * PI)
    if (d < 0) d += 2 * PI
    return d - PI
}

internal class Polyline(raw: List<En>, cornerRadiusM: Double = 12.0) {
    val pts: List<En> = if (cornerRadiusM > 0) fillet(raw, cornerRadiusM) else raw
    private val prefix = DoubleArray(pts.size).also {
        for (i in 1 until pts.size) it[i] = it[i - 1] + (pts[i] - pts[i - 1]).norm()
    }
    val length: Double get() = prefix.last()

    fun at(s: Double): Pair<En, Double> {
        val c = s.coerceIn(0.0, length)
        var i = 0
        while (i < pts.size - 2 && prefix[i + 1] < c) i++
        val seg = pts[i + 1] - pts[i]
        val len = seg.norm().coerceAtLeast(1e-9)
        val t = ((c - prefix[i]) / len).coerceIn(0.0, 1.0)
        return (pts[i] + seg * t) to compassOf(seg)
    }

    fun crossTrackM(p: En): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val ab = pts[i + 1] - a
            val l2 = ab.e * ab.e + ab.n * ab.n
            val t = if (l2 == 0.0) 0.0 else (((p.e - a.e) * ab.e + (p.n - a.n) * ab.n) / l2).coerceIn(0.0, 1.0)
            val d = (p - (a + ab * t)).norm()
            if (d < best) best = d
        }
        return best
    }

    private companion object {
        fun fillet(raw: List<En>, r: Double): List<En> {
            if (raw.size < 3) return raw
            val out = ArrayList<En>()
            out += raw.first()
            for (i in 1 until raw.size - 1) {
                val a = raw[i - 1]
                val b = raw[i]
                val c = raw[i + 1]
                val h1 = compassOf(b - a)
                val h2 = compassOf(c - b)
                val turn = wrapPi(h2 - h1)
                if (abs(turn) < 1e-3) {
                    out += b
                    continue
                }
                val cut = (r * tan(abs(turn) / 2)).coerceAtMost(minOf((b - a).norm(), (c - b).norm()) * 0.45)
                val arc = cut / tan(abs(turn) / 2) * abs(turn)
                val steps = (arc / 2.0).toInt().coerceAtLeast(3)
                var p = b + unit(h1) * -cut
                out += p
                for (k in 1..steps) {
                    p = p + unit(h1 + turn * (k - 0.5) / steps) * (arc / steps)
                    out += p
                }
            }
            out += raw.last()
            return out
        }
    }
}

internal fun unit(compassRad: Double) = En(sin(compassRad), cos(compassRad))

internal data class Leg(val seconds: Double, val speedMps: Double)

internal data class Truth(
    val tMs: Long,
    val p: En,
    val headingRad: Double,
    val speedMps: Double,
    val alongAccel: Double,
    val yawRateCw: Double,
    val altitudeM: Double,
    val s: Double,
)

internal class TruthMover(
    private val path: Polyline,
    private val legs: List<Leg>,
    private val maxAccel: Double,
    private val maxDecel: Double,
    private val altitudeAt: (Double) -> Double = { 0.0 },
) {
    private var s = 0.0
    private var v = 0.0
    private var heading = path.at(0.0).second
    private var t0 = 0.0

    fun targetAt(tS: Double): Double {
        var acc = 0.0
        for (l in legs) {
            if (tS < acc + l.seconds) return l.speedMps
            acc += l.seconds
        }
        return legs.lastOrNull()?.speedMps ?: 0.0
    }

    fun step(tMs: Long, dtS: Double): Truth {
        val tS = tMs / 1000.0 - t0
        val target = if (s >= path.length - 1.0) 0.0 else targetAt(tS)
        val a = ((target - v) / 1.5).coerceIn(-maxDecel, maxAccel)
        v = (v + a * dtS).coerceAtLeast(0.0)
        s = (s + v * dtS).coerceAtMost(path.length)
        val (p, h) = path.at(s)
        val dh = wrapPi(h - heading)
        val rate = if (dtS > 0) dh / dtS else 0.0
        heading = h
        return Truth(tMs, p, h, v, a, rate, altitudeAt(s), s)
    }
}

internal class RoadNet(val ways: List<RoadWay>) {
    companion object {
        fun of(vararg polylines: List<En>, oneway: Boolean = false): RoadNet {
            var id = 1L
            return RoadNet(
                polylines.map { pl -> RoadWay(id++, pl.map { it.lat to it.lon }, oneway = oneway) },
            )
        }

        fun splitAt(path: List<En>, everyM: Double): List<List<En>> {
            val pl = Polyline(path, 0.0)
            val out = ArrayList<List<En>>()
            var s = 0.0
            while (s < pl.length - 1.0) {
                val e = (s + everyM).coerceAtMost(pl.length)
                val pts = ArrayList<En>()
                var k = s
                while (k < e) {
                    pts += pl.at(k).first
                    k += 10.0
                }
                pts += pl.at(e).first
                out += pts
                s = e
            }
            return out
        }
    }
}
