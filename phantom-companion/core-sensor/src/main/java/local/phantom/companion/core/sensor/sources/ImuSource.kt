package local.phantom.companion.core.sensor.sources

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.sensor.ImuSample

/**
 * Three sensors at 50 Hz, leased. Live while someone holds a lease.
 *
 * Batching stays off until [ImuSample] carries the sensor timestamp: the nav
 * stack stamps a sample at delivery, so a batch would share one instant, dt
 * would collapse and the reckoner would stop reckoning.
 */
class ImuSource(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _state = MutableStateFlow<ImuSample?>(null)

    /** Latest sample. Conflated — for anyone who wants "where are we now". */
    val state: StateFlow<ImuSample?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ImuSample>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every sample, buffered. Dead reckoning integrates a series and cannot
     * read it off a conflated flow — a batch of 25 would arrive as one.
     */
    val events: SharedFlow<ImuSample> = _events.asSharedFlow()

    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val rot = FloatArray(4)

    private val lock = Any()
    private var leases = 0
    private var fullLeases = 0
    private var registered = false
    private var activePeriodUs = 0

    /** Publish once per frame, not once per sensor. */
    private var frameType = Sensor.TYPE_ACCELEROMETER
    private var frameElapsedMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == frameType) frameElapsedMs = stampOf(event)
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accel[0] = event.values[0]
                    accel[1] = event.values[1]
                    accel[2] = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyro[0] = event.values[0]
                    gyro[1] = event.values[1]
                    gyro[2] = event.values[2]
                }
                Sensor.TYPE_ROTATION_VECTOR ->
                    SensorManager.getQuaternionFromVector(rot, event.values)
            }
            if (event.sensor.type == frameType) publish()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun publish() {
        val s = ImuSample(
            accelX = accel[0], accelY = accel[1], accelZ = accel[2],
            gyroX = gyro[0], gyroY = gyro[1], gyroZ = gyro[2],
            rotW = rot[0], rotX = rot[1], rotY = rot[2], rotZ = rot[3],
            elapsedMs = frameElapsedMs,
        )
        _state.value = s
        _events.tryEmit(s)
    }

    /**
     * `event.timestamp` is elapsed-realtime nanos — except on vendors where it
     * is boot-nanos, epoch, or zero. A stamp that disagrees with the clock by
     * more than [STAMP_SANITY_MS] is not trusted; 0 tells the caller to fall
     * back rather than integrate over a fabricated interval.
     */
    private fun stampOf(event: SensorEvent): Long {
        val ms = event.timestamp / 1_000_000L
        if (ms <= 0L) return 0L
        return if (kotlin.math.abs(SystemClock.elapsedRealtime() - ms) > STAMP_SANITY_MS) 0L else ms
    }

    /**
     * [full] = dead reckoning, which integrates and needs every sample. Anything
     * else (motion classification, the compass) reads a tenth of that and pays a
     * tenth of the wake-ups.
     */
    fun acquire(full: Boolean = false) {
        synchronized(lock) {
            leases++
            if (full) fullLeases++
            val want = if (fullLeases > 0) SAMPLING_PERIOD_US else COARSE_PERIOD_US
            if (!registered || want != activePeriodUs) register(want)
        }
    }

    fun release(full: Boolean = false) {
        synchronized(lock) {
            if (leases == 0) return
            leases--
            if (full && fullLeases > 0) fullLeases--
            if (leases == 0) {
                if (registered) unregister()
            } else {
                val want = if (fullLeases > 0) SAMPLING_PERIOD_US else COARSE_PERIOD_US
                if (want != activePeriodUs) register(want)
            }
        }
    }

    val leaseCount: Int get() = synchronized(lock) { leases }

    val isLive: Boolean get() = synchronized(lock) { registered }

    fun stop() {
        synchronized(lock) {
            leases = 0
            fullLeases = 0
            if (registered) unregister()
        }
    }

    private fun register(periodUs: Int) {
        if (registered) sensorManager.unregisterListener(listener)
        var first: Int? = null
        SENSOR_TYPES.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(
                    listener, sensor, periodOf(type, periodUs), MAX_REPORT_LATENCY_US,
                )
                if (first == null) first = type
            }
        }
        frameType = first ?: Sensor.TYPE_ACCELEROMETER
        activePeriodUs = periodUs
        registered = true
    }

    private fun unregister() {
        sensorManager.unregisterListener(listener)
        registered = false
        activePeriodUs = 0
        // Absent, not a frozen reading a consumer would keep integrating.
        _state.value = null
    }

    companion object {
        private val SENSOR_TYPES = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
        )

        const val SAMPLING_PERIOD_US = 20_000

        /** 10 Hz — motion classification and the compass, not integration. */
        const val COARSE_PERIOD_US = 100_000

        /**
         * Rotation vector refuses batching on this hardware (measured
         * `BAD_VALUE`, Samsung, 2026-08-07), so its rate IS its wake-up rate.
         * Heading changes far slower than acceleration: 20 Hz is 1,5° per sample
         * even in a hard turn.
         */
        const val ROTATION_PERIOD_US = 50_000

        private fun periodOf(type: Int, base: Int) =
            if (type == Sensor.TYPE_ROTATION_VECTOR) maxOf(ROTATION_PERIOD_US, base) else base

        /**
         * Sensor-hub buffering. Safe only because [ImuSample.elapsedMs] carries
         * the sensor's own time — dt comes from the sample, not from delivery.
         */
        const val MAX_REPORT_LATENCY_US = 500_000

        const val STAMP_SANITY_MS = 5_000L

        /** ~10 batches deep at 50 Hz — a stalled collector loses history, not the present. */
        const val EVENT_BUFFER = 256
    }
}
