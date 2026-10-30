package local.phantom.companion.core.sensor.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample

/**
 * PHANTOM-EW — the location engine that assumes the sky is hostile.
 *
 * Civilian navigation has one input and one belief: the GNSS chip said so,
 * therefore it is so. Under electronic warfare that belief is a weapon
 * pointed at its owner — jamming blanks the fix, and spoofing does something
 * far worse, handing over a confident, precise, *chosen* position. Waze walks
 * the operator into it. PHANTOM must not.
 *
 * Four layers, each of which can be read alone:
 *
 *  1. [SpoofGate] — physics as the authority a transmitter cannot forge. A
 *     fix that implies a teleport, an impossible speed, 4 g of acceleration,
 *     or an altitude the barometer flatly contradicts is refused, and — this
 *     is the part naive gates get wrong — the refused fix does not become the
 *     reference for the next judgement, so a spoofer cannot walk the operator
 *     away one plausible step at a time.
 *  2. [Kalman2D] — a constant-velocity filter on a local tangent plane. Fixes
 *     are *evidence weighted by their accuracy*, never commands. A degraded
 *     fix nudges; a good one convinces.
 *  3. [DeadReckoner] — when the sky goes dark, the operator keeps moving, and
 *     so does the estimate: heading from the platform's fused rotation
 *     vector, speed integrated ONCE from the accelerometer (never twice — see
 *     that class for why the alternative is a fantasy).
 *  4. [Altimeter] — pressure, the one witness a distant jammer cannot reach.
 *     It says whether the operator is on the road or three floors above it,
 *     and it testifies against forged GPS altitudes.
 *
 * The engine's contract, and the reason it exists at all: **it never claims
 * more than it knows.** Every [FusedFix] carries its own 1σ radius, and under
 * dead reckoning that radius GROWS — visibly, on the map, until the operator
 * can see that PHANTOM has lost him. A navigator that says «±300 m, я веду по
 * інерції» is useful. One that draws a confident dot in the wrong street is
 * how people die.
 *
 * Pure: no Android types, no clock of its own. Feed it samples; it has no
 * opinion about where they came from.
 */
class CustomLocationEngine(
    private val gate: SpoofGate = SpoofGate(),
    private val reckoner: DeadReckoner = DeadReckoner(),
    private val altimeter: Altimeter = Altimeter(),
    private val filter: Kalman2D = Kalman2D(),
    /** Layer 5 — the inertial track, used as a witness against the sky. */
    private val audit: DriftAudit = DriftAudit(),
    /**
     * Layer 6, DORMANT — the road network as the third authority at
     * reacquisition (ATLAS §7). May only vouch, never veto — see
     * [RoadWitness] for the full contract. Null (the default, and the only
     * wiring that exists) = today's exact behavior; activation is a
     * deliberate app-layer change gated behind the Re-Drive QA.
     */
    private val roadWitness: RoadWitness? = null,
    /**
     * IRONCLAD-1 — the fused course channel. The published bearing comes
     * from HERE, not from the Kalman velocity azimuth (which is noise at a
     * standstill and a weapon under EW — the «spinning arrow» of the Kyiv
     * drive). See [BearingFuser] for the contract.
     */
    private val bearingFuser: BearingFuser = BearingFuser(),
) {
    private val _fix = MutableStateFlow<FusedFix?>(null)
    val fix: StateFlow<FusedFix?> = _fix.asStateFlow()

    // Починаємо з «небо ще не відповіло», а не з «нас глушать»: до першого
    // фікса доказів немає ЖОДНИХ, а звинувачення без доказу — це і є та смуга,
    // що стояла на екрані від запуску (див. [LocationTrust.SEARCHING]).
    private val _trust = MutableStateFlow(LocationTrust.SEARCHING)
    val trust: StateFlow<LocationTrust> = _trust.asStateFlow()

    /** The last thing the gate refused, for the UI to be honest with. */
    private val _lastRejection = MutableStateFlow<String?>(null)
    val lastRejection: StateFlow<String?> = _lastRejection.asStateFlow()

    /**
     * Layer 7 — the raw-measurement witness (`:core-gnss`).
     *
     * The gate above judges a finished `Location` and can only refuse the
     * IMPOSSIBLE. A careful spoofer never produces one: he hands over a run of
     * perfectly plausible points. What he cannot hide is one level down — the
     * carrier-to-noise ratio, the receiver clock, the agreement between Doppler
     * and carrier phase. An EW-resilient engine that ignores its own raw
     * measurements is not one, so those scores enter here.
     */
    private var sky: SkyIntegrity? = null
    private var skyAtMs = 0L

    /**
     * Latched when the raw measurements prove a spoof. It has to latch for the
     * same reason [driftSpoofed] does: every forged fix is individually legal,
     * so without a latch the engine would un-detect the attack on the next one.
     */
    private var rawSpoofed = false

    /** The last raw-sky verdict, for the HUD. Null until a host reports one. */
    val skyIntegrity: SkyIntegrity? get() = sky

    /**
     * Layer 8 — pedestrian continuity (`:core-indoor`).
     *
     * Published on TRUSTED fixes only, so the step counter is anchored — and
     * calibrated — while the sky is still honest, never in the moment it dies.
     */
    private val _pedestrianAnchor = MutableStateFlow<PedestrianAnchor?>(null)
    val pedestrianAnchor: StateFlow<PedestrianAnchor?> = _pedestrianAnchor.asStateFlow()

    private var pdrAnchorE = 0.0
    private var pdrAnchorN = 0.0
    private var pdrAnchorSigmaM = 0.0
    private var havePdrAnchor = false
    private var lastPdrEastM = 0.0
    private var lastPdrNorthM = 0.0

    /** Layer-5 diagnostics: how far the sky and the body last disagreed, and by
     *  how much they were allowed to. */
    val driftResidualM: Double get() = audit.lastResidualM
    val driftBudgetM: Double get() = audit.lastBudgetM

    /**
     * ZERO-FAIL — the engine's whole interior at one instant, as data, for the
     * flight recorder.
     *
     * Read-only and allocation-cheap: the recorder calls this once a second on
     * the tick, never on the hot path. It exists so [BlackBox] does not have to
     * be a friend of this class — an autopsy needs the filter's state vector and
     * the gate's tally, and both should be *published*, not prised out.
     */
    fun snapshot(): EngineSnapshot = EngineSnapshot(
        trust = _trust.value,
        accepted = acceptedFixes,
        refused = refusedFixes,
        lastRejection = _lastRejection.value,
        driftResidualM = audit.lastResidualM,
        driftBudgetM = audit.lastBudgetM,
        driftSpoofed = driftSpoofed,
        kalE = filter.e,
        kalN = filter.n,
        kalVE = filter.vE,
        kalVN = filter.vN,
        kalSpeedMps = filter.speedMps,
        kalUncertaintyM = filter.uncertaintyM,
        deadReckonedM = deadReckonedM,
    )

    /** Session tally. A refusal rate climbing off zero IS the attack indicator. */
    var acceptedFixes: Long = 0L
        private set
    var refusedFixes: Long = 0L
        private set

    /** Tangent-plane origin. Everything inside the filter is metres from here. */
    private var originLat = 0.0
    private var originLon = 0.0
    private var haveOrigin = false

    private var lastAcceptedMs = 0L
    private var lastPredictMs = 0L

    /** Distance the inertial track has carried the operator since the sky went
     *  dark. The re-acquisition budget is proportional to it. */
    private var deadReckonedM = 0.0

    /**
     * Latched when [DriftAudit] catches a plausible drag.
     *
     * It has to latch, because a drag is made of individually *legal* fixes: on
     * the very next one the gate would shrug, the filter would fuse it, and the
     * trust would flip straight back to TRUSTED — the engine would un-detect the
     * attack one second after detecting it. While this is set, the sky is heard
     * but not obeyed: fixes are still audited (that is how we learn the drag has
     * stopped) and never fused.
     */
    private var driftSpoofed = false

    /** Consecutive re-acquisitions refused because the inertial track disagreed. */
    private var staleRefusals = 0

    /**
     * Коли почалась поточна серія відмов поверненню неба. 0 = серії немає.
     *
     * СКАРГА ВЛАСНИКА №9, запис reb-20260804 — «не перемикається назад на
     * справжній GPS, коли той повернувся»: епізод недовіри тривав 4002 с,
     * і всередині нього надійшло 32 фікси з похибкою ≤30 м. Небо було.
     *
     * Причина була не в порозі, а в ОДИНИЦІ ВИМІРУ. Уся витримка рахувалась
     * у ПРИХОДАХ фіксів ([REACQUIRE_GRACE_FIXES] = 20, і вікно узгодження
     * двох фіксів), а приходи — це те, чим розпоряджається чип, а не
     * оператор. Калібрували під небо 1 Гц, де 20 приходів = 20 с; у полі чип,
     * який ледве бачить небо, відповідає раз на 125 с, і ті самі 20 приходів
     * стають 42 хвилинами. Оператор увесь цей час їде за галюцинацією.
     *
     * Тож витримка тепер має ДРУГУ межу — у секундах, які відчуває людина.
     */
    private var staleRefusalsSinceMs = 0L

    /** Одна відмова поверненню, з відміткою, коли серія почалась. */
    private fun noteStaleRefusal(nowMs: Long) {
        if (staleRefusals == 0) staleRefusalsSinceMs = nowMs
        staleRefusals++
    }

    private fun clearStaleRefusals() {
        staleRefusals = 0
        staleRefusalsSinceMs = 0L
    }

    /**
     * Чи вичерпав інерційний свідок право відмовляти небу, яке повернулось.
     *
     * Дві межі, і спрацьовує ТА, ЩО РАНІШЕ:
     *  · [REACQUIRE_GRACE_FIXES] приходів — стара, недоторкана: на небі 1 Гц
     *    вона й далі вирішує все, тож захист від jam-then-spoof на кадансі,
     *    під який його калібрували, не змінився ані на фікс;
     *  · [REACQUIRE_GRACE_MS] секунд — нова, і вона бере слово ЛИШЕ тоді,
     *    коли небо відповідає повільніше за проєктні 1 Гц. Саме цей випадок
     *    ніхто не рахував, і саме він дав 67 хвилин.
     *
     * Годинник НЕ може скоротити витримку до одного фікса: він вимагає ще й
     * [REACQUIRE_MIN_REFUSALS] відмов. Повернення довіри лишається доказом
     * (кілька фіксів, що кажуть те саме), а не наслідком очікування.
     */
    private fun holdoutSpent(nowMs: Long): Boolean {
        if (staleRefusals >= REACQUIRE_GRACE_FIXES) return true
        if (staleRefusals < REACQUIRE_MIN_REFUSALS) return false
        return staleRefusalsSinceMs != 0L && nowMs - staleRefusalsSinceMs >= REACQUIRE_GRACE_MS
    }

    /**
     * The last returning fix refused while the inertial witness had no
     * standing. Two such fixes that agree with EACH OTHER are accepted,
     * because past [WITNESS_STANDING_M] of admitted inertial error there is
     * nothing left to test the sky against except its own consistency. The
     * candidate carries its stated accuracy — agreement is judged against
     * what both fixes admit they might be wrong by.
     */
    private data class ReacquireCandidate(
        val e: Double,
        val n: Double,
        val atMs: Long,
        val accuracyM: Float,
    )

    private var reacquireCandidate: ReacquireCandidate? = null

    fun reset() {
        gate.reset()
        reckoner.reset()
        altimeter.reset()
        audit.reset()
        bearingFuser.reset()
        haveOrigin = false
        lastAcceptedMs = 0L
        lastPredictMs = 0L
        deadReckonedM = 0.0
        driftSpoofed = false
        clearStaleRefusals()
        reacquireCandidate = null
        acceptedFixes = 0L
        refusedFixes = 0L
        sky = null
        skyAtMs = 0L
        rawSpoofed = false
        havePdrAnchor = false
        pdrAnchorSigmaM = 0.0
        lastPdrEastM = 0.0
        lastPdrNorthM = 0.0
        _pedestrianAnchor.value = null
        _fix.value = null
        // Скидання означає «знову нічого не знаємо», а не «нас глушать».
        _trust.value = LocationTrust.SEARCHING
        _lastRejection.value = null
    }

    /**
     * One raw-measurement verdict from `:core-gnss`.
     *
     * Two attacks, opposite treatments — and conflating them gets both wrong.
     * SPOOFING gives a strong, healthy-looking signal that lies, so the answer
     * is to stop obeying the sky while still listening to it (that is how we
     * learn the attack stopped). JAMMING takes the signal away, so the answer
     * is to say so out loud: a silence with a measured C/N0 collapse behind it
     * is electronic warfare, where a silence over a parked phone is not.
     */
    fun onSkyIntegrity(report: SkyIntegrity, nowMs: Long) {
        sky = report
        skyAtMs = nowMs

        val spoof = report.spoofingScore >= SKY_COMPROMISED ||
            report.dopplerPhaseRmsM >= DOPPLER_PHASE_BREAK_M
        if (spoof) {
            if (!rawSpoofed) armDeadReckoning(nowMs)
            rawSpoofed = true
            _lastRejection.value = report.note ?: rawSpoofReason(report)
            _trust.value = LocationTrust.SPOOFED
            return
        }
        // A window that comes back clean is how the engine learns the attack
        // has stopped — the latch releases, and the next fix is fused normally.
        rawSpoofed = false
        val provenGoodSky = lastAcceptedAccuracyM in 0f..PROVEN_SKY_M
        if (report.jammingScore >= SKY_COMPROMISED && !provenGoodSky && _trust.value == LocationTrust.TRUSTED) {
            _trust.value = LocationTrust.DEGRADED
            _lastRejection.value = report.note ?: rawJamReason(report)
        }
    }

    /**
     * One pedestrian displacement from `:core-indoor`, in metres from the last
     * anchor. Applied ONLY while the sky is gone, and only as a MEASUREMENT
     * carrying its own honest σ — the operator's steps are evidence, not a
     * command, exactly like a fix.
     *
     * This is what keeps the dot moving in a metro vestibule instead of
     * freezing: a MEMS accelerometer integrated for speed is a fantasy on foot,
     * while a step counter with a heading is not.
     */
    fun onPedestrianDisplacement(d: PedestrianDisplacement) {
        if (!havePdrAnchor || !filter.isInitialized() || !d.informative) return
        if (_trust.value != LocationTrust.JAMMED && _trust.value != LocationTrust.SPOOFED) return

        // The same displacement re-offered is not a new observation. Folding it
        // in twice would shrink the covariance on evidence that never arrived —
        // a standing pedestrian would grow confident by doing nothing.
        val moved = Math.hypot(d.eastM - lastPdrEastM, d.northM - lastPdrNorthM)
        if (moved < PDR_MIN_STEP_M) return
        lastPdrEastM = d.eastM
        lastPdrNorthM = d.northM

        val dtS = if (lastPredictMs == 0L) 0.0 else (d.atMs - lastPredictMs) / 1000.0
        if (dtS > 0.0) {
            filter.predict(dtS, processNoiseOverride = Kalman2D.DEAD_RECKONING_PROCESS_NOISE_MPS2)
        }
        lastPredictMs = d.atMs
        // The step track's own doubt and the anchor's are independent sources.
        val sigma = Math.hypot(d.accuracyM, pdrAnchorSigmaM).coerceAtLeast(1.0)
        filter.update(pdrAnchorE + d.eastM, pdrAnchorN + d.northM, sigma)
        deadReckonedM += moved
        publish(d.atMs, dead = true)
    }

    /** Raw evidence still fresh enough to reason with. */
    private fun skyFresh(nowMs: Long): Boolean =
        sky != null && skyAtMs != 0L && nowMs - skyAtMs <= SKY_EVIDENCE_TTL_MS

    /** The sky is not clean by `:core-gnss`'s own «clean epoch» bar. */
    /**
     * Чи є підстава сумніватись у небі — з ДВОМА свідками, не з одним.
     *
     * Раніше вирок стояв на самому балі сирих вимірювань, і власник, якого
     * ніколи не глушили, бачив «GPS: точність слабка — веду обережно» при
     * фіксі ±3 м. Це та сама однобокість, що вранці дала хибну «ПІДМІНУ
     * КООРДИНАТ»: свідок був один, і його ніхто не перевіряв.
     *
     * Фізика проста: **приймач, який глушать, не видає фіксів по три метри.**
     * Щоб зійтись на такій точності, потрібні чисті сигнали з кількох
     * супутників — саме те, чого глушіння й позбавляє. Тож точність краща за
     * [PROVEN_SKY_M] спростовує бал глушіння, і мовчання тут чесніше за
     * попередження.
     *
     * ПІДМІНА цієї поблажки НЕ отримує, і це навмисно: підроблений фікс
     * заявляє гарну точність за побудовою — там саме гарна точність і є
     * частиною брехні.
     */
    private fun skyDoubted(nowMs: Long): Boolean {
        val s = sky ?: return false
        if (!skyFresh(nowMs)) return false
        if (s.spoofingScore >= SKY_DOUBT) return true
        val provenGoodSky = lastAcceptedAccuracyM in 0f..PROVEN_SKY_M
        return s.jammingScore >= SKY_DOUBT && !provenGoodSky
    }

    /** Точність останнього ПРИЙНЯТОГО фікса — другий свідок для [skyDoubted]. */
    private var lastAcceptedAccuracyM: Float = -1f

    private fun rawJammed(nowMs: Long): Boolean =
        skyFresh(nowMs) && (sky?.jammingScore ?: 0.0) >= SKY_COMPROMISED && !(lastAcceptedAccuracyM in 0f..PROVEN_SKY_M)

    private fun rawSpoofReason(r: SkyIntegrity): String =
        "сирі вимірювання свідчать про підміну (бал ${"%.2f".format(r.spoofingScore)}, " +
            "розбіжність фази й доплера ${r.dopplerPhaseRmsM.toInt()} м) — не веду по супутниках"

    private fun rawJamReason(r: SkyIntegrity): String =
        "сигнал придушують: придатних супутників ${r.usableSats}, " +
            "бал глушіння ${"%.2f".format(r.jammingScore)}"

    /** One barometer sample (hPa). */
    fun onPressure(hpa: Float, nowMs: Long) {
        altimeter.onPressure(hpa, nowMs)
    }

    /**
     * One IMU sample. While the sky is trusted this only maintains the
     * reckoner's heading, so that the moment a jam starts it already knows
     * which way the operator is pointing. While the sky is dark it IS the
     * navigation.
     */
    fun onImu(sample: ImuSample, nowMs: Long) {
        val v = reckoner.onImu(sample, nowMs)
        // The audit's own inertial track runs at ALL times, especially while the
        // sky is trusted — that is precisely when a patient spoofer is at work,
        // and the whole point is to have a witness that was never listening to
        // him.
        audit.onImu(sample, nowMs)
        // The course backbone likewise runs at ALL times: it must already be
        // tracking the turn when the sky goes dark, not start guessing then.
        bearingFuser.onImu(sample, nowMs)
        if (_trust.value == LocationTrust.TRUSTED || _trust.value == LocationTrust.DEGRADED) return
        if (!filter.isInitialized()) return

        val dtS = if (lastPredictMs == 0L) 0.0 else (nowMs - lastPredictMs) / 1000.0
        lastPredictMs = nowMs
        if (dtS <= 0.0) return

        // P0 JAMMED-WHILE-PARKED — a still body has nothing to reckon. Keep the
        // clock current so motion resumes cleanly, but advance no position: a
        // MEMS accelerometer integrated on a parked phone is exactly the
        // wandering trail the owner photographed, and there is no truth in it.
        if (isStationary()) return

        filter.predict(
            dtS,
            v?.first,
            v?.second,
            processNoiseOverride = Kalman2D.DEAD_RECKONING_PROCESS_NOISE_MPS2,
        )
        if (v != null) deadReckonedM += Math.hypot(v.first, v.second) * dtS
        publish(nowMs, dead = true)
    }

    /**
     * One GPS fix, straight from the chip — judged, not obeyed.
     *
     * @param altitudeM the chip's altitude, if it reports one. It is checked
     *   against the barometer, which is the whole point of asking for it.
     */
    fun onGps(sample: GpsSample, nowMs: Long, altitudeM: Float? = null) {
        val verdict = gate.judge(sample, nowMs, altitudeM, altimeter.climbMps)

        if (!verdict.accepted) {
            refusedFixes++
            _lastRejection.value = verdict.reason
            // One impossible fix is a glitch — the chip does produce them. A
            // RUN of them is an adversary, and PHANTOM stops listening to the
            // sky entirely rather than averaging a lie into its position.
            if (gate.consecutiveRejects >= SPOOF_STREAK) {
                _trust.value = LocationTrust.SPOOFED
                armDeadReckoning(nowMs)
            }
            return
        }

        if (!haveOrigin) {
            originLat = sample.lat
            originLon = sample.lon
            haveOrigin = true
        }

        val e = lonToMeters(sample.lon - originLon, originLat)
        val n = latToMeters(sample.lat - originLat)

        // The gate declined to judge: too much silence for physics to say
        // anything. The sky is asking to be believed again after a gap, and the
        // one witness that did NOT go dark during that gap is the operator's own
        // body. Ask it.
        //
        // This is what makes the F1 repair safe. Simply trusting a fix after a
        // gap would hand an adversary the classic chain — jam until the gate
        // gives up, then present a false origin to a gate that has agreed to
        // stop asking. Corroboration closes that: the re-acquired position must
        // land inside the doubt the engine had already admitted to.
        if (verdict.staleReference && filter.isInitialized()) {
            val dtGap = if (lastPredictMs == 0L) 0.0 else (nowMs - lastPredictMs) / 1000.0
            if (dtGap > 0.0) filter.predict(dtGap)
            lastPredictMs = nowMs

            val apart = Math.hypot(e - filter.e, n - filter.n)

            // The budget is what DEAD RECKONING could plausibly have got wrong
            // over the distance it just carried the operator — NOT a multiple of
            // the Kalman's σ.
            //
            // Scaling it off σ was the first attempt and it was worthless: the
            // covariance of a constant-velocity filter grows with the FOURTH
            // power of time, so two minutes of blindness produced a ±22 km doubt
            // — against a true inertial error of a few hundred metres. A budget
            // of 3σ then waved an 8 km spoof straight through. The covariance is
            // an honest thing to *show* an operator; it is a terrible thing to
            // *reason* with at long horizons.
            //
            // Distance-proportional is the model the inertial track actually
            // obeys, and it is the same one [DriftAudit] is calibrated on.
            //
            // ПОЛЬОВА ПОМИЛКА, 16.08 17:54:10 — обвинувачення, яке спростовують
            // його ж числа. Телефон стояв на місці, фікс повернувся з власною
            // похибкою ±699 м і ліг за 356 м від інерційного сліду. Двигун
            // порівняв 356 м із жорстким допуском 200 м і сказав «ПІДМІНА
            // КООРДИНАТ». Але 356 м — це пів сигми тієї самої похибки, яку небо
            // саме про себе й заявило: суперечності немає взагалі.
            //
            // Допуск моделював похибку САМОГО ІНЕРЦІЙНОГО СЛІДУ і жодного разу
            // не питав, наскільки добре небо зізнається знати своє місце. Той
            // самий принцип уже стоїть нижче, у перевірці «небо проти себе»
            // ([CONSIST_ACCURACY_SCALE] — «два фікси ±100 м за 250 м один від
            // одного кажуть те саме в межах власного сумніву»); сюди він просто
            // не дійшов.
            //
            // Тепер розбіжність мусить перевищити те, що визнають ОБИДВА:
            // інерція своїм дрейфом, небо своєю заявленою похибкою. Стеля на
            // внесок неба стоїть тому, що похибку заявляє те саме джерело, яке
            // може бути ворожим: без неї підмінник розширював би собі ворота,
            // просто друкуючи більше число.
            val inertialBudget = REACQUIRE_ERROR_FRACTION * deadReckonedM + REACQUIRE_BASE_M
            val skyDoubtM = REACQUIRE_ACCURACY_SCALE *
                minOf(sample.accuracyM.toDouble(), REACQUIRE_ACCURACY_CAP_M)
            val budget = inertialBudget + skyDoubtM

            // Layer 6 (DORMANT until Re-Drive QA) — when the sky and the
            // body disagree, ask the map. Every disagreeing fix feeds the
            // witness's chain, including the ones refused below; a returning
            // sky that has walked [ROAD_WITNESS_MIN_CHAIN] consecutive,
            // confident, CONNECTED road matches is corroborated by topology
            // and accepted as DEGRADED now — not after twenty seconds of
            // grace the operator spends being navigated by a drifted track.
            // The witness cannot refuse anything: an off-road verdict simply
            // leaves every rule below exactly as it was.
            if (apart > budget && roadWitness != null) {
                val t = roadWitness.testify(
                    sample.lat, sample.lon, sample.accuracyM, sample.bearingDeg, nowMs,
                )
                if (t != null && t.onRoad &&
                    t.confidence >= ROAD_WITNESS_MIN_CONFIDENCE &&
                    t.chainLength >= ROAD_WITNESS_MIN_CHAIN
                ) {
                    _lastRejection.value =
                        "прийняв сигнал за свідченням дорожньої мережі " +
                            "(${t.chainLength} узгоджених збігів) — розбіжність з " +
                            "інерцією ${apart.toInt()} м; веду обережно"
                    concedeReacquisition(sample, e, n, nowMs, altitudeM)
                    return
                }
            }

            // FIELD FAILURE, Kyiv 2026-07-16: a witness whose own admitted
            // error has passed [WITNESS_STANDING_M] has no standing to refuse
            // ANYTHING — yet the grace counter still made it refuse twenty
            // honest fixes per cycle, and each cycle the track drifted further
            // (277 refusals over 4 h; the «допуск» itself grew to 14 km; σ hit
            // 346 km; the operator was navigated by a hallucination all day).
            // Past standing, the only test left is the sky against ITSELF: two
            // consecutive returning fixes that agree with each other are the
            // truth or an adversary indistinguishable from it — either way
            // inertia has nothing further to say, and the honest verdict is
            // DEGRADED, quickly, not SPOOFED, forever.
            // FIELD DEADLOCK, home 2026-07-18 20:09: this consistency test
            // was calibrated for a 1 Hz sky — two fixes within 6 s and 120 m.
            // A deep-indoor chip delivers one ±100 m fix per 10–30 s, so the
            // gap test could NEVER pass (every candidate expired before the
            // next fix was born) and two honest ±100 m fixes are routinely
            // >120 m apart. The engine sat in SPOOFED·DR at a kitchen table,
            // σ at 600 km, saying «чекаю другого підтвердження» to a sky
            // that was answering as fast as it could. Consistency must be
            // judged against the sky's OWN cadence and stated accuracy:
            // the agreement radius grows with both fixes' admitted error plus
            // the distance the operator could have legitimately travelled
            // between them.
            //
            // СКАРГА №9, запис reb-20260804 — той самий глухий кут, лише
            // ширший. Дедлайн на прихід не прибрали, його підняли з 6 с до
            // 45 с; чип у записі відповідав раз на 125 с, і умова знову не
            // могла стати істиною ЖОДНОГО разу. Гілка завершувалась `return`,
            // тож поступки нижче вона не діставала взагалі: недовіра трималась
            // 4002 с, поки надходили фікси ≤30 м.
            //
            // Дедлайн прибрано, і не з великодушності: він ДУБЛЮВАВ те, що вже
            // рахує доданок «швидкість × пауза» — власний коментар до
            // константи це й визнавав. Два фікси за дві хвилини одне від
            // одного лишаються двома свідками; час не робить їхнє свідчення
            // слабшим, він лише робить менш визначеною відстань між ними, і
            // саме її той доданок і оплачує. Натомість доданок отримав СТЕЛЮ
            // ([REACQUIRE_CONSIST_TRAVEL_CAP_M]) — інакше довге очікування з
            // заявленою швидкістю саме собі розширювало б ворота. Нетто
            // ворота стали ВУЖЧІ: раніше «30 м/с × 45 с» пропускало 1350 м,
            // тепер стеля 1000 м.
            // Standing is about the INERTIAL track alone — how far it has itself
            // drifted. Measuring it against the widened budget would let a fix
            // with poor stated accuracy silently strip the witness of standing,
            // which is a different rule wearing this one's name.
            val witnessStands = inertialBudget <= WITNESS_STANDING_M
            if (apart > budget && !witnessStands) {
                val cand = reacquireCandidate
                var consistent = false
                if (cand != null) {
                    val gapS = (nowMs - cand.atMs).coerceAtLeast(0L) / 1000.0
                    // Скільки оператор МІГ законно проїхати між двома фіксами.
                    val travelM = ((sample.speedMps ?: 0f) * gapS)
                        .coerceAtMost(REACQUIRE_CONSIST_TRAVEL_CAP_M)
                    val allowedM = REACQUIRE_CONSIST_M +
                        CONSIST_ACCURACY_SCALE * (cand.accuracyM + sample.accuracyM) +
                        travelM
                    consistent = Math.hypot(e - cand.e, n - cand.n) <= allowedM
                }
                if (consistent) {
                    // Небо підтвердило само себе. Це ДОКАЗ, а не капітуляція,
                    // і він заслуговує іншого слова, ніж поступка нижче.
                    _lastRejection.value =
                        "небо підтвердило себе двома узгодженими фіксами — інерційний " +
                            "слід (${apart.toInt()} м убік) уже без стояння; " +
                            "веду по супутниках, обережно"
                    concedeReacquisition(sample, e, n, nowMs, altitudeM)
                    return
                }
                if (!holdoutSpent(nowMs)) {
                    reacquireCandidate = ReacquireCandidate(e, n, nowMs, sample.accuracyM)
                    noteStaleRefusal(nowMs)
                    refusedFixes++
                    _lastRejection.value =
                        "інерційний слід уже сам за межами довіри — чекаю другого " +
                            "підтвердження від неба (${apart.toInt()} м від інерції)"
                    _trust.value = LocationTrust.SPOOFED
                    armDeadReckoning(nowMs)
                    return
                }
                // Витримка вичерпана і без узгодження: небо каже своє вже
                // [REACQUIRE_MIN_REFUSALS] разів і довше за [REACQUIRE_GRACE_MS],
                // а свідок, який його заперечує, за власним правилом цієї гілки
                // стояння НЕ МАЄ. Тримати далі — це не цілісність, це замок
                // 2026-07-16 у новому костюмі. Падаємо в поступку нижче.
            }

            if (apart > budget && witnessStands && !holdoutSpent(nowMs)) {
                if (apart <= URBAN_MULTIPATH_MAX_M) {
                    _lastRejection.value =
                        "міська похибка сигналу (${apart.toInt()} м від інерції) — веду в режимі низької точності"
                    _trust.value = LocationTrust.DEGRADED
                } else {
                    noteStaleRefusal(nowMs)
                    refusedFixes++
                    _lastRejection.value =
                        "сигнал повернувся за ${apart.toInt()} м від інерційного шляху " +
                            "(допуск ${budget.toInt()} м) — схоже на підміну після глушіння"
                    _trust.value = LocationTrust.SPOOFED
                    armDeadReckoning(nowMs)
                    return
                }
            }

            // The witness must be able to concede.
            //
            // Holding out forever is not integrity, it is the F1 lockout wearing
            // a third hat: once dead reckoning has drifted past the budget it
            // only drifts FURTHER, so a single refusal would become permanent and
            // the operator would be stranded with a confident inertial dot in the
            // wrong district. An inertial track that is itself 500 m adrift has
            // no standing to testify about a 500 m discrepancy.
            //
            // So once the holdout is spent ([holdoutSpent] — twenty arrivals,
            // or [REACQUIRE_GRACE_MS] of a sky that keeps saying the same thing)
            // the engine yields — but it yields to DEGRADED, never to TRUSTED,
            // and it keeps saying out loud that it does not like this. The
            // operator is told he is being navigated by a sky his own body
            // disagrees with. That is the honest floor; doing better needs a
            // THIRD authority (map-matching), not a braver constant.
            if (apart > budget) {
                _lastRejection.value =
                    "прийняв сигнал попри розбіжність ${apart.toInt()} м з інерцією — " +
                        "інерційний слід уже недостовірний; веду по супутниках, обережно"
                concedeReacquisition(sample, e, n, nowMs, altitudeM)
                return
            }
            clearStaleRefusals()
            reacquireCandidate = null
            // Corroborated. NOW the gate may move its reference — and not one
            // moment before.
            gate.acceptReacquisition(sample, nowMs, altitudeM)
            // The audit's window restarts here: everything before the gap is
            // another world, and diffing across it would be meaningless.
            audit.reset()
        }

        acceptedFixes++
        _lastRejection.value = null

        val dtS = if (lastPredictMs == 0L) 0.0 else (nowMs - lastPredictMs) / 1000.0
        if (dtS > 0.0) filter.predict(dtS)
        lastPredictMs = nowMs

        // STATION-KEEPING (field report 2026-07-18, «стоячи вдома карта
        // стрибає щосекунди») — a body that is demonstrably STILL does not
        // teleport ten metres a second; indoor multipath does. Three
        // witnesses must agree (the chip's own ~zero speed, a quiet IMU
        // vibration floor, the filter's ~zero velocity), and then each
        // fix's stated accuracy is floored at [STILL_ACCURACY_FLOOR_M]:
        // scatter nudges the state by centimetres instead of walking the
        // dot around the flat. The instant any witness reports motion the
        // full gain is back — this gate can delay a launch by nothing.
        val still = (sample.speedMps ?: Float.MAX_VALUE) < DOPPLER_STANDSTILL_MPS &&
            reckoner.isQuiet && filter.speedMps < STILL_SPEED_MPS
        val measVar = if (still) {
            maxOf(sample.accuracyM.toDouble(), STILL_ACCURACY_FLOOR_M)
        } else {
            sample.accuracyM.toDouble()
        }
        filter.update(e, n, measVar.coerceAtLeast(1.0))
        fuseDopplerVelocity(sample)
        lastAcceptedMs = nowMs
        // The sky is carrying the operator again; the inertial track's error
        // budget starts over from here.
        deadReckonedM = 0.0

        // Layer 5 — the patient spoofer. Every fix here was individually
        // plausible; what betrays a drag is that the satellites and the
        // operator's own body stop agreeing about how far he has come.
        //
        // Audited BEFORE fusion, and always — including while already latched,
        // because a window that comes back in agreement is how we learn the
        // attack has stopped.
        // The witness uses the same sensor, so it uses the same calibration.
        audit.primeBias(reckoner.accelBiasMps2)
        val finding = audit.onAcceptedFix(
            e = e,
            n = n,
            speedMps = sample.speedMps ?: filter.speedMps.toFloat(),
            bearingDeg = sample.bearingDeg,
            accuracyM = sample.accuracyM,
            nowMs = nowMs,
        )
        // Set when a DIVERGE on a good sky demotes trust without seizing the
        // position — a warning that must survive the trust re-evaluation below.
        var auditDegraded = false
        // OWNER FIELD FAILURE, 2026-07 («ніколи не був під глушінням, а мітку
        // тягне куди хоче»). The operator has NEVER been jammed, yet the engine
        // dead-reckoned him off a clean sky. The single continuous-sky path to
        // dead reckoning is HERE — a DriftAudit DIVERGE latching [driftSpoofed].
        //
        // The witness is a MEMS inertial track, and on a genuinely good sky it
        // is the LESS trustworthy of the two. A ZUPT misfire on a smooth road,
        // a phone re-gripped in its cradle (its yaw delta steers the reckoned
        // course into a field — see [DeadReckoner]), or a turn the scalar
        // along-track model handles badly all make the BODY diverge from an
        // HONEST sky. Believing the body over a tight, steadily-arriving fix is
        // precisely the «confident dot in the wrong street» this engine exists
        // to prevent — turned inward on its own operator.
        //
        // So the owner's directive: when the sky is genuinely good (accuracy
        // within [GOOD_ACCURACY_M]), a DIVERGE may no longer seize the position.
        // The fix is still FUSED — the puck stays on the satellites, never
        // dead-reckoned — the operator is TOLD his body and the sky disagree,
        // and trust drops to DEGRADED for that fix: a warning, not a seizure.
        // Dead reckoning engages only on PROVEN degradation: a degraded sky
        // (poor accuracy, handled below), a gate physics violation, or a jam
        // dropout ([onTick]).
        //
        // The cost is stated plainly: a patient drag that forges good accuracy
        // (F3) is no longer caught HERE. That defence moves to the third
        // authority the DriftAudit doc always named as the real answer —
        // map-matching: is the operator on a road that exists and connects?
        // (Layer 6 [RoadWitness] / the OSRM `/match` road-witness, ATLAS §7.)
        // The inertial witness alone cannot tell its own error from the sky's
        // lie, and in the field its false alarms were the harm.
        when (finding) {
            DriftAudit.Finding.DIVERGE -> {
                if (sample.accuracyM > GOOD_ACCURACY_M && !isStationary()) {
                    // Proven degradation: a poor sky that ALSO parts company
                    // with the body is the classic jam-then-spoof handover.
                    // Here inertia is the better witness — dead-reckon, latched.
                    //
                    // P0 JAMMED-WHILE-PARKED — but a STILL body cannot be
                    // dragged: there is no motion for the audit to catch parting
                    // from, so a DIVERGE while stationary is MEMS noise, never a
                    // handover. Seizing the position there is the false positive
                    // the owner (never once jammed) kept being shown. Stationary
                    // falls through to the good-sky branch below: follow the
                    // satellites, say it out loud, never dead-reckon.
                    driftSpoofed = true
                    _lastRejection.value =
                        "супутники розійшлися з інерцією на ${audit.lastResidualM.toInt()} м " +
                            "(допуск ${audit.lastBudgetM.toInt()} м) за поганого сигналу — зміщення"
                    _trust.value = LocationTrust.SPOOFED
                    armDeadReckoning(nowMs)
                } else {
                    // Good sky: never abandon it for a MEMS track. Follow the
                    // satellites, say the disagreement out loud, mark DEGRADED.
                    driftSpoofed = false
                    auditDegraded = true
                    _lastRejection.value =
                        "супутники трохи розходяться з інерцією " +
                            "(${audit.lastResidualM.toInt()} м) — веду по супутниках, стежу"
                }
            }
            DriftAudit.Finding.AGREE -> {
                // The body and the sky are telling the same story again.
                driftSpoofed = false
            }
            DriftAudit.Finding.PENDING -> Unit
        }

        // Layer 7 — the raw measurements caught what the physics gate cannot:
        // a run of individually legal fixes that the signal itself says are
        // forged. Heard, not obeyed, on exactly the same terms as a drag.
        if (rawSpoofed && skyFresh(nowMs)) {
            refusedFixes++
            _lastRejection.value = sky?.note ?: sky?.let { rawSpoofReason(it) }
            _trust.value = LocationTrust.SPOOFED
            return
        }

        if (driftSpoofed) {
            // Heard, not obeyed. The position is inertial until the sky earns
            // its way back with a window that agrees.
            refusedFixes++
            _trust.value = LocationTrust.SPOOFED
            return
        }

        // A fix is not a state — the accuracy the chip reports is. A ±60 m fix
        // in a canyon is evidence, not truth, and the map should show doubt.
        // A good sky whose body momentarily disagreed is DEGRADED for this fix
        // and returns to TRUSTED as soon as the windows agree again.
        // A sky the raw measurements are already unhappy about never reaches
        // TRUSTED either, however tight the accuracy it claims — a forged fix
        // claims a good one by construction.
        // Записати ДО вироку: сама точність цього фікса і є другим свідком,
        // якого skyDoubted питає про глушіння.
        lastAcceptedAccuracyM = sample.accuracyM
        _trust.value = if (!auditDegraded && sample.accuracyM <= GOOD_ACCURACY_M && !skyDoubted(nowMs)) {
            LocationTrust.TRUSTED
        } else {
            LocationTrust.DEGRADED
        }
        if (_trust.value == LocationTrust.TRUSTED) {
            // Layer 8 — hand the step track a world anchor while the sky is
            // still worth anchoring to, and reset the displacement it is
            // measured against.
            pdrAnchorE = filter.e
            pdrAnchorN = filter.n
            pdrAnchorSigmaM = filter.uncertaintyM
            havePdrAnchor = true
            lastPdrEastM = 0.0
            lastPdrNorthM = 0.0
            _pedestrianAnchor.value = PedestrianAnchor(
                accuracyM = sample.accuracyM.toDouble(),
                bearingDeg = sample.bearingDeg,
                speedMps = sample.speedMps,
                atMs = nowMs,
            )
        }
        // Keep the reckoner primed against the moment the sky goes out.
        reckoner.seed(
            speedMps = sample.speedMps ?: filter.speedMps.toFloat(),
            bearingDeg = sample.bearingDeg,
            nowMs = nowMs,
        )
        // An accepted fix's course corrects the fused channel — the fuser's
        // own speed gate and step clamp decide how much it is worth.
        bearingFuser.onCourse(
            courseDeg = sample.bearingDeg,
            speedMps = sample.speedMps ?: filter.speedMps.toFloat(),
            nowMs = nowMs,
        )
        publish(nowMs, dead = false)
    }

    /**
     * Called on a timer by the host. Without this, a total GNSS blackout is
     * indistinguishable from a quiet moment — nothing arrives to tell the
     * engine that nothing is arriving.
     */
    fun onTick(nowMs: Long) {
        if (!filter.isInitialized()) return
        val silentFor = nowMs - lastAcceptedMs
        val stationary = isStationary()

        if (_trust.value != LocationTrust.SPOOFED && silentFor > JAM_TIMEOUT_MS) {
            if (stationary) {
                // P0 JAMMED-WHILE-PARKED (field capture 2026-07-21) — a silent
                // sky over a body that is DEMONSTRABLY STILL is a weak or slow
                // sky (indoors, a courtyard, a canyon — where the chip answers
                // once per 10–30 s), NOT electronic warfare. JAMMED here was a
                // ~100 % false positive: the owner has never been jammed, yet
                // this branch fired on every normal indoor gap and armed dead
                // reckoning, which then drew a wandering trail on a parked
                // phone. Jamming needs positive evidence — a sky that has
                // genuinely gone dark while the operator was MOVING (below).
                // Absence of a fix over a parked phone is not that. Fall back no
                // further than DEGRADED: honest low confidence, position held.
                //
                // Layer 7 supplies the positive evidence that comment asked
                // for: a measured C/N0 collapse across the whole constellation
                // IS jamming, whether the operator is moving or parked, and the
                // operator is entitled to be told. Nothing is invented by
                // saying so — the predict below is still gated on !stationary,
                // so a parked phone does not acquire a wandering trail.
                if (rawJammed(nowMs)) {
                    if (_trust.value != LocationTrust.JAMMED) armDeadReckoning(nowMs)
                    _trust.value = LocationTrust.JAMMED
                } else if (_trust.value == LocationTrust.TRUSTED) {
                    _trust.value = LocationTrust.DEGRADED
                }
            } else {
                if (rawJammed(nowMs) || silentFor > SUSTAINED_BLACKOUT_TIMEOUT_MS) {
                    if (_trust.value != LocationTrust.JAMMED) armDeadReckoning(nowMs)
                    _trust.value = LocationTrust.JAMMED
                } else if (_trust.value == LocationTrust.TRUSTED) {
                    _trust.value = LocationTrust.DEGRADED
                    armDeadReckoning(nowMs)
                }
            }
        }

        // Advance the inertial estimate ONLY when there is genuine motion to
        // carry. Stationary ⇒ hold: no predict, no growing doubt, no dead fix.
        // The clock still advances (below) so a jam that begins the moment the
        // operator pulls away does not integrate one huge catch-up step.
        val dtS = if (lastPredictMs == 0L) 0.0 else (nowMs - lastPredictMs) / 1000.0
        if (_trust.value == LocationTrust.JAMMED || _trust.value == LocationTrust.SPOOFED || _trust.value == LocationTrust.DEGRADED) {
            lastPredictMs = nowMs
            if (dtS > 0.0 && !stationary) {
                filter.predict(
                    dtS,
                    processNoiseOverride = Kalman2D.DEAD_RECKONING_PROCESS_NOISE_MPS2,
                )
                deadReckonedM += filter.speedMps * dtS
                publish(nowMs, dead = true)
            }
        } else if (stationary) {
            // Keep the predict clock current under a held (DEGRADED/held-still)
            // sky, so the first moving tick after a real dropout sees a normal
            // dt rather than the whole parked interval.
            lastPredictMs = nowMs
        }
    }

    /**
     * Accept a returning sky the inertial track disagrees with — as
     * DEGRADED, never TRUSTED, with the caller having already said out loud
     * WHY it is conceding (grace exhausted, or the road network vouched).
     * One path, whichever witness yielded: the reference moves, the audit's
     * window restarts, the filter fuses, the reckoner re-seeds.
     */
    private fun concedeReacquisition(
        sample: GpsSample,
        e: Double,
        n: Double,
        nowMs: Long,
        altitudeM: Float?,
    ) {
        gate.acceptReacquisition(sample, nowMs, altitudeM)
        audit.reset()
        clearStaleRefusals()
        reacquireCandidate = null
        deadReckonedM = 0.0
        acceptedFixes++
        val dtR = if (lastPredictMs == 0L) 0.0 else (nowMs - lastPredictMs) / 1000.0
        if (dtR > 0.0) filter.predict(dtR)
        lastPredictMs = nowMs
        filter.update(e, n, sample.accuracyM.toDouble().coerceAtLeast(1.0))
        fuseDopplerVelocity(sample)
        lastAcceptedMs = nowMs
        _trust.value = LocationTrust.DEGRADED
        reckoner.seed(
            speedMps = sample.speedMps ?: filter.speedMps.toFloat(),
            bearingDeg = sample.bearingDeg,
            nowMs = nowMs,
        )
        bearingFuser.onCourse(
            courseDeg = sample.bearingDeg,
            speedMps = sample.speedMps ?: filter.speedMps.toFloat(),
            nowMs = nowMs,
        )
        publish(nowMs, dead = false)
    }

    /**
     * APPLE-LOCK — the accepted fix's Doppler velocity is a measurement in
     * its own right, and a better one than the position it rode in with.
     *
     * Carrier Doppler stays ±0.2–0.5 m/s honest in exactly the urban
     * multipath that scatters the position by 20 m; a filter that only ever
     * sees positions must invent velocity from that scatter, and the
     * invention was the puck's lateral wobble and turn-lag next to Apple
     * Maps (whose filter fuses Doppler). Runs only on fixes the gate has
     * already accepted — a spoofed course was refused before it got here.
     *
     * A ~zero reported speed is fused as an explicit (0,0): the chip pinning
     * the state at a red light beats position scatter dragging it around.
     */
    private fun fuseDopplerVelocity(sample: GpsSample) {
        val speed = sample.speedMps ?: return
        if (speed < DOPPLER_STANDSTILL_MPS) {
            filter.updateVelocity(0.0, 0.0, DOPPLER_STILL_SIGMA_MPS)
            return
        }
        val bearing = sample.bearingDeg ?: return
        val rad = Math.toRadians(bearing.toDouble())
        filter.updateVelocity(
            speed * Math.sin(rad),
            speed * Math.cos(rad),
            DOPPLER_SIGMA_BASE_MPS + DOPPLER_SIGMA_FRACTION * speed,
        )
    }

    /**
     * P0 JAMMED-WHILE-PARKED (field capture 2026-07-21, `EW JAMMED · DR` on a
     * phone lying on a desk) — the authoritative «the body is not going
     * anywhere» signal, and the gate dead reckoning is forbidden to cross.
     *
     * Two independent witnesses must BOTH say still: the fused speed is at a
     * standstill ([STILL_SPEED_MPS]) AND the platform is not vibrating like a
     * moving vehicle ([DeadReckoner.isMoving]). Neither alone is enough — a
     * smooth cruise can be quiet, and a poor Doppler can read low — but a body
     * that is both is genuinely parked, and a parked body has NOTHING to
     * reckon. Dead reckoning on such a body is pure invention, and inventing
     * motion was the wandering orange trail the owner photographed.
     *
     * Note the asymmetry with the old `still` predicate this replaces: that one
     * used [DeadReckoner.isQuiet] (the LOW threshold), so ordinary desk noise
     * still counted as motion. This uses the HIGH [DeadReckoner.isMoving]
     * threshold, so only vehicle-grade vibration disqualifies «still».
     */
    val stationary: Boolean get() = isStationary()

    private fun isStationary(): Boolean =
        filter.speedMps < STILL_SPEED_MPS && !reckoner.isMoving

    private fun armDeadReckoning(nowMs: Long) {
        reckoner.seed(
            speedMps = filter.speedMps.toFloat(),
            bearingDeg = bearingOf(filter.vE, filter.vN),
            nowMs = nowMs,
        )
    }

    private fun publish(nowMs: Long, dead: Boolean) {
        if (!haveOrigin) return
        val lat = originLat + metersToLat(filter.n)
        val lon = originLon + metersToLon(filter.e, originLat)
        _fix.value = FusedFix(
            lat = lat,
            lon = lon,
            speedMps = filter.speedMps.toFloat(),
            // IRONCLAD-1 — the fused course is the bearing, both under a
            // clear sky and a dark one. The Kalman velocity azimuth is the
            // last resort only (cold start before any course was ever seen):
            // at a standstill that azimuth is atan2(noise, noise), and it is
            // exactly the arrow the operator watched spin.
            bearingDeg = if (dead) {
                reckoner.heading ?: bearingFuser.courseDeg ?: bearingOf(filter.vE, filter.vN)
            } else {
                bearingFuser.courseDeg ?: bearingOf(filter.vE, filter.vN)
            },
            uncertaintyM = filter.uncertaintyM.toFloat(),
            trust = _trust.value,
            dead = dead,
            altitudeM = altimeter.altitudeM,
            vertical = altimeter.context,
            timestampMs = nowMs,
        )
    }

    companion object {
        /** No plausible fix for this long = the sky is gone (jammed, or a tunnel). */
        const val JAM_TIMEOUT_MS = 6_000L

        /**
         * Sustained silence in motion before declaring JAMMED without hardware SNR collapse.
         * In city canyons or tunnels, short dropouts (6-20s) fall back to DEGRADED without false EW alerts.
         */
        const val SUSTAINED_BLACKOUT_TIMEOUT_MS = 20_000L

        /** Maximum position bounce attributed to urban multipath before suspecting spoofing. */
        const val URBAN_MULTIPATH_MAX_M = 120.0

        /** Below this fused speed a quiet-vibration body counts as STILL
         *  for doubt growth (never for zeroing a speed — that stays ZUPT's). */
        const val STILL_SPEED_MPS = 1.0

        /** One impossible fix is a glitch; this many in a row is an adversary. */
        const val SPOOF_STREAK = 3

        // ── APPLE-LOCK — Doppler velocity fusion ──────────────────────────

        /** Below this reported speed the chip means «standing»; the bearing
         *  is mush but the ZERO is a measurement worth fusing. */
        const val DOPPLER_STANDSTILL_MPS = 0.75f

        /** How much a reported standstill is doubted (m/s, 1σ). */
        const val DOPPLER_STILL_SIGMA_MPS = 0.6

        /** Moving-fix Doppler doubt: base + fraction·speed (m/s, 1σ). */
        const val DOPPLER_SIGMA_BASE_MPS = 0.5
        const val DOPPLER_SIGMA_FRACTION = 0.05

        /** STATION-KEEPING — a still body's fixes are never trusted below
         *  this accuracy (m): indoor per-fix accuracy claims understate
         *  multipath, and believing them walks the parked dot around. */
        const val STILL_ACCURACY_FLOOR_M = 30.0

        /** Better than this, and the fix is worth being led by. */
        const val GOOD_ACCURACY_M = 25f

        /**
         * How far the sky may be from the inertial track when it returns after
         * a gap, in multiples of the doubt the engine had already admitted to.
         *
         * The engine can only reject what its own uncertainty can distinguish —
         * a five-minute tunnel leaves a ±1.2 km doubt, and a spoofer who plants
         * the operator inside that radius is, honestly, indistinguishable from
         * the truth. Saying so is better than pretending otherwise. What this
         * DOES stop is the cheap version: the jam-then-teleport-across-town.
         */
        /**
         * How wrong the inertial track may honestly be, as a fraction of the
         * distance it carried the operator through the blackout.
         *
         * **0.8 is not a fudge factor; it is the measurement.** Stop-and-go
         * urban dead reckoning really does accumulate 60–80 % of the distance
         * travelled as position error over 30–60 s (the repeated stops, starts
         * and turns are exactly what a scalar along-track model handles worst).
         * A tighter number here is not «more secure» — it simply refuses the
         * operator's OWN honest sky when he comes out of a tunnel, which is F1
         * wearing a different hat, and it was doing precisely that at 0.30.
         *
         * The cost is stated plainly: after a five-minute urban blackout, an
         * adversary who plants the operator within ~2.8 km of where he actually
         * is cannot be distinguished from the truth by inertia alone. That is
         * the real limit of this witness, and the answer to it is a third
         * authority (map-matching), not a braver constant.
         */
        const val REACQUIRE_ERROR_FRACTION = 0.8

        /** Floor, so a brief gap over a short distance is not hair-trigger. */
        const val REACQUIRE_BASE_M = 200.0

        /**
         * Скільки власної заявленої похибки неба входить у допуск на
         * розбіжність з інерцією.
         *
         * Одна сигма, а не півтори як у [CONSIST_ACCURACY_SCALE]: там сходяться
         * ДВА фікси й додаються дві похибки, а тут фікс стоїть проти сліду, чия
         * похибка вже порахована першим доданком.
         */
        const val REACQUIRE_ACCURACY_SCALE = 1.0

        /**
         * Стеля на внесок заявленої похибки.
         *
         * Похибку друкує те саме джерело, яке може бути підмінником, тож без
         * стелі ворота розширювалися б на замовлення нападника. Взято рівною
         * [WITNESS_STANDING_M]: за цією межею інерція вже не має чим заперечити
         * взагалі, тож ширші ворота нічого нового не дають.
         */
        const val REACQUIRE_ACCURACY_CAP_M = 1_000.0

        /**
         * How long the engine may refuse a sky its inertial track disagrees
         * with, before conceding that the inertial track is the unreliable one.
         * ~20 s at 1 Hz. Long enough to ride out a jam-then-spoof handover;
         * short enough that the operator is never permanently stranded.
         */
        const val REACQUIRE_GRACE_FIXES = 20

        /**
         * ...і та сама витримка, названа в секундах оператора.
         *
         * СКАРГА №9: «20 приходів» — це «~20 с» ЛИШЕ на небі 1 Гц, під яке її
         * і рахували (див. коментар вище — він сам так і каже). Чип, що ледве
         * бачить небо, відповідає раз на 10–125 с, і та сама двадцятка мовчки
         * стає 3–42 хвилинами. Одиниця виміру належала чипові, а платив за неї
         * оператор.
         *
         * **30 000 — не смак, а наслідок.** Воно навмисно ДОВШЕ за 20 с, які
         * двадцять приходів займають на проєктному кадансі: доки небо відповідає
         * швидше за ~0,67 Гц, годинник не встигає сказати нічого, лічильник
         * приходів вирішує все сам, і поведінка проти jam-then-spoof лишається
         * побайтово тією самою. Слово він бере рівно там, де його ніколи не
         * рахували — на небі, повільнішому за проєктне. І це впʼятеро довше за
         * [JAM_TIMEOUT_MS], тож жодна коротка пауза його не запускає.
         */
        const val REACQUIRE_GRACE_MS = 30_000L

        /**
         * Нижче цього числа відмов годинник витримку не скорочує НІКОЛИ.
         *
         * Без цієї межі довга пауза сама ставала б доказом, і небо поверталося
         * б з ОДНОГО фікса — рівно та дірка, проти якої весь цей блок і стоїть.
         * Три — з тієї самої логіки, що й [SPOOF_STREAK]: один фікс — збіг,
         * три поспіль — це вже поведінка. Отже, найшвидше можливе повернення
         * коштує чотирьох приходів чипа, а не одного.
         */
        const val REACQUIRE_MIN_REFUSALS = 3

        /**
         * Past this much admitted inertial error, the witness has no standing
         * and the grace counter does not apply: two mutually-consistent
         * returning fixes are accepted (as DEGRADED) immediately. Field-set:
         * the Kyiv drive showed the grace loop repeating for hours once the
         * track was kilometres adrift, each cycle drifting it further.
         */
        const val WITNESS_STANDING_M = 1_000.0

        /** Base agreement radius for two returning fixes; their stated
         *  accuracies and legitimate travel between them are added on top. */
        const val REACQUIRE_CONSIST_M = 120.0

        /**
         * How much of both fixes' ADMITTED error counts toward agreement.
         * Two ±100 m fixes 250 m apart are telling the same story within
         * their own stated doubt — refusing them as «inconsistent» was the
         * home deadlock of 2026-07-18.
         */
        const val CONSIST_ACCURACY_SCALE = 1.5

        /**
         * Стеля на доданок «швидкість × пауза» в радіусі узгодження.
         *
         * Замінює дедлайн на прихід (`REACQUIRE_CONSIST_GAP_MS` = 45 с), який
         * СКАРГА №9 показала непрохідним: чип у записі відповідав раз на 125 с,
         * тож кандидат протухав завжди й умова не ставала істиною ніколи. Сам
         * дедлайн був зайвий — відстань, яку оператор міг подолати, уже оплачує
         * цей доданок; дедлайн лише не давав повільному небу дійти до перевірки.
         *
         * Але без дедлайну пауза стала необмеженою, а швидкість друкує ТЕ САМЕ
         * джерело, яке може бути ворожим: 30 м/с × 10 хв — це 18 км воріт на
         * замовлення нападника. Тому стеля, і з тієї ж причини, що й
         * [REACQUIRE_ACCURACY_CAP_M]. Число взято рівним [WITNESS_STANDING_M]:
         * за цією межею інерція вже нічого не заперечує, тож ширші ворота
         * нічого не додають — лише допомагають підміннику.
         *
         * Нетто це ЗВУЖЕННЯ: старий дедлайн неявно пропускав 30 м/с × 45 с =
         * 1350 м, нова стеля — 1000 м.
         */
        const val REACQUIRE_CONSIST_TRAVEL_CAP_M = 1_000.0

        // ── Layer 6, dormant — road-witness corroboration (ATLAS §7) ──────
        // [DEVICE]-tunable pre-QA: these gate how quickly a road-coherent
        // returning sky is BELIEVED (as DEGRADED); they can never cause a
        // refusal that would not have happened without the witness.

        /** The winning hypothesis must carry at least this much posterior —
         *  in a dense grid a fix that merely lands NEAR several roads does
         *  not tell a story, it tells all of them at once. */
        const val ROAD_WITNESS_MIN_CONFIDENCE = 0.6f

        /** Consecutive connected matches before topology has standing:
         *  three seconds of the sky riding a road, not one lucky landing. */
        const val ROAD_WITNESS_MIN_CHAIN = 3

        // ── Layer 7 — raw-measurement evidence (`:core-gnss`) ─────────────

        /** `GnssIntegrity.compromised` — the producing module's own bar. */
        const val SKY_COMPROMISED = 0.6

        /**
         * Точність, за якої небо доведено працює.
         *
         * Не смак: щоб зійтись на восьми метрах, приймачеві треба кілька
         * чистих сигналів одночасно — рівно те, що глушіння руйнує першим.
         * Ширше за типові 3–5 м у чистому полі й помітно вужче за
         * [GOOD_ACCURACY_M] = 25 м, за яких сумнів іще доречний.
         */
        const val PROVEN_SKY_M = 8f

        /**
         * `JamSpoofMetrics` refuses to update its own baseline above this:
         * it is that module's definition of «not a clean epoch». Below the
         * compromised bar, so it never seizes the position — it only stops
         * the engine calling such a sky TRUSTED.
         */
        const val SKY_DOUBT = 0.3

        /**
         * Doppler-vs-carrier-phase disagreement that cannot be honest signal.
         * `Tdcp` measures centimetres on a real sky (its own test asserts
         * < 0.5 m); a metre is a generator that synthesises code and Doppler
         * without holding coherent phase — most available hardware.
         */
        const val DOPPLER_PHASE_BREAK_M = 1.0

        /**
         * Raw epochs arrive at ~1 Hz. Past this the last verdict describes a
         * sky that no longer exists, and a stale spoof score must never latch
         * the engine for a session.
         */
        const val SKY_EVIDENCE_TTL_MS = 10_000L

        // ── Layer 8 — pedestrian continuity (`:core-indoor`) ──────────────

        /** One human step. Less is the same displacement re-offered. */
        const val PDR_MIN_STEP_M = 0.5
    }
}
