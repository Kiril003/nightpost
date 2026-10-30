package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ATLAS-1 — display-side dot dynamics. The fused fix is smooth in *state
 * space* but arrives at the UI quantized to 1 Hz, and a dot redrawn once a
 * second teleports once a second — that stutter was the whole of the
 * operator-visible "GPS jitter". This class is the missing half: a
 * critically-damped spring tracker meant to be stepped once per *display
 * frame*, chasing the newest fix extrapolated along its own reported
 * velocity.
 *
 * Division of labour, stated precisely so it never blurs:
 *
 *  - [CustomLocationEngine] owns the TRUTH — gating, fusion, dead
 *    reckoning, doubt. Long-horizon prediction through a tunnel is its job,
 *    and it keeps publishing inertial fixes while the sky is dark.
 *  - FixFlux owns only the RENDERING of that truth between publications:
 *    constant-velocity bridging plus a spring that reaches the target without
 *    overshoot. It invents no information — every number it eases toward was
 *    published by the engine.
 *
 * ── СТЕЛЯ ЧИСЛЕННЯ — НЕ ЧАС, А ВЛАСНА ВИЗНАНА ПОХИБКА ───────────────────────
 *
 * До 2026-08-30 бридж обрубувався фіксованою стелею 1,6 с. На записі власника
 * за кермом фікси приходили з МЕДІАННИМ проміжком 9 с — тобто намальована
 * точка жила 1,6 с і стояла мертвою 7,4 с (18 % / 82 %), а потім клацала на
 * нову позицію. Це і є «я їду, а точка стоїть на місці, тільки телепортується».
 *
 * Замінити 1,6 на 9 — та сама вада з іншим числом: час нічого не знає про те,
 * наскільки ми ще маємо право вірити власному численню. Тому стеля тепер одна:
 * **накопичене коло сумніву** [doubtBudgetM]. Числимо, доки коло, яке ДОДАЛО
 * саме числення, не перевищило бюджет; за бюджетом позиція завмирає і кадр
 * виходить із [DrawAs.AREA] — споживач зобовʼязаний намалювати область.
 *
 * Коло росте чесно, з визнаної похибки самої швидкості, а не з красивого
 * числа — див. [coastDoubtM] і константи внизу. Наслідок (бюджет 30 м):
 *
 *   пішохід 1,4 м/с → ≈ 6,3 с    50 км/год (14 м/с) → ≈ 5,9 с
 *   10 м/с          → ≈ 6,1 с    90 км/год (25 м/с) → ≈ 5,1 с
 *   144 км/год      → ≈ 3,9 с
 *
 * Тобто з 9-секундного проміжку запису точка тепер жива дві третини часу на
 * міській швидкості (5,9 с з 9) замість 18 %, а решту — чесно область.
 * Числена точка — НЕ вимір: пауза чесна, гладка брехня ні, тож за бюджетом ми
 * зупиняємо рух, а не домальовуємо впевненість.
 *
 * Pure Kotlin, no Android, no clock of its own: feed it targets and frame
 * times, read eased positions back. One instance per rendered dot.
 */
class FixFlux(
    /** Spring stiffness ω (s⁻¹); damping is fixed critical (ζ = 1). */
    private val omega: Double = OMEGA,
    /** Скільки сумніву численню дозволено ДОДАТИ до виміру, перш ніж точка
     *  перестане бути точкою. Це і є стеля бриджу — в метрах, не в секундах. */
    private val doubtBudgetM: Double = DOUBT_BUDGET_M,
    /** A target jump beyond this snaps instead of rubber-banding across town. */
    private val teleportM: Double = TELEPORT_M,
) {

    /**
     * Чим кадр дозволено малювати. Це НЕ підказка й не косметика: за
     * [AREA] намальована крапка бреше — вона показує впевненість, якої в
     * цей момент не існує ні в одному вимірі.
     */
    enum class DrawAs {
        /** Коло сумніву в межах бюджету — кадр можна малювати точкою. */
        POINT,

        /**
         * Бюджет вичерпано. Позиція в кадрі — центр області радіусом
         * [Eased.uncertaintyM], а не позиція. Споживач ЗОБОВʼЯЗАНИЙ
         * намалювати область.
         *
         * Хто цей стан ігнорує, отримує не брехню, а паузу: за бюджетом
         * числення зупиняє рух точки, тож найгірше, що станеться, — це
         * сьогоднішня чесна зупинка. Але коло при цьому росте далі, і
         * [Eased.settled] ніколи не стане true, тож кадр перемальовується.
         */
        AREA,
    }

    /** One eased frame. [bearingDeg] is null when no fix ever carried one. */
    data class Eased(
        val lat: Double,
        val lon: Double,
        val bearingDeg: Float?,
        /**
         * ЧЕСНЕ коло сумніву навколо ([lat], [lon]) — 1σ, метри.
         *
         * УВАГА, зміна сенсу від 2026-08-30: раніше тут була лише згладжена
         * похибка виміру. Тепер це вимір У СУМІ з тим, що додало числення
         * ([measuredUncertaintyM] ⊕ [coastDoubtM] у квадратурі). Тобто halo,
         * який споживач уже малює з цього поля, під час числення росте сам —
         * без жодної правки на боці малювання.
         */
        val uncertaintyM: Float,
        /** Тільки виміряна частина — згладжена похибка, яку опублікував рушій. */
        val measuredUncertaintyM: Float,
        /** Тільки вигадана частина — скільки сумніву додало саме числення.
         *  0, доки бридж не працює; скидається в 0 на кожному свіжому фіксі. */
        val coastDoubtM: Float,
        /** Точка це ще чи вже область. Див. [DrawAs]. */
        val drawAs: DrawAs,
        /** True when the dot has caught its target and stopped moving —
         *  the caller may skip the draw call entirely. Ніколи не true, поки
         *  коло сумніву росте: інакше область застигла б недомальованою. */
        val settled: Boolean,
    )

    // Local tangent plane: metres east/north of the first target. Re-based on
    // every teleport snap, so the small-angle approximation never has to hold
    // over more than a neighbourhood.
    private var originLat = 0.0
    private var originLon = 0.0
    private var hasTarget = false

    // Target = the newest published fix, in plane metres + its velocity.
    private var tgtE = 0.0
    private var tgtN = 0.0
    private var tgtVE = 0.0
    private var tgtVN = 0.0
    private var tgtAtMs = 0L
    private var tgtBearing: Float? = null
    private var tgtUncertaintyM = 0f

    // Скільки секунд числення бюджет сумніву дозволяє ПРИ ЦІЙ швидкості.
    // Перераховується на кожному фіксі, бо залежить від |v| (див.
    // [recomputeHorizon]). 0 — коли бриджити нема чим.
    private var horizonS = 0.0

    // Display state — what is actually on screen.
    private var e = 0.0
    private var n = 0.0
    private var vE = 0.0
    private var vN = 0.0
    private var bearing: Float? = null

    // Тільки ВИМІРЯНА похибка, згладжена. Те, що йде назовні як
    // Eased.uncertaintyM, — це вона в сумі з вигаданою частиною.
    private var measuredM = 0f
    private var lastStepMs = 0L

    fun reset() {
        hasTarget = false
        lastStepMs = 0L
        bearing = null
        horizonS = 0.0
    }

    /**
     * A new fix arrived. [atMs] is the caller's monotonic clock.
     *
     * [trusted] is the EW verdict on this fix (sky-fused, small doubt). It
     * decides what a beyond-[teleportM] jump means: a TRUSTED jump is a
     * re-acquisition or an engine reset — the truth moved, snap to it. An
     * UNTRUSTED jump is a runaway estimate (spoofed sky, drifted dead
     * reckoning — the Kyiv drive produced 50 of them, up to 12.8 km), and
     * hurling the dot (and the camera chasing it) across town after every
     * one was the operator's «дригання». The display walks toward an
     * untrusted target at no more than [SLEW_MAX_MPS] — the map stays
     * watchable no matter how violently the estimate teleports, and the
     * trust halo (fed by the true uncertainty) carries the honesty.
     */
    fun onTarget(
        lat: Double,
        lon: Double,
        speedMps: Float,
        bearingDeg: Float?,
        uncertaintyM: Float,
        atMs: Long,
        trusted: Boolean = true,
    ) {
        if (!hasTarget) {
            rebase(lat, lon)
            snapTo(0.0, 0.0, speedMps, bearingDeg, uncertaintyM, atMs)
            return
        }
        val newE = lonToMeters(lon - originLon, originLat)
        val newN = latToMeters(lat - originLat)
        val jump = hypot(newE - e, newN - n)
        if (jump > teleportM) {
            if (trusted) {
                // Reacquisition, engine reset, or the operator genuinely moved
                // a district between frames. Chasing it with a spring would
                // draw a confident glide the operator never made.
                rebase(lat, lon)
                snapTo(0.0, 0.0, speedMps, bearingDeg, uncertaintyM, atMs)
                return
            }
            // Untrusted teleport: advance the target toward it at a bounded
            // rate from where the DISPLAY is now. Repeated far targets crawl
            // the dot in their direction; a returning trusted fix snaps.
            val dtT = ((atMs - tgtAtMs) / 1000.0).coerceIn(0.0, SLEW_MAX_GAP_S)
            val maxStep = SLEW_MAX_MPS * dtT
            val scale = if (jump <= maxStep) 1.0 else maxStep / jump
            tgtE = e + (newE - e) * scale
            tgtN = n + (newN - n) * scale
            setTargetMotion(speedMps, bearingDeg, uncertaintyM, atMs)
            return
        }
        tgtE = newE
        tgtN = newN
        setTargetMotion(speedMps, bearingDeg, uncertaintyM, atMs)
    }

    /**
     * Advance the display state to [nowMs] and return it, or null before the
     * first target. Call once per display frame; dt is clamped and substepped,
     * so dropped frames degrade smoothly instead of exploding the integrator.
     */
    fun step(nowMs: Long): Eased? {
        if (!hasTarget) return null
        var dtS = if (lastStepMs == 0L) 0.0 else (nowMs - lastStepMs) / 1000.0
        lastStepMs = nowMs
        dtS = dtS.coerceIn(0.0, MAX_FRAME_GAP_S)

        // Where the target *is believed to be* this frame: the last fix plus
        // its own velocity — доки коло сумніву, яке додає саме це числення, не
        // вийшло за бюджет. Стеля тут метрова, не секундна: [horizonS] — це вже
        // перерахований у секунди бюджет ДЛЯ ЦІЄЇ швидкості.
        val ageS = max(0.0, (nowMs - tgtAtMs) / 1000.0)
        val bridging = hypot(tgtVE, tgtVN) > 0.0
        // Коло росте від ПОВНОГО віку, а не від обрізаного: після зупинки
        // точки оператор не перестає віддалятися, і замерзле коло було б
        // рівно тією косметикою, яку штаб заборонив.
        val coastDoubt = if (bridging) coastDoubtM(ageS) else 0.0
        val drawAs = if (bridging && ageS > horizonS) DrawAs.AREA else DrawAs.POINT
        val bridgeS = if (bridging) min(ageS, horizonS) else 0.0
        val aimE = tgtE + tgtVE * bridgeS
        val aimN = tgtN + tgtVN * bridgeS

        var remaining = dtS
        while (remaining > 1e-9) {
            val h = if (remaining > SUBSTEP_S) SUBSTEP_S else remaining
            remaining -= h
            // Critically damped spring, semi-implicit Euler.
            val aE = omega * omega * (aimE - e) - 2.0 * omega * vE
            val aN = omega * omega * (aimN - n) - 2.0 * omega * vN
            vE += aE * h
            vN += aN * h
            e += vE * h
            n += vN * h
        }

        // Bearing eases by shortest arc toward the newest published bearing.
        val tb = tgtBearing
        if (tb != null) {
            val current = bearing
            bearing = if (current == null) {
                tb
            } else {
                val delta = ((tb - current + 540f) % 360f) - 180f
                val k = (1.0 - Math.exp(-dtS / BEARING_TAU_S)).toFloat()
                (current + delta * k + 360f) % 360f
            }
        }

        // Doubt breathes on its own gentler spring — але згладжуємо лише
        // ВИМІРЯНУ частину: опублікована похибка стрибає сходинкою, а вигадана
        // росте детерміновано й гладко сама, згладжувати її нема чого.
        val ku = (1.0 - Math.exp(-dtS / UNCERTAINTY_TAU_S)).toFloat()
        measuredM += (tgtUncertaintyM - measuredM) * ku
        // Похибка виміру й похибка нашої екстраполяції незалежні → квадратура.
        val honestM = hypot(measuredM.toDouble(), coastDoubt).toFloat()

        val distToAim = hypot(aimE - e, aimN - n)
        val speed = hypot(vE, vN)
        val bearingSettled = tb == null || bearing == null ||
            abs(((tb - bearing!! + 540f) % 360f) - 180f) < SETTLE_BEARING_DEG
        // `drawAs == POINT` тут не зайвий: поки коло росте, кадр мусить
        // перемальовуватись, інакше споживач, що пропускає settled-кадри,
        // залишить на склі застиглу область неправильного розміру.
        val settled = distToAim < SETTLE_DIST_M && speed < SETTLE_SPEED_MPS &&
            bearingSettled && abs(tgtUncertaintyM - measuredM) < SETTLE_UNCERTAINTY_M &&
            hypot(tgtVE, tgtVN) < SETTLE_SPEED_MPS && drawAs == DrawAs.POINT

        return Eased(
            lat = originLat + metersToLat(n),
            lon = originLon + metersToLon(e, originLat),
            bearingDeg = bearing,
            uncertaintyM = honestM,
            measuredUncertaintyM = measuredM,
            coastDoubtM = coastDoubt.toFloat(),
            drawAs = drawAs,
            settled = settled,
        )
    }

    /**
     * Коло, яке додало САМЕ ЧИСЛЕННЯ за [ageS] секунд без нового фікса, 1σ, м.
     *
     * Три незалежні джерела, у квадратурі — жодне не підібране «щоб гарно
     * вийшло», кожне названо своєю фізичною причиною:
     *
     *  1. [SPEED_SIGMA_MPS] — похибка модуля швидкості (доплер). Вздовж
     *     курсу: σ·t.
     *  2. [COURSE_SIGMA_RAD] — похибка курсу. Впоперек: |v|·t·σ. Саме вона
     *     робить стелю коротшою на трасі, ніж пішки: помилка в 10° коштує
     *     тим більше метрів, чим довший пройдений шлях.
     *  3. [MANOEUVRE_ACC_MPS2] — те, чого ми не бачили: гальмо перед
     *     світлофором, поворот. ½·a·t². Домінує у хвості й саме через нього
     *     після ~6 с ми чесно не знаємо, чи людина взагалі ще їде.
     *
     * Нічого не вигадується, коли нічого не малюється: якщо швидкості цілі
     * немає (стоїть, або фікс без курсу), бридж не працює і це коло — 0.
     * Числення не додає сумніву до чужого виміру; вік самого виміру — клопіт
     * рушія, який його опублікував.
     */
    private fun coastDoubtM(ageS: Double): Double {
        val v = hypot(tgtVE, tgtVN)
        val cross = v * COURSE_SIGMA_RAD
        val driftSq = (SPEED_SIGMA_MPS * SPEED_SIGMA_MPS + cross * cross) * ageS * ageS
        val manoeuvre = 0.5 * MANOEUVRE_ACC_MPS2 * ageS * ageS
        return sqrt(driftSq + manoeuvre * manoeuvre)
    }

    /**
     * Переводить бюджет [doubtBudgetM] у секунди для поточної швидкості —
     * рівно один раз на фікс, замкненою формулою, щоб момент, коли точка
     * перестає бути точкою, не залежав від того, коли впав кадр.
     *
     * З [coastDoubtM]: R² = A²t² + B²t⁴, де A² = σ_v² + (|v|·σ_курс)²,
     * B = a/2. Підстановка u = t² дає квадратне
     *     B²u² + A²u − R² = 0  →  u = (−A² + √(A⁴ + 4B²R²)) / 2B²
     */
    private fun recomputeHorizon() {
        val v = hypot(tgtVE, tgtVN)
        if (v <= 0.0) {
            horizonS = 0.0
            return
        }
        val cross = v * COURSE_SIGMA_RAD
        val a2 = SPEED_SIGMA_MPS * SPEED_SIGMA_MPS + cross * cross
        val b2 = (0.5 * MANOEUVRE_ACC_MPS2) * (0.5 * MANOEUVRE_ACC_MPS2)
        val r2 = doubtBudgetM * doubtBudgetM
        val u = (-a2 + sqrt(a2 * a2 + 4.0 * b2 * r2)) / (2.0 * b2)
        horizonS = sqrt(max(0.0, u))
    }

    private fun rebase(lat: Double, lon: Double) {
        originLat = lat
        originLon = lon
    }

    private fun snapTo(
        atE: Double,
        atN: Double,
        speedMps: Float,
        bearingDeg: Float?,
        uncM: Float,
        atMs: Long,
    ) {
        hasTarget = true
        tgtE = atE
        tgtN = atN
        e = atE
        n = atN
        setTargetMotion(speedMps, bearingDeg, uncM, atMs)
        vE = tgtVE
        vN = tgtVN
        bearing = bearingDeg
        measuredM = uncM
    }

    private fun setTargetMotion(speedMps: Float, bearingDeg: Float?, uncM: Float, atMs: Long) {
        // A fix without a bearing has speed but no direction the display may
        // use — bridging with a made-up direction would be invention, so the
        // target holds still and the spring simply catches up.
        if (bearingDeg != null && speedMps > MIN_BRIDGE_SPEED_MPS) {
            val rad = Math.toRadians(bearingDeg.toDouble())
            tgtVE = speedMps * sin(rad)
            tgtVN = speedMps * cos(rad)
        } else {
            tgtVE = 0.0
            tgtVN = 0.0
        }
        tgtAtMs = atMs
        tgtBearing = bearingDeg ?: tgtBearing
        tgtUncertaintyM = uncM
        // Свіжий фікс = вік 0 = вигадане коло скинуте в нуль; стеля числення
        // перераховується під нову швидкість.
        recomputeHorizon()
    }

    companion object {
        /** ω = 7 s⁻¹ ≈ 300 ms to visually reach a stepped target — brisk
         *  enough to never lag a turn, slow enough to read as motion. */
        const val OMEGA = 7.0

        /**
         * СТЕЛЯ ЧИСЛЕННЯ, у метрах сумніву. Скільки числення має право
         * ДОДАТИ понад похибку самого виміру, перш ніж кадр перестане бути
         * точкою.
         *
         * Походження — з тих самих даних, що й скарга. У записі власника
         * міський фікс несе похибку 5–30 м, і найгірший із них, 30 м, екран
         * уже малює точкою. Тобто 30 м — це рівно та неточність, яку продукт
         * УЖЕ визнав сумісною зі словом «точка». Дозволити численню додати
         * більше означало б намалювати впевненість, якої не було навіть у
         * момент виміру; менше — знову вимкнути рух там, де він чесний.
         *
         * Число НЕ підбиралося під 9-секундний проміжок. Медіана 9 с — це
         * перевірка, а не вхід: з бюджетом 30 м вона покривається на ~60 %
         * (див. таблицю в шапці класу), решта чесно стає областю. Якщо
         * колись виміряють іншу міську похибку — рухати треба це число, і
         * секунди самі підуть слідом.
         */
        const val DOUBT_BUDGET_M = 30.0

        /** 1σ похибки модуля швидкості з доплера на телефонному чипі, м/с. */
        const val SPEED_SIGMA_MPS = 0.3

        /** 1σ похибки курсу на ходу: 10° ≈ 0,175 рад. Android рапортує
         *  bearing accuracy у місті в межах 5–20°; беремо середину. */
        const val COURSE_SIGMA_RAD = 0.175

        /** Невраховане прискорення міської їзди, м/с²: гальмування перед
         *  світлофором чи поворот, яких ми не бачили. Комфортні міські
         *  1,5 м/с² — не максимум (різке гальмо це 4–5), а типовий манеор,
         *  який числення пропустило б повністю. */
        const val MANOEUVRE_ACC_MPS2 = 1.5

        const val TELEPORT_M = 150.0

        /** Fastest the display may chase an UNTRUSTED estimate (≈160 km/h) —
         *  quick enough to matter, never a hurl. */
        const val SLEW_MAX_MPS = 45.0

        /** Sparse untrusted updates still step at most this many seconds' worth. */
        const val SLEW_MAX_GAP_S = 2.0

        /** Integrator hygiene: frame gaps clamp here (app was backgrounded). */
        private const val MAX_FRAME_GAP_S = 0.25
        private const val SUBSTEP_S = 1.0 / 30.0

        private const val BEARING_TAU_S = 0.22
        private const val UNCERTAINTY_TAU_S = 0.4

        private const val SETTLE_DIST_M = 0.10
        private const val SETTLE_SPEED_MPS = 0.05
        private const val SETTLE_BEARING_DEG = 0.5f
        private const val SETTLE_UNCERTAINTY_M = 0.1f

        /** Below this, a reported bearing is compass noise, not travel. */
        private const val MIN_BRIDGE_SPEED_MPS = 0.5f
    }
}
