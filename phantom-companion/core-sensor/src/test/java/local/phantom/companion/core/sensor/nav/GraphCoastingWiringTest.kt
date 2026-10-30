package local.phantom.companion.core.sensor.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.cos

/**
 * ШОВ [CoastWitness] — доказ того, що лічильник шляху **підʼєднано** і що від
 * цього **нічого не змінилось**.
 *
 * Це навмисно НЕ тест лічильника: обіцянки самого [GraphCoasting] закріплені в
 * [GraphCoastingTest] і тут не повторюються. Тут перевіряється рівно те, чого
 * бракувало: клас був написаний, зелений — і його не кликав НІХТО.
 *
 * ## Що цей тест доводить
 *
 *  1. Під сліпотою в чорну скриню лягають рядки `coast,` — рівно один на такт
 *     конвеєра, з позицією, сумнівом і головним числом «лічильник проти рушія».
 *  2. `fix` і `trust` НЕ змінюються від самої лише присутності лічильника —
 *     явним порівнянням двох прогонів, такт за тактом.
 *  3. Без постачальника графа шва не існує і жодного рядка `coast,` не буває,
 *     хоча скриня при цьому пише все інше.
 *  4. Відсутній граф і впалий шов — це рядок з причиною, а не тиша.
 *
 * ## Чого цей тест НЕ доводить
 *
 *  - **Він не доводить, що лічильник правий.** Він не міряє точність і не
 *    порівнює жодну з двох позицій із землею; він лише доводить, що число, яке
 *    їх РОЗРІЗНИТЬ, тепер записується. Хто ближчий до правди, скаже реальна
 *    поїздка власника з розбором `coast,` — і нічого до неї.
 *  - Він не запускає [PhantomLocator] цілком: барометр і `SensorInventory`
 *    вимагають живого Android `Context`, якого в цьому модулі юніт-тести не
 *    мають (ні Robolectric, ні `returnDefaultValues`). Тому тест ганяє ту саму
 *    трійцю, що й такт локатора — рушій, скриню й шов, — тими самими викликами
 *    й у тому самому порядку. Сама ж НЕМОЖЛИВІСТЬ шва щось зіпсувати доводиться
 *    не прогоном, а типом: у [CoastWitness.onEngine] їдуть ЗНАЧЕННЯ, і рушія
 *    він не бачить.
 */
class GraphCoastingWiringTest {

    private companion object {
        const val BASE_LAT = 50.4500
        const val BASE_LON = 30.5200

        /** Метрів на градус широти на сфері 6 371 км. */
        const val M_PER_DEG_LAT = 111_194.93

        /** ~40 км/год: міський рух, на якому лічильник і має сенс. */
        const val SPEED_MPS = 11.0

        /** Скільки секунд чесного неба перед сліпотою. */
        const val SKY_SECONDS = 40

        /** Скільки триває сліпота: більше за `JAM_TIMEOUT_MS` із запасом. */
        const val BLIND_MS = 60_000L

        const val TICK_MS = 1_000L

        /**
         * Запис без ком, поданий ОСТАННІМ: коли він зʼявився у файлі, усе, що
         * стало в чергу раніше, теж уже на диску. Сигнал, а не сон.
         */
        const val BARRIER = "END-OF-QUEUE"

        // Колонки рядка `coast,`: t,coast,held,reason,lat,lon,unc,since,junc,vs
        const val HELD = 2
        const val REASON = 3
        const val VS_ENGINE = 9
    }

    private val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(BASE_LAT))

    private lateinit var root: File
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "coast-wiring-${System.nanoTime()}")
        root.mkdirs()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope?.cancel()
        root.deleteRecursively()
    }

    /** Своя скриня в своїй теці — щоб два прогони не читали рядки одне одного. */
    private fun newBox(name: String): BlackBox =
        BlackBox(dir = File(root, name)).also { it.start(scope!!) }

    /** Точка за [northM] метрів на північ і [eastM] метрів на схід від бази. */
    private fun p(northM: Double, eastM: Double): Pair<Double, Double> =
        (BASE_LAT + northM / M_PER_DEG_LAT) to (BASE_LON + eastM / mPerDegLon)

    /**
     * Один прямий проспект на схід, довший за все, що встигне пройти числення:
     * ~430 м під небом плюс щонайбільше ~1,1 км сліпоти. Перехресть немає
     * навмисно — цей тест про шов, а не про вибір на розгалуженні.
     */
    private fun prospekt() = RoadGraph(
        listOf(RoadWay(id = 1L, name = "Прямий проспект", points = listOf(p(0.0, 0.0), p(0.0, 5_000.0)))),
    )

    private fun gps(eastM: Double): GpsSample {
        val (lat, lon) = p(0.0, eastM)
        return GpsSample(
            lat = lat,
            lon = lon,
            accuracyM = 5f,
            speedMps = SPEED_MPS.toFloat(),
            bearingDeg = 90f,
        )
    }

    /** Телефон лежить рівно: платформа не додає ані прискорення, ані обертання. */
    private fun imu() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /** Те, що бачить людина: позиція і рівень довіри на кожному такті. */
    private fun beatsOf(engine: CustomLocationEngine) = engine.fix.value to engine.trust.value

    /**
     * Рівно те, що робить такт [PhantomLocator]: датчики 50 Гц, тік раз на
     * секунду, потім запис у скриню, потім шов — і ЛИШЕ ПОТІМ знімок того, що
     * побачила б людина. Порядок навмисний: якби шов міг зрушити рушія, це
     * читалося б саме в цьому знімку.
     */
    private fun seconds(
        engine: CustomLocationEngine,
        from: Long,
        forMs: Long,
        witness: CoastWitness?,
        box: BlackBox?,
        beats: MutableList<Pair<FusedFix?, LocationTrust>>,
    ): Long {
        var t = from
        val end = from + forMs
        var nextTick = from + TICK_MS
        while (t < end) {
            t += 20
            engine.onImu(imu(), t)
            if (t >= nextTick) {
                engine.onTick(t)
                box?.recordEngine(engine.fix.value, engine.snapshot())
                witness?.onEngine(engine.fix.value, engine.trust.value, t)
                beats += beatsOf(engine)
                nextTick += TICK_MS
            }
        }
        return t
    }

    /** Поїздка проспектом на схід, а потім хвилина сліпоти. */
    private fun drive(witness: CoastWitness?, box: BlackBox?): List<Pair<FusedFix?, LocationTrust>> {
        val engine = CustomLocationEngine()
        val beats = ArrayList<Pair<FusedFix?, LocationTrust>>()
        var t = 1_000L
        repeat(SKY_SECONDS) { i ->
            engine.onGps(gps(SPEED_MPS * i), t)
            t = seconds(engine, t, 1_000L, witness, box, beats)
        }
        seconds(engine, t, BLIND_MS, witness, box, beats)
        return beats
    }

    /** Сценарій без сліпоти нічого не доводить — це перевіряється, а не мається на увазі. */
    private fun assertSceneIsUseful(beats: List<Pair<FusedFix?, LocationTrust>>) {
        assertTrue(
            "сценарій не дійшов ані до довіри, ані до сліпоти — доводити нема чого",
            beats.any { it.second == LocationTrust.TRUSTED },
        )
        assertTrue(
            "сценарій не осліп — шов не мав би чого рахувати",
            beats.any {
                it.second == LocationTrust.JAMMED ||
                    it.second == LocationTrust.SPOOFED ||
                    it.first?.dead == true
            },
        )
    }

    /** Дочекатись, поки все записане справді ляже на диск, і віддати текст. */
    private fun settle(box: BlackBox): String = runBlocking {
        box.note("barrier", BARRIER)
        val deadline = System.currentTimeMillis() + 6_000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            text = box.currentFile.takeIf { it.exists() }?.readText().orEmpty()
            if (text.contains(BARRIER)) break
            delay(25)
        }
        assertTrue("скриня так і не дописала до барʼєра — читати нічого", text.contains(BARRIER))
        text
    }

    private fun linesOfType(text: String, type: String): List<List<String>> =
        text.lineSequence()
            .map { it.split(',') }
            .filter { it.size > 1 && it[1] == type }
            .toList()

    @Test
    fun `шов пише рядок coast на кожен такт — з позицією і з числом проти рушія`() {
        val box = newBox("wired")
        val graph = prospekt()
        val witness = CoastWitness(graphNear = { _, _ -> graph }, blackBox = box)

        val beats = drive(witness, box)
        assertSceneIsUseful(beats)
        val coast = linesOfType(settle(box), "coast")

        assertEquals(
            "рядок `coast,` мусить бути на КОЖЕН такт: відсутній рядок неможливо " +
                "відрізнити від невимкненого лічильника",
            beats.size, coast.size,
        )
        assertTrue(
            "жоден такт не втримав позиції — лічильник підʼєднано, але він мовчить",
            coast.any { it[HELD] == "true" },
        )
        assertTrue(
            "немає жодного числа «лічильник проти рушія» — а весь шов робився заради нього",
            coast.any { it[HELD] == "true" && it[VS_ENGINE].isNotBlank() },
        )
        assertNotNull(
            "потік для майбутнього споживача лишився порожнім",
            witness.coasted.value,
        )
    }

    /**
     * НАЙВАЖЛИВІШИЙ ТЕСТ. Два однакові прогони, єдина різниця між якими —
     * увімкнений лічильник, і покадрове порівняння того, що побачила б людина.
     *
     * Порівняння спершу доводиться НЕВАКУУМНИМ: якби шов насправді не працював,
     * рівність двох прогонів була б тавтологією.
     */
    @Test
    fun `увімкнений лічильник не змінює ані fix, ані trust — жодного такту`() {
        val graph = prospekt()
        val boxWithout = newBox("without")
        val boxWith = newBox("with")

        val bare = drive(witness = null, box = boxWithout)
        val witness = CoastWitness(graphNear = { _, _ -> graph }, blackBox = boxWith)
        val wired = drive(witness, boxWith)

        assertSceneIsUseful(bare)
        val coast = linesOfType(settle(boxWith), "coast")
        assertTrue("лічильник не працював — порівнювати нічого", coast.isNotEmpty())
        assertTrue(
            "лічильник жодного разу не втримав позиції — рівність прогонів була б тавтологією",
            coast.any { it[HELD] == "true" },
        )

        assertEquals("прогони мусять мати однакову кількість тактів", bare.size, wired.size)
        for (i in bare.indices) {
            assertEquals(
                "такт $i: живий конвеєр змінився від самої лише присутності лічильника",
                bare[i], wired[i],
            )
        }
    }

    @Test
    fun `без постачальника графа шва не існує — і жодного рядка coast`() {
        val box = newBox("bare")
        val witness = CoastWitness.orNull(graphNear = null, blackBox = box)
        assertNull("без постачальника графа шов не мусить існувати взагалі", witness)

        val beats = drive(witness, box)
        val text = settle(box)

        assertEquals(
            "типова поведінка мусить лишитись дослівно теперішньою",
            0, linesOfType(text, "coast").size,
        )
        assertEquals(
            "а скриня при цьому писала — отже нуль вище означає «шва нема», а не «запис зламався»",
            beats.size, linesOfType(text, "ew").size,
        )
    }

    @Test
    fun `графа поруч немає — це рядок з причиною, а не тиша`() {
        val box = newBox("nograph")
        val witness = CoastWitness(graphNear = { _, _ -> null }, blackBox = box)

        val beats = drive(witness, box)
        val coast = linesOfType(settle(box), "coast")

        assertEquals("мовчати про відсутній граф заборонено", beats.size, coast.size)
        assertTrue("без графа втримати позицію нема як", coast.none { it[HELD] == "true" })
        assertTrue("кожна відмова мусить нести причину", coast.all { it[REASON].isNotBlank() })
        assertTrue(
            "причина «графа тут нема» мусить бути названа своїм словом",
            coast.any { it[REASON] == CoastWitness.REASON_NO_GRAPH },
        )
    }

    /**
     * Лічильник обороняється `require`, а шов живе в корутині такту локатора:
     * виняток звідси вбив би тік — і разом із ним живий конвеєр. Тут падає сам
     * постачальник графа, і перевіряється, що конвеєр цього навіть не помітив.
     */
    @Test
    fun `шов, що впав, не валить конвеєра і лишає по собі рядок`() {
        val boxWithout = newBox("healthy")
        val boxBroken = newBox("broken")

        val without = drive(witness = null, box = boxWithout)
        val witness = CoastWitness(
            graphNear = { _, _ -> throw IllegalStateException("постачальник графа вибухнув") },
            blackBox = boxBroken,
        )
        val broken = drive(witness, boxBroken)

        assertSceneIsUseful(without)
        assertEquals(without.size, broken.size)
        for (i in without.indices) {
            assertEquals("такт $i: падіння шва дісталось живого конвеєра", without[i], broken[i])
        }

        val coast = linesOfType(settle(boxBroken), "coast")
        assertEquals("падіння теж мусить бути записане на кожному такті", broken.size, coast.size)
        assertTrue(
            "падіння шва виглядає як тиша — саме так і губляться шви",
            coast.any { it[REASON] == CoastWitness.REASON_SEAM_ERROR },
        )
    }
}
