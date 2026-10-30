package local.phantom.companion.core.indoor.pdr

import local.phantom.companion.core.indoor.LocalPoint
import local.phantom.companion.core.indoor.Step
import local.phantom.companion.core.indoor.baro.BarometricLevel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Які саме пішохідні датчики реально є на цьому пристрої.
 *
 * Інвентар приходить ззовні, з платформи, а не «вгадується» за тим, чи прийшли
 * семпли: різниця між «магнітометра немає» і «магнітометр є, але поки мовчить»
 * для користувача принципова. Перше — назавжди, друге — на секунди, і підпис на
 * екрані має бути різний.
 */
data class PedestrianSensors(
    val accelerometer: Boolean = false,
    val gyroscope: Boolean = false,
    val rotationVector: Boolean = false,
    val magnetometer: Boolean = false,
    val barometer: Boolean = false,
)

/**
 * Стан автономного ходу — рівно те, що можна чесно сказати людині.
 *
 * Станів шість, а не два, бо «працює / не працює» приховує причину. Людині в
 * переході потрібна не оцінка якості, а дія: чекати, вийти на відкрите місце,
 * дістати телефон із кишені, чи взагалі не покладатись на цю цифру.
 */
enum class ContinuityState {
    /** Немає акселерометра — рахувати кроки нічим. Незворотно. */
    UNAVAILABLE,

    /** Датчики живі, але жодного кроку ще не зафіксовано. Показувати нічого. */
    WARMING,

    /** Кроків немає вже кілька секунд: людина стоїть. Похибка не росте. */
    STILL,

    /** Кроки йдуть, але курс невідомий: шлях відомий, напрямок — ні. */
    BLIND,

    /** Кроки й курс є, похибка ще в межах корисного. */
    HOLDING,

    /** Похибка переросла поріг корисності — потрібна нова привʼязка. */
    DRIFTED,
}

/**
 * Незмінний знімок автономного ходу.
 *
 * Тут навмисно НЕМАЄ широти й довготи. Числення шляху дає ЗМІЩЕННЯ від точки
 * привʼязки, а не координату; перетворення зміщення на координату — окрема
 * відповідальність того, хто володіє привʼязкою. Знімок, який ніс би «позицію»,
 * неминуче показав би вигадану позицію в той момент, коли привʼязки немає, —
 * рівно те, проти чого написаний увесь цей модуль.
 *
 * @param displacementM модуль зміщення від привʼязки, м
 * @param displacementBearingRad азимут зміщення (від півночі за годинниковою);
 *   `null`, поки курс невідомий — напрямок не вигадується
 * @param accuracyM чесна 1σ-похибка зміщення разом із внеском «сліпих» кроків
 * @param informative зміщення більше за власну похибку, тобто справді щось
 *   стверджує. Хибне значення означає «людина, можливо, і не рухалась» — і саме
 *   так це має бути написано на екрані, а не числом
 * @param anchoredToWorld привʼязка — реальна супутникова точка (а не умовний
 *   початок сесії), тож зміщення можна перекласти в координату
 */
data class PedestrianSnapshot(
    val state: ContinuityState = ContinuityState.UNAVAILABLE,
    val elapsedMs: Long = 0L,
    val stepCount: Int = 0,
    val pathLengthM: Double = 0.0,
    val cadenceHz: Double = 0.0,
    val displacementM: Double = 0.0,
    val displacementBearingRad: Double? = null,
    val accuracyM: Double = 0.0,
    val informative: Boolean = false,
    val anchoredToWorld: Boolean = false,
    val sinceAnchorMs: Long = 0L,
    val headingRad: Double? = null,
    val headingUncertaintyRad: Double = 0.0,
    val magneticAnomaly: Boolean = false,
    /** Кут «пристрій ↔ тіло», якщо його вдалося спостерегти; `null` — ще ні. */
    val misalignmentRad: Double? = null,
    val level: Double? = null,
    val relativeAltitudeM: Double = 0.0,
    val verticalRateMps: Double = 0.0,
    val inVerticalTransit: Boolean = false,
    val sensors: PedestrianSensors = PedestrianSensors(),
)

/**
 * Складання пішохідної автономії з деталей цього модуля в одну живу річ.
 *
 * **Навіщо цей клас узагалі існує.** `:core-indoor` містив чотири окремі,
 * ретельно перевірені механізми — детектор кроків, оцінювач курсу, числення
 * шляху і барометр — і жодного місця, де вони зводяться докупи. Кожен окремо
 * марний: детектор кроків без курсу дає лише лічильник, курс без кроків не рухає
 * нікого, а барометр без решти — цікавий графік. Тут вони стають одним джерелом
 * безперервності: коли супутник зникає (глушіння, тунель, вестибюль), позиція не
 * обривається, а продовжується від останньої довіреної точки з чесно зростаючою
 * похибкою.
 *
 * **Чому весь клас — чистий Kotlin.** Жодного `android.hardware`: на вході сирі
 * числа (три осі акселерометра, три осі гіроскопа, азимут, модуль поля, тиск),
 * на виході [PedestrianSnapshot]. Тому вся послідовність «крок → курс →
 * зміщення → похибка» перевіряється JVM-тестом за секунду, а не спуском у метро
 * з ноутбуком. Платформи тут торкається рівно один клас у `:feature-precision`.
 *
 * **Головна проблема PDR на телефоні — неузгодженість «пристрій ↔ тіло».**
 * Гіроскоп і магнітометр дають курс КОРПУСУ ТЕЛЕФОНА, а йде ЛЮДИНА. Телефон у
 * задній кишені дивиться назад, у нагрудній — убік, у руці при ходьбі гуляє на
 * десятки градусів. Різниця між цими двома курсами — систематична помилка, яка
 * не зникає з усередненням і повертає всю траєкторію. Класичне «рішення» —
 * мовчки вважати її нулем — і є тим, як PDR перетворюється на впевнену брехню.
 *
 * Тут вона, по-перше, оголошена: у похибку курсу завжди входить її 1σ. По-друге,
 * вона СПОСТЕРЕЖУВАНА рівно в один момент — коли супутник ще живий і людина вже
 * йде: тоді курс руху з GNSS — це курс тіла, азимут із магнітометра — курс
 * пристрою, а їхня різниця і є шукана неузгодженість. Саме тому передача
 * «супутник → числення» має відбуватись ДО того, як супутник зник, а не після.
 *
 * **Чому невизначеність цього кута знову росте з часом.** Людина дістає телефон
 * із кишені, перекладає в іншу руку, повертає екраном до себе. Виміряна дві
 * хвилини тому неузгодженість після цього просто хибна. Тому її σ повзе назад до
 * апріорної: стара калібровка чесно втрачає силу замість удавати точність.
 *
 * Клас не потокобезпечний — у нього рівно один власник, цикл датчиків.
 */
class PedestrianContinuity(
    val sensors: PedestrianSensors,
    /**
     * Апріорна 1σ неузгодженості «пристрій ↔ тіло», рад. 0.35 ≈ 20° — це
     * приблизно розкид між «телефон у витягнутій руці екраном уперед» і
     * «телефон у передній кишені»; менше означало б обіцяти знання про те, як
     * саме людина його несе.
     */
    private val misalignmentPriorSigmaRad: Double = DEFAULT_MISALIGNMENT_PRIOR_RAD,
) {

    private val steps = StepDetector()
    private val heading = HeadingEstimator()
    private val pdr = Pdr2D()
    private val baro = BarometricLevel()

    // — напрямок «вгору» в системі координат пристрою ————————————————
    // Потрібен рівно для одного: спроєктувати вектор кутової швидкості на
    // вертикаль. Без цього гіроскоп телефона, що лежить екраном до стіни, дав би
    // «поворот» там, де людина йде прямо.
    private var upX = 0.0
    private var upY = 0.0
    private var upZ = 0.0
    private var gravityReady = false
    private var lastAccelMs = 0L

    private var azimuthRad = Double.NaN
    private var azimuthMs = 0L

    /** Курс має абсолютну привʼязку (магнітну або супутникову), а не «нуль за замовчуванням». */
    private var haveAbsoluteHeading = false

    private var misalignmentRad = 0.0
    private var misalignmentSigmaRad = misalignmentPriorSigmaRad
    private var misalignmentObservedMs = 0L
    private var misalignmentObserved = false

    private var firstSampleMs = 0L
    private var lastStepMs = 0L
    private var cadenceHz = 0.0

    private var anchored = false
    private var anchorMs = 0L
    private var anchorAccuracyM = 0.0
    private var anchoredToWorld = false

    private var stepsSinceAnchor = 0
    private var totalSteps = 0
    private var pathLengthM = 0.0

    /**
     * Шлях, пройдений кроками БЕЗ відомого курсу, м.
     *
     * Ці кроки не зрушили жодної координати — напрямку не було, — але людина в
     * просторі перемістилась. Ігнорувати їх означало б показати похибку меншою,
     * ніж вона є; тому вони входять у похибку ізотропним доданком: «десь у колі
     * такого радіуса».
     */
    private var blindPathM = 0.0

    // ————————————————————————————————————————————————————————————————
    // Входи з датчиків
    // ————————————————————————————————————————————————————————————————

    /**
     * Три осі акселерометра, м/с², разом із силою тяжіння.
     *
     * Це єдиний вхід, що просуває числення: саме тут закривається крок і саме до
     * нього прикладається поточний курс. Решта входів лише уточнюють курс і
     * висоту.
     */
    fun onAccelerometer(elapsedMs: Long, ax: Double, ay: Double, az: Double) {
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return
        if (firstSampleMs == 0L) firstSampleMs = elapsedMs
        // Умовний початок відліку: зміщення від точки, де людина була в цю мить,
        // дорівнює нулю — і відоме воно точно, з нульовою похибкою. Невідома тут
        // АБСОЛЮТНА координата, а не зміщення, тому anchoredToWorld лишається
        // хибним. Умова саме на [anchored], а не на перший семпл: супутник
        // звичайно вже працює, коли екран відкривають, тож довірена привʼязка
        // цілком може прийти РАНІШЕ за перший семпл акселерометра — і затерти її
        // умовним початком означало б викинути єдину справжню привʼязку.
        if (!anchored) anchor(elapsedMs, accuracyM = 0.0, toWorld = false)
        updateGravity(elapsedMs, ax, ay, az)

        val event = steps.onSample(elapsedMs, sqrt(ax * ax + ay * ay + az * az)) ?: return
        lastStepMs = event.elapsedMs
        cadenceHz = event.cadenceHz
        totalSteps++
        stepsSinceAnchor++
        pathLengthM += event.lengthM

        val bodyHeading = bodyHeadingRad()
        if (bodyHeading == null) {
            blindPathM += event.lengthM
            return
        }
        pdr.onStep(
            Step(
                elapsedMs = event.elapsedMs,
                lengthM = event.lengthM,
                headingRad = bodyHeading,
                headingUncertaintyRad = headingSigmaRad(event.elapsedMs),
            ),
        )
    }

    /**
     * Три осі гіроскопа, рад/с, у системі координат пристрою.
     *
     * Проєктується на вертикаль, задану напрямком тяжіння: цікавить лише
     * обертання навколо вертикалі, бо саме воно змінює курс. Нахили телефона
     * навколо горизонтальних осей людину нікуди не повертають.
     */
    fun onGyroscope(elapsedMs: Long, gx: Double, gy: Double, gz: Double) {
        if (!gravityReady) return
        if (!gx.isFinite() || !gy.isFinite() || !gz.isFinite()) return
        heading.onGyro(elapsedMs, yawRateAboutVertical(gx, gy, gz, upX, upY, upZ))
    }

    /**
     * Азимут пристрою, рад від півночі за годинниковою стрілкою.
     *
     * Береться готовим (на Android — з `TYPE_ROTATION_VECTOR`), бо компенсація
     * нахилу — робота платформи, і дублювати тут кватерніони немає сенсу. Сам по
     * собі азимут ще нічого не змінює: він набуває сили лише разом із модулем
     * поля, який каже, чи можна цьому азимуту вірити.
     */
    fun onAzimuth(elapsedMs: Long, azimuthRad: Double) {
        if (!azimuthRad.isFinite()) return
        this.azimuthRad = wrap(azimuthRad)
        this.azimuthMs = elapsedMs
    }

    /**
     * Три осі магнітометра, мкТл.
     *
     * Модуль поля — детектор брехні для азимута: під землею рейки, контактна
     * мережа і сам вагон піднімають його з ~50 до 90 мкТл, і азимут у такому полі
     * повернутий на десятки градусів у випадковий бік.
     */
    fun onMagneticField(elapsedMs: Long, mx: Double, my: Double, mz: Double) {
        if (!mx.isFinite() || !my.isFinite() || !mz.isFinite()) return
        val az = azimuthRad
        if (az.isNaN() || elapsedMs - azimuthMs > MAX_AZIMUTH_AGE_MS) return

        heading.onMagnetic(elapsedMs, az, sqrt(mx * mx + my * my + mz * mz))

        // Перший АБСОЛЮТНИЙ курс беремо не з першого ж виміру, а лише коли
        // оцінювач сам набрав довіру до поля. Інакше курс, узятий біля
        // ескалатора, зафіксував би всю подальшу траєкторію поверненою.
        if (!haveAbsoluteHeading && !heading.anomaly && heading.magTrust >= MAG_TRUST_FOR_SEED) {
            heading.seedHeading(az, MAG_SEED_SIGMA_RAD)
            haveAbsoluteHeading = true
        }
    }

    /** Абсолютний тиск, гПа. Вертикальна складова — найдешевша й найнадійніша. */
    fun onPressure(elapsedMs: Long, hpa: Double) {
        baro.onPressure(elapsedMs, hpa)
    }

    // ————————————————————————————————————————————————————————————————
    // Привʼязка до довіреного розвʼязку
    // ————————————————————————————————————————————————————————————————

    /**
     * Довірений супутниковий розвʼязок: скинути накопичений дрейф і, головне,
     * навчитись на ньому, поки він ще є.
     *
     * Викликати ЛИШЕ тоді, коли розвʼязку справді можна вірити (немає ознак
     * підміни, супутників достатньо, похибка мала) — рішення про це належить
     * тому, хто бачить цілісність сигналу, а не цьому класу.
     *
     * @param bearingRad курс РУХУ з GNSS; має сенс тільки на ходу
     * @param speedMps горизонтальна швидкість — і калібрування довжини кроку,
     *   і умова осмисленості курсу руху
     */
    fun onTrustedFix(
        elapsedMs: Long,
        accuracyM: Double,
        bearingRad: Double? = null,
        speedMps: Double? = null,
    ) {
        if (speedMps != null) steps.calibrate(speedMps, elapsedMs)

        if (bearingRad != null && bearingRad.isFinite() &&
            speedMps != null && speedMps >= MIN_BEARING_SPEED_MPS
        ) {
            if (haveAbsoluteHeading) {
                observeMisalignment(elapsedMs, signedDelta(heading.headingRad, wrap(bearingRad)))
            } else {
                // Магнітометра немає або він ще не вартий довіри. Тоді курс руху
                // з GNSS стає єдиною абсолютною привʼязкою: далі гіроскоп веде
                // його сам. Неузгодженість при цьому за визначенням нульова —
                // але лише в цю мить, тож її σ лишається апріорною.
                heading.seedHeading(wrap(bearingRad), GNSS_BEARING_SIGMA_RAD)
                misalignmentRad = 0.0
                misalignmentSigmaRad = misalignmentPriorSigmaRad
                misalignmentObservedMs = elapsedMs
                misalignmentObserved = false
                haveAbsoluteHeading = true
            }
        }

        anchor(elapsedMs, accuracyM, toWorld = true)

        // Супутник видно — отже, небо відкрите, отже, це вулиця. Єдиний момент,
        // коли відносну висоту барометра можна назвати нульовим рівнем.
        baro.calibrateAt(0.0)
    }

    /** Забути привʼязку і почати відлік зміщення заново. */
    private fun anchor(elapsedMs: Long, accuracyM: Double, toWorld: Boolean) {
        val acc = if (accuracyM.isFinite() && accuracyM >= 0.0) accuracyM else DEFAULT_ANCHOR_ACCURACY_M
        pdr.seed(LocalPoint(0.0, 0.0), acc, elapsedMs)
        anchored = true
        anchorMs = elapsedMs
        anchorAccuracyM = acc
        anchoredToWorld = toWorld
        stepsSinceAnchor = 0
        pathLengthM = 0.0
        blindPathM = 0.0
    }

    // ————————————————————————————————————————————————————————————————
    // Вихід
    // ————————————————————————————————————————————————————————————————

    /** Знімок на момент [elapsedMs]. Чиста функція від накопиченого стану. */
    fun snapshot(elapsedMs: Long): PedestrianSnapshot {
        if (!sensors.accelerometer) {
            return PedestrianSnapshot(
                state = ContinuityState.UNAVAILABLE,
                elapsedMs = elapsedMs,
                sensors = sensors,
            )
        }

        val fix = pdr.fix
        val east = fix?.point?.eastM ?: 0.0
        val north = fix?.point?.northM ?: 0.0
        val displacement = hypot(east, north)

        // Похибка PDR — уздовж і поперек траєкторії; «сліпі» кроки додаються
        // ізотропно, бо про їхній напрямок не відомо взагалі нічого.
        val pdrAccuracy = fix?.accuracyM ?: anchorAccuracyM
        val accuracy = sqrt(pdrAccuracy * pdrAccuracy + blindPathM * blindPathM)

        val headingKnown = haveAbsoluteHeading
        val informative = headingKnown && displacement > accuracy
        val idle = lastStepMs == 0L || elapsedMs - lastStepMs > STILL_TIMEOUT_MS

        val state = when {
            totalSteps == 0 -> ContinuityState.WARMING
            idle -> ContinuityState.STILL
            !headingKnown -> ContinuityState.BLIND
            accuracy > MAX_USEFUL_ACCURACY_M -> ContinuityState.DRIFTED
            else -> ContinuityState.HOLDING
        }

        return PedestrianSnapshot(
            state = state,
            elapsedMs = elapsedMs,
            stepCount = stepsSinceAnchor,
            pathLengthM = pathLengthM,
            cadenceHz = if (idle) 0.0 else cadenceHz,
            displacementM = displacement,
            // Напрямок зміщення оголошується лише тоді, коли курс має абсолютну
            // привʼязку І зміщення вже відрізняється від нуля. Інакше atan2 від
            // шуму дав би впевнену стрілку в випадковий бік.
            displacementBearingRad = if (headingKnown && displacement > MIN_BEARING_DISPLACEMENT_M) {
                wrap(atan2(east, north))
            } else {
                null
            },
            accuracyM = accuracy,
            informative = informative,
            anchoredToWorld = anchoredToWorld,
            sinceAnchorMs = if (!anchored) 0L else (elapsedMs - anchorMs).coerceAtLeast(0L),
            headingRad = if (headingKnown) bodyHeadingRad() else null,
            headingUncertaintyRad = if (headingKnown) headingSigmaRad(elapsedMs) else PI,
            magneticAnomaly = heading.anomaly,
            misalignmentRad = if (misalignmentObserved) misalignmentRad else null,
            level = if (sensors.barometer) baro.level else null,
            relativeAltitudeM = baro.relativeAltitudeM,
            verticalRateMps = baro.verticalRateMps,
            inVerticalTransit = sensors.barometer && baro.inVerticalTransit,
            sensors = sensors,
        )
    }

    fun reset() {
        steps.reset()
        heading.reset()
        pdr.reset()
        baro.reset()
        upX = 0.0; upY = 0.0; upZ = 0.0
        gravityReady = false
        lastAccelMs = 0L
        azimuthRad = Double.NaN
        azimuthMs = 0L
        haveAbsoluteHeading = false
        misalignmentRad = 0.0
        misalignmentSigmaRad = misalignmentPriorSigmaRad
        misalignmentObservedMs = 0L
        misalignmentObserved = false
        firstSampleMs = 0L
        lastStepMs = 0L
        cadenceHz = 0.0
        anchored = false
        anchorMs = 0L
        anchorAccuracyM = 0.0
        anchoredToWorld = false
        stepsSinceAnchor = 0
        totalSteps = 0
        pathLengthM = 0.0
        blindPathM = 0.0
    }

    // ————————————————————————————————————————————————————————————————
    // Внутрішнє
    // ————————————————————————————————————————————————————————————————

    /**
     * Напрямок тяжіння як однополюсне середнє сирого вектора прискорення.
     *
     * Стала часу та сама, що й у смуговому фільтрі кроків: усе, що швидше за
     * секунду, — це хода, а не поворот телефона в руці.
     */
    private fun updateGravity(elapsedMs: Long, ax: Double, ay: Double, az: Double) {
        if (!gravityReady) {
            upX = ax; upY = ay; upZ = az
            gravityReady = true
            lastAccelMs = elapsedMs
            return
        }
        val dtS = (elapsedMs - lastAccelMs) / 1000.0
        lastAccelMs = elapsedMs
        if (dtS <= 0.0) return
        if (dtS > MAX_ACCEL_GAP_S) {
            upX = ax; upY = ay; upZ = az
            return
        }
        val k = 1.0 - exp(-dtS / TAU_GRAVITY_S)
        upX += k * (ax - upX)
        upY += k * (ay - upY)
        upZ += k * (az - upZ)
    }

    /** Курс ТІЛА: курс пристрою плюс спостережена неузгодженість. */
    private fun bodyHeadingRad(): Double? =
        if (haveAbsoluteHeading) wrap(heading.headingRad + misalignmentRad) else null

    /**
     * 1σ курсу тіла: невизначеність курсу пристрою і невизначеність
     * неузгодженості складаються в квадратурі — це незалежні джерела.
     */
    private fun headingSigmaRad(elapsedMs: Long): Double {
        val device = heading.uncertaintyRad
        val mis = misalignmentSigmaNow(elapsedMs)
        return min(PI, sqrt(device * device + mis * mis))
    }

    /**
     * Невизначеність неузгодженості на момент [elapsedMs].
     *
     * Росте від часу останнього спостереження назад до апріорної: телефон могли
     * перекласти з руки в кишеню, і калібровка двохвилинної давності після цього
     * просто хибна. Обмежена зверху апріорною — гірше, ніж «не знаю», не буває.
     */
    private fun misalignmentSigmaNow(elapsedMs: Long): Double {
        if (!misalignmentObserved) return misalignmentPriorSigmaRad
        val ageS = ((elapsedMs - misalignmentObservedMs).coerceAtLeast(0L)) / 1000.0
        val grown = sqrt(misalignmentSigmaRad * misalignmentSigmaRad + MISALIGN_WALK_VAR_PER_S * ageS)
        return min(misalignmentPriorSigmaRad, grown)
    }

    /**
     * Один вимір неузгодженості «пристрій ↔ тіло».
     *
     * Оновлення повільне і по найкоротшій дузі: один супутниковий курс на
     * повороті — це не нова манера нести телефон, а лише те, що людина в цю
     * секунду завертала.
     */
    private fun observeMisalignment(elapsedMs: Long, observedDelta: Double) {
        if (!observedDelta.isFinite()) return
        val sigmaBefore = misalignmentSigmaNow(elapsedMs)
        misalignmentRad = wrapSigned(
            misalignmentRad + MISALIGN_RATE * signedDelta(misalignmentRad, observedDelta),
        )
        val blended = sqrt(
            (1.0 - MISALIGN_RATE) * (1.0 - MISALIGN_RATE) * sigmaBefore * sigmaBefore +
                MISALIGN_RATE * MISALIGN_RATE * GNSS_BEARING_SIGMA_RAD * GNSS_BEARING_SIGMA_RAD,
        )
        misalignmentSigmaRad = blended.coerceIn(MIN_MISALIGN_SIGMA_RAD, misalignmentPriorSigmaRad)
        misalignmentObservedMs = elapsedMs
        misalignmentObserved = true
    }

    companion object {
        /**
         * Проєкція кутової швидкості на вертикаль.
         *
         * **Чому це не просто `gyroZ`.** Вісь Z телефона — це нормаль до екрана,
         * а не вертикаль світу. У телефона в кишені вона майже горизонтальна, і
         * «поворот навколо Z» там — це людина, що нахиляється, а не повертає.
         * Вертикаль задає вектор тяжіння, виміряний тим самим пристроєм.
         *
         * @param upX/[upY]/[upZ] напрямок «вгору» в системі пристрою — це саме
         *   низькочастотна частина акселерометра: у спокої він міряє питому
         *   силу, тобто вектор, спрямований ВІД центру Землі
         * @return рад/с, додатне — проти годинникової стрілки, якщо дивитись
         *   згори (тобто азимут ЗМЕНШУЄТЬСЯ) — знак, якого чекає
         *   [HeadingEstimator.onGyro]
         */
        fun yawRateAboutVertical(
            gyroX: Double,
            gyroY: Double,
            gyroZ: Double,
            upX: Double,
            upY: Double,
            upZ: Double,
        ): Double {
            val norm = sqrt(upX * upX + upY * upY + upZ * upZ)
            if (!norm.isFinite() || norm < MIN_GRAVITY_NORM) return 0.0
            return (gyroX * upX + gyroY * upY + gyroZ * upZ) / norm
        }

        const val DEFAULT_MISALIGNMENT_PRIOR_RAD = 0.35

        /** Нижче цієї σ не спускаємось: сам курс руху з GNSS точніший не буває. */
        const val MIN_MISALIGN_SIGMA_RAD = 0.10

        /** Типова 1σ курсу руху з GNSS на ходу, рад (≈10°). */
        const val GNSS_BEARING_SIGMA_RAD = 0.17

        /** Частка невʼязки, що приймається за один вимір неузгодженості. */
        const val MISALIGN_RATE = 0.30

        /** (0.05 рад/с)² — за хвилину без підтвердження σ додає ≈22°. */
        const val MISALIGN_WALK_VAR_PER_S = 2.5e-3

        /** Повільніше — це вже не хода, і курс руху з GNSS там шум. */
        const val MIN_BEARING_SPEED_MPS = 0.8

        /** Довіра до магнітометра, з якої можна брати перший абсолютний курс. */
        const val MAG_TRUST_FOR_SEED = 0.35

        /** Навіть у чистому полі компас телефона дає ±10°. */
        const val MAG_SEED_SIGMA_RAD = 0.17

        /** Азимут, старший за це, до модуля поля не вʼяжеться. */
        const val MAX_AZIMUTH_AGE_MS = 1_500L

        const val TAU_GRAVITY_S = 1.0
        const val MAX_ACCEL_GAP_S = 1.5
        const val MIN_GRAVITY_NORM = 0.5

        /** Стільки без жодного кроку — людина стоїть, а не «повільно йде». */
        const val STILL_TIMEOUT_MS = 4_000L

        /**
         * Похибка, за якою зміщення перестає бути корисним пішоходу.
         *
         * Пʼятдесят метрів — це вже не «той самий коридор», а «десь у цьому
         * кварталі». Показувати таке як хід означає вчити людину довіряти
         * цифрі, яка вже нічого не означає.
         */
        const val MAX_USEFUL_ACCURACY_M = 50.0

        /** Менше — і напрямок зміщення був би азимутом шуму. */
        const val MIN_BEARING_DISPLACEMENT_M = 0.5

        const val DEFAULT_ANCHOR_ACCURACY_M = 10.0

        private const val TAU = 2.0 * PI

        internal fun wrap(r: Double): Double = ((r % TAU) + TAU) % TAU

        /** Той самий кут, зведений у (−π, π]. */
        internal fun wrapSigned(r: Double): Double {
            val w = wrap(r)
            return if (w > PI) w - TAU else w
        }

        internal fun signedDelta(from: Double, to: Double): Double {
            var d = (to - from + PI) % TAU
            if (d < 0) d += TAU
            return d - PI
        }
    }
}
