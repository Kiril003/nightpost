package local.phantom.companion.core.indoor.pdr

import local.phantom.companion.core.indoor.IndoorFix
import local.phantom.companion.core.indoor.IndoorSource
import local.phantom.companion.core.indoor.LocalPoint
import local.phantom.companion.core.indoor.Similarity
import local.phantom.companion.core.indoor.Step
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Числення шляху пішохода з двовимірною моделлю похибки.
 *
 * **Чому саме дві величини помилки, а не 15-вимірний вектор стану EKF.**
 * Повний інерціальний фільтр (положення, швидкість, кватерніон, зсуви трьох
 * гіроскопів і трьох акселерометрів) спостережуваний лише тоді, коли є зовнішні
 * виміри, які його оновлюють. Під землею їх немає хвилинами. На MEMS телефона
 * без оновлень такий фільтр робить дві речі одночасно: накопичує похибку
 * швидше, ніж встигає оцінити її ковариацію, і — гірше — сам собі звужує цю
 * ковариацію на кожному кроці передбачення, бо модель вважає себе кращою за
 * реальність. Результат — точка, що впевнено стоїть за 80 метрів від людини з
 * підписом «±3 м». Це найгірший з можливих виходів: не «не знаю», а впевнена
 * брехня.
 *
 * Тут визнано, що спостережуваних величин рівно дві, і саме вони домінують:
 *  • **масштабна похибка довжини кроку** — зростає вздовж пройденого шляху;
 *  • **похибка курсу** — накопичується випадковим блуканням і повертає всю
 *    траєкторію, тобто дає поперечне зміщення, пропорційне пройденому шляху.
 *
 * Усе інше (зсуви акселерометрів, вертикальний канал) або не спостережуване без
 * зовнішніх вимірів, або належить іншим класам модуля.
 *
 * **Похибка росте з ВІДСТАННЮ, а не з часом.** Людина, що стоїть на платформі
 * десять хвилин, не стає менш відомою: кроків немає — помилка не накопичується.
 * Це протилежність до автомобільного числення шляху в `:core-sensor`, де
 * інтегрується швидкість і час є природним аргументом.
 */
class Pdr2D(
    /** Відносна систематична похибка довжини кроку (1σ). 8 % — чесно для моделі без калібрування. */
    private val stepScaleSigma: Double = DEFAULT_STEP_SCALE_SIGMA,
    /** Приріст СКВ курсу за один крок (рад) — випадкове блукання. */
    private val headingWalkSigmaRad: Double = DEFAULT_HEADING_WALK_SIGMA_RAD,
    /** Скільки точок траєкторії тримати. Обмежено, щоб довга сесія не зʼїла памʼять. */
    private val trajectoryCapacity: Int = DEFAULT_TRAJECTORY_CAPACITY,
) {

    private val points = ArrayDeque<LocalPoint>()

    private var eastM = 0.0
    private var northM = 0.0
    private var lastMs = 0L
    private var lastHeadingRad: Double? = null

    /** Пройдений шлях від останньої привʼязки, м. Аргумент росту похибки. */
    var pathLengthM: Double = 0.0
        private set

    /** Кроків від останньої привʼязки. */
    var stepCount: Int = 0
        private set

    /** Дисперсія курсу, накопичена від останньої привʼязки (рад²). */
    private var headingVarRad2 = 0.0

    /** Похибка точки привʼязки — від неї все й починає рости. */
    private var seedAccuracyM = DEFAULT_SEED_ACCURACY_M

    /** Найбільша невизначеність курсу, повідомлена кроками від привʼязки. */
    private var reportedHeadingSigmaRad = 0.0

    /** Копія траєкторії в порядку від найстарішої точки. */
    val trajectory: List<LocalPoint> get() = points.toList()

    /** Поточна оцінка, або `null` поки не було жодного кроку і жодної привʼязки. */
    var fix: IndoorFix? = null
        private set

    /**
     * Привʼязати числення до відомої точки: вихід із метро з останнім
     * супутниковим розвʼязком, розвʼязок по трьох якорях RTT, ручна вказівка на
     * плані. Скидає накопичену похибку — і саме тому це має бути справжня
     * привʼязка, а не здогад.
     */
    fun seed(point: LocalPoint, accuracyM: Double, elapsedMs: Long) {
        eastM = point.eastM
        northM = point.northM
        seedAccuracyM = if (accuracyM.isFinite()) max(accuracyM, 0.0) else DEFAULT_SEED_ACCURACY_M
        pathLengthM = 0.0
        stepCount = 0
        headingVarRad2 = 0.0
        reportedHeadingSigmaRad = 0.0
        lastMs = elapsedMs
        points.clear()
        push(point)
        fix = IndoorFix(
            point = point,
            accuracyM = seedAccuracyM,
            source = IndoorSource.PDR,
            elapsedMs = elapsedMs,
            headingRad = lastHeadingRad,
        )
    }

    /**
     * Один крок. Рекурсивне оновлення: нова точка залежить лише від попередньої,
     * тож вартість кроку стала і не залежить від довжини сесії.
     */
    fun onStep(step: Step): IndoorFix {
        if (!step.lengthM.isFinite() || !step.headingRad.isFinite() || step.lengthM <= 0.0) {
            return fix ?: emptyFix(step.elapsedMs)
        }

        eastM += step.lengthM * sin(step.headingRad)
        northM += step.lengthM * cos(step.headingRad)
        pathLengthM += step.lengthM
        stepCount++
        headingVarRad2 += headingWalkSigmaRad * headingWalkSigmaRad
        if (step.headingUncertaintyRad.isFinite()) {
            reportedHeadingSigmaRad = max(reportedHeadingSigmaRad, step.headingUncertaintyRad)
        }
        lastMs = step.elapsedMs
        lastHeadingRad = step.headingRad

        val point = LocalPoint(eastM, northM)
        push(point)
        val f = IndoorFix(
            point = point,
            accuracyM = accuracyM(),
            source = IndoorSource.PDR,
            elapsedMs = step.elapsedMs,
            headingRad = step.headingRad,
        )
        fix = f
        return f
    }

    /**
     * Чесна оцінка похибки.
     *
     * Уздовж шляху працює масштабна похибка кроку, поперек — накопичений курс.
     * Поперечний доданок навмисне БЕЗ послаблюючого множника: строго кажучи,
     * випадкове блукання курсу дає поперечне зміщення трохи менше за
     * `σ_курсу · L`, бо ранні кроки зроблені з меншою похибкою. Але похибка
     * курсу на телефоні майже завжди має ще й систематичну складову (магнітна
     * аномалія тягне в один бік), а її внесок — рівно `σ · L`. Занизити тут
     * означає показати людині впевнений кружечок не в тому коридорі.
     */
    private fun accuracyM(): Double {
        val alongM = stepScaleSigma * pathLengthM
        val headingSigma = max(sqrt(headingVarRad2), reportedHeadingSigmaRad)
        val acrossM = headingSigma * pathLengthM
        return sqrt(seedAccuracyM * seedAccuracyM + alongM * alongM + acrossM * acrossM)
    }

    /**
     * Посадити всю траєкторію на результат суміщення з якорями RTT.
     *
     * Перетворення застосовується до КОЖНОЇ точки, а не лише до поточної: після
     * повороту кривої змінюється й уся її форма в світовій системі, а траєкторія
     * тут — не історія для краси, а вхід наступного суміщення.
     *
     * @param residualRmsM залишкова невʼязка суміщення — нова базова похибка.
     *   Похибка після суміщення дорівнює якості самого суміщення і починає
     *   рости заново; удавати, що вона нульова, було б тією самою фальшивою
     *   впевненістю, проти якої написаний цей клас.
     */
    fun applyAlignment(transform: Similarity, residualRmsM: Double) {
        if (!transform.dxM.isFinite() || !transform.dyM.isFinite() ||
            !transform.rotationRad.isFinite() || !transform.scale.isFinite()
        ) {
            return
        }
        val moved = points.map { transform.apply(it) }
        points.clear()
        moved.forEach { points.addLast(it) }

        val last = points.lastOrNull() ?: return
        eastM = last.eastM
        northM = last.northM

        seedAccuracyM = if (residualRmsM.isFinite()) max(residualRmsM, MIN_ALIGNED_ACCURACY_M) else DEFAULT_SEED_ACCURACY_M
        pathLengthM = 0.0
        headingVarRad2 = 0.0
        reportedHeadingSigmaRad = 0.0

        val rotated = lastHeadingRad?.let { it + transform.rotationRad }
        lastHeadingRad = rotated
        fix = IndoorFix(
            point = last,
            accuracyM = seedAccuracyM,
            source = IndoorSource.FUSED,
            elapsedMs = lastMs,
            headingRad = rotated,
        )
    }

    fun reset() {
        points.clear()
        eastM = 0.0
        northM = 0.0
        pathLengthM = 0.0
        stepCount = 0
        headingVarRad2 = 0.0
        reportedHeadingSigmaRad = 0.0
        seedAccuracyM = DEFAULT_SEED_ACCURACY_M
        lastMs = 0L
        lastHeadingRad = null
        fix = null
    }

    private fun push(p: LocalPoint) {
        points.addLast(p)
        while (points.size > trajectoryCapacity) points.removeFirst()
    }

    private fun emptyFix(elapsedMs: Long) = IndoorFix(
        point = LocalPoint(eastM, northM),
        accuracyM = accuracyM(),
        source = IndoorSource.PDR,
        elapsedMs = elapsedMs,
        headingRad = lastHeadingRad,
    )

    companion object {
        const val DEFAULT_STEP_SCALE_SIGMA = 0.08
        const val DEFAULT_HEADING_WALK_SIGMA_RAD = 0.02
        const val DEFAULT_SEED_ACCURACY_M = 10.0
        const val DEFAULT_TRAJECTORY_CAPACITY = 4_096

        /** Суміщення не буває точнішим за це — не обіцяй сантиметрів. */
        const val MIN_ALIGNED_ACCURACY_M = 1.0
    }
}
