package local.phantom.companion.core.indoor

import local.phantom.companion.core.indoor.pdr.StepDetector
import local.phantom.companion.core.indoor.pdr.StepEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Тести детектора кроків з акселерометра.
 *
 * Сценарій: людина спускається в київське метро, супутника вже немає, і єдине,
 * що лишилось для горизонтального числення шляху, — рахувати кроки з MEMS
 * телефона. Ціна помилки асиметрична: зайвий крок від тряски в руці штовхає
 * людину «вперед» по коридору до неправильного виходу, а пропущений крок лишає
 * її позаду. Тому перевіряємо і те, що справжня хода рахується повно, і те, що
 * несправжня не рахується взагалі.
 *
 * Усі сигнали синтезовані «від істини»: модуль прискорення — це g плюс чиста
 * синусоїда відомої частоти й амплітуди, тобто кожен період — рівно один крок.
 * Тоді закладена кількість кроків відома точно, і з нею звіряється вихід коду.
 */
class StepDetectorTest {

    private val g = 9.81
    private val sampleHz = 50.0

    /**
     * Прогнати через детектор відрізок ходьби заданої частоти й амплітуди.
     *
     * @param cadenceHz кроків за секунду — фундаментальна частота модуля |a|
     * @param amplitudeMps2 розмах вертикального коливання над g
     * @return усі виявлені кроки за цей відрізок
     */
    private fun walk(
        detector: StepDetector,
        cadenceHz: Double,
        amplitudeMps2: Double,
        seconds: Double,
        startMs: Long = 0L,
    ): List<StepEvent> {
        val out = ArrayList<StepEvent>()
        val dtMs = (1000.0 / sampleHz).toLong()
        val n = (seconds * sampleHz).toInt()
        for (i in 0..n) {
            val t = startMs + i * dtMs
            val phase = 2.0 * PI * cadenceHz * (i / sampleHz)
            val mag = g + amplitudeMps2 * sin(phase)
            detector.onSample(t, mag)?.let { out += it }
        }
        return out
    }

    // ————————————————————————————————————————————————————————————————
    // Повнота рахунку — ходьба і біг
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `хода на 1_8 Гц дає закладену кількість кроків`() {
        // Каденс спокійної ходьби вестибюлем. За 20 с при 1.8 Гц закладено 36
        // кроків; допускаємо ±2 на розігрів смугового фільтра й обрізаний
        // останній період, але не більше.
        val det = StepDetector()
        val steps = walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 20.0)
        val expected = (1.8 * 20.0).toInt()
        assertTrue(
            "хода 1.8 Гц: очікувалось ~$expected кроків, а виявлено ${steps.size}",
            steps.size in (expected - 2)..(expected + 1),
        )
    }

    @Test
    fun `біг на 2_8 Гц дає закладену кількість кроків`() {
        // Людина біжить до потяга. 2.8 Гц вище за смугу «спокійної» ходьби, але
        // рефрактерне вікно 250 мс (=4 Гц) його ще пропускає, тож усі кроки
        // мають лишитись, а не злитись.
        val det = StepDetector()
        val steps = walk(det, cadenceHz = 2.8, amplitudeMps2 = 4.0, seconds = 15.0)
        val expected = (2.8 * 15.0).toInt()
        assertTrue(
            "біг 2.8 Гц: очікувалось ~$expected кроків, а виявлено ${steps.size}",
            steps.size in (expected - 3)..(expected + 1),
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Хибні джерела — тряска без ходьби
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `тряска в руці без ходьби не дає кроків`() {
        // Людина стоїть на ескалаторі й гортає стрічку: тремор кисті ~9 Гц.
        // Смуга 0.5–3 Гц має придушити його ще до порога — жодного «кроку»
        // не повинно зʼявитись, інакше нерухома людина «поїде» по коридору.
        val det = StepDetector()
        val steps = walk(det, cadenceHz = 9.0, amplitudeMps2 = 2.5, seconds = 20.0)
        assertTrue(
            "тремор 9 Гц не має породжувати кроків, а породив ${steps.size}",
            steps.isEmpty(),
        )
    }

    @Test
    fun `слабкі коливання нижче абсолютного дна порога не рахуються`() {
        // Телефон у витягнутій руці біля вуха під час дзвінка: амплітуда ходи
        // придушена до частки м/с². Адаптивний поріг сам би підлаштувався й почав
        // рахувати цей шум як кроки — тому існує абсолютне дно MIN_PEAK_MPS2.
        val det = StepDetector()
        val steps = walk(det, cadenceHz = 1.8, amplitudeMps2 = 0.4, seconds = 20.0)
        assertTrue(
            "коливання, слабші за дно порога, — не кроки, а виявлено ${steps.size}",
            steps.isEmpty(),
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Довжина кроку
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `оцінка довжини кроку зростає з каденсом`() {
        // Той самий телефон, той самий розмах — відрізняється лише частота.
        // Модель довжини кроку мусить віддати довший крок на вищому каденсі:
        // це фізіологія бігу проти прогулянки, і саме вона дає правильний
        // пройдений шлях у численні PDR.
        val slow = walk(StepDetector(), cadenceHz = 1.4, amplitudeMps2 = 4.0, seconds = 20.0)
        val fast = walk(StepDetector(), cadenceHz = 2.6, amplitudeMps2 = 4.0, seconds = 20.0)

        // Перший крок кожної серії йде з дефолтним каденсом (інтервалу ще нема),
        // тож порівнюємо усталені кроки, відкинувши по першому.
        val slowLen = slow.drop(1).map { it.lengthM }.average()
        val fastLen = fast.drop(1).map { it.lengthM }.average()

        assertTrue(
            "довжина кроку мала зрости з каденсом: повільна=$slowLen, швидка=$fastLen",
            fastLen > slowLen + 0.05,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Сміття з сенсора
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `NaN із сенсора не отруює фільтр`() {
        // Один NaN, потрапивши в ковзне середнє, зіпсував би його назавжди. Після
        // сплеску NaN нормальна хода все одно має рахуватись.
        val det = StepDetector()
        // Розігрів чистою ходою.
        walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 5.0)
        assertNull("NaN не має закривати крок", det.onSample(6000L, Double.NaN))
        assertNull("нескінченність не має закривати крок", det.onSample(6020L, Double.POSITIVE_INFINITY))
        val after = walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 10.0, startMs = 6100L)
        assertTrue(
            "після NaN детектор має відновити рахунок кроків, а дав ${after.size}",
            after.size >= 15,
        )
    }

    @Test
    fun `час назад не породжує фальшивого кроку`() {
        // Переплутані семпли або стрибок годинника: dt<=0. Інтегрувати крізь це
        // не можна — інакше на першому ж семплі після розриву вискочив би пік.
        val det = StepDetector()
        walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 5.0)
        // Семпл із часом у минулому.
        val res = det.onSample(100L, g + 4.0)
        assertNull("семпл із часом назад не має давати крок", res)
    }

    @Test
    fun `розрив потоку переініціалізує фільтр без сплеску`() {
        // Потік сенсора урвався на секунди (додаток згорнули). Після паузи
        // фільтр має початись заново, а не видати пік на різниці рівнів.
        val det = StepDetector()
        walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 5.0)
        // Стрибок за MAX_GAP_S — перший семпл після нього лише переініціалізує.
        val res = det.onSample(20_000L, g + 4.0)
        assertNull("перший семпл після розриву не є кроком", res)
    }

    @Test
    fun `reset обнуляє лічильник кроків`() {
        val det = StepDetector()
        walk(det, cadenceHz = 1.8, amplitudeMps2 = 4.0, seconds = 10.0)
        assertTrue("до reset кроки мали накопичитись", det.stepCount > 0)
        det.reset()
        assertEquals("reset має обнулити лічильник", 0, det.stepCount)
    }
}
