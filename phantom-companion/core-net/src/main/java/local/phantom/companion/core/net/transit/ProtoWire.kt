package local.phantom.companion.core.net.transit

/**
 * Minimal protobuf wire-format reader — just enough to decode GTFS-RT's
 * `VehiclePositions` feed without pulling in a full protobuf runtime.
 *
 * Why hand-rolled: the standard `gtfs-realtime-bindings` generated classes
 * extend `GeneratedMessageV3` (the FULL protobuf-java runtime), which
 * duplicate-class-conflicts at dex-merge with `protobuf-javalite` — a real,
 * load-bearing transitive dependency of MediaPipe's on-device GenAI/vision
 * tasks (`tasks-core`'s own generated classes extend `GeneratedMessageLite`;
 * excluding it breaks that feature). Both protobuf runtime flavors ship
 * classes under the same `com.google.protobuf` package name, so only one
 * can be on the classpath at once — hand-rolling this one small, stable,
 * well-documented wire format avoids the conflict entirely rather than
 * risking mediapipe to get it.
 *
 * Only what GTFS-RT's `VehiclePosition` message needs is implemented:
 * varints, fixed32 (float), and length-delimited (string/sub-message).
 * Anything else is a bug in the caller, not a feed we need to tolerate.
 */
internal class ProtoReader(private val data: ByteArray, private var pos: Int = 0, private val limit: Int = data.size) {
    fun hasRemaining(): Boolean = pos < limit

    /** Truncated/malformed input (feed cut short, corrupt bytes) degrades to a partial read + `pos = limit`, never an exception — this parses untrusted network bytes. */
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < limit) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) break // malformed varint that never terminates
        }
        pos = limit
        return result
    }

    /** (fieldNumber, wireType) for the next field. */
    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7L).toInt()
    }

    private fun readFixed32Bits(): Int {
        if (pos + 4 > limit) {
            pos = limit
            return 0
        }
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF
        val b3 = data[pos + 3].toInt() and 0xFF
        pos += 4
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readFloat(): Float = Float.fromBits(readFixed32Bits())

    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > limit) {
            val remaining = (limit - pos).coerceAtLeast(0)
            val bytes = if (remaining > 0) data.copyOfRange(pos, pos + remaining) else ByteArray(0)
            pos = limit
            return bytes
        }
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        return bytes
    }

    fun readString(): String = String(readLengthDelimited(), Charsets.UTF_8)

    /** Advances past a field of the given wire type without decoding it. */
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos = (pos + 8).coerceAtMost(limit)
            2 -> {
                val len = readVarint().toInt()
                pos = if (len < 0) limit else (pos + len).coerceAtMost(limit)
            }
            5 -> pos = (pos + 4).coerceAtMost(limit)
            else -> pos = limit // unknown wire type — bail out of this message rather than loop forever
        }
    }
}
