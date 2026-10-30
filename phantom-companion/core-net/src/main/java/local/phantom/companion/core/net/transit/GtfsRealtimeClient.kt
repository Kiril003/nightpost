package local.phantom.companion.core.net.transit

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ground Transit Engine (Vector A) — GTFS-RT VehiclePositions, decoded
 * straight off the wire via [ProtoReader] (see its doc comment for why this
 * is hand-rolled instead of the standard generated bindings). GTFS-RT is a
 * polled protobuf feed (there is no agency-side WebSocket variant in the
 * standard); [TransitRepository] upstream owns the poll cadence.
 */
enum class VehicleOccupancy {
    EMPTY, MANY_SEATS_AVAILABLE, FEW_SEATS_AVAILABLE, STANDING_ROOM_ONLY,
    CRUSHED_STANDING_ROOM_ONLY, FULL, NOT_ACCEPTING_PASSENGERS,
    NO_DATA_AVAILABLE, NOT_BOARDABLE, UNKNOWN,
}

/** One vehicle's fix as decoded from a single `FeedEntity`, before any repository-side diffing. */
data class RawVehiclePosition(
    val vehicleId: String,
    val tripId: String?,
    val routeId: String?,
    val lat: Double,
    val lon: Double,
    val bearingDegrees: Float?,
    val occupancy: VehicleOccupancy,
    val timestampEpochSec: Long?,
    val speedMps: Float? = null,
)

/**
 * Fetches and decodes one GTFS-RT `VehiclePositions` feed. Honesty (Law 3):
 * a transport failure or malformed payload returns `null` — the caller keeps
 * its last good snapshot rather than blanking the map.
 */
class GtfsRealtimeClient(
    private val feedUrl: String,
    private val userAgent: String = "PhantomCompanion/0.1 (transit GTFS-RT)",
    private val apiKeyHeader: Pair<String, String>? = null,
) {
    private val client: HttpClient = HttpClient(OkHttp)

    suspend fun fetchVehiclePositions(): List<RawVehiclePosition>? = withContext(Dispatchers.IO) {
        runCatching {
            val response: HttpResponse = client.get(feedUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/x-protobuf")
                apiKeyHeader?.let { (name, value) -> header(name, value) }
            }
            if (!response.status.isSuccess()) return@runCatching null
            decodeFeedMessage(response.readBytes())
        }.getOrNull()
    }
}

/**
 * Walks a top-level GTFS-RT `FeedMessage` (`repeated FeedEntity entity = 2`)
 * and decodes each entity's `VehiclePosition` (field 4), skipping entities
 * with no vehicle position (trip updates / alerts) exactly like the
 * generated-bindings `hasVehicle()` check would.
 */
internal fun decodeFeedMessage(bytes: ByteArray): List<RawVehiclePosition> {
    val reader = ProtoReader(bytes)
    val result = ArrayList<RawVehiclePosition>()
    while (reader.hasRemaining()) {
        val (field, wireType) = reader.readTag()
        if (field == 2 && wireType == 2) {
            decodeFeedEntity(reader.readLengthDelimited())?.let { result += it }
        } else {
            reader.skip(wireType)
        }
    }
    return result
}

/** `FeedEntity`: `string id = 1`, `VehiclePosition vehicle = 4`. */
private fun decodeFeedEntity(bytes: ByteArray): RawVehiclePosition? {
    val reader = ProtoReader(bytes)
    var entityId: String? = null
    var vehicleBytes: ByteArray? = null
    while (reader.hasRemaining()) {
        val (field, wireType) = reader.readTag()
        when {
            field == 1 && wireType == 2 -> entityId = reader.readString()
            field == 4 && wireType == 2 -> vehicleBytes = reader.readLengthDelimited()
            else -> reader.skip(wireType)
        }
    }
    val vb = vehicleBytes ?: return null
    return decodeVehiclePosition(vb, fallbackId = entityId.orEmpty())
}

/**
 * `VehiclePosition`: `TripDescriptor trip = 1`, `Position position = 2`,
 * `uint64 timestamp = 5`, `VehicleDescriptor vehicle = 8`,
 * `OccupancyStatus occupancy_status = 9`.
 */
private fun decodeVehiclePosition(bytes: ByteArray, fallbackId: String): RawVehiclePosition? {
    val reader = ProtoReader(bytes)
    var vehicleId: String? = null
    var tripId: String? = null
    var routeId: String? = null
    var lat: Float? = null
    var lon: Float? = null
    var bearing: Float? = null
    var speedMps: Float? = null
    var occupancyRaw: Long? = null
    var timestamp: Long? = null

    while (reader.hasRemaining()) {
        val (field, wireType) = reader.readTag()
        when {
            field == 1 && wireType == 2 -> {
                val tripReader = ProtoReader(reader.readLengthDelimited())
                while (tripReader.hasRemaining()) {
                    val (tf, twt) = tripReader.readTag()
                    when {
                        tf == 1 && twt == 2 -> tripId = tripReader.readString() // trip_id
                        tf == 5 && twt == 2 -> routeId = tripReader.readString() // route_id
                        else -> tripReader.skip(twt)
                    }
                }
            }
            field == 8 && wireType == 2 -> {
                val vdReader = ProtoReader(reader.readLengthDelimited())
                while (vdReader.hasRemaining()) {
                    val (vf, vwt) = vdReader.readTag()
                    if (vf == 1 && vwt == 2) vehicleId = vdReader.readString() else vdReader.skip(vwt) // id
                }
            }
            field == 2 && wireType == 2 -> {
                val posReader = ProtoReader(reader.readLengthDelimited())
                while (posReader.hasRemaining()) {
                    val (pf, pwt) = posReader.readTag()
                    when {
                        pf == 1 && pwt == 5 -> lat = posReader.readFloat()
                        pf == 2 && pwt == 5 -> lon = posReader.readFloat()
                        pf == 3 && pwt == 5 -> bearing = posReader.readFloat()
                        pf == 5 && pwt == 5 -> speedMps = posReader.readFloat() // Tag 5: speed in m/s
                        else -> posReader.skip(pwt)
                    }
                }
            }
            field == 5 && wireType == 0 -> timestamp = reader.readVarint()
            field == 9 && wireType == 0 -> occupancyRaw = reader.readVarint()
            else -> reader.skip(wireType)
        }
    }

    val latV = lat ?: return null
    val lonV = lon ?: return null
    return RawVehiclePosition(
        vehicleId = vehicleId ?: fallbackId,
        tripId = tripId,
        routeId = routeId,
        lat = latV.toDouble(),
        lon = lonV.toDouble(),
        bearingDegrees = bearing,
        occupancy = occupancyRaw?.let { occupancyFromWireValue(it.toInt()) } ?: VehicleOccupancy.UNKNOWN,
        timestampEpochSec = timestamp,
        speedMps = speedMps,
    )
}

private fun occupancyFromWireValue(raw: Int): VehicleOccupancy = when (raw) {
    0 -> VehicleOccupancy.EMPTY
    1 -> VehicleOccupancy.MANY_SEATS_AVAILABLE
    2 -> VehicleOccupancy.FEW_SEATS_AVAILABLE
    3 -> VehicleOccupancy.STANDING_ROOM_ONLY
    4 -> VehicleOccupancy.CRUSHED_STANDING_ROOM_ONLY
    5 -> VehicleOccupancy.FULL
    6 -> VehicleOccupancy.NOT_ACCEPTING_PASSENGERS
    7 -> VehicleOccupancy.NO_DATA_AVAILABLE
    8 -> VehicleOccupancy.NOT_BOARDABLE
    else -> VehicleOccupancy.UNKNOWN
}
