package local.phantom.companion.core.sensor.nav

import kotlin.math.sqrt

/**
 * PHANTOM-EW, layer 2 — the filter that carries the operator between fixes.
 *
 * A constant-velocity Kalman filter on a local tangent plane: state is
 * (east, north, vEast, vNorth) in metres and m/s from the session origin.
 * Four dimensions, hand-rolled — a matrix library would be a dependency, and
 * this is small enough to read, which for the one piece of code that has to
 * keep working while an adversary is transmitting matters more.
 *
 * The honest part is [uncertaintyM]: with no measurement, [predict] alone
 * grows the covariance without bound. That growth IS the product. It is what
 * lets PHANTOM say «я вже не знаю, де ти, ±300 метрів» instead of drawing a
 * confident dot in the wrong street.
 */
class Kalman2D(
    /**
     * Process noise: how much unmodelled acceleration the operator is assumed
     * to be capable of, per second. Higher = the filter trusts its own
     * prediction less and the (possibly jammed) sky more. This is the single
     * dial that decides how long dead reckoning stays believable.
     */
    private val processNoiseMps2: Double = DEFAULT_PROCESS_NOISE_MPS2,
) {
    // State.
    private var east = 0.0
    private var north = 0.0
    private var vEast = 0.0
    private var vNorth = 0.0

    // Covariance, symmetric 4×4, stored dense. Row-major.
    private val p = DoubleArray(16)
    private var initialized = false

    val e: Double get() = east
    val n: Double get() = north
    val vE: Double get() = vEast
    val vN: Double get() = vNorth

    val speedMps: Double get() = sqrt(vEast * vEast + vNorth * vNorth)

    /** 1σ position radius, in metres — the size of PHANTOM's doubt. */
    val uncertaintyM: Double get() = sqrt((p[0] + p[5]).coerceAtLeast(0.0))

    fun isInitialized(): Boolean = initialized

    fun seed(e0: Double, n0: Double, positionVarM2: Double) {
        east = e0
        north = n0
        vEast = 0.0
        vNorth = 0.0
        java.util.Arrays.fill(p, 0.0)
        p[0] = positionVarM2
        p[5] = positionVarM2
        p[10] = INITIAL_VELOCITY_VAR
        p[15] = INITIAL_VELOCITY_VAR
        initialized = true
    }

    /**
     * Carry the state forward by [dtS], optionally driven by an inertial
     * velocity estimate (dead reckoning). Without a measurement update this
     * is pure extrapolation, and the covariance says so.
     */
    fun predict(
        dtS: Double,
        drVEast: Double? = null,
        drVNorth: Double? = null,
        /**
         * Process noise for THIS step, overriding the constructor's dial.
         *
         * Under GPS, 0.7 m/s² describes a driver's unmodelled manoeuvring. Under
         * dead reckoning it describes nothing at all: the dominant error is no
         * longer the operator's manoeuvring but the *accelerometer's own bias*,
         * integrating into a speed error that grows without bound.
         *
         * Leaving the dial at 0.7 while blind was the engine's third lie. The
         * covariance said ±1.2 km after a five-minute blackout while the true
         * error was 8.5 km — and worse, the re-acquisition check downstream is
         * scaled off that covariance, so an under-confident σ made the engine
         * refuse the *real* sky when it came back. Doubt that is too small does
         * not merely mislead the operator; it locks him out of the truth.
         */
        processNoiseOverride: Double? = null,
    ) {
        if (!initialized || dtS <= 0.0) return
        if (drVEast != null && drVNorth != null) {
            vEast = drVEast
            vNorth = drVNorth
        }
        east += vEast * dtS
        north += vNorth * dtS

        // F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]]  →  P = F P Fᵀ + Q
        val dt = dtS
        val p00 = p[0] + dt * (p[2] + p[8]) + dt * dt * p[10]
        val p01 = p[1] + dt * (p[3] + p[9]) + dt * dt * p[11]
        val p02 = p[2] + dt * p[10]
        val p03 = p[3] + dt * p[11]
        val p10r = p[4] + dt * (p[6] + p[12]) + dt * dt * p[14]
        val p11 = p[5] + dt * (p[7] + p[13]) + dt * dt * p[15]
        val p12 = p[6] + dt * p[14]
        val p13 = p[7] + dt * p[15]
        val p20 = p[8] + dt * p[10]
        val p21 = p[9] + dt * p[11]
        val p30 = p[12] + dt * p[14]
        val p31 = p[13] + dt * p[15]

        p[0] = p00; p[1] = p01; p[2] = p02; p[3] = p03
        p[4] = p10r; p[5] = p11; p[6] = p12; p[7] = p13
        p[8] = p20; p[9] = p21
        p[12] = p30; p[13] = p31

        // Q — a constant-acceleration process noise (the classic
        // piecewise-white-noise model).
        val noise = processNoiseOverride ?: processNoiseMps2
        val q = noise * noise
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        p[0] += q * dt4 / 4.0
        p[5] += q * dt4 / 4.0
        p[2] += q * dt3 / 2.0
        p[7] += q * dt3 / 2.0
        p[8] += q * dt3 / 2.0
        p[13] += q * dt3 / 2.0
        p[10] += q * dt2
        p[15] += q * dt2
    }

    /**
     * Fold in a position measurement of stated accuracy. A DEGRADED fix comes
     * in with a large variance and moves the state barely at all — which is
     * exactly what should happen, and is why the filter is worth having at
     * all rather than just snapping to whatever the chip last said.
     */
    fun update(measE: Double, measN: Double, accuracyM: Double) {
        if (!initialized) {
            seed(measE, measN, accuracyM * accuracyM)
            return
        }
        val r = (accuracyM * accuracyM).coerceAtLeast(MIN_MEASUREMENT_VAR)

        // H = [[1,0,0,0],[0,1,0,0]] — S = H P Hᵀ + R (2×2), K = P Hᵀ S⁻¹ (4×2).
        val s00 = p[0] + r
        val s01 = p[1]
        val s10 = p[4]
        val s11 = p[5] + r
        val det = s00 * s11 - s01 * s10
        if (det == 0.0 || !det.isFinite()) return
        val i00 = s11 / det
        val i01 = -s01 / det
        val i10 = -s10 / det
        val i11 = s00 / det

        val k = DoubleArray(8) // 4 rows × 2 cols
        for (row in 0 until 4) {
            val a = p[row * 4]     // P[row][0]
            val b = p[row * 4 + 1] // P[row][1]
            k[row * 2] = a * i00 + b * i10
            k[row * 2 + 1] = a * i01 + b * i11
        }

        val yE = measE - east
        val yN = measN - north
        east += k[0] * yE + k[1] * yN
        north += k[2] * yE + k[3] * yN
        vEast += k[4] * yE + k[5] * yN
        vNorth += k[6] * yE + k[7] * yN

        // P = (I − K H) P
        val old = p.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val khp = k[row * 2] * old[col] + k[row * 2 + 1] * old[4 + col]
                p[row * 4 + col] = old[row * 4 + col] - khp
            }
        }
    }

    /**
     * APPLE-LOCK — fold in a Doppler velocity measurement (m/s, east/north).
     *
     * The chip's course/speed pair is not derived from positions: it is
     * measured from carrier Doppler, and it stays honest (±0.2–0.5 m/s) in
     * exactly the urban multipath that scatters the position by 20 m. A
     * position-only filter has to INFER velocity from those scattered
     * positions — one second of 15 m multipath reads as 15 m/s of phantom
     * velocity, which is the lateral wobble and turn-lag the operator
     * watched next to Apple Maps (whose filter fuses Doppler, and glides).
     *
     * H = [[0,0,1,0],[0,0,0,1]] — the mirror of [update], acting on the
     * velocity block. Same hand-rolled algebra, same honesty: a noisy
     * velocity nudges, a clean one convinces.
     */
    fun updateVelocity(measVE: Double, measVN: Double, sigmaMps: Double) {
        if (!initialized) return
        val r = (sigmaMps * sigmaMps).coerceAtLeast(MIN_VELOCITY_VAR)

        val s00 = p[10] + r
        val s01 = p[11]
        val s10 = p[14]
        val s11 = p[15] + r
        val det = s00 * s11 - s01 * s10
        if (det == 0.0 || !det.isFinite()) return
        val i00 = s11 / det
        val i01 = -s01 / det
        val i10 = -s10 / det
        val i11 = s00 / det

        val k = DoubleArray(8) // 4 rows × 2 cols
        for (row in 0 until 4) {
            val a = p[row * 4 + 2] // P[row][2]
            val b = p[row * 4 + 3] // P[row][3]
            k[row * 2] = a * i00 + b * i10
            k[row * 2 + 1] = a * i01 + b * i11
        }

        val yE = measVE - vEast
        val yN = measVN - vNorth
        east += k[0] * yE + k[1] * yN
        north += k[2] * yE + k[3] * yN
        vEast += k[4] * yE + k[5] * yN
        vNorth += k[6] * yE + k[7] * yN

        // P = (I − K H) P, H picking rows 2 and 3.
        val old = p.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val khp = k[row * 2] * old[8 + col] + k[row * 2 + 1] * old[12 + col]
                p[row * 4 + col] = old[row * 4 + col] - khp
            }
        }
    }

    companion object {
        /** ≈0.7 m/s² of unmodelled manoeuvring — a normal driver in traffic. */
        const val DEFAULT_PROCESS_NOISE_MPS2 = 0.7

        /**
         * While dead-reckoning, the error is driven by the accelerometer's
         * residual bias rather than by the driver. Calibrated against the
         * measured drift of the repaired [DeadReckoner] so that the covariance
         * actually COVERS the error it is describing — see
         * `docs/EW_DRIFT_MATRIX.md`. Honest doubt is the product.
         */
        const val DEAD_RECKONING_PROCESS_NOISE_MPS2 = 2.2

        /**
         * QA FIELD FAILURE (стоячи вдома) — a PHYSICALLY STILL platform in a
         * sky gap. The IMU vibration floor is quiet and the filter's own
         * velocity is ~0: the body cannot be running anywhere, so its doubt
         * must not gallop as though it were. At home, sparse duty-cycled
         * indoor fixes (one per 10–30 s) put the engine in the JAMMED·DR
         * branch most of the time, and DR-grade noise blew σ to ±400 m at a
         * desk — a red blob over the neighbourhood and a locate camera
         * chasing a ghost. A still body earns a crawl, not a gallop.
         */
        const val STILL_PROCESS_NOISE_MPS2 = 0.05

        /** A fix is never allowed to claim it is perfect. */
        const val MIN_MEASUREMENT_VAR = 1.0

        /** Nor may a Doppler velocity — (0.3 m/s)², the chip's best day. */
        const val MIN_VELOCITY_VAR = 0.09

        /** At seeding the operator's velocity is entirely unknown. */
        const val INITIAL_VELOCITY_VAR = 100.0
    }
}
