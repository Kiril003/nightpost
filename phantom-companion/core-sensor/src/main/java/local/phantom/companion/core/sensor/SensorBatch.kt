package local.phantom.companion.core.sensor

data class SensorBatch(
    val timestampMs: Long,
    val gps: GpsSample?,
    val imu: ImuSample?,
    val micRmsDb: Float?,
)

data class GpsSample(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    /**
     * PHANTOM-EW — the chip's own altitude, when it reports one. Useless for
     * telling a floor from the street (±10-20 m), but priceless as something
     * the barometer can be made to contradict: see [nav.SpoofGate].
     */
    val altitudeM: Float? = null,
    /** Elapsed-realtime of the fix, ms. Wall-clock lies; this does not. */
    val elapsedMs: Long = 0L,
)

/**
 * H-02 — one reading of the satellites' own clock, and the monotonic instant it
 * was read at.
 *
 * Both halves are needed to say anything about *now*: [utcMs] is decoded from
 * the navigation message and does not age gracefully on its own, while
 * [elapsedMs] is the one clock on the device that no reboot, no timezone and no
 * dead battery can move. Advancing the first by the second is what makes this a
 * statement about the present rather than about the moment of the fix.
 */
data class SatelliteTime(
    /** UTC as decoded from satellites — independent of the device wall clock. */
    val utcMs: Long,
    /** Elapsed-realtime of the reading, same clock as [GpsSample.elapsedMs]. */
    val elapsedMs: Long,
)

data class ImuSample(
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val rotW: Float,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    /** Sensor's own time, same clock as [GpsSample.elapsedMs]. 0 = unknown. */
    val elapsedMs: Long = 0L,
)
