package local.phantom.companion.core.sensor.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * ZERO-FAIL — the flight recorder.
 *
 * One drive, one shot, no debugger on the road. If PHANTOM leads the operator
 * into a wall, the only thing that makes the failure *learnable* is a record of
 * what the engine believed, and why, in the seconds before it. This writes that
 * record: the raw sky, the raw body, the gate's verdicts, the filter's interior,
 * and the temperature of the machine doing the thinking.
 *
 * Three properties matter more than completeness, because a recorder that
 * degrades the thing it is recording is worse than none at all:
 *
 *  1. **It never blocks a producer.** Every `record*` call is a [Channel.trySend]
 *     onto a bounded queue and returns immediately. The GPS callback, the IMU
 *     callback at sensor rate, and the 1 Hz tick never touch a disk, never take
 *     a lock, and never wait on one.
 *
 *  2. **It never grows without bound.** The queue drops its OLDEST entry under
 *     pressure — a black box exists for the moments around the failure, so when
 *     something has to be lost it must be the ancient history, not the crash.
 *     On disk, two generations are kept and the total is capped at [maxBytes],
 *     split between two independent streams so the fat one (`imu`, two thirds of
 *     all bytes) cannot evict the thin one that carries the sky and the verdicts.
 *
 *  3. **It admits what it lost.** A recorder that silently drops samples will
 *     produce an autopsy with a hole in it exactly where the interesting thing
 *     happened, and nobody will know the hole is there. Every flush emits a
 *     `stat` line carrying `produced` and `written`; their difference is the
 *     number of records this recorder threw away, and it is the first thing to
 *     read when a trace looks too clean.
 *
 * Format is one CSV record per line, `t_ms,type,…`, greppable by type:
 *
 * ```
 * gps    lat lon accM speedMps bearingDeg altM elapsedMs
 * imu    ax ay az gx gy gz     (decimated; own file — see [imuFile])
 * fix    lat lon speedMps bearingDeg uncM trust dead altM vertical
 * ew     trust accepted refused driftResidualM driftBudgetM driftSpoofed rejection
 * kal    e n vE vN speedMps uncertaintyM deadReckonedM
 * guard  source dispLat dispLon dispAccM fLat fLon fAccM fSpd fBear nLat nLon nAccM divM cn0Max cn0Mean matchConf spoofed reason status hasPosition
 * nav    lat lon snapLat snapLon offM offRoute offStreak reroute routeBrg motionBrg spd trusted accM gait step distToManeuverM maneuver road
 * route  source ms distanceM points
 * coast  held reason lat lon uncM sinceFixM junctions vsEngineM
 * therm  status batteryTempC
 * stat   produced written queueCap
 * note   tag message
 * ```
 *
 * Not product UI, not shipped-on telemetry, and it leaves the device only if the
 * operator pulls it: [currentFile] and [previousFile] are plain files in the
 * app's private storage.
 */
class BlackBox(
    private val dir: File,
    /** Hard ceiling across BOTH generations. The recorder may not eat the disk. */
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /**
     * Keep every Nth IMU sample. The IMU arrives at 50–200 Hz; a full-rate text
     * log of it is ~50 MB/hour and would rotate the *start* of the drive out of
     * existence long before the drive ended. At 1-in-10 the body is still
     * sampled several times a second — plenty to reconstruct a turn — and a
     * whole drive fits under the cap.
     */
    private val imuDecimation: Int = DEFAULT_IMU_DECIMATION,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** `PowerManager.getCurrentThermalStatus()` (0..6), or null where unavailable. */
    private val thermalStatus: () -> Int? = { null },
    private val batteryTempC: () -> Float? = { null },
) {
    /** Bounded, lossy-at-the-tail, and never suspends a producer. */
    private val queue = Channel<String>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val produced = AtomicLong(0)

    @Volatile
    private var written = 0L

    private val imuSeen = AtomicLong(0)

    private var job: Job? = null

    /**
     * Records handed to the recorder, and records actually on disk. Their
     * difference is what the recorder threw away — read it before you trust a
     * trace that looks suspiciously clean.
     */
    val producedCount: Long get() = produced.get()
    val writtenCount: Long get() = written

    val currentFile: File get() = File(dir, "blackbox.log")
    val previousFile: File get() = File(dir, "blackbox.1.log")

    /** Own budget: `imu` is 68 % of bytes and used to evict the sky. */
    val imuFile: File get() = File(dir, "blackbox-imu.log")
    val imuPreviousFile: File get() = File(dir, "blackbox-imu.1.log")

    /** Sky, verdicts, filter — ~70 kB/h. */
    private val mainStream = Stream(currentFile, previousFile, maxBytes / 2)

    /** Body alone — ~145 kB/h. */
    private val imuStream = Stream(imuFile, imuPreviousFile, maxBytes / 2)

    // — producers: hot-path, non-blocking, allocation-light ————————————————

    fun recordGps(s: GpsSample, atMs: Long = nowMs()) = emit(atMs) {
        append("gps,")
        num(s.lat, 6); c(); num(s.lon, 6); c()
        num(s.accuracyM); c(); num(s.speedMps); c(); num(s.bearingDeg); c()
        num(s.altitudeM); c(); append(s.elapsedMs.toString())
    }

    /** Decimated by [imuDecimation] — see the constructor note. */
    fun recordImu(s: ImuSample, atMs: Long = nowMs()) {
        if (imuSeen.incrementAndGet() % imuDecimation != 0L) return
        emit(atMs) {
            append("imu,")
            num(s.accelX); c(); num(s.accelY); c(); num(s.accelZ); c()
            num(s.gyroX); c(); num(s.gyroY); c(); num(s.gyroZ)
        }
    }

    /** The fused output, the gate's verdict and the filter's interior, in one tick. */
    fun recordEngine(fix: FusedFix?, snap: EngineSnapshot, atMs: Long = nowMs()) {
        if (fix != null) emit(atMs) {
            append("fix,")
            num(fix.lat, 6); c(); num(fix.lon, 6); c()
            num(fix.speedMps); c(); num(fix.bearingDeg); c(); num(fix.uncertaintyM); c()
            append(fix.trust.name); c(); append(fix.dead.toString()); c()
            num(fix.altitudeM); c(); append(fix.vertical.name)
        }
        emit(atMs) {
            append("ew,")
            append(snap.trust.name); c()
            append(snap.accepted.toString()); c(); append(snap.refused.toString()); c()
            num(snap.driftResidualM); c(); num(snap.driftBudgetM); c()
            append(snap.driftSpoofed.toString()); c()
            append(sanitize(snap.lastRejection))
        }
        emit(atMs) {
            append("kal,")
            num(snap.kalE); c(); num(snap.kalN); c()
            num(snap.kalVE); c(); num(snap.kalVN); c()
            num(snap.kalSpeedMps); c(); num(snap.kalUncertaintyM); c()
            num(snap.deadReckonedM)
        }
    }

    /**
     * PRECISION ENGINE, Section 5 — one consistency-gate decision per fix.
     *
     * The displayed position and WHY it was chosen: the source the gate settled
     * on, the raw fused fix it judged, the independent network anchor it judged
     * against, their separation, and the verdict. `cn0Max`/`cn0Mean`/`matchConf`
     * are absent for now (no raw-GNSS or match-confidence feed yet) and render as
     * empty fields — an absent value stays absent, never a laundered zero.
     */
    fun recordGuard(
        fused: GpsSample?,
        network: GpsSample?,
        verdict: ConsistencyGate.Verdict,
        cn0Max: Float? = null,
        cn0Mean: Float? = null,
        matchConf: Float? = null,
        atMs: Long = nowMs(),
    ) = emit(atMs) {
        append("guard,")
        append(verdict.source.name); c()
        num(verdict.displayLat, 6); c(); num(verdict.displayLon, 6); c(); num(verdict.displayHAccM); c()
        num(fused?.lat, 6); c(); num(fused?.lon, 6); c(); num(fused?.accuracyM); c()
        num(fused?.speedMps); c(); num(fused?.bearingDeg); c()
        num(network?.lat, 6); c(); num(network?.lon, 6); c(); num(network?.accuracyM); c()
        num(verdict.divergenceM); c()
        num(cn0Max); c(); num(cn0Mean); c(); num(matchConf); c()
        append(verdict.spoofed.toString()); c()
        append(verdict.reason.name); c()
        // Appended, never inserted — column positions are load-bearing for the
        // capture-analysis scripts that read these files.
        append(verdict.status.name); c()
        append(verdict.hasPosition.toString())
    }

    /**
     * Один рядок супроводу маршрутом — те, чого в цьому записі не було ніколи.
     *
     * Досі скриня бачила небо, тіло й вирок про довіру, і жодного слова про
     * маршрут: ні куди зшили точку, ні наскільки збоку вона була, ні чому не
     * сталось перебудови. Через це скарги «притягує на дорогу, де маршрут» і
     * «каже "поверніться", хоч я їду не туди» неможливо було ні відтворити,
     * ні спростувати.
     *
     * Два напрямки поруч навмисно: [routeBearingDeg] — дороги, на яку зшили
     * (його показує стрілка), [motionBearingDeg] — реального руху. Порожній
     * другий означає «невідомий», а не «нуль».
     *
     * Приймає прості числа, а не тип із `core-nav`: цей модуль про нього не
     * знає й знати не повинен.
     */
    fun recordNav(
        lat: Double,
        lon: Double,
        snapLat: Double,
        snapLon: Double,
        offMeters: Double,
        offRoute: Boolean,
        offStreak: Int,
        requestReroute: Boolean,
        routeBearingDeg: Double,
        motionBearingDeg: Float?,
        speedMps: Float?,
        trusted: Boolean,
        accuracyM: Float?,
        gait: String,
        stepIndex: Int,
        distanceToManeuverM: Double,
        maneuver: String?,
        roadName: String?,
        atMs: Long = nowMs(),
    ) = emit(atMs) {
        append("nav,")
        num(lat, 6); c(); num(lon, 6); c()
        num(snapLat, 6); c(); num(snapLon, 6); c()
        num(offMeters); c(); append(offRoute.toString()); c(); append(offStreak.toString()); c()
        append(requestReroute.toString()); c()
        num(routeBearingDeg); c(); num(motionBearingDeg); c()
        num(speedMps); c(); append(trusted.toString()); c(); num(accuracyM); c()
        append(sanitize(gait)); c()
        append(stepIndex.toString()); c(); num(distanceToManeuverM); c()
        append(sanitize(maneuver)); c(); append(sanitize(roadName))
    }

    /**
     * Хто відповів на запит маршруту і за скільки — те, чим міряються ворота
     * «перебудова менш ніж за 200 мс».
     *
     * Без цього рядка швидкість перебудови можна лише відчувати. [source] —
     * GRAPH (граф на пристрої) або NETWORK; [ms] — від запиту до відповіді,
     * не від початку кадру. Порожній [distanceM] означає відмову: обидва
     * джерела сказали «не знаю», і це теж подія, яку треба бачити.
     */
    /**
     * Скільки коштувала ПІДГОТОВКА коридору, окремо від пошуку.
     *
     * На апараті власника 31 серпня власний граф відповідав 1769 і 1980 мс —
     * удевʼятеро понад ворота 200 мс. Питання «де ці секунди» без цього рядка
     * не має відповіді: `route` міряє все разом.
     *
     * Рядок зʼявляється ЛИШЕ при промаху кеша коридору. Тому його відсутність
     * поруч із повільним `route` — теж відповідь, і саме та, що звужує пошук
     * найсильніше: підготовки не було, отже час пішов у сам A*.
     */
    fun recordPrep(
        ways: Int,
        readMs: Long,
        buildMs: Long,
        atMs: Long = nowMs(),
    ) = emit(atMs) {
        append("prep,")
        append(ways.toString()); c(); append(readMs.toString()); c(); append(buildMs.toString())
    }

    fun recordRoute(
        source: String,
        ms: Long,
        distanceM: Double?,
        points: Int,
        /**
         * На чому стоїть час у дорозі: `POSTED`, `CLASS`, `ASSUMED` або
         * порожньо, коли рахував мережевий рушій і не сказав.
         *
         * Без цього поля «пакет із тегами подіяв» довелося б приймати на
         * віру: джерело (`GRAPH`) однакове до й після, відстань міняється з
         * багатьох причин, а основа міняється рівно з однієї — дорога нарешті
         * має клас. Це найдешевший доказ, який пристрій може дати сам.
         */
        basis: String = "",
        atMs: Long = nowMs(),
    ) = emit(atMs) {
        append("route,")
        append(sanitize(source)); c(); append(ms.toString()); c()
        num(distanceM); c(); append(points.toString()); c()
        append(sanitize(basis))
    }

    /**
     * Один такт лічильника шляху, обмеженого дорожнім графом ([GraphCoasting]) —
     * рядок, заради якого шов [CoastWitness] узагалі існує.
     *
     * Лічильник увімкнено ПАРАЛЕЛЬНО живому конвеєру і ЛИШЕ НА ЗАПИС: він не
     * рухає ані `fix`, ані `trust`, і людина його не бачить. Довести його можна
     * тільки на справжній поїздці власника, а вести оператора за недоведеним
     * числом — рівно те, чого цей дім не робить. Тому число їде сюди, у скриню,
     * а не на скло.
     *
     * Головне поле — [vsEngineM]: відстань між точкою лічильника й точкою рушія
     * в ту саму мілісекунду. Це єдине число, яке після поїздки скаже, хто з них
     * був ближчий до правди; решта полів існує, щоб його пояснити.
     *
     * [held] false — це чесне «не знаю», і тоді [reason] обовʼязковий: або
     * причина самого лічильника ([GraphCoasting.Reason]), або причина шва
     * (див. константи `REASON_*` у [CoastWitness]). Порожніх полів тут не
     * бояться: відсутнє значення лишається відсутнім, а не стає нулем, який
     * розтин прочитає як вимір.
     */
    fun recordCoast(
        held: Boolean,
        reason: String?,
        lat: Double?,
        lon: Double?,
        uncertaintyM: Double?,
        distanceSinceFixM: Double?,
        junctionsPassed: Int?,
        vsEngineM: Double?,
        atMs: Long = nowMs(),
    ) = emit(atMs) {
        append("coast,")
        append(held.toString()); c()
        append(sanitize(reason)); c()
        num(lat, 6); c(); num(lon, 6); c()
        num(uncertaintyM); c(); num(distanceSinceFixM); c()
        junctionsPassed?.let { append(it.toString()) }; c()
        num(vsEngineM)
    }

    fun note(tag: String, message: String, atMs: Long = nowMs()) = emit(atMs) {
        append("note,").append(sanitize(tag)).append(',').append(sanitize(message))
    }

    // — lifecycle ————————————————————————————————————————————————————————

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { drain() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // — the single writer ————————————————————————————————————————————————

    /**
     * The only thread that touches the disk. Batches whatever has accumulated,
     * appends it in one write, and flushes on a fixed cadence rather than per
     * record — a `flush()` per GPS fix is an fsync-storm on a phone under
     * thermal load, and this recorder is supposed to cost nothing.
     */
    private suspend fun CoroutineScope.drain() {
        runCatching { dir.mkdirs() }
        val batch = StringBuilder(8 * 1024)
        val imuBatch = StringBuilder(8 * 1024)
        var lastThermalMs = 0L
        var lastStatMs = 0L

        while (isActive) {
            val first = withTimeoutOrNull(FLUSH_MS) { queue.receive() }
            if (first != null) {
                first.into(batch, imuBatch).append(first).append('\n')
                written++
                // Take what else is already queued — but only up to a bound. An
                // unbounded drain lets one flush grow to the size of the whole
                // queue (~250 kB), which would then be appended in a single write
                // and carry a generation clean past [maxBytes] before the next
                // rotation check ever ran. Anything left over is picked up on the
                // very next pass, which does not wait: `withTimeoutOrNull` returns
                // immediately while the queue is non-empty.
                var taken = 1
                while (taken < BATCH_MAX_RECORDS) {
                    val more = queue.tryReceive().getOrNull() ?: break
                    more.into(batch, imuBatch).append(more).append('\n')
                    written++
                    taken++
                }
            }

            val t = nowMs()
            if (t - lastThermalMs >= THERMAL_INTERVAL_MS) {
                lastThermalMs = t
                val status = runCatching { thermalStatus() }.getOrNull()
                val temp = runCatching { batteryTempC() }.getOrNull()
                batch.append(t).append(",therm,")
                if (status != null) batch.append(status)
                batch.append(',')
                batch.num(temp, 1)
                batch.append('\n')
            }
            if (t - lastStatMs >= STAT_INTERVAL_MS) {
                lastStatMs = t
                batch.append(t).append(",stat,").append(produced.get()).append(',')
                    .append(written).append(',').append(QUEUE_CAPACITY).append('\n')
            }

            if (batch.isNotEmpty()) {
                mainStream.write(batch)
                batch.setLength(0)
            }
            if (imuBatch.isNotEmpty()) {
                imuStream.write(imuBatch)
                imuBatch.setLength(0)
            }
        }
    }

    /** Route a record to its stream's batch. Format is `t,type,…`. */
    private fun String.into(main: StringBuilder, imu: StringBuilder): StringBuilder {
        val i = indexOf(',')
        return if (i >= 0 && startsWith("imu,", i + 1)) imu else main
    }

    /** One rotating file pair with its own ceiling across both generations. */
    private class Stream(
        private val cur: File,
        private val prev: File,
        private val maxBytes: Long,
    ) {
        fun write(batch: CharSequence) {
            runCatching {
                // Rotate counting what this batch adds, or a big flush sails past.
                if (cur.length() + batch.length > maxBytes / 2) rotate()
                cur.appendText(batch.toString())
            }
        }

        private fun rotate() {
            runCatching {
                if (prev.exists()) prev.delete()
                cur.renameTo(prev)
            }
        }
    }

    // — formatting: no Locale surprises, no allocation storms ————————————

    private inline fun emit(atMs: Long, build: StringBuilder.() -> Unit) {
        val sb = StringBuilder(96)
        sb.append(atMs).append(',')
        sb.build()
        produced.incrementAndGet()
        // Bounded + DROP_OLDEST: this always succeeds and never suspends.
        queue.trySend(sb.toString())
    }

    private fun StringBuilder.c() = append(',')

    private fun StringBuilder.num(v: Float?, dp: Int = 2) = num(v?.toDouble(), dp)

    /**
     * Fixed-point, by hand, and deliberately so.
     *
     * This runs on the GPS and IMU callbacks. `String.format` costs tens of
     * microseconds per field — it parses its format string, allocates a
     * Formatter and consults a Locale, every single time — and at a dozen fields
     * per record on a sensor thread that is a real, measurable tax on the thing
     * this class is supposed to be observing without disturbing. Scaling to a
     * long and appending the digits is ~20× cheaper and allocation-free, and it
     * sidesteps the Locale trap outright: no decimal comma can ever appear here
     * to forge a CSV column.
     *
     * Null / NaN / infinite all render as an empty field — an absent value is
     * absent, and must not be laundered into a `0.00` that an autopsy would read
     * as a measurement.
     */
    private fun StringBuilder.num(v: Double?, dp: Int = 2) {
        if (v == null || v.isNaN() || v.isInfinite()) return
        var scale = 1L
        repeat(dp) { scale *= 10L }
        val scaled = Math.round(v * scale)
        val neg = scaled < 0L
        val abs = if (neg) -scaled else scaled
        if (neg) append('-')
        append(abs / scale)
        if (dp > 0) {
            append('.')
            val frac = abs % scale
            var div = scale / 10L
            while (div > 0L) {
                append('0' + ((frac / div) % 10L).toInt())
                div /= 10L
            }
        }
    }

    /** Commas and newlines would forge new columns and new records. */
    private fun sanitize(s: String?): String =
        s?.replace(',', ';')?.replace('\n', ' ')?.trim() ?: ""

    companion object {
        /**
         * ~48 MB split evenly: 7,3 days of sky and 3,4 of body, against the
         * 18,7 hours the old shared 8 MB gave — which is how 04.08 was lost.
         */
        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024

        const val DEFAULT_IMU_DECIMATION = 10

        /**
         * Deep enough to ride out a multi-second I/O stall (a thermal-throttled
         * phone writing to a busy flash chip) without losing the seconds around
         * it, and small enough that the queue itself is never the memory problem.
         */
        const val QUEUE_CAPACITY = 4096

        /**
         * Most records one flush may carry. Bounds a single append so it cannot
         * outrun the rotation check — see [writeOut]. The leftover is taken on
         * the next pass, which does not wait.
         */
        const val BATCH_MAX_RECORDS = 512

        /** One disk touch a second, not one per sample. */
        const val FLUSH_MS = 1_000L

        const val THERMAL_INTERVAL_MS = 5_000L

        /** How often the recorder confesses its own losses. */
        const val STAT_INTERVAL_MS = 10_000L
    }
}

/**
 * ZERO-FAIL — everything [CustomLocationEngine] believes about itself at one
 * instant, as data, so the flight recorder can write it down without reaching
 * into the engine's internals.
 */
data class EngineSnapshot(
    val trust: LocationTrust,
    val accepted: Long,
    val refused: Long,
    val lastRejection: String?,
    val driftResidualM: Double,
    val driftBudgetM: Double,
    val driftSpoofed: Boolean,
    val kalE: Double,
    val kalN: Double,
    val kalVE: Double,
    val kalVN: Double,
    val kalSpeedMps: Double,
    val kalUncertaintyM: Double,
    val deadReckonedM: Double,
)
