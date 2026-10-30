package local.phantom.companion.core.data.transit.surface

/**
 * Google encoded-polyline codec, precision 1e-5 (~1.1 m) — the wire format
 * carrying real track geometry inside the bundled feeds. Pure, JVM-tested.
 */
object Polyline5 {

    fun decode(encoded: String): List<Pair<Double, Double>>? {
        if (encoded.isEmpty()) return null
        val out = ArrayList<Pair<Double, Double>>(encoded.length / 4)
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                if (i >= encoded.length) return null
                b = encoded[i++].code - 63
                if (b < 0) return null
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            result = 0L
            shift = 0
            do {
                if (i >= encoded.length) return null
                b = encoded[i++].code - 63
                if (b < 0) return null
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            out.add(lat / 1e5 to lon / 1e5)
        }
        return out
    }

    fun encode(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder(points.size * 5)
        var prevLat = 0L
        var prevLon = 0L
        for ((lat, lon) in points) {
            val la = Math.round(lat * 1e5)
            val lo = Math.round(lon * 1e5)
            encodeValue(la - prevLat, sb)
            encodeValue(lo - prevLon, sb)
            prevLat = la
            prevLon = lo
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1F).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value.toInt() + 63).toChar())
    }
}
