package local.phantom.companion.core.indoor

import local.phantom.companion.core.indoor.pdr.Pdr2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Тести числення шляху пішохода.
 *
 * Головна теза модуля, яку тут і перевіряємо: **похибка росте з ПРОЙДЕНОЮ
 * ВІДСТАННЮ, а не з часом.** Людина, що стоїть на платформі глибокого закладення
 * десять хвилин, не стає менш відомою — кроків немає, помилка не накопичується.
 * Це протилежність автомобільному численню, де інтегрується швидкість і час є
 * природним аргументом. Занизити похибку тут — показати впевнений кружечок не в
 * тому коридорі; роздути від часу простою — оголосити людину загубленою, поки
 * вона просто чекає потяг.
 *
 * Кроки будуються «від істини»: задаємо довжину й курс, тоді координата виходу
 * рахується вручну за тією ж моделлю `E += L·sin(h)`, `N += L·cos(h)`.
 */
class Pdr2DTest {

    private fun step(lengthM: Double, headingRad: Double, elapsedMs: Long, uncRad: Double = 0.0) =
        Step(elapsedMs = elapsedMs, lengthM = lengthM, headingRad = headingRad, headingUncertaintyRad = uncRad)

    // ————————————————————————————————————————————————————————————————
    // Геометрія
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `прямий прохід відкладає відстань точно вздовж курсу`() {
        // Курс 0 — рух строго на північ. 20 кроків по 0.7 м = 14 м на північ,
        // схід лишається нулем. Це базова санітарна перевірка знаку sin/cos.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = 2.0, elapsedMs = 0L)
        var t = 0L
        repeat(20) { pdr.onStep(step(0.7, 0.0, t.also { t += 500 })) }
        val f = pdr.fix!!
        assertEquals("прохід на північ не має зміщувати схід", 0.0, f.point.eastM, 1e-9)
        assertEquals("20×0.7 м = 14 м на північ", 14.0, f.point.northM, 1e-9)
        assertEquals("усі 20 кроків мали зарахуватись", 20, pdr.stepCount)
        assertEquals("пройдений шлях = 14 м", 14.0, pdr.pathLengthM, 1e-9)
    }

    @Test
    fun `прохід з поворотом дає L-подібну траєкторію`() {
        // Типова геометрія вестибюля: коридор на північ, поворот на схід. Кожне
        // плече рахуємо окремо й вимагаємо, щоб фінальна точка збіглася із сумою.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = 2.0, elapsedMs = 0L)
        var t = 0L
        repeat(10) { pdr.onStep(step(0.7, 0.0, t.also { t += 500 })) }        // північ 7 м
        repeat(10) { pdr.onStep(step(0.7, PI / 2, t.also { t += 500 })) }     // схід 7 м
        val f = pdr.fix!!
        assertEquals("східне плече = 7 м", 7.0, f.point.eastM, 1e-6)
        assertEquals("північне плече = 7 м", 7.0, f.point.northM, 1e-6)
    }

    // ————————————————————————————————————————————————————————————————
    // Похибка від відстані, а не від часу
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `похибка росте з кількістю кроків за точною моделлю`() {
        // Звіряємо не «більше-менше», а точну формулу класу:
        //   along  = σ_scale · L
        //   across = √(N·σ_walk²) · L
        //   acc    = √(seed² + along² + across²)
        val seed = 2.0
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = seed, elapsedMs = 0L)
        var t = 0L
        val n = 25
        repeat(n) { pdr.onStep(step(0.7, 0.0, t.also { t += 500 })) }

        val path = n * 0.7
        val along = Pdr2D.DEFAULT_STEP_SCALE_SIGMA * path
        val headingSigma = sqrt(n * Pdr2D.DEFAULT_HEADING_WALK_SIGMA_RAD * Pdr2D.DEFAULT_HEADING_WALK_SIGMA_RAD)
        val across = headingSigma * path
        val expected = sqrt(seed * seed + along * along + across * across)

        assertEquals(
            "оцінка похибки має точно збігтися з моделлю відстань×σ",
            expected, pdr.fix!!.accuracyM, 1e-9,
        )
    }

    @Test
    fun `простій НЕ роздуває похибку — час не є аргументом`() {
        // Ключовий тест модуля. Дві однакові послідовності кроків: у першій між
        // кроками 0.5 с, у другій — цілих 10 хвилин (людина стоїть на платформі
        // між кроками). Похибка залежить лише від пройденої відстані, тож обидві
        // мають дати ІДЕНТИЧНУ оцінку. Якби час підмішувався — друга «загубила б»
        // людину, поки та просто чекає потяг.
        val fast = Pdr2D().apply { seed(LocalPoint(0.0, 0.0), 3.0, 0L) }
        val slow = Pdr2D().apply { seed(LocalPoint(0.0, 0.0), 3.0, 0L) }

        var tf = 0L
        var ts = 0L
        repeat(15) {
            fast.onStep(step(0.75, 0.3, tf.also { tf += 500 }))       // крок кожні 0.5 с
            slow.onStep(step(0.75, 0.3, ts.also { ts += 600_000 }))   // крок кожні 10 хв
        }

        assertEquals(
            "простій між кроками не має впливати на похибку",
            fast.fix!!.accuracyM, slow.fix!!.accuracyM, 1e-9,
        )
        assertEquals(
            "простій не має впливати й на позицію",
            fast.fix!!.point.eastM, slow.fix!!.point.eastM, 1e-9,
        )
    }

    @Test
    fun `повідомлена похибка курсу піднімає оцінку, коли перевищує накопичену`() {
        // Магнітна аномалія в метро підняла невизначеність курсу окремого кроку
        // вище за випадкове блукання гіроскопа. Модуль бере максимум із двох —
        // не можна замовчати той факт, що курс став ненадійним.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = 1.0, elapsedMs = 0L)
        var t = 0L
        // Кілька кроків із великою повідомленою невизначеністю курсу.
        repeat(10) { pdr.onStep(step(0.7, 0.0, t.also { t += 500 }, uncRad = 0.5)) }

        val path = 10 * 0.7
        val across = 0.5 * path // reportedHeadingSigma домінує над √(N)·0.02
        val along = Pdr2D.DEFAULT_STEP_SCALE_SIGMA * path
        val expected = sqrt(1.0 + along * along + across * across)
        assertEquals(
            "велика повідомлена σ курсу має домінувати в поперечній похибці",
            expected, pdr.fix!!.accuracyM, 1e-9,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Сміття на вході
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `крок із NaN не зсуває позицію і не рахується`() {
        // Платформний глюк дав крок із NaN довжиною. Позиція й лічильник мають
        // лишитись недоторканими — крок просто ігнорується.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(5.0, 5.0), accuracyM = 2.0, elapsedMs = 0L)
        pdr.onStep(step(0.7, 0.0, 500))
        val before = pdr.fix!!
        pdr.onStep(step(Double.NaN, 0.0, 1000))
        pdr.onStep(step(0.7, Double.POSITIVE_INFINITY, 1500))
        pdr.onStep(step(-1.0, 0.0, 2000)) // відʼємна довжина — теж брак
        val after = pdr.fix!!
        assertEquals("NaN/∞/відʼємний крок не має зсувати схід", before.point.eastM, after.point.eastM, 1e-12)
        assertEquals("NaN/∞/відʼємний крок не має зсувати північ", before.point.northM, after.point.northM, 1e-12)
        assertEquals("браковані кроки не мають рахуватись", 1, pdr.stepCount)
    }

    // ————————————————————————————————————————————————————————————————
    // Суміщення скидає похибку
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `суміщення садить траєкторію і скидає накопичену похибку до невʼязки`() {
        // Після трьох якорів RTT ГА видав перетворення й залишкову невʼязку.
        // Похибка після суміщення дорівнює якості самого суміщення й починає
        // рости заново; удавати нуль було б тією самою фальшивою впевненістю,
        // проти якої написаний клас.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = 10.0, elapsedMs = 0L)
        var t = 0L
        repeat(20) { pdr.onStep(step(0.7, 0.2, t.also { t += 500 })) }

        val residual = 2.5
        pdr.applyAlignment(Similarity(dxM = 3.0, dyM = -4.0, rotationRad = 0.1, scale = 1.0), residual)

        assertEquals("похибка після суміщення = невʼязка суміщення", residual, pdr.fix!!.accuracyM, 1e-9)
        assertEquals("джерело після суміщення — FUSED", IndoorSource.FUSED, pdr.fix!!.source)
        assertEquals("пройдений шлях має обнулитись після привʼязки", 0.0, pdr.pathLengthM, 1e-12)
    }

    @Test
    fun `нескінченне перетворення суміщення ігнорується, а не руйнує стан`() {
        // ГА може повернути NaN на виродженому вході. Застосовувати таке до всієї
        // кривої — знищити траєкторію. Модуль має мовчки відхилити його.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(1.0, 2.0), accuracyM = 5.0, elapsedMs = 0L)
        pdr.onStep(step(0.7, 0.0, 500))
        val before = pdr.fix!!
        pdr.applyAlignment(Similarity(dxM = Double.NaN, dyM = 0.0, rotationRad = 0.0, scale = 1.0), 1.0)
        val after = pdr.fix!!
        assertEquals("невалідне суміщення не має зсувати позицію", before.point.northM, after.point.northM, 1e-12)
        assertEquals("невалідне суміщення не має змінювати джерело", IndoorSource.PDR, after.source)
    }

    @Test
    fun `seed із NaN похибкою падає на дефолт, а не отруює оцінку`() {
        // Привʼязка з платформи прийшла з NaN точністю. Модуль мусить підставити
        // чесний дефолт, інакше вся подальша похибка стане NaN.
        val pdr = Pdr2D()
        pdr.seed(LocalPoint(0.0, 0.0), accuracyM = Double.NaN, elapsedMs = 0L)
        assertTrue("похибка після seed із NaN має бути скінченною", pdr.fix!!.accuracyM.isFinite())
        assertEquals(Pdr2D.DEFAULT_SEED_ACCURACY_M, pdr.fix!!.accuracyM, 1e-9)
    }
}
