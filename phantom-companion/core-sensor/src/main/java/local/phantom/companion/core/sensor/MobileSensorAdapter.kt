package local.phantom.companion.core.sensor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.sensor.sources.GpsSource
import local.phantom.companion.core.sensor.sources.ImuSource
import local.phantom.companion.core.sensor.sources.MicRmsSource

private const val BATCH_INTERVAL_MS = 1_000L

/**
 * Orchestrates GPS, IMU, and microphone RMS sources into a 1 Hz
 * [SharedFlow] of [SensorBatch]. Each source independently tracks its
 * latest state; the aggregator reads a snapshot every second.
 *
 * Lifecycle: call [start] once with the owning [CoroutineScope]; call
 * [stop] to cancel all internal jobs and release hardware resources.
 * Not a singleton — each call site owns its own instance.
 *
 * MIC-1 — the [audioLevelProvider] lambda lets the adapter sample the
 * live mic amplitude without opening its own [android.media.AudioRecord].
 * The lambda lives in `:app` (AppContainer) and reads from
 * `AudioLevelHub`, the process-global hub the canonical audio recorder
 * writes into. When no recorder is active the lambda returns null and
 * `SensorBatch.micRmsDb` carries null, which tells downstream consumers
 * (SystemPulse VOICE dot, AmbientAudioNode) the mic channel is off.
 */
class MobileSensorAdapter(
    private val context: Context,
    audioLevelProvider: () -> Float? = { null },
) {

    private val gps = GpsSource(context)
    private val imu = ImuSource(context)
    private val micRms = MicRmsSource(audioLevelProvider)

    /**
     * `replay = 1` is load-bearing, not a tuning knob. Half a dozen call sites
     * (the location chain, `location.current`, ContextEngine's motion signal)
     * read `batches.replayCache.lastOrNull()` as their live GPS rung — with a
     * replay of zero that cache is *always empty*, so every one of them was
     * silently falling through to a last-known fix that could be an hour old.
     */
    private val _batches = MutableSharedFlow<SensorBatch>(replay = 1, extraBufferCapacity = 16)
    val batches: SharedFlow<SensorBatch> = _batches.asSharedFlow()

    /**
     * PHANTOM-EW — the raw feeds, unbatched. The 1 Hz [batches] snapshot is the
     * right grain for a prompt header and far too coarse for a filter: dead
     * reckoning needs the IMU at sensor rate, and the spoof gate needs each fix
     * as it lands, not a second later.
     */
    val gpsFeed: StateFlow<GpsSample?> get() = gps.state
    val imuFeed: StateFlow<ImuSample?> get() = imu.state

    /** Lossless IMU series — what dead reckoning must integrate. */
    val imuEvents: SharedFlow<ImuSample> get() = imu.events

    /**
     * PRECISION ENGINE, Section 4 — the independent network anchor, the
     * spoof-resistant reference the [nav.ConsistencyGate] holds the fused fix
     * against. Coarse and slow by nature; null until the network provider
     * delivers (or forever, on a device without one).
     */
    val networkFeed: StateFlow<GpsSample?> get() = gps.networkState

    /**
     * H-02 — UTC as the satellites tell it, advanced to this instant, or null
     * when no GNSS fix has been overheard recently enough to speak for now.
     *
     * The one signal on this adapter that is not about position. See
     * [GpsSource.satelliteNowMs] for why it cannot be read off [gpsFeed].
     */
    fun satelliteNowMs(): Long? = gps.satelliteNowMs()

    /**
     * Chip to 1 Hz for a drive, and the IMU lease for exactly as long.
     * Not screen-shaped: Android Auto drives with no Activity alive.
     */
    /**
     * Темп трьома щаблями — те, заради чого існує [NavCadence].
     *
     * Двійкове «так/ні» нижче лишається шимом для старої труби, але саме воно
     * робило щаблі недосяжними: реалізовані, під тестами — і не доходили до
     * чипа. Повна ліза IMU береться лише на справжній навігаційний темп: на
     * щаблі спокою вона палила б батарею заради нерухомої точки.
     */
    @Synchronized
    fun setCadence(cadence: local.phantom.companion.core.sensor.sources.NavCadence) {
        gps.setCadence(cadence)
        val full = cadence == local.phantom.companion.core.sensor.sources.NavCadence.NAVIGATION
        if (full == navLeaseHeld) return
        navLeaseHeld = full
        if (full) imu.acquire(full = true) else imu.release(full = true)
    }

    @Synchronized
    fun setNavigationGrade(enabled: Boolean) {
        gps.setNavigationGrade(enabled)
        if (enabled == navLeaseHeld) return
        navLeaseHeld = enabled
        if (enabled) imu.acquire(full = true) else imu.release(full = true)
    }

    private var navLeaseHeld = false

    /** The IMU is leased, not started with the adapter — see [ImuSource]. */
    fun acquireImu() = imu.acquire()

    fun releaseImu() = imu.release()

    val imuLive: Boolean get() = imu.isLive

    fun rearmLocation() = gps.rearm()

    private var aggregatorJob: Job? = null

    fun start(scope: CoroutineScope) {
        gps.start(scope)
        // IMU is leased, see [acquireImu].
        // MIC-1 — micRms is a passive consumer now; nothing to start.

        aggregatorJob = scope.launch {
            while (true) {
                delay(BATCH_INTERVAL_MS)
                micRms.refresh()
                val batch = SensorBatch(
                    timestampMs = System.currentTimeMillis(),
                    gps = gps.state.value,
                    imu = imu.state.value,
                    micRmsDb = micRms.state.value,
                )
                _batches.tryEmit(batch)
            }
        }
    }

    fun stop() {
        aggregatorJob?.cancel()
        aggregatorJob = null
        gps.stop()
        imu.stop()
        micRms.stop()
    }
}
