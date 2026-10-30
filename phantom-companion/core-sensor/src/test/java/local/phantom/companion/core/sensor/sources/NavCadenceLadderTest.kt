package local.phantom.companion.core.sensor.sources

import local.phantom.companion.core.sensor.nav.CustomLocationEngine
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЩАБЛІ ТЕМПУ GNSS — з боку людини, яка їде.
 *
 * Привід: запис власника 29.08 — 30 хв реальної їзди, 1083 зразки. Позиція
 * оновилась 165 разів, медіанний проміжок 9 с, найдовший 526 с. Дослівна
 * скарга за кермом: «я їду, а точка стоїть на місці, не рухається майже,
 * тільки телепортується».
 *
 * ЧОМУ ЦЕ ЧЕРВОНІЛО НА СТАРОМУ КОДІ. Старе правило жило одним рядком у
 * `ContinuumHost` — `guiding || (appStarted && (followArmed || resting ==
 * WORLD))` — і давало Boolean: два щаблі, 1 Гц або 5 с. Воно не знало ані
 * слова про рух. Тому:
 *
 *  - третього щабля не існувало як типу — `NavCadence` немає в старому
 *    дереві, і весь файл не збирається («Unresolved reference»);
 *  - навіть якщо руками підставити старий двопозиційний перемикач, по суті
 *    впадуть: «щаблів три», «стоїть із видимою мапою», «коротка зупинка на
 *    світлофорі», «спуск долітає у повній тиші» і «повна послідовність
 *    власника» — у старому коді немає ані щабля спокою, ані витримки, ані
 *    поняття руху;
 *  - два останні тести проганяють СТАРЕ правило по тих самих сценах і
 *    показують обидві його вади числом: воно сліпе до руху й смикає чип на
 *    кожен рух камери.
 */
class NavCadenceLadderTest {

    // ── Стенд ─────────────────────────────────────────────────────────────

    /**
     * Тримає стан світу і крутить годинник. Планувальник у продакшені
     * питають на кожну звістку про рух (тобто на кожен фікс) плюс окремо в
     * мить, коли дозріває притриманий спуск, — тут те саме, кроком у секунду.
     */
    private class Rig(private val planner: NavCadencePlanner = NavCadencePlanner()) {
        var nowMs = 0L
        var leading = false
        var foreground = true
        var mapOnGlass = true
        var motion: KinematicsEngine.MotionProfile? = null

        /** Кожен застосований щабель, по порядку — для підрахунку смикань. */
        val trace = mutableListOf<NavCadence>()

        val cadence: NavCadence get() = planner.cadence

        fun tick(): NavCadence {
            val c = planner.update(leading, foreground, mapOnGlass, motion, nowMs)
            trace += c
            return c
        }

        /** [seconds] секунд світу, по одному опитуванню на секунду. */
        fun run(seconds: Int): NavCadence {
            var last = planner.cadence
            repeat(seconds) {
                nowMs += 1_000L
                last = tick()
            }
            return last
        }

        /** Скільки разів чип насправді перепрограмували б. */
        fun switches(): Int = trace.zipWithNext().count { (a, b) -> a != b }

        fun demotionDueInMs(): Long = planner.demotionDueInMs(nowMs)
    }

    private fun profile(
        state: KinematicsEngine.MotionState,
        confidence: Float,
    ) = KinematicsEngine.MotionProfile(state = state, confidence = confidence)

    /** Машина: двигун віддає DRIVE із 0.55 без IMU і 0.8 з ним. */
    private val driving = profile(KinematicsEngine.MotionState.DRIVE, 0.8f)

    /** Стоїть, доведено: 0.6 без IMU, 0.9 з IMU. Беремо гірший випадок. */
    private val standing = profile(KinematicsEngine.MotionState.STILL, 0.6f)

    /** Двигун ще не має чотирьох зразків швидкості й мовчить. */
    private val unknown = profile(KinematicsEngine.MotionState.UNKNOWN, 0f)

    // ── Самі числа щаблів ─────────────────────────────────────────────────

    @Test
    fun `щаблів три, і темпи в них різні`() {
        val intervals = NavCadence.values().map { it.intervalMs }
        assertEquals(3, intervals.size)
        assertEquals(3, intervals.distinct().size)
        assertEquals(1_000L, NavCadence.NAVIGATION.intervalMs)
        assertEquals(5_000L, NavCadence.AMBIENT.intervalMs)
        // Щабель спокою — між ними, і НЕ ambient: людина дивиться на мапу.
        assertTrue(NavCadence.STANDSTILL.intervalMs > NavCadence.NAVIGATION.intervalMs)
        assertTrue(NavCadence.STANDSTILL.intervalMs < NavCadence.AMBIENT.intervalMs)
        assertTrue(NavCadence.STANDSTILL.highAccuracy)
    }

    /**
     * ЧОМУ 2,5 с, а не «якесь повільне».
     *
     * `KinematicsEngine.classify` виходить одразу, доки у вікні
     * [KinematicsEngine.WINDOW_MS] менше за чотири зразки швидкості
     * (`MIN_SPEED_SAMPLES`, приватна — число процитоване тут). Щабель, з
     * якого класифікатор не бачить руху, — це щабель, з якого не злізти:
     * рівно так телефон власника й лишався сліпим до того, що він поїхав.
     */
    @Test
    fun `щабель спокою годує класифікатор руху, а ambient — ні`() {
        val minSpeedSamples = 4
        fun samplesInWindow(intervalMs: Long) =
            (KinematicsEngine.WINDOW_MS / intervalMs).toInt() + 1

        assertTrue(
            "щабель спокою мусить давати класифікатору не менше за $minSpeedSamples зразки",
            samplesInWindow(NavCadence.STANDSTILL.intervalMs) >= minSpeedSamples,
        )
        // Один загублений фікс не має обвалювати його нижче порога.
        assertTrue(
            samplesInWindow(NavCadence.STANDSTILL.intervalMs) > minSpeedSamples,
        )
        // І документуємо, чому ambient не годиться на роль «повільного, але
        // достатнього»: на ньому вирок про рух не зʼявляється НІКОЛИ.
        assertTrue(
            "ambient сліпить класифікатор — це і є причина, чому щаблів три",
            samplesInWindow(NavCadence.AMBIENT.intervalMs) < minSpeedSamples,
        )
    }

    /**
     * Друга межа того самого числа: детектор глушіння оголошує небо зниклим
     * після [CustomLocationEngine.JAM_TIMEOUT_MS] тиші. Один загублений фікс
     * на щаблі спокою не має читатись як глушіння.
     */
    @Test
    fun `щабель спокою переживає один загублений фікс без підозри на глушіння`() {
        assertTrue(
            2 * NavCadence.STANDSTILL.intervalMs < CustomLocationEngine.JAM_TIMEOUT_MS,
        )
        // Для контрасту: на ambient той самий один загублений фікс — уже «глушать».
        assertTrue(
            2 * NavCadence.AMBIENT.intervalMs > CustomLocationEngine.JAM_TIMEOUT_MS,
        )
    }

    // ── Щабель 1: їде ─────────────────────────────────────────────────────

    @Test
    fun `їде з видимою мапою — один герц, без торгу`() {
        val rig = Rig().apply { motion = driving }
        assertEquals(NavCadence.NAVIGATION, rig.run(30))
        assertEquals(0, rig.switches())
    }

    @Test
    fun `їде без прокладеного маршруту й без стеження — все одно один герц`() {
        // Рівно діра, яку намацав власник: супроводу немає, стеження немає,
        // мапа відкрита, людина їде.
        val rig = Rig().apply {
            leading = false
            mapOnGlass = true
            motion = driving
        }
        assertEquals(NavCadence.NAVIGATION, rig.run(60))
    }

    @Test
    fun `невідомий рух читається як рух`() {
        // Сповільнення вимагає ПОЗИТИВНОГО свідчення спокою. «Не знаю» —
        // не свідчення: навігатор має право помилятись у бік швидкого темпу.
        val rig = Rig().apply { motion = unknown }
        assertEquals(NavCadence.NAVIGATION, rig.run(60))
    }

    @Test
    fun `слабкий вирок STILL не сповільнює чип`() {
        val rig = Rig().apply {
            motion = profile(KinematicsEngine.MotionState.STILL, 0.5f)
        }
        assertEquals(NavCadence.NAVIGATION, rig.run(60))
    }

    // ── Щабель 2: стоїть ──────────────────────────────────────────────────

    @Test
    fun `стоїть із видимою мапою — сповільнення лише після витримки`() {
        val rig = Rig().apply { motion = driving }
        rig.run(5)
        assertEquals(NavCadence.NAVIGATION, rig.cadence)

        // Планувальник дізнається про спокій на наступному опитуванні — на
        // 6-й секунді; звідти й тікає витримка в 12 с.
        rig.motion = standing
        rig.run(12) // 6..17 с — минуло 11
        assertEquals(NavCadence.NAVIGATION, rig.cadence)
        rig.run(1) // 18-та секунда — рівно 12
        assertEquals(NavCadence.STANDSTILL, rig.cadence)
    }

    @Test
    fun `спуск долітає у повній тиші`() {
        // Людина заглушила мотор: нових звісток про рух може вже не бути.
        // Викликач мусить дізнатись, коли дозріє притриманий спуск, інакше
        // щабель спокою не настане ніколи.
        val rig = Rig().apply { motion = driving }
        rig.nowMs = 1_000L
        assertEquals(NavCadence.NAVIGATION, rig.tick())

        rig.motion = standing
        rig.nowMs = 2_000L
        assertEquals("спуск притримано", NavCadence.NAVIGATION, rig.tick())

        val due = rig.demotionDueInMs()
        assertEquals(NavCadencePlanner.LADDER_SETTLE_MS, due)

        rig.nowMs += due
        assertEquals(NavCadence.STANDSTILL, rig.tick())
        assertEquals(0L, rig.demotionDueInMs())
    }

    // ── Гістерезис ────────────────────────────────────────────────────────

    /**
     * Світлофор. Двигун сам тримає машину машиною
     * [KinematicsEngine.VEHICLE_TO_STILL_DWELL_MS] = 25 с, і навіть коли він
     * здасться, зверху лежить наша витримка. Тут — гірший випадок: двигун
     * уже вимовив STILL, а людина за 10 с поїхала далі.
     */
    @Test
    fun `коротка зупинка на світлофорі не смикає щабель`() {
        val rig = Rig().apply { motion = driving }
        rig.run(10)
        rig.motion = standing
        rig.run(10)
        rig.motion = driving
        rig.run(10)

        assertEquals(NavCadence.NAVIGATION, rig.cadence)
        assertEquals("щабель не мав змінитись жодного разу", 0, rig.switches())
    }

    @Test
    fun `підйом щабля миттєвий`() {
        val rig = Rig().apply { motion = standing }
        rig.run(20)
        assertEquals(NavCadence.STANDSTILL, rig.cadence)

        rig.motion = driving
        // Та сама секунда, жодної витримки: чекати з підйомом — це і є баг.
        assertEquals(NavCadence.NAVIGATION, rig.run(1))
    }

    @Test
    fun `перемикання приводу спуску перезапускає витримку`() {
        val rig = Rig().apply { motion = driving }
        rig.run(5) // 1..5 — навігаційний темп

        rig.motion = standing // привід спуску №1: STANDSTILL, годинник із 6-ї
        rig.run(8) // 6..13 — минуло 7
        assertEquals(NavCadence.NAVIGATION, rig.cadence)

        rig.mapOnGlass = false // привід змінився: тепер у бік AMBIENT
        rig.run(8) // 14..21 — від НОВОГО приводу минуло 7, від старого 15
        assertEquals(
            "якби годинник не перезапустився, чип уже сповільнили б",
            NavCadence.NAVIGATION,
            rig.cadence,
        )

        rig.run(5) // 22..26 — рівно 12 від нового приводу
        assertEquals(NavCadence.AMBIENT, rig.cadence)
    }

    // ── Щабель 3: мапи не видно ───────────────────────────────────────────

    @Test
    fun `сховав мапу — ambient, але теж після витримки`() {
        val rig = Rig().apply { motion = driving }
        rig.run(5)
        rig.mapOnGlass = false
        rig.run(12) // 6..17 — минуло 11
        assertEquals(NavCadence.NAVIGATION, rig.cadence)
        rig.run(1) // 18-та — рівно 12
        assertEquals(NavCadence.AMBIENT, rig.cadence)
    }

    @Test
    fun `фон гасить темп навіть із відкритою мапою`() {
        // THERMAL 11.08 — палата не міняється від кнопки «Додому».
        val rig = Rig().apply { motion = driving }
        rig.run(5)
        rig.foreground = false
        rig.run(13) // 6..18 — рівно 12 витримки
        assertEquals(NavCadence.AMBIENT, rig.cadence)
    }

    @Test
    fun `живий супровід не сповільнюється ні спокоєм, ні фоном`() {
        // Android Auto веде без жодної живої Activity, а детектор глушіння
        // рахує тишу в секундах.
        val rig = Rig().apply {
            leading = true
            foreground = false
            mapOnGlass = false
            motion = standing
        }
        assertEquals(NavCadence.NAVIGATION, rig.run(60))
        assertEquals(0, rig.switches())
    }

    // ── Послідовність із завдання ─────────────────────────────────────────

    /** їде → став → поїхав → сховав мапу. */
    @Test
    fun `повна послідовність власника`() {
        val rig = Rig().apply { motion = driving }

        // 1. Їде з відкритою мапою. Ні маршруту, ні стеження.
        rig.run(60)
        assertEquals("їде — один герц", NavCadence.NAVIGATION, rig.cadence)

        // 2. Став надовго (заглушив мотор).
        rig.motion = standing
        rig.run(11)
        assertEquals("щойно став — ще не сповільнюємось", NavCadence.NAVIGATION, rig.cadence)
        rig.run(49)
        assertEquals("простояв хвилину — щабель спокою", NavCadence.STANDSTILL, rig.cadence)

        // 3. Поїхав.
        rig.motion = driving
        assertEquals("поїхав — миттєво один герц", NavCadence.NAVIGATION, rig.run(1))

        // 4. Сховав мапу (пішов у чат / на головний екран).
        rig.mapOnGlass = false
        rig.run(13)
        assertEquals("мапи не видно — ambient", NavCadence.AMBIENT, rig.cadence)

        // Рівно три перепрограмування чипа за всю сцену: NAV → STANDSTILL →
        // NAV → AMBIENT. Жодного зайвого — а старе правило дало б по одному
        // на кожен рух камери.
        assertEquals(3, rig.switches())
    }

    // ── Що саме було зламано ──────────────────────────────────────────────

    /** Дослівна транскрипція рядка, який стояв у `ContinuumHost:290`. */
    private fun legacyNavigationGrade(
        guiding: Boolean,
        appStarted: Boolean,
        followArmed: Boolean,
        worldResting: Boolean,
    ): Boolean = guiding || (appStarted && (followArmed || worldResting))

    @Test
    fun `стара умова сліпа до руху`() {
        // Той самий екран, та сама палата камери — і їзда, і стояння дають
        // ОДНАКОВУ відповідь: у старого правила просто немає входу для руху,
        // тож щабель спокою ним не виражається в принципі.
        val whileDriving = legacyNavigationGrade(
            guiding = false, appStarted = true, followArmed = false, worldResting = true,
        )
        val whileStanding = legacyNavigationGrade(
            guiding = false, appStarted = true, followArmed = false, worldResting = true,
        )
        assertEquals(whileDriving, whileStanding)
        assertTrue("стоячи з мапою старий код палив повний герц", whileStanding)

        // А планувальник на тих самих входах екрана розрізняє їх.
        val moving = Rig().apply { motion = driving }
        val still = Rig().apply { motion = standing }
        moving.run(60)
        still.run(60)
        assertEquals(NavCadence.NAVIGATION, moving.cadence)
        assertEquals(NavCadence.STANDSTILL, still.cadence)
        assertNotEquals(moving.cadence, still.cadence)
    }

    @Test
    fun `стара умова смикала чип на кожен рух камери`() {
        // Людина їде, опускає камеру в чат на три секунди й повертається.
        // Кожен `request()` — це removeLocationUpdates + requestLocationUpdates.
        val staraTrace = mutableListOf<Boolean>()
        repeat(3) { staraTrace += legacyNavigationGrade(false, true, false, worldResting = true) }
        repeat(3) { staraTrace += legacyNavigationGrade(false, true, false, worldResting = false) }
        repeat(3) { staraTrace += legacyNavigationGrade(false, true, false, worldResting = true) }
        val staraFlips = staraTrace.zipWithNext().count { (a, b) -> a != b }
        assertEquals("старе правило перепрограмувало чип двічі за 9 секунд", 2, staraFlips)

        val rig = Rig().apply { motion = driving }
        rig.run(3)
        rig.mapOnGlass = false
        rig.run(3)
        rig.mapOnGlass = true
        rig.run(3)
        assertEquals("витримка зʼїдає весь цей епізод", 0, rig.switches())
        assertEquals(NavCadence.NAVIGATION, rig.cadence)
    }
}
