package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.sensor.ImuSample

/**
 * ATLAS-3 — how is the operator moving? Walking, running, cycling, driving,
 * riding a train, flying. The map morphs on the answer (zoom bands, default
 * route profile, POI radius), so the answer must be *stable* — a classifier
 * that flaps between DRIVE and TRAIN at every smooth stretch of asphalt is
 * worse than no classifier.
 *
 * Design, in order of importance:
 *
 *  1. **Hysteresis over accuracy.** A candidate state must hold for a dwell
 *     period before the profile switches, and leaving a vehicle state for
 *     STILL takes far longer than entering one — a car at a red light is
 *     still a car, and Google's own Activity Recognition gets this right by
 *     being similarly stubborn.
 *  2. **Honest confidence.** TRAIN and DRIVE are physically near-identical
 *     for the first minutes (smooth, fast, straight). The profile SAYS SO
 *     with a low confidence instead of pretending; consumers that morph UI
 *     should act only above their own threshold.
 *  3. **Cheap.** IMU work is O(1) per sample into one-second buckets; the
 *     classification is a handful of comparisons at 1 Hz. No FFT — at these
 *     signal-to-noise ratios, zero-crossing counting resolves the step band
 *     (1.2–3.2 Hz) perfectly well.
 *
 * Pure Kotlin, no Android, no clock of its own — same doctrine as the rest
 * of this package. Feed it what the locator already has.
 */
class KinematicsEngine(
    private val windowMs: Long = WINDOW_MS,
    private val dwellMs: Long = DWELL_MS,
    private val vehicleToStillDwellMs: Long = VEHICLE_TO_STILL_DWELL_MS,
) {

    enum class MotionState { UNKNOWN, STILL, WALK, RUN, CYCLE, DRIVE, TRAIN, FLIGHT }

    /**
     * WHICH vehicle — the one question this engine structurally cannot answer.
     *
     * A trolleybus and a car ride on the same suspension at the same speeds;
     * over a 12-second window of accelerometer they are the same signal, and
     * [MotionState.DRIVE] is the honest answer. What separates them is the
     * profile of STOPS over minutes, which is what `:core-mobility` measures
     * (18 s windows, Viterbi over ~9 minutes of context). That module's verdict
     * arrives here through [onModeOpinion] and refines the profile — it never
     * replaces [MotionState], because the camera needs an answer in seconds and
     * this engine is the only one that gives one.
     *
     * The product consequence is concrete: «Маршрут сюди» from inside a bus
     * used to route by CAR, because DRIVE was all anyone could say.
     */
    enum class VehicleKind { PUBLIC_ROAD, RAIL, METRO, MARINE }

    data class MotionProfile(
        val state: MotionState = MotionState.UNKNOWN,
        /** 0..1. TRAIN/DRIVE stay ≤ [AMBIGUOUS_CONFIDENCE] until sustained. */
        val confidence: Float = 0f,
        /** When this state began (caller's clock). */
        val sinceMs: Long = 0L,
        /** Median speed over the evidence window, for consumers' own bands. */
        val speedMps: Float = 0f,
        /** Refinement from `:core-mobility`, or null when nobody said. */
        val vehicle: VehicleKind? = null,
    )

    private val _profile = MutableStateFlow(MotionProfile())
    val profile: StateFlow<MotionProfile> = _profile.asStateFlow()

    // ── Evidence: speed + altitude at fix rate ────────────────────────────
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var lastAltitudeM = Float.NaN
    private var lastAltitudeAtMs = 0L
    private var climbMps = 0f

    // ── Evidence: IMU, folded into one-second buckets ─────────────────────
    private class Bucket {
        var n = 0
        var sumHpAbs = 0.0
        var zeroCrossings = 0
        var sumYawAbs = 0.0
        var sumJerk = 0.0
    }

    private val buckets = ArrayDeque<Pair<Long, Bucket>>()

    /**
     * [buckets] is the ONE piece of state two threads touch: [onImu] folds into
     * it from the sensor callback, [classify] sums it from the fix pump's
     * coroutine. An `ArrayDeque` is not thread-safe, and `removeFirst()` nulls
     * the slot it vacates — so an unguarded read raced a trim and destructured
     * null, killing the pump with an NPE mid-drive. Observed on the owner's
     * device (07-19). Everything else here is confined to one thread.
     */
    private val bucketLock = Any()

    private var current = Bucket()
    private var currentBucketStartMs = 0L
    private var gravityLp = 9.81f
    private var prevHp = 0f
    private var peakSinceCross = 0f
    private var prevMag = Float.NaN

    /** Written on the sensor thread, read by [classify] on the pump's. */
    @Volatile
    private var imuSeen = false

    // ── Hysteresis ────────────────────────────────────────────────────────
    private var candidate = MotionState.UNKNOWN
    private var candidateSinceMs = 0L

    /**
     * Коли востаннє приходила ШВИДКІСТЬ. Volatile, бо [onImu] і [onFix] можуть
     * жити на різних потоках, а спільну чергу доказів звідти чіпати не можна —
     * див. [expireStaleVehicleVerdict], який читає лише це число.
     */
    @Volatile
    private var lastFixMs = 0L

    // ── The second opinion (`:core-mobility`) ─────────────────────────────
    private var opinion: VehicleKind? = null
    private var opinionConfidence = 0.0
    private var opinionAtMs = 0L

    /**
     * One verdict from the transport-mode classifier, at its own pace (a new
     * segment every ~9 s). Null clears the refinement — «it is a car after
     * all» must be sayable.
     */
    fun onModeOpinion(kind: VehicleKind?, confidence: Double, nowMs: Long) {
        opinion = kind
        opinionConfidence = confidence
        opinionAtMs = nowMs
        // Publish immediately: the refinement must not wait for the next fix,
        // or the operator taps «Маршрут сюди» before it ever arrives.
        _profile.value = _profile.value.copy(vehicle = refinement(_profile.value.state, nowMs))
    }

    /**
     * The refinement is admitted only where this engine is not already sure of
     * something better. WALK/RUN/CYCLE are positive evidence that the operator
     * is NOT in a vehicle, and FLIGHT is 70 m/s of it — none of those may be
     * overridden by a slower classifier. STILL is admitted on purpose: a bus at
     * a stop is still a bus, and dropping the refinement there would make the
     * route profile flicker at every kerb.
     */
    private fun refinement(state: MotionState, nowMs: Long): VehicleKind? {
        val k = opinion ?: return null
        if (opinionConfidence < MIN_OPINION_CONFIDENCE) return null
        if (nowMs - opinionAtMs > OPINION_TTL_MS) return null
        return when (state) {
            MotionState.UNKNOWN, MotionState.STILL, MotionState.DRIVE, MotionState.TRAIN -> k
            else -> null
        }
    }

    fun reset() {
        speeds.clear()
        synchronized(bucketLock) { buckets.clear() }
        current = Bucket()
        currentBucketStartMs = 0L
        lastAltitudeM = Float.NaN
        climbMps = 0f
        imuSeen = false
        prevMag = Float.NaN
        candidate = MotionState.UNKNOWN
        candidateSinceMs = 0L
        opinion = null
        opinionConfidence = 0.0
        opinionAtMs = 0L
        _profile.value = MotionProfile()
    }

    /** One IMU sample, at sensor rate. O(1). */
    fun onImu(sample: ImuSample, nowMs: Long) {
        imuSeen = true
        val mag = sqrt(
            (sample.accelX * sample.accelX +
                sample.accelY * sample.accelY +
                sample.accelZ * sample.accelZ).toDouble(),
        ).toFloat()
        // Slow low-pass tracks gravity; what is left is body acceleration.
        gravityLp += (mag - gravityLp) * GRAVITY_LP_ALPHA
        val hp = mag - gravityLp

        if (currentBucketStartMs == 0L) currentBucketStartMs = nowMs
        if (nowMs - currentBucketStartMs >= 1_000L) {
            // Publishing the finished bucket and trimming the window are one
            // atomic step as far as the reader is concerned — see [bucketLock].
            // The lock is entered once per SECOND, not per sample, so the O(1)
            // per-sample promise below is untouched.
            synchronized(bucketLock) {
                buckets.addLast(currentBucketStartMs to current)
                while (buckets.isNotEmpty() && nowMs - buckets.first().first > windowMs) {
                    buckets.removeFirst()
                }
            }
            current = Bucket()
            currentBucketStartMs = nowMs
            expireStaleVehicleVerdict(nowMs)
        }
        current.n++
        current.sumHpAbs += abs(hp).toDouble()
        // A step cycle crosses zero twice; only count crossings whose half-wave
        // had real amplitude behind it, or a phone on a car seat "walks". The
        // gate is on the PEAK since the last crossing — at the crossing itself
        // |hp| is near zero by definition.
        if (hp * prevHp < 0f) {
            if (peakSinceCross > STEP_CROSSING_MIN_MPS2) current.zeroCrossings++
            peakSinceCross = 0f
        }
        if (abs(hp) > peakSinceCross) peakSinceCross = abs(hp)
        prevHp = hp
        current.sumYawAbs += abs(sample.gyroZ).toDouble()
        if (!prevMag.isNaN()) current.sumJerk += abs(mag - prevMag).toDouble()
        prevMag = mag
    }

    /**
     * One fused fix, at ~1 Hz — [speedMps] from the Kalman, [altitudeM] from
     * the barometer via the fused fix. Classification runs here.
     */
    fun onFix(speedMps: Float, altitudeM: Float, nowMs: Long) {
        lastFixMs = nowMs
        speeds.addLast(nowMs to speedMps)
        while (speeds.isNotEmpty() && nowMs - speeds.first().first > windowMs) {
            speeds.removeFirst()
        }
        // NaN encodes "no altitude witness" (no barometer, no GNSS altitude).
        // It must never enter the climb filter — one NaN would poison the
        // low-pass forever, and FLIGHT would become undetectable for the
        // session. An unknown sample simply leaves the climb estimate as-is.
        if (!altitudeM.isNaN()) {
            if (!lastAltitudeM.isNaN() && nowMs > lastAltitudeAtMs) {
                val dt = (nowMs - lastAltitudeAtMs) / 1000f
                if (dt > 0.2f) {
                    val rawClimb = (altitudeM - lastAltitudeM) / dt
                    climbMps += (rawClimb - climbMps) * CLIMB_LP_ALPHA
                }
            }
            lastAltitudeM = altitudeM
            lastAltitudeAtMs = nowMs
        }

        classify(nowMs, altitudeM)
    }

    // ── The classifier ────────────────────────────────────────────────────

    private fun classify(nowMs: Long, altitudeM: Float) {
        if (speeds.size < MIN_SPEED_SAMPLES) return

        val sorted = speeds.map { it.second }.sorted()
        val speed = sorted[sorted.size / 2]

        // Window IMU stats. n == 0 → no IMU (or a bucket boundary drought):
        // classification degrades to speed-only with capped confidence.
        var n = 0
        var hpAbs = 0.0
        var crossings = 0
        var yawAbs = 0.0
        var jerk = 0.0
        // Under the lock for two reasons: the deque may be trimmed underneath
        // this loop (the NPE), and the Bucket fields were written on the other
        // thread — the same monitor is what makes them visible here.
        val windowSec = synchronized(bucketLock) {
            for ((_, b) in buckets) {
                n += b.n; hpAbs += b.sumHpAbs; crossings += b.zeroCrossings
                yawAbs += b.sumYawAbs; jerk += b.sumJerk
            }
            buckets.size.coerceAtLeast(1)
        }
        val haveImu = imuSeen && n > MIN_IMU_SAMPLES
        val vibration = if (n > 0) (hpAbs / n).toFloat() else 0f
        val yawRate = if (n > 0) (yawAbs / n).toFloat() else 0f
        val jerkMean = if (n > 0) (jerk / n).toFloat() else 0f
        val stepHz = crossings / (2f * windowSec)
        val stepping = haveImu && stepHz in STEP_HZ_MIN..STEP_HZ_MAX &&
            vibration > STEP_VIBRATION_MIN_MPS2

        val (instant, instantConf) = when {
            // The sky-high witnesses first: nothing else moves at 70 m/s.
            speed > FLIGHT_SPEED_MPS -> MotionState.FLIGHT to 0.9f
            climbMps > FLIGHT_CLIMB_MPS && altitudeM > FLIGHT_ALT_M ->
                MotionState.FLIGHT to 0.7f

            // Fast and mechanical.
            speed > VEHICLE_SPEED_MPS -> {
                if (haveImu && speed > TRAIN_MIN_SPEED_MPS &&
                    jerkMean < TRAIN_JERK_MAX && yawRate < TRAIN_YAW_MAX
                ) {
                    // Smooth + straight + fast = rails. But a highway cruise
                    // wears the same signature for a while — honesty cap.
                    MotionState.TRAIN to AMBIGUOUS_CONFIDENCE
                } else {
                    MotionState.DRIVE to (if (haveImu) 0.8f else 0.55f)
                }
            }

            // Human-powered bands need the IMU to disambiguate.
            stepping && speed > RUN_SPEED_MPS -> MotionState.RUN to 0.8f
            stepping -> MotionState.WALK to 0.85f
            speed > CYCLE_MIN_SPEED_MPS && haveImu && !stepping &&
                vibration > CYCLE_VIBRATION_MIN_MPS2 -> MotionState.CYCLE to 0.6f
            speed > CYCLE_MIN_SPEED_MPS -> MotionState.DRIVE to 0.45f
            speed > WALK_MIN_SPEED_MPS ->
                MotionState.WALK to (if (haveImu) 0.5f else WALK_SPEED_ONLY_CONFIDENCE)
            else -> MotionState.STILL to (if (haveImu && vibration < STILL_VIBRATION_MPS2) 0.9f else 0.6f)
        }

        // ── Dwell/hysteresis ──────────────────────────────────────────────
        val held = _profile.value.state
        if (instant == held) {
            candidate = held
            candidateSinceMs = nowMs
            // Sustained agreement lets an ambiguous verdict firm up: ten
            // minutes of rail-smooth cruise IS a train.
            val sustainedS = (nowMs - _profile.value.sinceMs) / 1000f
            val bonus = (sustainedS / SUSTAIN_FULL_TRUST_S).coerceIn(0f, 1f) * 0.3f
            _profile.value = _profile.value.copy(
                confidence = (instantConf + bonus).coerceAtMost(1f),
                speedMps = speed,
            )
            return
        }
        if (instant != candidate) {
            // A different verdict restarts the dwell clock — flapping evidence
            // never accumulates toward a switch.
            candidate = instant
            candidateSinceMs = nowMs
        }
        val needed = if (instant == MotionState.STILL && held.isVehicle()) {
            vehicleToStillDwellMs // a car at a red light is still a car
        } else {
            dwellMs
        }
        if (nowMs - candidateSinceMs >= needed || held == MotionState.UNKNOWN) {
            _profile.value = MotionProfile(
                state = instant,
                confidence = instantConf,
                sinceMs = nowMs,
                speedMps = speed,
            )
        }
    }

    /**
     * Вирок про транспорт не має права пережити докази, на яких стоїть.
     *
     * 1 вересня 2026, 02:18: телефон власника лежав удома нерухомо, швидкість
     * 0,08 м/с — а чип казав «авто». Витримка тут ні до чого (25 с давно
     * минули): класифікація відбувається ЛИШЕ коли приходить фікс, а в
     * приміщенні фіксів немає. Отже останній вирок лишався назавжди — його
     * просто не було чим спростувати.
     *
     * DRIVE/TRAIN/FLIGHT стоять на ШВИДКОСТІ, а швидкість приходить тільки з
     * неба. Немає неба — немає підстави. Тому такий вирок протухає в UNKNOWN,
     * а не в STILL: «стою» — це теж твердження, і зробити його з самої лише
     * тиші акселерометра означало б замінити одну вигадку іншою. Людина в
     * тихому вагоні не стоїть.
     *
     * Пішохідні стани не чіпаємо: WALK/RUN/STILL спираються на кроки й
     * вібрацію, тобто на IMU, який іде далі й без супутників.
     *
     * Кличеться раз на СЕКУНДУ, з того ж блоку, де підбиваються відра —
     * обіцянка O(1) на зразок недоторкана.
     */
    private fun expireStaleVehicleVerdict(nowMs: Long) {
        val held = _profile.value
        if (!held.state.isVehicle()) return
        val last = lastFixMs
        if (last == 0L || nowMs - last <= VEHICLE_EVIDENCE_TTL_MS) return
        _profile.value = MotionProfile(
            state = MotionState.UNKNOWN,
            confidence = 0f,
            sinceMs = nowMs,
            speedMps = 0f,
            vehicle = null,
        )
        candidate = MotionState.UNKNOWN
        candidateSinceMs = nowMs
    }

    private fun MotionState.isVehicle() =
        this == MotionState.DRIVE || this == MotionState.TRAIN || this == MotionState.FLIGHT

    companion object {
        const val WINDOW_MS = 12_000L
        const val DWELL_MS = 8_000L

        /** Leaving a vehicle for STILL — long enough to sit out a red light. */
        const val VEHICLE_TO_STILL_DWELL_MS = 25_000L

        /**
         * Скільки вирок про транспорт живе без свіжої швидкості.
         *
         * 45 с — це три вікна доказів ([WINDOW_MS] = 12 с) із запасом. Менше
         * було б небезпечно: у місті проміжок між фіксами доходить до девʼяти
         * секунд (заміряно на записі власника), а тунель чи двір колодязем
         * легко зʼїдають пів хвилини — і чип миготів би на кожному мості.
         * Більше — і телефон на столі ще хвилину стверджував би «авто».
         */
        const val VEHICLE_EVIDENCE_TTL_MS = 45_000L

        /** TRAIN-vs-DRIVE is genuinely ambiguous early; the ceiling says so. */
        const val AMBIGUOUS_CONFIDENCE = 0.6f

        /** Walking pace with no step detector behind it. Anything gating on the
         *  gait must admit this or it never sees a walk at all. */
        const val WALK_SPEED_ONLY_CONFIDENCE = 0.4f

        /** A guess below this must not rename what the operator is riding. */
        private const val MIN_OPINION_CONFIDENCE = 0.55

        /** The classifier segments every ~9 s; three misses and it is stale. */
        private const val OPINION_TTL_MS = 30_000L

        /** This long in one state and an ambiguous call earns full trust. */
        private const val SUSTAIN_FULL_TRUST_S = 180f

        private const val MIN_SPEED_SAMPLES = 4
        private const val MIN_IMU_SAMPLES = 50

        private const val GRAVITY_LP_ALPHA = 0.02f
        private const val CLIMB_LP_ALPHA = 0.3f

        private const val STEP_HZ_MIN = 1.2f
        private const val STEP_HZ_MAX = 3.4f
        private const val STEP_VIBRATION_MIN_MPS2 = 0.8f
        private const val STEP_CROSSING_MIN_MPS2 = 0.6f
        private const val STILL_VIBRATION_MPS2 = 0.35f

        private const val WALK_MIN_SPEED_MPS = 0.35f
        private const val RUN_SPEED_MPS = 2.3f
        private const val CYCLE_MIN_SPEED_MPS = 2.0f
        private const val CYCLE_VIBRATION_MIN_MPS2 = 0.45f
        private const val VEHICLE_SPEED_MPS = 5.0f

        private const val TRAIN_MIN_SPEED_MPS = 12f
        private const val TRAIN_JERK_MAX = 0.25f
        private const val TRAIN_YAW_MAX = 0.035f

        private const val FLIGHT_SPEED_MPS = 70f
        private const val FLIGHT_CLIMB_MPS = 4f
        private const val FLIGHT_ALT_M = 2_500f
    }
}
