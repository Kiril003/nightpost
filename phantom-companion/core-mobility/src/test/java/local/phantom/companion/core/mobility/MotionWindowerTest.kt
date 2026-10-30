package local.phantom.companion.core.mobility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Тести ресемплера сирого акселерометра на рівну сітку 10 Гц і нарізки на вікна.
 *
 * Уся система TMD стоїть на цьому кроці: класифікатор порівнює спектри вікон, а
 * спектр над нерівномірною сіткою — це різне число на різних телефонах у тій
 * самій поїздці. Тому тут перевіряється не «код не падає», а що вихід справді
 * рівномірний, що прогалина не породжує вигаданого руху і що межі вікон точні.
 *
 * Побудова «від істини»: подається сигнал відомої форми (синусоїда відомої
 * частоти) на свідомо нерівному годиннику, і від коду вимагається відновити ту
 * саму частоту на рівній сітці.
 */
class MotionWindowerTest {

    /** Свідомо нерівний крок годинника: реальний потік Android «дихає». */
    private val jitterDt = longArrayOf(70L, 130L, 90L, 110L, 100L, 120L, 80L, 105L)

    /**
     * Подати синусоїду по вертикальній осі (осі тяжіння) з нерівним кроком.
     * `az = g + amp·sin(2πf t)`, решта осей нульові — так коливання цілком
     * потрапляє у вертикальну складову вікна, а не в горизонтальну.
     */
    private fun feedVerticalSine(
        w: MotionWindower,
        originMs: Long,
        untilMs: Long,
        freqHz: Double,
        amp: Double,
        dts: LongArray = jitterDt,
        g: Double = 9.8,
    ): List<MotionWindow> {
        val out = ArrayList<MotionWindow>()
        var t = originMs
        var i = 0
        while (t <= untilMs) {
            val s = g + amp * sin(2.0 * Math.PI * freqHz * (t / 1000.0))
            out += w.offer(MotionSample(elapsedMs = t, ax = 0.0, ay = 0.0, az = s))
            t += dts[i % dts.size]
            i++
        }
        return out
    }

    @Test
    fun `нерівномірний вхід дає рівну сітку і відновлює задану частоту`() {
        // Київський тролейбус трясе телефон із каденсом ~1.5 Гц, а Android віддає
        // події то через 70, то через 130 мс. Правильний ресемплер має стерти цю
        // нерівність: вихідне вікно — рівно 180 вузлів по 100 мс, і його спектр
        // показує ту саму 1.5 Гц, що ми подали.
        val w = MotionWindower()
        val windows = feedVerticalSine(w, originMs = 0L, untilMs = 20_000L, freqHz = 1.5, amp = 2.0)

        assertTrue("за 20 с сирих даних мало завершитись хоч одне вікно", windows.isNotEmpty())
        val win = windows.first()
        assertEquals("вікно 18 с при 10 Гц — це рівно 180 вузлів", 180, win.size)
        assertEquals(
            "крок сітки має бути рівно 100 мс попри нерівний вхід, отримано ${win.gridStepMs()}",
            100.0, win.gridStepMs(), 1e-9,
        )
        assertEquals("тривалість вікна 179×100 мс", 17_900L, win.durationMs)
        assertEquals("свіжа сітка не має прогалин", 0.0, win.gapFraction, 1e-9)

        val f = MotionFeatures.extract(win)
        val cadence = f[MotionFeatureKeys.CADENCE_HZ] ?: -1.0
        assertTrue(
            "після ресемплінгу нерівного входу каденс має лишитись 1.5 Гц, отримано $cadence",
            abs(cadence - 1.5) < 0.15,
        )
    }

    @Test
    fun `перша межа вікна припадає рівно на 180-й вузол`() {
        // Межа вікна — це контракт із сегментацією: якщо перше вікно завершується
        // не там, зсунеться вся часова вісь поїздки. Подаємо рівний потік і
        // перевіряємо, що вікно стартує на початку сітки й закінчується через 17.9 с.
        val w = MotionWindower()
        val origin = 5_000L
        val windows = feedVerticalSine(w, originMs = origin, untilMs = origin + 20_000L, freqHz = 1.5, amp = 2.0)
        val win = windows.first()
        assertEquals("старт вікна = початок сітки", origin, win.startElapsedMs)
        assertEquals("кінець вікна = старт + 17.9 с", origin + 17_900L, win.endElapsedMs)
    }

    @Test
    fun `два перекривні вікна відстоять рівно на крок 9 секунд`() {
        // Перекриття 50 % означає новий вердикт кожні 9 с. Якщо крок збитий,
        // сегментація або смикається (крок менший), або сліпне на межах (більший).
        val w = MotionWindower()
        val windows = feedVerticalSine(w, originMs = 0L, untilMs = 40_000L, freqHz = 1.5, amp = 2.0)
        assertTrue("на 40 с даних мало вміститись щонайменше два вікна", windows.size >= 2)
        val step = windows[1].startElapsedMs - windows[0].startElapsedMs
        assertEquals("крок між вікнами — рівно 9 с", 9_000L, step)
    }

    @Test
    fun `прогалина довша за поріг скидання не породжує вікна крізь неї`() {
        // Найнебезпечніший сценарій: система заснула на 5 с (Doze, перереєстрація
        // слухача). Інтерполювати крізь таку паузу — означало б вигадати 5 секунд
        // руху, якого не спостерігали. Сітка мусить початися заново ПІСЛЯ паузи.
        val w = MotionWindower()
        // Трохи даних до паузи (менше за вікно — саме по собі нічого не віддасть).
        feedVerticalSine(w, originMs = 0L, untilMs = 8_000L, freqHz = 1.5, amp = 2.0)
        // Пауза 5 с (> RESET_GAP_MS = 3 с), далі знову дані.
        val afterGap = feedVerticalSine(w, originMs = 13_000L, untilMs = 33_000L, freqHz = 1.5, amp = 2.0)

        assertTrue("після відновлення потоку вікно все одно має зʼявитись", afterGap.isNotEmpty())
        val win = afterGap.first()
        assertTrue(
            "старт вікна має бути ПІСЛЯ паузи (>=13 с), інакше сітку простягли крізь сон: ${win.startElapsedMs}",
            win.startElapsedMs >= 13_000L,
        )
        assertEquals("вікно після скидання — чиста сітка без прогалин", 0.0, win.gapFraction, 1e-9)
    }

    @Test
    fun `помірна прогалина інтерполюється але чесно позначається в gapFraction`() {
        // Прогалина 800 мс — це не сон системи, а «повільний сенсор»: інтерполяція
        // ще припустима, але вікно мусить нести чесний слід про добудовані вузли,
        // щоб класифікатор міг змовчати, а не вгадувати.
        val w = MotionWindower()
        var t = 0L
        var i = 0
        val out = ArrayList<MotionWindow>()
        while (t <= 20_000L) {
            out += w.offer(MotionSample(t, 0.0, 0.0, 9.8 + 2.0 * sin(2.0 * Math.PI * 1.5 * (t / 1000.0))))
            // Одна навмисна діра 800 мс (> MAX_INTERPOLATION_GAP_MS = 400) на 5-й секунді.
            t += if (i == 50) 800L else 100L
            i++
        }
        val win = out.first()
        assertTrue(
            "вікно з дірою 800 мс має мати ненульову частку добудованих вузлів, отримано ${win.gapFraction}",
            win.gapFraction > 0.0,
        )
        assertTrue("але помірна діра не має скидати сітку — вікно все одно повне 180 вузлів", win.size == 180)
    }

    @Test
    fun `нечисловий зразок відкидається і не стає опорою інтерполяції`() {
        // NaN з платформи трапляється при перемиканні режиму сенсора. Один
        // зіпсований вузол, якби став опорою, протік би у два сусідні вікна.
        val w = MotionWindower()
        // Перший зразок — NaN: не має навіть започаткувати сітку.
        val r0 = w.offer(MotionSample(0L, Double.NaN, 0.0, 9.8))
        assertTrue("NaN не породжує вікна", r0.isEmpty())
        val windows = feedVerticalSine(w, originMs = 100L, untilMs = 20_100L, freqHz = 1.5, amp = 2.0)
        assertTrue("після відкидання NaN нормальний потік має дати вікно", windows.isNotEmpty())
        val f = MotionFeatures.extract(windows.first())
        assertTrue(
            "жоден вузол не має бути NaN — інакше NaN протік крізь інтерполяцію",
            (f[MotionFeatureKeys.ACC_MEAN] ?: Double.NaN).isFinite(),
        )
    }

    @Test
    fun `час що йде назад скидає сітку без збою`() {
        // Монотонний годинник теоретично не стрибає назад, але захисний код мусить
        // це витримати: інакше відʼємний dt дасть відʼємні ваги інтерполяції.
        val w = MotionWindower()
        feedVerticalSine(w, originMs = 10_000L, untilMs = 15_000L, freqHz = 1.5, amp = 2.0)
        // Стрибок назад на 5 с.
        val afterBack = feedVerticalSine(w, originMs = 3_000L, untilMs = 23_000L, freqHz = 1.5, amp = 2.0)
        assertTrue("після стрибка назад система має відновитись і видати вікно", afterBack.isNotEmpty())
        val win = afterBack.first()
        assertEquals("вікно після скидання — чиста сітка", 0.0, win.gapFraction, 1e-9)
        assertTrue("вузли вікна скінченні", win.accelMag.all { it.isFinite() })
    }

    @Test
    fun `reset повертає ресемплер у порожній стан`() {
        val w = MotionWindower()
        feedVerticalSine(w, originMs = 0L, untilMs = 10_000L, freqHz = 1.5, amp = 2.0)
        w.reset()
        // Після скидання одразу подаємо повний потік; вікно має стартувати з нового початку.
        val windows = feedVerticalSine(w, originMs = 100_000L, untilMs = 120_000L, freqHz = 1.5, amp = 2.0)
        assertTrue("після reset потік має дати вікно", windows.isNotEmpty())
        assertEquals("reset обнуляє сітку — старт із нового походження", 100_000L, windows.first().startElapsedMs)
    }
}
