package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.CoastMode
import local.phantom.companion.core.nav.CoastState
import local.phantom.companion.core.nav.RouteCoastingEngine
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Витримка на перехід «вірю небу ↔ не вірю небу» — [SkyBelief].
 *
 * ЧОМУ ЦЕЙ ФАЙЛ ІСНУЄ. Скарга власника №1 — «точку телепортує». Заміряно на
 * його ж записі з телефона (поїздка 27.08, `nav-journal/nav-1787837540846.csv`,
 * 1083 зразки за 30 хв): **112 перемикань `trusted`↔`held`, тобто раз на 16 с**;
 * 368 зразків із 1083 (третина) — без довіреної позиції; 73 стрибки показаної
 * точки понад 200 км/год, з них **56% — рівно в мить перемикання**; медіана
 * стрибка 202 м. Причина не в згладжуванні, а в тому, що вирок про небо не мав
 * ПАМʼЯТІ: [CoastPilot.believedCoastFix] дивиться рівно на один кадр, і володар
 * намальованої точки мінявся від кожного окремого зразка.
 *
 * ЧИМ ЦЕЙ СЦЕНАРІЙ ВІДРІЗНЯЄТЬСЯ ВІД СПРАВЖНЬОГО ЗАПИСУ — прямо, бо на цьому
 * тримається все інше:
 *  - ЗВІДТИ взято й відтворено точно (це перевіряє окремий тест нижче):
 *    1083 зразки, 368 із них без довіри (34%), 112 перемикань, крок 1,7 с
 *    (1083 зразки за 30 хв), швидкість 20 м/с;
 *  - ЗВІДТИ **НЕ** взято гістограму довжин серій: журнал власника дає суми й
 *    кількість перемикань, але не те, як довжини розподілені. Тут прийнято
 *    трискладову модель — довгі епізоди відмови (тунель / РЕБ), усередині яких
 *    небо на 2 секунди «щастить», і чисті ділянки, які пробиває одиничний
 *    розпухлий зразок. Це ПРИПУЩЕННЯ. Протилежне припущення (рівні серії по
 *    6,6 с, без коротких) дало б менший виграш — але воно й суперечить
 *    заміряному: 43 різкі стрибки за поїздку не виникають із чистих відмов,
 *    бо чисту відмову движок ЗЛИВАЄ сповзанням, а не клацанням;
 *  - позиція «недовіреного» зразка блукає в межах ±250 м — звідти й медіана
 *    великого стрибка близько заміряних 202 м;
 *  - маршрут — прямий коридор. Крива тільки ЗМЕНШУЄ швидкість коуста
 *    (`RouteCoastingEngine.curveSpeedCapMps`), тож прямий коридор тут гірший
 *    випадок, а не легший.
 *
 * ЧОГО ЦЕЙ ФАЙЛ НЕ ДОВОДИТЬ — див. `витримка не обіцяє більше, ніж уміє`.
 */
class CoastSkyHysteresisTest {

    // ── коридор і кадри ───────────────────────────────────────────────────

    /** ~44,5 км прямого коридору: 1083 зразки по 1,7 с на 20 м/с — це 36,8 км. */
    private val corridor = (0..400).map { (50.0 + it * 0.001) to 30.0 }

    private val variant = RouteVariant(
        points = corridor,
        distanceMeters = corridor.zipWithNext().sumOf { (a, b) -> haversineM(a, b) },
        durationSeconds = 1_900.0,
        steps = listOf(
            RouteStep("depart", null, "Тестова", 0.0, 0.0, corridor.first().first, corridor.first().second),
            RouteStep("arrive", null, "Тестова", 0.0, 0.0, corridor.last().first, corridor.last().second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    private val degPerMetre = 1.0 / 111_320.0

    private fun atM(alongM: Double, accuracyM: Float) = LocationFix(
        lat = 50.0 + alongM * degPerMetre,
        lon = 30.0,
        bearing = 0f,
        accuracyM = accuracyM,
        speedMps = SPEED_MPS,
        grade = FixGrade.NAVIGATION,
        dead = false,
        register = FixRegister.MEASURED,
    )

    /** Довірений зразок: похибка 8 м, позиція = істина. */
    private fun believable(alongM: Double) = atM(alongM, 8f)

    /**
     * Недовірений зразок: похибка 400 м — понад стелю
     * [CoastPilot.TRUSTED_ACCURACY_M] (150 м), тобто саме те «розпухле небо»,
     * яким і дихає запис навколо порога. Позиція блукає: розпухла похибка не
     * буває нерухомою, і саме це блукання людина бачить як телепорт.
     */
    private fun doubted(alongM: Double, wanderM: Double) = atM(alongM + wanderM, 400f)

    private class Rig(variant: RouteVariant?) {
        val journal = mutableListOf<String>()
        var now = 10_000L
        val pilot = CoastPilot(
            guiding = { true },
            planRevision = { 1 },
            variant = { variant },
            journal = { journal += it },
        )

        fun frame(fix: LocationFix?, stepMs: Long = 1_000L): CoastState {
            now += stepMs
            return pilot.frame(fix, now)
        }

        fun word(): String = journal.last().split(',')[1]
    }

    /** Вирок пілота, як його читає перший стовпчик журналу (див. `skyWord`). */
    private fun verdictOf(line: String): Boolean = line.split(',')[1].startsWith("trusted")

    private fun <T> flips(xs: List<T>): Int = xs.zipWithNext().count { (a, b) -> a != b }

    // ── реконструкція запису ──────────────────────────────────────────────

    /**
     * `true` — небо цього зразка проходить [CoastPilot.believedCoastFix].
     * Форма: 7 чистих ділянок (по 3 довгі довірені серії, пробиті двома
     * одиничними розпухлими зразками) і 6 епізодів відмови (по 7 довгих серій
     * відмови, розділених «щасливими» просвітами по 2 зразки).
     */
    private fun record(): List<Boolean> {
        val out = mutableListOf<Boolean>()
        var ti = 0
        var hi = 0
        fun tLong(): Int = (if (ti < 13) 31 else 30).also { ti++ }
        fun hLong(): Int = (if (hi < 18) 9 else 8).also { hi++ }
        fun run(believed: Boolean, n: Int) = repeat(n) { out += believed }

        repeat(7) { s ->
            run(true, tLong()); run(false, 1)
            run(true, tLong()); run(false, 1)
            run(true, tLong())
            if (s < 6) {
                repeat(6) { run(false, hLong()); run(true, 2) }
                run(false, hLong())
            }
        }
        return out
    }

    /** Детермінований блукач у ±250 м — щоб «до» і «після» бачили ОДНЕ небо. */
    private fun wander(n: Int): List<Double> {
        var x = 12_345L
        return List(n) {
            x = (1_103_515_245L * x + 12_345L) and 0x7FFFFFFFL
            (x.toDouble() / 0x80000000L) * 500.0 - 250.0
        }
    }

    /** Одна й та сама послідовність кадрів для обох логік. */
    private fun frames(): List<LocationFix> {
        val sky = record()
        val w = wander(sky.size)
        return sky.mapIndexed { i, ok ->
            val truthM = START_ALONG_M + i * SPEED_MPS * (SAMPLE_MS / 1000.0)
            if (ok) believable(truthM) else doubted(truthM, w[i])
        }
    }

    @Test
    fun `реконструкція збігається із записом у заміряних числах`() {
        val sky = record()
        assertEquals("зразків", 1083, sky.size)
        assertEquals("без довіри", 368, sky.count { !it })
        assertEquals("перемикань сирого неба", 112, flips(sky))
        assertEquals(
            // 368 із 1083 — це 33,98 %, і `toInt()` обрізав це до 33, тож перевірка
            // була червоною від народження: автор рахував із округленням (у KDoc
            // теж «34%»), а код відкидав дробову частину. Дані не чіпаємо —
            // виправляємо саме те, що розійшлось: спосіб перетворення на відсоток.
            "третина часу без довіреної позиції, %",
            34,
            Math.round(100.0 * sky.count { !it } / sky.size).toInt(),
        )
        assertEquals("тривалість поїздки, хв", 30, (sky.size * SAMPLE_MS / 60_000.0).toInt())
    }

    // ── ГОЛОВНЕ: два числа, одна послідовність, дві логіки ────────────────

    @Test
    fun `витримка збиває 112 перемикань запису до третини`() {
        val fixes = frames()

        // «ДО» — рівно та логіка, що була до правки: вирок пілота дорівнював
        // [CoastPilot.believedCoastFix] від ОДНОГО кадру, без жодної памʼяті.
        // Не переказ поведінки, а виклик тієї самої функції на тих самих кадрах.
        val before = flips(fixes.map { CoastPilot.believedCoastFix(it) != null })

        // «ПІСЛЯ» — той самий список кадрів крізь справжній [CoastPilot], і
        // вирок читається з того самого стовпчика журналу, яким заміряні 112.
        val rig = Rig(variant)
        fixes.forEach { rig.frame(it, SAMPLE_MS) }
        val after = flips(rig.journal.map(::verdictOf))

        val said = "перемикань вироку: ДО=$before, ПІСЛЯ=$after"
        assertEquals("журнал мусить сказати про кожен зразок", fixes.size, rig.journal.size)
        assertEquals("$said — «до» мусить відтворити заміряні 112", 112, before)
        assertTrue("$said — витримка не спрацювала", after * 2 < before)
        assertTrue(said, after <= 45)
    }

    // ── ядро хвороби: щасливий просвіт ────────────────────────────────────

    /**
     * Найкоротший опис того, що бачив власник. Посеред відмови небо на дві
     * секунди «щастить». До правки цього досить, щоб движок ПРИЙНЯВ просвіт за
     * істину: `onMeasured` обнуляє лічильник відмови й починає злиття, тобто
     * тягне намальовану точку до недоведеного зразка, а коли відмова
     * повертається — перезаякорює коуст туди, куди встиг доповзти. Звідси й
     * «56% стрибків рівно в мить перемикання».
     *
     * Після правки просвіт коротший за [SKY_RETURN_HOLD_MS] взагалі не доходить
     * до движка: режим лишається [CoastMode.COASTING] наскрізь.
     */
    @Test
    fun `щасливий просвіт посеред відмови не зриває коуст`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(null)
        }
        assertEquals(CoastMode.COASTING, rig.frame(null).mode.also { travelledM += SPEED_MPS })

        val seen = mutableListOf<CoastState>()
        repeat(2) {
            travelledM += SPEED_MPS
            seen += rig.frame(believable(travelledM))
        }
        repeat(6) {
            travelledM += SPEED_MPS
            seen += rig.frame(null)
        }

        assertTrue(
            "движок прийняв двосекундний просвіт за істину: режими ${seen.map { it.mode }}",
            seen.all { it.mode == CoastMode.COASTING },
        )
        assertTrue("точку не можна було віддавати", seen.all { it.puck != null })
        assertTrue(
            "і мовчати про це не можна: витримка мусить назватись",
            rig.journal.count { it.split(',')[1] == "held-proving" } == 2,
        )
    }

    // ── жорсткий поріг: правда не чекає ──────────────────────────────────

    /**
     * Вимога штабу, і вона несуча. У справжньому записі розбіжність між
     * інерційною точкою та поверненим небом сягала **52 059 м**. Тримати стару
     * точку в такій ситуації означало б лишити людину за 52 км від себе — і
     * мовчати про це рівно стільки, скільки триває витримка. Тому вище
     * [SKY_HARD_GAP_M] витримки нема ВЗАГАЛІ.
     */
    @Test
    fun `небо за кілометри від інерційної точки проходить одразу, без витримки`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        // 20 с сліпоти: коуст володіє точкою, але ще НЕ вичерпався — тобто
        // спрацювати може лише поріг розбіжності, а не стеля движка.
        var last: CoastState = CoastPilot.LIVE
        repeat(20) {
            travelledM += SPEED_MPS
            last = rig.frame(null)
        }
        assertEquals("коуст мусить бути живим, а не вичерпаним", CoastMode.COASTING, last.mode)
        assertNotNull(last.puck)

        // Небо повертається за 5 км попереду інерційної точки.
        val first = rig.frame(believable(travelledM + 5_000.0))

        assertNull("правду за 5 км віддано з першого ж кадру", first.puck)
        assertTrue("це передача істини одним кадром", first.hardResync)
        assertTrue("журнал каже довіру, а не «доводить»", verdictOf(rig.journal.last()))
    }

    /**
     * Друга половина того самого порога: РЯДОМ — це смикання, і воно чекає.
     * Без цієї пари попередній тест не доводив би нічого — він проходив би й на
     * логіці «жодної витримки взагалі».
     */
    @Test
    fun `небо, що повернулось поруч, чекає витримку і не забирає точку`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        repeat(20) {
            travelledM += SPEED_MPS
            rig.frame(null)
        }

        // Три зразки чесного неба поруч із коустом — 3 с проти
        // [SKY_RETURN_HOLD_MS] = 4 с. Це ще не повернення.
        val burst = (1..3).map {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        assertTrue(
            "трисекундний просвіт поруч не є поверненням — точку тримає коуст",
            burst.all { it.puck != null && it.mode == CoastMode.COASTING },
        )
        assertTrue(
            "і це видно словом, а не тишею",
            rig.journal.takeLast(3).all { it.split(',')[1] == "held-proving" },
        )

        // Четверта секунда добирає витримку — і точку віддано.
        travelledM += SPEED_MPS
        assertTrue(
            "чесне небо мусить забрати точку рівно за витримку",
            rig.frame(believable(travelledM)).mode != CoastMode.COASTING,
        )
    }

    // ── застрягання: межа, названа в коді ─────────────────────────────────

    /**
     * Витримка не сміє перетворитись на замок. Небо, що повертається з
     * провалами (три чесні зразки, один порожній, і так по колу), — це
     * ПОВЕРНЕННЯ, і людина мусить опинитись там, де вона є. Тримає це
     * симетрична витрата в [SkyBelief]: незгода не обнуляє накопичене, а лише
     * витрачає його, тож усе, що чесне більше ніж наполовину, зрештою виграє.
     * Якби один провал обнуляв лічильник, небо з дірою раз на три секунди не
     * поверталося б НІКОЛИ.
     */
    @Test
    fun `повернення з провалами не заклинює витримку`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(null)
        }
        assertNotNull(rig.frame(null).puck.also { travelledM += SPEED_MPS })

        var released = -1
        for (i in 0 until 24) {
            travelledM += SPEED_MPS
            val honest = i % 4 != 3
            if (rig.frame(if (honest) believable(travelledM) else null).puck == null) {
                released = i
                break
            }
        }
        assertTrue(
            "точку заклинило в коусті попри переважно чесне небо " +
                "(минуло ${if (released < 0) "усі 24" else "$released"} кадрів)",
            released in 0..15,
        )
    }

    // ── порожнеча — словом ────────────────────────────────────────────────

    @Test
    fun `витримка називає себе словом, а не тишею`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        assertEquals("trusted", rig.word())

        // Один розпухлий зразок усередині чистого неба: вирок ще вірить, але
        // цей кадр — уже ні. Це мусить бути станом, а не тишею.
        travelledM += SPEED_MPS
        rig.frame(doubted(travelledM, 200.0))
        assertEquals("trusted-wobble", rig.word())

        // Секунда без неба взагалі, поки вирок ще вірить.
        travelledM += SPEED_MPS
        rig.frame(null)
        assertEquals("trusted-dark", rig.word())

        // Сумнів вистояв — вирок здався.
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(null)
        }
        assertEquals("null", rig.word())

        // Небо повертається — і поки доводить, журнал каже саме це.
        travelledM += SPEED_MPS
        rig.frame(believable(travelledM))
        assertEquals("held-proving", rig.word())

        // Стовпчик лишився порівнюваним зі старим записом: префікс — це вирок.
        assertTrue(
            "слова мусять лишитись словником запису: ${rig.journal.map { it.split(',')[1] }.toSet()}",
            rig.journal.all {
                it.split(',')[1] in setOf(
                    "trusted", "trusted-wobble", "trusted-dark", "held", "held-proving", "null",
                )
            },
        )
    }

    // ── межі ──────────────────────────────────────────────────────────────

    /**
     * Чого ця правка НЕ лікує — щоб ніхто не читав зелене ширше, ніж воно є:
     *
     *  1. **Стрибок після справжньої довгої сліпоти.** Коли движок вичерпав
     *     власні стелі ([RouteCoastingEngine.MAX_COAST_MS] 45 с /
     *     [RouteCoastingEngine.MAX_COAST_M] 1 км), пуск замерзає і чесно каже
     *     `WAITING`. Повернення неба після цього — передача істини одним
     *     кадром, і прибирати її не можна й не треба: людина справді ТАМ.
     *     Цей тест її фіксує, а не лікує.
     *  2. **Одиничний розпухлий зразок посеред чистого неба.** Точку тоді
     *     малюють ворота, бо движок (правильно) відмовляється вигадати метр
     *     раніше ніж за [RouteCoastingEngine.ENTER_AFTER_MS]. Гасить це
     *     `FixFlux` повільним ходом до недовіреної цілі — не цей файл. На
     *     реконструкції вище такі кадри дають майже всі стрибки понад 150 м,
     *     і їх кількість витримка НЕ зменшує.
     *  3. **Похибку самого коуста.** Витримка вирішує, ХТО володіє точкою, і
     *     нічого не каже про те, наскільки коуст правий.
     */
    @Test
    fun `витримка не обіцяє більше, ніж уміє`() {
        val rig = Rig(variant)
        var travelledM = START_ALONG_M
        repeat(10) {
            travelledM += SPEED_MPS
            rig.frame(believable(travelledM))
        }
        // 90 с сліпоти — удвічі довше за стелю движка.
        var last: CoastState = CoastPilot.LIVE
        repeat(90) {
            travelledM += SPEED_MPS
            last = rig.frame(null)
        }
        assertEquals("движок мусить сам визнати пуск байкою", CoastMode.EXPIRED, last.mode)

        // Небо повертається там, де людина справді є — за понад кілометр від
        // замерзлої точки. Це ТОЙ САМИЙ стрибок, і він мусить статись одразу.
        val back = rig.frame(believable(travelledM))
        assertNull("замерзла точка не має права пережити повернення неба", back.puck)
        assertTrue("чесна передача одним кадром, а не сповзання через півкраїни", back.hardResync)
    }

    private companion object {
        /** 1083 зразки за 30 хв — крок запису власника. */
        const val SAMPLE_MS = 1_700L
        const val SPEED_MPS = 20f
        const val START_ALONG_M = 100.0
    }
}
