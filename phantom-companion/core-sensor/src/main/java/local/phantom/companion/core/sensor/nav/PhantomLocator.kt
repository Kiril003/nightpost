package local.phantom.companion.core.sensor.nav

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import local.phantom.companion.core.sensor.sources.BarometerSource

/**
 * PHANTOM-EW, live — the pipeline that stands between the GNSS chip and the
 * operator, and the only thing in the app allowed to answer «де я?».
 *
 * [CustomLocationEngine] is pure and knows nothing about Android. This is the
 * host that feeds it: raw fixes from the fused provider (judged, never obeyed),
 * the IMU at sensor rate (heading always, navigation when the sky goes dark),
 * the barometer (the witness a jammer cannot reach), and a 1 Hz tick — without
 * which a total blackout is indistinguishable from a quiet moment, since
 * nothing arrives to say that nothing is arriving.
 *
 * Everything downstream — the map's operator dot, GuidanceController's corridor,
 * the AI's `[LOCATION:…]` header — reads [fix]. Nothing downstream reads the
 * chip. That is the entire point: there is one place where a lie can enter, and
 * it is guarded.
 */
class PhantomLocator(
    context: Context,
    private val gpsFeed: StateFlow<GpsSample?>,
    private val imuFeed: kotlinx.coroutines.flow.Flow<ImuSample?>,
    private val engine: CustomLocationEngine = CustomLocationEngine(),
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * ZERO-FAIL — the flight recorder, or null to fly without one.
     *
     * This is the right and only tap point: it is the single place that sees the
     * raw sky, the raw body and the engine's verdict on both, which is exactly
     * the triple an autopsy needs. Every call into it is non-blocking by
     * contract — see [BlackBox].
     */
    private val blackBox: BlackBox? = null,
    /**
     * Двері для [GraphCoasting] — постачальник графа навколо точки, або null.
     *
     * Сюди їде функція «дай граф ось тут», а не сам граф: граф на пристрої — це
     * тайл, що зʼявляється й зникає, і локатор не має права ані тримати його
     * вічно, ані знати, звідки він береться. Коли тут null — поведінка локатора
     * лишається ДОСЛІВНО теперішньою: шва не створюється, у скриню не лягає
     * жодного рядка `coast,`, зайвих гілок на такті немає.
     */
    graphNear: ((lat: Double, lon: Double) -> RoadGraph?)? = null,
) {
    private val barometer = BarometerSource(context)

    /** Лічильник шляху — ПАРАЛЕЛЬНО й ЛИШЕ НА ЗАПИС, див. [CoastWitness]. */
    private val coast: CoastWitness? = CoastWitness.orNull(graphNear, blackBox)

    /**
     * Остання відповідь лічильника — для майбутнього споживача.
     *
     * На цей потік НЕ ПІДПИСАНИЙ НІХТО, і це свідомо: число ще не доведене на
     * реальній поїздці, а вести людину за недоведеним числом гірше, ніж не
     * вести зовсім. Поки що воно живе у чорній скрині.
     */
    val coasted: StateFlow<CoastReport?> =
        coast?.coasted ?: MutableStateFlow<CoastReport?>(null).asStateFlow()

    /** HORIZON — held only for the one-shot sensor roll call in [start]. */
    private val appContext = context.applicationContext

    /** The fused, judged, doubt-carrying position. The only one anybody may use. */
    val fix: StateFlow<FusedFix?> = engine.fix
    val trust: StateFlow<LocationTrust> = engine.trust

    /**
     * ATLAS-3 — how the operator is moving (walk/run/cycle/drive/train/
     * flight), classified from the same feeds this host already judges. Fed
     * the FUSED speed, never the raw chip's — a spoofed fix must not be able
     * to convince the map its owner boarded a train.
     */
    private val kinematics = KinematicsEngine()
    val motion: StateFlow<KinematicsEngine.MotionProfile> = kinematics.profile

    /**
     * Уточнення «яким саме транспортом», що приходить іззовні.
     *
     * [KinematicsEngine] чесно каже, що цю відповідь дати НЕ МОЖЕ: тролейбус і
     * авто на дванадцяти секундах акселерометра — той самий сигнал. Розрізняє
     * їх `:core-mobility` за профілем ЗУПИНОК на хвилинах. Ці двері були
     * єдиним, чого бракувало: сам `onModeOpinion` існував, і його не кликав
     * ніхто в усьому дереві, тож `MotionProfile.vehicle` завжди лишався null —
     * звідси й «визначення транспортного засобу — тупорилі прорахунки».
     *
     * `:core-sensor` не бачить `:core-mobility` і не повинен, тому сюди їде вже
     * перекладений вид, а переклад робить застосунок.
     */
    fun onVehicleOpinion(
        kind: KinematicsEngine.VehicleKind?,
        confidence: Double,
        nowMs: Long = this.nowMs(),
    ) = kinematics.onModeOpinion(kind, confidence, nowMs)

    private val _telemetry = MutableStateFlow(EwTelemetry(baroAvailable = barometer.available))
    /** Raw instrument panel for the QA HUD. Not for product UI — for the windshield. */
    val telemetry: StateFlow<EwTelemetry> = _telemetry.asStateFlow()

    private var jobs = mutableListOf<Job>()

    /** Arrival times of the last raw fixes, for an honest observed fix rate. */
    private val rawArrivals = ArrayDeque<Long>()
    private var lastRawAtMs = 0L
    private var lastRaw: GpsSample? = null

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        // HORIZON — the roll call: which witnesses does this device actually
        // carry? Logged (adb logcat -s PhantomSensors) and recorded, so a
        // «барометр не працює» report resolves to hardware truth in one look.
        val inventory = runCatching {
            local.phantom.companion.core.sensor.SensorInventory.log(appContext)
        }.getOrDefault(emptyMap())
        barometer.start()
        blackBox?.start(scope)
        blackBox?.note(
            "session",
            "locator start baro=${barometer.available} " +
                local.phantom.companion.core.sensor.SensorInventory.summarize(inventory),
        )

        jobs += scope.launch {
            gpsFeed.filterNotNull()
                // The feed is a conflated StateFlow; the same fix re-emitted is
                // not a new observation, and must not be counted as one.
                .distinctUntilChanged { a, b -> a.elapsedMs == b.elapsedMs && a.elapsedMs != 0L }
                .collect { sample -> onRawGps(sample) }
        }
        jobs += scope.launch {
            imuFeed.filterNotNull().collect { imu ->
                // Recorded BEFORE the engine sees it: the autopsy needs the sky
                // and the body as they arrived, not as the filter left them.
                blackBox?.recordImu(imu)
                // The sample's own time, not delivery time — batched samples
                // arrive together and would otherwise share one instant.
                val t = imu.elapsedMs.takeIf { it > 0L } ?: nowMs()
                engine.onImu(imu, t)
                kinematics.onImu(imu, t)
            }
        }
        jobs += scope.launch {
            barometer.hpa.filterNotNull().collect { hpa -> engine.onPressure(hpa, nowMs()) }
        }
        // P0-B (field capture 2026-07-21) — the engine was a black box: a full
        // `adb logcat` of the process carried zero navigation diagnostics, so
        // every «why is it JAMMED?» cost a round of guessing. Every trust
        // transition now prints WHY, with the inputs that caused it, to
        // `adb logcat -s PhantomEW` and to the flight recorder. Rare by nature
        // (a handful a minute), so the cost is nil and the debugging value large.
        jobs += scope.launch {
            var prev: LocationTrust? = null
            engine.trust.collect { now ->
                val line = trustLine(prev, now)
                android.util.Log.i("PhantomEW", line)
                blackBox?.note("trust", line)
                prev = now
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                val t = nowMs()
                engine.onTick(t)
                // The kinematic witness samples the FUSED state at tick rate —
                // it keeps classifying through a jam (inertial speed) exactly
                // when «he entered the tunnel on a train» matters most.
                // altitudeM is nullable on the fused fix (no barometer, no
                // GNSS altitude); the engine's own "unknown" encoding is NaN
                // (see lastAltitudeM), and every consumer guards with isNaN().
                engine.fix.value?.let { f ->
                    kinematics.onFix(f.speedMps, f.altitudeM ?: Float.NaN, t)
                }
                publishTelemetry()
                blackBox?.recordEngine(engine.fix.value, engine.snapshot())
                // ШОВ ЛІЧИЛЬНИКА. Останній рядок такту — і навмисно останній:
                // все, що бачить оператор, уже опубліковано вище. Сюди їдуть
                // ЗНАЧЕННЯ, а не рушій, тож змінити `fix` чи `trust` шов не
                // може навіть помилково — це доводить сам тип виклику.
                coast?.onEngine(engine.fix.value, engine.trust.value, t)
            }
        }
    }

    fun stop() {
        blackBox?.note("session", "locator stop")
        jobs.forEach { it.cancel() }
        jobs.clear()
        barometer.stop()
        blackBox?.stop()
    }

    /** A new session, a new origin — e.g. after a long process death. */
    fun reset() {
        engine.reset()
        kinematics.reset()
        // Нова сесія — новий початок відліку. Без цього лічильник після
        // воскресіння процесу крокував би від точки, якої вже немає.
        coast?.reset()
        rawArrivals.clear()
        lastRaw = null
        lastRawAtMs = 0L
    }

    /** Feed one pedestrian step displacement during GNSS loss or metro indoor traversal. */
    fun onPedestrianDisplacement(d: PedestrianDisplacement) {
        engine.onPedestrianDisplacement(d)
    }

    /** Feed a sky integrity report to corroborate GNSS vs Spoofing/Jamming. */
    fun onSkyIntegrity(report: SkyIntegrity, tMs: Long = nowMs()) {
        engine.onSkyIntegrity(report, tMs)
    }

    private fun onRawGps(sample: GpsSample) {
        val t = nowMs()
        blackBox?.recordGps(sample)
        lastRaw = sample
        lastRawAtMs = t
        rawArrivals.addLast(t)
        while (rawArrivals.isNotEmpty() && t - rawArrivals.first() > RATE_WINDOW_MS) {
            rawArrivals.removeFirst()
        }
        // The chip's own timestamp, when it has one — a fix that sat in a queue
        // must not be judged as though it had just been born.
        val stamp = sample.elapsedMs.takeIf { it in 1..t } ?: t
        engine.onGps(sample, stamp, sample.altitudeM)
        publishTelemetry()
    }

    /**
     * One human-readable line explaining a trust transition and the evidence
     * behind it — accuracy, staleness, fused speed, IMU-vs-sky divergence,
     * whether the body is stationary, how far dead reckoning has carried, and
     * the engine's own last rejection string. This is the sentence that turns a
     * field capture from «it says JAMMED» into «it says JAMMED because …».
     */
    private fun trustLine(from: LocationTrust?, to: LocationTrust): String {
        val s = engine.snapshot()
        val raw = lastRaw
        val ageMs = if (lastRawAtMs == 0L) null else nowMs() - lastRawAtMs
        return buildString {
            append(from?.name ?: "—").append("→").append(to.name)
            append(" still=").append(engine.stationary)
            append(" rawAcc=").append(raw?.accuracyM?.let { "${it.toInt()}m" } ?: "—")
            append(" rawAge=").append(ageMs?.let { "${it}ms" } ?: "—")
            append(" spd=").append(String.format(java.util.Locale.US, "%.1f", s.kalSpeedMps)).append("m/s")
            append(" drift=").append(s.driftResidualM.toInt()).append("/")
                .append(s.driftBudgetM.toInt()).append("m")
            append(" spoofLatch=").append(s.driftSpoofed)
            append(" dr=").append(s.deadReckonedM.toInt()).append("m")
            append(" acc/rej=").append(s.accepted).append("/").append(s.refused)
            append(" why=").append(s.lastRejection ?: "—")
        }
    }

    private fun publishTelemetry() {
        val t = nowMs()
        val f = engine.fix.value
        val raw = lastRaw
        _telemetry.value = EwTelemetry(
            trust = engine.trust.value,
            dead = f?.dead ?: false,
            lat = f?.lat,
            lon = f?.lon,
            speedMps = f?.speedMps ?: 0f,
            bearingDeg = f?.bearingDeg,
            uncertaintyM = f?.uncertaintyM ?: 0f,
            rawAccuracyM = raw?.accuracyM,
            rawAgeMs = if (lastRawAtMs == 0L) null else (t - lastRawAtMs),
            rawFixHz = rawArrivals.size.toFloat() / (RATE_WINDOW_MS / 1000f),
            accepted = engine.acceptedFixes,
            refused = engine.refusedFixes,
            lastRejection = engine.lastRejection.value,
            baroAvailable = barometer.available,
            pressureHpa = barometer.hpa.value,
            altitudeM = f?.altitudeM ?: 0f,
            vertical = f?.vertical ?: VerticalContext.UNKNOWN,
        )
    }

    private companion object {
        /** The heartbeat that lets silence be noticed. */
        const val TICK_MS = 1_000L

        /** Observed fix rate is measured over this window, not assumed from the request. */
        const val RATE_WINDOW_MS = 10_000L
    }
}

/**
 * Everything the engine knows about itself, in one struct, for the QA HUD.
 *
 * This is deliberately raw: counts, radii, a rejection string. It exists so an
 * operator in a tunnel can read PHANTOM's mind through a windscreen, and it is
 * built only in debug builds. It is not, and must never become, product UI —
 * what the operator is *told* while driving is a growing circle and a sentence,
 * not a telemetry dump.
 */
data class EwTelemetry(
    val trust: LocationTrust = LocationTrust.JAMMED,
    val dead: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedMps: Float = 0f,
    val bearingDeg: Float? = null,
    val uncertaintyM: Float = 0f,
    val rawAccuracyM: Float? = null,
    val rawAgeMs: Long? = null,
    val rawFixHz: Float = 0f,
    val accepted: Long = 0L,
    val refused: Long = 0L,
    val lastRejection: String? = null,
    val baroAvailable: Boolean = false,
    val pressureHpa: Float? = null,
    val altitudeM: Float = 0f,
    val vertical: VerticalContext = VerticalContext.UNKNOWN,
)

/**
 * ШОВ [GraphCoasting] — лічильник шляху, увімкнений ПАРАЛЕЛЬНО живому конвеєру
 * і ЛИШЕ НА ЗАПИС.
 *
 * [GraphCoasting] був написаний, покритий десятьма тестами — і не покликаний
 * ніким. Це та сама хвороба, яку ми ловимо в чужому коді, тож його зʼєднано.
 * Але НЕ підміною позиції: клас читає те, що конвеєр уже опублікував, і пише
 * результат у чорну скриню. Змінити `fix`, `trust` чи те, що бачить людина, він
 * не має права — і не може: сюди їдуть значення, а не рушій.
 *
 * Причина рівно одна: довести лічильник можна лише на реальній поїздці
 * власника. Число, заради якого все й робиться, — `vsEngineM` у рядку `coast,`:
 * відстань між точкою лічильника й точкою рушія в ту саму мілісекунду.
 *
 * ## Що цей шов НЕ доводить
 *
 * Він **не доводить, що лічильник правий**. Він не робить позицію точнішою, не
 * рятує жодного повороту і не додає жодної гарантії — лише дає число для
 * порівняння. Так само він нічого не каже про те, чи ГРАФ покриває дорогу, якою
 * реально їде оператор: межі самого лічильника названі в докстрінгу
 * [GraphCoasting] і цим швом не лікуються.
 *
 * ## Ціна й обовʼязок не впасти
 *
 * Поки небо довірене, шов ЩОСЕКУНДИ питає граф і сіється наново — запит до
 * постачальника плюс одна сітчаста вибірка в графі. Платиться це лише там, де
 * постачальника справді дали.
 *
 * [GraphCoasting.seed] і [GraphCoasting.step] обороняються `require`, а шов
 * живе всередині корутини такту локатора: викинутий звідси виняток убив би тік,
 * а з ним — і живий конвеєр. Тому вхід перевіряється до виклику, а падіння стає
 * рядком `coast,` з причиною `SEAM_ERROR`, а не тишею й не мертвим тіком.
 *
 * Клас не потокобезпечний за побудовою: його кличе рівно один такт локатора.
 */
class CoastWitness(
    private val graphNear: (lat: Double, lon: Double) -> RoadGraph?,
    private val blackBox: BlackBox?,
) {
    private val _coasted = MutableStateFlow<CoastReport?>(null)

    /** Остання відповідь лічильника, або null, коли він її не має. */
    val coasted: StateFlow<CoastReport?> = _coasted.asStateFlow()

    private var graph: RoadGraph? = null
    private var coasting: GraphCoasting? = null

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastAtMs = 0L

    /** Крок був неможливий — і лічильник не оживає сам, лише довіреним фіксом. */
    private var desynced = false

    /**
     * Один такт конвеєра. Поки небо довірене — лічильник СИДИТЬ засіяний
     * останнім довіреним фіксом; щойно позиція стає інерційною — годується
     * кроками. Пише рівно один рядок `coast,` на виклик, завжди.
     */
    fun onEngine(fix: FusedFix?, trust: LocationTrust, atMs: Long) {
        // Ловиться саме Throwable, і свідомо: усе нижче — синхронний код без
        // жодної точки призупинення, тож скасування корутини сюди не долітає,
        // а от будь-яке падіння лічильника мусить лишитись у скрині рядком, а
        // не забрати з собою тік локатора.
        val attempt = runCatching { evaluate(fix, trust, atMs) }
            .getOrElse { Attempt(null, REASON_SEAM_ERROR) }
        publish(attempt, fix)
    }

    /** Нова сесія — новий початок відліку. */
    fun reset() {
        graph = null
        coasting = null
        lastLat = Double.NaN
        lastLon = Double.NaN
        lastAtMs = 0L
        desynced = false
        _coasted.value = null
    }

    private fun evaluate(fix: FusedFix?, trust: LocationTrust, atMs: Long): Attempt {
        if (fix == null) return Attempt(null, REASON_NO_FIX)
        if (!fix.lat.isFinite() || !fix.lon.isFinite() ||
            !fix.bearingDeg.isFinite() || !fix.uncertaintyM.isFinite() || fix.uncertaintyM < 0f
        ) {
            return Attempt(null, REASON_BAD_FIX)
        }

        val blind = fix.dead || trust == LocationTrust.JAMMED || trust == LocationTrust.SPOOFED
        val result = if (blind) blindStep(fix, atMs) else seed(fix, atMs)
        lastLat = fix.lat
        lastLon = fix.lon
        lastAtMs = atMs
        return result
    }

    private fun seed(fix: FusedFix, atMs: Long): Attempt {
        val g = graphNear(fix.lat, fix.lon) ?: return Attempt(null, REASON_NO_GRAPH)
        // Той самий граф — той самий лічильник: `seed` і так скидає весь стан,
        // а збережений екземпляр зберігає кеш довжин уздовж доріг.
        if (g !== graph) {
            graph = g
            coasting = GraphCoasting(g)
        }
        desynced = false
        val c = coasting ?: return Attempt(null, REASON_NO_GRAPH)
        return Attempt(
            c.seed(
                lat = fix.lat,
                lon = fix.lon,
                courseDeg = fix.bearingDeg.toDouble(),
                accuracyM = fix.uncertaintyM.toDouble(),
                atMs = atMs,
            ),
            null,
        )
    }

    /**
     * ЧОМУ ВІДСТАНЬ БЕРЕТЬСЯ З РІЗНИЦІ ПОЗИЦІЙ, А НЕ ЗІ ШВИДКОСТІ×ЧАС.
     *
     * Обидва числа походять з одного джерела — числення рушія, — але дають різну
     * похибку. Швидкість×час — це прямокутне інтегрування ЗГЛАДЖЕНОЇ швидкості
     * раз на секунду: на розгоні й гальмуванні воно систематично промахується в
     * один бік, а коли машина СТАЛА, згладжена швидкість ще кілька тактів
     * лишається ненульовою — і лічильник тихо їхав би вулицею далі, хоч ніхто не
     * рухається. Різниця позицій цього не робить: вона нуль рівно тоді, коли
     * рушій вважає, що нічого не зрушилось, і вже містить усе, що рушій знає про
     * рух (Калман, інерція, барометр), без другого, гіршого інтегратора поруч.
     *
     * Ціна названа: різниця позицій успадкує будь-який СТРИБОК рушія. На
     * поверненні неба ми не крокуємо, а сіємось наново, тож туди стрибок не
     * потрапляє. Лишається застиглий такт (заснулий процес, тротлінг); його
     * ловить [MAX_PLAUSIBLE_MPS] і каже `STEP_IMPLAUSIBLE`, а не ковтає мовчки,
     * ведучи лічильник на кілометр уперед по графу.
     */
    private fun blindStep(fix: FusedFix, atMs: Long): Attempt {
        val c = coasting ?: return Attempt(null, REASON_NOT_SEEDED)
        if (desynced) return Attempt(null, REASON_DESYNC)
        if (!lastLat.isFinite() || !lastLon.isFinite()) return Attempt(null, REASON_NOT_SEEDED)

        val dtMs = atMs - lastAtMs
        val movedM = haversineM(lastLat, lastLon, fix.lat, fix.lon)
        if (dtMs <= 0L || movedM > MAX_PLAUSIBLE_MPS * dtMs / 1_000.0 + STEP_SLACK_M) {
            desynced = true
            return Attempt(null, REASON_STEP_IMPLAUSIBLE)
        }
        return Attempt(c.step(movedM, fix.bearingDeg.toDouble(), atMs), null)
    }

    /**
     * Рівно один рядок на такт — і в скриню, і в потік. Тиші тут не буває:
     * відсутній рядок неможливо відрізнити від невимкненого лічильника, і саме
     * так помирають шви, які «нібито підключені».
     */
    private fun publish(attempt: Attempt, fix: FusedFix?) {
        // Відмова їде СЛОВОМ, а не порожнечею: порожній потік означає рівно
        // «шва не встановлено» і нічого більше (див. [CoastReport]).
        _coasted.value = attempt.coast
            ?.let { CoastReport.Ran(it) }
            ?: CoastReport.Refused(attempt.reason ?: REASON_SEAM_ERROR)
        when (val c = attempt.coast) {
            is GraphCoasting.Coast.Held -> blackBox?.recordCoast(
                held = true,
                reason = null,
                lat = c.lat,
                lon = c.lon,
                uncertaintyM = c.uncertaintyM,
                distanceSinceFixM = c.distanceSinceFixM,
                junctionsPassed = c.junctionsPassed,
                vsEngineM = fix?.let { haversineM(c.lat, c.lon, it.lat, it.lon) },
            )

            is GraphCoasting.Coast.Lost -> blackBox?.recordCoast(
                held = false,
                reason = c.reason.name,
                // Це НЕ позиція оператора, а місце, де обірвався слід — саме так
                // його називає [GraphCoasting.Coast.Lost].
                lat = c.lostAtLat,
                lon = c.lostAtLon,
                uncertaintyM = null,
                distanceSinceFixM = null,
                junctionsPassed = null,
                vsEngineM = null,
            )

            null -> blackBox?.recordCoast(
                held = false,
                reason = attempt.reason,
                lat = null,
                lon = null,
                uncertaintyM = null,
                distanceSinceFixM = null,
                junctionsPassed = null,
                vsEngineM = null,
            )
        }
    }

    /** Відповідь лічильника, або — коли її не було — причина шва. */
    private class Attempt(val coast: GraphCoasting.Coast?, val reason: String?)

    companion object {
        /** Рушій не опублікував жодної позиції — крокувати нема від чого. */
        const val REASON_NO_FIX = "NO_FIX"

        /** Постачальник сказав «тут графа нема». Це слово, а не тиша. */
        const val REASON_NO_GRAPH = "NO_GRAPH"

        /** Осліпли, так і не отримавши жодного довіреного фікса з графом. */
        const val REASON_NOT_SEEDED = "NOT_SEEDED"

        /** Позиція рушія нечислова або з відʼємним радіусом сумніву. */
        const val REASON_BAD_FIX = "BAD_FIX"

        /** Такт застиг і «крок» вийшов фізично неможливим — див. [MAX_PLAUSIBLE_MPS]. */
        const val REASON_STEP_IMPLAUSIBLE = "STEP_IMPLAUSIBLE"

        /** Після неможливого кроку лічильник мовчить, доки не прийде фікс. */
        const val REASON_DESYNC = "DESYNC"

        /** Шов упав. Рядок про це — обовʼязковий: інакше падіння виглядає як тиша. */
        const val REASON_SEAM_ERROR = "SEAM_ERROR"

        /**
         * 60 м/с (216 км/год) — швидше за будь-яку дорогу, якою може їхати
         * оператор. Це не оцінка швидкості, а стеля правдоподібності кроку: на
         * секундному такті вона дозволяє 60 м, і все, що більше, приходить не з
         * руху, а з застиглого такту.
         */
        const val MAX_PLAUSIBLE_MPS = 60.0

        /** Запас на дрижання такту, щоб чесний швидкий крок не читався як збій. */
        const val STEP_SLACK_M = 10.0

        /**
         * Той самий виклик, що робить [PhantomLocator]: без постачальника графа
         * шва не існує — і тоді в скриню не лягає жодного рядка `coast,`.
         */
        fun orNull(
            graphNear: ((lat: Double, lon: Double) -> RoadGraph?)?,
            blackBox: BlackBox?,
        ): CoastWitness? = graphNear?.let { CoastWitness(it, blackBox) }
    }
}
