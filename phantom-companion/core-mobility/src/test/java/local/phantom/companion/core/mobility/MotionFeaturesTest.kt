package local.phantom.companion.core.mobility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Тести ознак вікна і саморобного DSP під ними.
 *
 * Це серце класифікації: якщо каденс визначається неправильно або постійний
 * сигнал дає ненульову «енергію», брехатиме все дерево правил над ним. Тому
 * тести побудовані «від істини» — подається сигнал відомої частоти, і від коду
 * вимагається повернути саме її; подається константа, і енергія має бути нулем.
 *
 * Окремо перевіряється стабільність імен ключів: вони є контрактом для
 * класифікатора (нинішнього евристичного і майбутнього нейромережевого), і
 * мовчазна зміна ключа зламала б споживача без помилки компіляції.
 */
class MotionFeaturesTest {

    private companion object {
        const val HZ = 10.0
        const val N = 180
    }

    /** Чиста синусоїда частоти [freqHz] амплітуди [amp] з нульовим середнім. */
    private fun sine(freqHz: Double, amp: Double, n: Int = N, hz: Double = HZ): DoubleArray =
        DoubleArray(n) { i -> amp * sin(2.0 * Math.PI * freqHz * (i / hz)) }

    /** Вікно, у якого вертикальна складова — заданий сигнал, а модуль = g + сигнал. */
    private fun windowWith(vertical: DoubleArray, gravity: Double = 9.8): MotionWindow {
        val n = vertical.size
        val mag = DoubleArray(n) { gravity + vertical[it] }
        val horiz = DoubleArray(n)
        return MotionWindow(
            startElapsedMs = 0L,
            sampleHz = HZ,
            accelMag = mag,
            vertical = vertical,
            horizontal = horiz,
            gyroMag = DoubleArray(0),
            gravityMps2 = gravity,
            gapFraction = 0.0,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Від істини: відомий сигнал → відома ознака
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `спектральний пік чистої синусоїди дорівнює її частоті`() {
        // 2.0 Гц — це біговий каденс. За 180 вузлів при 10 Гц це рівно 36 повних
        // періодів: спектр без витоку, пік має сісти точно на 2.0 Гц.
        val x = sine(2.0, 1.0)
        val peak = MotionDsp.spectralPeak(x, 0.0, HZ, 0.5, 4.6, 0.025)
        assertEquals("пік має бути на частоті сигналу", 2.0, peak.hz, 0.03)

        // `strength` — це частка потужності піку в СУМІ по всіх точках скану.
        // При дрібному кроці 0.025 Гц точок ~165, тож абсолютне значення частки
        // мале за визначенням — і магічний поріг тут був би безглуздий. Значуще
        // інше: чистий тон мусить концентруватись помітно сильніше за шум. Саме
        // цю дискримінаційну здатність класифікатор і використовує, відрізняючи
        // ритмічну ходьбу від тряски в кишені.
        val noise = java.util.Random(1).let { r -> DoubleArray(N) { r.nextGaussian() } }
        val noisePeak = MotionDsp.spectralPeak(noise, mean(noise), HZ, 0.5, 4.6, 0.025)
        assertTrue(
            "тон (${peak.strength}) має концентруватись сильніше за шум (${noisePeak.strength})",
            peak.strength > noisePeak.strength * 2.0,
        )
    }

    private fun mean(x: DoubleArray) = x.average()

    @Test
    fun `автокореляційний пік дає ту саму частоту і високу періодичність`() {
        // Автокореляція відповідає на інше питання — чи повторюється ФОРМА циклу.
        // Для чистої синусоїди 2 Гц лаг періоду = 5 вузлів, а нормований пік ≈ 1.
        val x = sine(2.0, 1.0)
        val acorr = MotionDsp.autocorrelationPeak(x, 0.0, HZ, 0.5, 4.6)
        assertEquals("частота автокореляційного піку = частота сигналу", 2.0, acorr.hz, 0.1)
        assertTrue("ідеально періодичний сигнал має пік близько 1.0: ${acorr.value}", acorr.value > 0.8)
    }

    @Test
    fun `перетини нуля синусоїди дорівнюють подвоєній частоті`() {
        // Синусоїда f Гц перетинає своє середнє 2f разів на секунду. Це незалежна
        // від спектра перевірка, що визначення частоти взагалі узгоджене.
        val x = sine(1.5, 3.0)
        val zc = MotionDsp.zeroCrossingsPerSecond(x, 0.0, HZ)
        assertEquals("перетинів має бути 2f = 3.0 за секунду", 3.0, zc, 0.3)
    }

    @Test
    fun `постійний сигнал має нульову енергію в усіх смугах`() {
        // Телефон на сидінні авто, що плавно котиться: акселерометр дає майже
        // константу. Якби DSP приписав такій константі енергію, дерево правил
        // «побачило» б неіснуючий рух. Дисперсія і спектр мусять бути строго нуль.
        val x = DoubleArray(N) { 9.81 }
        val mean = MotionDsp.mean(x)
        assertEquals("дисперсія константи — нуль", 0.0, MotionDsp.variance(x, mean), 1e-12)
        val bands = MotionDsp.bandFractions(x, mean, HZ, MotionFeatures.BAND_EDGES_HZ)
        assertEquals("на кожну смугу — по нулю енергії", 0.0, bands.sum(), 1e-9)
        val peak = MotionDsp.spectralPeak(x, mean, HZ, 0.5, 4.6, 0.025)
        assertEquals("сила піку константи — нуль", 0.0, peak.strength, 1e-9)
    }

    @Test
    fun `енергія синусоїди лягає у смугу її частоти`() {
        // 2.0 Гц — це смуга P2 (1.6–2.6 Гц) за розкладкою BAND_EDGES_HZ.
        // Основна частка енергії має бути саме там, а не розмазана.
        val x = sine(2.0, 1.0)
        val bands = MotionDsp.bandFractions(x, 0.0, HZ, MotionFeatures.BAND_EDGES_HZ)
        // Індекси: p0[0.2,0.8) p1[0.8,1.6) p2[1.6,2.6) p3[2.6,3.6) p4[3.6,4.8)
        assertTrue("енергія 2 Гц має бути в смузі P2 (1.6–2.6): ${bands.toList()}", bands[2] > 0.7)
        assertEquals("частки смуг мають нормуватись у одиницю", 1.0, bands.sum(), 1e-9)
    }

    @Test
    fun `гоертцель нечутливий до сталої складової`() {
        // Ключова властивість: тяжіння (велика стала ~9.8) не має давати потужності
        // на жодній частоті, інакше кожне вікно «дзвеніло» б на 0 Гц.
        val x = DoubleArray(N) { 500.0 + sin(2.0 * Math.PI * 2.0 * (it / HZ)) }
        val mean = MotionDsp.mean(x)
        val pAtZeroBand = MotionDsp.goertzelPower(x, mean, HZ, 0.3)
        val pAtSignal = MotionDsp.goertzelPower(x, mean, HZ, 2.0)
        assertTrue("потужність на частоті сигналу має домінувати над низькочастотним фоном", pAtSignal > pAtZeroBand * 100)
    }

    // ————————————————————————————————————————————————————————————————
    // Контракт імен ключів
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `повне вікно віддає весь спектральний набір ключів`() {
        // Класифікатор читає ознаки за іменами з MotionFeatureKeys. Якщо ключ зник
        // або перейменувався — споживач мовчки отримає «не вимірювалось».
        val f = MotionFeatures.extract(windowWith(sine(2.0, 1.0)))
        val required = listOf(
            MotionFeatureKeys.WINDOW_SAMPLES, MotionFeatureKeys.WINDOW_SAMPLE_HZ,
            MotionFeatureKeys.WINDOW_GAP_FRACTION, MotionFeatureKeys.WINDOW_DURATION_S,
            MotionFeatureKeys.ACC_MEAN, MotionFeatureKeys.ACC_VAR, MotionFeatureKeys.ACC_STD,
            MotionFeatureKeys.ACC_RANGE, MotionFeatureKeys.ACC_ZERO_CROSS_HZ,
            MotionFeatureKeys.ACC_JERK_MEAN, MotionFeatureKeys.ACC_GRAVITY,
            MotionFeatureKeys.CADENCE_HZ, MotionFeatureKeys.CADENCE_STRENGTH,
            MotionFeatureKeys.ACORR_PEAK, MotionFeatureKeys.ACORR_HZ,
            MotionFeatureKeys.BAND_P0, MotionFeatureKeys.BAND_P1, MotionFeatureKeys.BAND_P2,
            MotionFeatureKeys.BAND_P3, MotionFeatureKeys.BAND_P4,
            MotionFeatureKeys.VERT_RMS, MotionFeatureKeys.HORIZ_RMS, MotionFeatureKeys.VERT_FRACTION,
            MotionFeatureKeys.GYRO_AVAILABLE,
        )
        for (key in required) {
            assertTrue("ознака '$key' зникла з контракту — класифікатор осліпне на неї", f.containsKey(key))
        }
    }

    @Test
    fun `відсутність гіроскопа і швидкості позначена нулем а не тишею`() {
        // «Не вимірювалось» ≠ «нуль руху». Без гіроскопа/швидкості прапорці
        // доступності мусять бути присутні й дорівнювати 0, а самих величин — не бути.
        val f = MotionFeatures.extract(windowWith(sine(2.0, 1.0)))
        assertEquals("немає гіроскопа → прапорець 0", 0.0, f[MotionFeatureKeys.GYRO_AVAILABLE]!!, 1e-9)
        assertFalse("без гіроскопа не має бути значення GYRO_MEAN", f.containsKey(MotionFeatureKeys.GYRO_MEAN))
        assertEquals("немає швидкості → прапорець 0", 0.0, f[MotionFeatureKeys.SPEED_AVAILABLE]!!, 1e-9)
        assertFalse("без швидкості не має бути SPEED_MEAN", f.containsKey(MotionFeatureKeys.SPEED_MEAN))
    }

    @Test
    fun `зовнішня швидкість дає повний набір швидкісних ознак`() {
        // Коли GNSS дав швидкості, ознаки мусять їх узагальнити: середнє, максимум,
        // коефіцієнт варіації і частку зупинок — саме на них спирається розділення
        // «авто проти громадського».
        val speeds = doubleArrayOf(8.0, 8.5, 0.0, 0.2, 9.0, 8.2)
        val f = MotionFeatures.extract(windowWith(sine(0.5, 0.3)), speeds)
        assertEquals("є швидкість → прапорець 1", 1.0, f[MotionFeatureKeys.SPEED_AVAILABLE]!!, 1e-9)
        assertEquals("максимум швидкості", 9.0, f[MotionFeatureKeys.SPEED_MAX]!!, 1e-9)
        assertTrue("частка зупинок має бути ненульова (два виміри < 0.6 м/с)", f[MotionFeatureKeys.SPEED_STOP_FRACTION]!! > 0.0)
    }

    @Test
    fun `нескінченні і відʼємні швидкості відкидаються перед статистикою`() {
        // GNSS-джерело зрідка віддає Inf/відʼємну швидкість при втраті фіксу.
        // Якби вони потрапили в середнє, воно стало б Inf і отруїло весь вердикт.
        val speeds = doubleArrayOf(Double.POSITIVE_INFINITY, -3.0, 5.0, 5.2, Double.NaN)
        val f = MotionFeatures.extract(windowWith(sine(0.5, 0.3)), speeds)
        assertTrue("середня швидкість має лишитись скінченною", f[MotionFeatureKeys.SPEED_MEAN]!!.isFinite())
        assertTrue("у середнє мали потрапити лише валідні 5.0 і 5.2", abs(f[MotionFeatureKeys.SPEED_MEAN]!! - 5.1) < 0.01)
    }

    @Test
    fun `коротке вікно не породжує вигаданого спектру`() {
        // Вікно коротше за MIN_SAMPLES — не привід «домальовувати» спектр. Мають
        // бути лише службові ключі, а класифікатор побачить WINDOW_SAMPLES і змовчить.
        val f = MotionFeatures.extract(windowWith(sine(2.0, 1.0, n = 16)))
        assertTrue("службовий ключ присутній завжди", f.containsKey(MotionFeatureKeys.WINDOW_SAMPLES))
        assertFalse("для короткого вікна каденс не рахується", f.containsKey(MotionFeatureKeys.CADENCE_HZ))
    }

    @Test
    fun `порожні масиви DSP не кидають виняток`() {
        // Вироджений вхід (порожньо, один елемент) не має валити конвеєр.
        assertEquals(0.0, MotionDsp.mean(DoubleArray(0)), 1e-12)
        assertEquals(0.0, MotionDsp.variance(DoubleArray(1) { 5.0 }, 5.0), 1e-12)
        assertEquals(0.0, MotionDsp.zeroCrossingsPerSecond(DoubleArray(0), 0.0, HZ), 1e-12)
        assertEquals("порожня автокореляція — нульовий пік", 0.0, MotionDsp.autocorrelationPeak(DoubleArray(0), 0.0, HZ, 0.5, 4.6).value, 1e-12)
    }
}
