package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2 — таблиця деривації під фальшивим годинником.
 *
 * Головне, що тут доводиться, — не «режим правильний», а «режим НЕ смикається».
 * Мапа, яка перебудовується на кожен рух класифікатора, гірша за мапу без
 * режимів узагалі: людина перестає їй вірити рівно на другому мигтінні.
 */
class SceneDeriverTest {

    private val t0 = 1_000_000L

    private fun run(
        signals: SceneSignals,
        from: SceneMemory = SceneMemory(),
        atMs: Long = t0,
    ): SceneMemory = SceneDeriver.fold(from, signals, atMs)

    /** Скільки б крокiв не було — стан тримається у значенні, не в обʼєкті. */
    private fun march(
        signals: SceneSignals,
        from: SceneMemory,
        startMs: Long,
        stepMs: Long,
        steps: Int,
    ): SceneMemory {
        var m = from
        for (i in 1..steps) m = SceneDeriver.fold(m, signals, startMs + stepMs * i)
        return m
    }

    // ── порядок таблиці ───────────────────────────────────────────────────

    @Test
    fun `мапу щойно відкрили — це огляд, а не спокій`() {
        assertEquals(SceneMode.EXPLORE, run(SceneSignals()).mode)
    }

    @Test
    fun `ведення старше за все`() {
        val loud = SceneSignals(
            guiding = true,
            ownOblastAlarm = true,
            transitContext = true,
            motion = MotionClass.DRIVE,
            motionTrusted = true,
        )
        val held = march(loud, run(loud), t0, 5_000L, 20)
        assertEquals(SceneMode.GUIDE, held.mode)
    }

    /** Успадковано від знятого `deriveMapScene`: обране НЕ скасовує ведення. */
    @Test
    fun `ведення з відкритим листом і пошуком лишається веденням`() {
        val busy = SceneSignals(guiding = true, browsing = true, interactionAtMs = t0)
        assertEquals(SceneMode.GUIDE, march(busy, run(busy), t0, 10_000L, 12).mode)
    }

    /** Успадковано від знятого `deriveMapScene`: сама лише позначка — це огляд. */
    @Test
    fun `одне лише обране місце дає огляд`() {
        val picked = SceneSignals(browsing = true, interactionAtMs = t0)
        assertEquals(SceneMode.EXPLORE, run(picked).mode)
    }

    @Test
    fun `сирена над власною областю бʼє ходу й транзит`() {
        val alarm = SceneSignals(
            ownOblastAlarm = true,
            transitContext = true,
            motion = MotionClass.DRIVE,
            motionTrusted = true,
        )
        assertEquals(SceneMode.THREAT, march(alarm, run(alarm), t0, 5_000L, 20).mode)
    }

    /**
     * Автобус для класифікатора — це авто. Якби хода стояла перед транзитним
     * наміром, режим DRIVE сховав би зупинки саме тому, хто щойно попросив
     * маршрут громадським транспортом.
     */
    @Test
    fun `транзитний намір старший за ходу`() {
        val bus = SceneSignals(
            transitContext = true,
            motion = MotionClass.DRIVE,
            motionTrusted = true,
        )
        assertEquals(SceneMode.TRANSIT, march(bus, run(bus), t0, 5_000L, 20).mode)
    }

    // ── витримка ──────────────────────────────────────────────────────────

    @Test
    fun `хода стає режимом лише через вікно витримки`() {
        val driving = SceneSignals(motion = MotionClass.DRIVE, motionTrusted = true)
        var m = run(driving)
        assertEquals(SceneMode.EXPLORE, m.mode)

        m = SceneDeriver.fold(m, driving, t0 + SceneDeriver.SUSTAINED_MS - 1)
        assertEquals("режим куплено за 19,999 с", SceneMode.EXPLORE, m.mode)

        m = SceneDeriver.fold(m, driving, t0 + SceneDeriver.SUSTAINED_MS)
        assertEquals(SceneMode.DRIVE, m.mode)
    }

    @Test
    fun `витримана ходьба дає пішохідний режим`() {
        val onFoot = SceneSignals(motion = MotionClass.WALK, motionTrusted = true)
        val m = SceneDeriver.fold(run(onFoot), onFoot, t0 + SceneDeriver.SUSTAINED_MS)
        assertEquals(SceneMode.WALK, m.mode)
    }

    @Test
    fun `невпевнений класифікатор режиму не купує`() {
        // browsing тримає екран притомним — інакше через півтори хвилини сюди
        // прийшов би спокій і сховав би те, що саме перевіряється.
        val unsure = SceneSignals(motion = MotionClass.DRIVE, motionTrusted = false, browsing = true)
        val m = march(unsure, run(unsure), t0, 5_000L, 20)
        assertEquals(SceneMode.EXPLORE, m.mode)
    }

    /** Світлофор — не пішохід: вихід із режиму коштує стільки ж, скільки вхід. */
    @Test
    fun `зупинка на світлофорі не знімає режим за кермом`() {
        val driving = SceneSignals(motion = MotionClass.DRIVE, motionTrusted = true)
        val inDrive = SceneDeriver.fold(run(driving), driving, t0 + SceneDeriver.SUSTAINED_MS)
        assertEquals(SceneMode.DRIVE, inDrive.mode)

        // Вікно рахується від першої побаченої зупинки, а не від входу в режим.
        val stopped = driving.copy(motion = MotionClass.STILL)
        val stoodAt = t0 + SceneDeriver.SUSTAINED_MS + 1_000L
        var m = SceneDeriver.fold(inDrive, stopped, stoodAt)
        assertEquals(SceneMode.DRIVE, m.mode)
        m = SceneDeriver.fold(m, stopped, stoodAt + SceneDeriver.SUSTAINED_MS - 1)
        assertEquals(SceneMode.DRIVE, m.mode)
        // Далі — вже не поїздка. Що саме приходить на зміну, вирішують нижчі
        // рядки: телефон у тримачі ніхто не чіпав, отже спокій.
        m = SceneDeriver.fold(m, stopped, stoodAt + SceneDeriver.SUSTAINED_MS)
        assertNotEquals("двадцять секунд стояння — це вже не поїздка", SceneMode.DRIVE, m.mode)
    }

    /**
     * Свідчення, що мигає, ніколи не накопичується до перемикання — той самий
     * закон, що вже стоїть у самому класифікаторі, тільки поверхом вище.
     */
    @Test
    fun `мигання ходи не купує жодного режиму`() {
        val drive = SceneSignals(motion = MotionClass.DRIVE, motionTrusted = true, browsing = true)
        val walk = drive.copy(motion = MotionClass.WALK)
        var m = run(drive)
        for (i in 1..40) {
            val signals = if (i % 2 == 0) drive else walk
            m = SceneDeriver.fold(m, signals, t0 + 5_000L * i)
            assertEquals("крок $i перемкнув режим", SceneMode.EXPLORE, m.mode)
        }
    }

    // ── спокій ────────────────────────────────────────────────────────────

    @Test
    fun `тридцять секунд без дотику дають спокій`() {
        val idle = SceneSignals(interactionAtMs = t0)
        var m = run(idle)
        m = SceneDeriver.fold(m, idle, t0 + SceneDeriver.IDLE_MS - 1)
        assertEquals(SceneMode.EXPLORE, m.mode)
        m = SceneDeriver.fold(m, idle, t0 + SceneDeriver.IDLE_MS)
        assertEquals(SceneMode.CALM, m.mode)
    }

    @Test
    fun `дотик будить екран із спокою`() {
        val idle = SceneSignals(interactionAtMs = t0)
        val calm = SceneDeriver.fold(run(idle), idle, t0 + SceneDeriver.IDLE_MS)
        assertEquals(SceneMode.CALM, calm.mode)

        val touched = idle.copy(interactionAtMs = t0 + SceneDeriver.IDLE_MS)
        val awake = SceneDeriver.fold(calm, touched, t0 + SceneDeriver.IDLE_MS + 1)
        assertEquals(SceneMode.EXPLORE, awake.mode)
    }

    /** Відкритий лист — це та сама рука на телефоні, тільки без дотику до землі. */
    @Test
    fun `поки щось відкрито, спокій не настає`() {
        val reading = SceneSignals(browsing = true, interactionAtMs = t0)
        val m = march(reading, run(reading), t0, 10_000L, 30)
        assertEquals(SceneMode.EXPLORE, m.mode)
    }

    /**
     * САМЕ ТУТ дві сцени розходились.
     *
     * Поки густоту виводила діяльність, а хром — стан екрана, пів хвилини без
     * дотику з відкритою панеллю давали спокій одному і огляд другому: мапа
     * стишувала себе під рукою, яка тримала відкритий лист. Тепер вісь одна,
     * і цей стан не просто узгоджений — він НЕПРЕДСТАВНИЙ: відкрита панель
     * сама і є активністю, тож жодна витримка спокою до неї не доживає.
     */
    @Test
    fun `тридцять секунд без дотику з відкритою панеллю — це не спокій`() {
        val panelOpen = SceneSignals(browsing = true, interactionAtMs = t0)
        var m = run(panelOpen)
        for (step in listOf(SceneDeriver.IDLE_MS, 2 * SceneDeriver.IDLE_MS, 10 * SceneDeriver.IDLE_MS)) {
            m = SceneDeriver.fold(m, panelOpen, t0 + step)
            assertNotEquals("панель відкрито, а екран стишився", SceneMode.CALM, m.mode)
        }
        // І пробуджень під відкритою панеллю не планують: вердикт не зміниться.
        assertEquals(SceneDeriver.NO_DEADLINE, m.dueAtMs)
    }

    @Test
    fun `закритий лист починає відлік спокою з себе, а не з давнього дотику`() {
        val reading = SceneSignals(browsing = true, interactionAtMs = t0)
        val read = march(reading, run(reading), t0, 10_000L, 12)
        val closedAt = t0 + 120_000L
        val closed = reading.copy(browsing = false)

        var m = SceneDeriver.fold(read, closed, closedAt)
        assertEquals(SceneMode.EXPLORE, m.mode)
        m = SceneDeriver.fold(m, closed, closedAt + SceneDeriver.IDLE_MS)
        assertEquals(SceneMode.CALM, m.mode)
    }

    // ── контракт пробудження ──────────────────────────────────────────────

    @Test
    fun `дедлайн вказує саме на мить, коли вердикт зміниться`() {
        val driving = SceneSignals(motion = MotionClass.DRIVE, motionTrusted = true)
        val m = run(driving)
        assertEquals(t0 + SceneDeriver.SUSTAINED_MS, m.dueAtMs)
        assertEquals(SceneMode.DRIVE, SceneDeriver.fold(m, driving, m.dueAtMs).mode)
    }

    /**
     * Пробудження без наслідку — це вибуджений процесор задарма. Поки ведення
     * тримає екран, ані вікно ходи, ані відлік спокою вердикту не змінять, і
     * будити мапу нема за чим.
     */
    @Test
    fun `під веденням пробуджень не планують`() {
        val guiding = SceneSignals(
            guiding = true,
            motion = MotionClass.DRIVE,
            motionTrusted = true,
            interactionAtMs = t0,
        )
        assertEquals(SceneDeriver.NO_DEADLINE, run(guiding).dueAtMs)
    }

    @Test
    fun `нерухомий телефон у спокої більше не прокидається`() {
        val idle = SceneSignals(interactionAtMs = t0)
        val calm = SceneDeriver.fold(run(idle), idle, t0 + SceneDeriver.IDLE_MS)
        assertEquals(SceneDeriver.NO_DEADLINE, calm.dueAtMs)
    }

    @Test
    fun `кожен набір сигналів дає визначений режим`() {
        var seen = 0
        for (guiding in listOf(false, true)) {
            for (alarm in listOf(false, true)) {
                for (transit in listOf(false, true)) {
                    for (motion in MotionClass.entries) {
                        for (browsing in listOf(false, true)) {
                            val s = SceneSignals(
                                guiding = guiding,
                                ownOblastAlarm = alarm,
                                transitContext = transit,
                                motion = motion,
                                motionTrusted = true,
                                browsing = browsing,
                                interactionAtMs = t0,
                            )
                            val m = march(s, run(s), t0, 10_000L, 12)
                            assertTrue("$s без режиму", m.mode in SceneMode.entries)
                            seen++
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * MotionClass.entries.size * 2, seen)
    }
}
