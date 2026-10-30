package local.phantom.companion.core.indoor

import local.phantom.companion.core.indoor.pdr.ContinuityState
import local.phantom.companion.core.indoor.pdr.PedestrianContinuity
import local.phantom.companion.core.indoor.pdr.PedestrianSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Тести пішохідної безперервності — того самого джерела, що має підхопити
 * позицію, коли супутник зник.
 *
 * Сценарій один і той самий: людина йде вулицею з живим супутником, входить у
 * вестибюль метро (або потрапляє під глушіння), і далі єдине, що лишається, —
 * власні кроки. Ціна помилки тут асиметрична, і перевіряється саме це:
 * ЗАНИЗИТИ похибку страшніше, ніж завищити. Впевнений кружечок «±5 м» за
 * сімдесят метрів від людини гірший за чесне «не знаю», бо на нього спираються.
 *
 * Тому головні перевірки — не «числа приблизно правильні», а:
 *  • напрямок не вигадується, коли його нема звідки взяти;
 *  • кроки без курсу входять у похибку, а не мовчки зникають;
 *  • похибка росте з ВІДСТАННЮ і не росте, поки людина стоїть;
 *  • знак повороту такий, що лівий поворот веде на захід, а не на схід.
 */
class PedestrianContinuityTest {

    private val g = 9.81
    private val sampleHz = 50.0
    private val dtMs = (1000.0 / sampleHz).toLong()

    private fun sensors(
        accelerometer: Boolean = true,
        gyroscope: Boolean = true,
        rotationVector: Boolean = true,
        magnetometer: Boolean = true,
        barometer: Boolean = false,
    ) = PedestrianSensors(accelerometer, gyroscope, rotationVector, magnetometer, barometer)

    /**
     * Відрізок ходьби: телефон екраном угору, вертикальні коливання ходи йдуть
     * по осі Z пристрою. Кожен період синусоїди — рівно один крок, тож закладена
     * кількість кроків відома точно.
     *
     * @return час останнього семпла
     */
    private fun walk(
        c: PedestrianContinuity,
        seconds: Double,
        startMs: Long,
        cadenceHz: Double = 1.8,
        amplitudeMps2: Double = 4.0,
        yawRateRadPerS: Double? = null,
    ): Long {
        val n = (seconds * sampleHz).toInt()
        var t = startMs
        for (i in 0..n) {
            t = startMs + i * dtMs
            val phase = 2.0 * PI * cadenceHz * (i / sampleHz)
            c.onAccelerometer(t, 0.0, 0.0, g + amplitudeMps2 * sin(phase))
            if (yawRateRadPerS != null) c.onGyroscope(t, 0.0, 0.0, yawRateRadPerS)
        }
        return t
    }

    /** Стояння: сила тяжіння без коливань ходи. */
    private fun stand(c: PedestrianContinuity, seconds: Double, startMs: Long): Long {
        val n = (seconds * sampleHz).toInt()
        var t = startMs
        for (i in 0..n) {
            t = startMs + i * dtMs
            c.onAccelerometer(t, 0.0, 0.0, g)
        }
        return t
    }

    /** Спокійне земне поле разом зі свіжим азимутом — 5 Гц, як на реальному пристрої. */
    private fun quietField(
        c: PedestrianContinuity,
        seconds: Double,
        startMs: Long,
        azimuthRad: Double = 0.0,
        fieldUt: Double = 48.0,
    ): Long {
        val step = 200L
        var t = startMs
        var i = 0L
        while (i * step <= (seconds * 1000).toLong()) {
            t = startMs + i * step
            c.onAzimuth(t, azimuthRad)
            c.onMagneticField(t, fieldUt, 0.0, 0.0)
            i++
        }
        return t
    }

    // ————————————————————————————————————————————————————————————————
    // Напрямок не вигадується
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `без жодного джерела курсу хід сліпий, а не спрямований`() {
        // Телефон без магнітометра, супутника не було жодного разу. Кроки
        // рахуються — це чиста фізика акселерометра, — але куди саме людина
        // йшла, не відомо. Найгірше, що тут можна зробити, — прийняти курс за
        // нуль і повести людину на північ.
        val c = PedestrianContinuity(sensors(magnetometer = false, rotationVector = false))
        val end = walk(c, seconds = 20.0, startMs = 0L)
        val s = c.snapshot(end)

        assertEquals(ContinuityState.BLIND, s.state)
        assertTrue("кроки мали рахуватись, а виявлено ${s.stepCount}", s.stepCount > 25)
        assertTrue("шлях мав накопичитись, а вийшло ${s.pathLengthM}", s.pathLengthM > 15.0)
        assertEquals("зміщення без курсу дорівнює нулю", 0.0, s.displacementM, 1e-9)
        assertNull("напрямок зміщення не вигадується", s.displacementBearingRad)
        assertNull("курс не вигадується", s.headingRad)
    }

    @Test
    fun `кроки без курсу входять у похибку, а не зникають`() {
        // Ключова чесність: людина пройшла 20 метрів у невідомому напрямку —
        // отже, вона десь у колі радіусом 20 метрів. Якщо ці кроки просто не
        // зрушили координату, похибка зобовʼязана вирости рівно на їхній шлях,
        // інакше екран покаже «±0 м» для людини, що вже за квартал звідси.
        val c = PedestrianContinuity(sensors(magnetometer = false, rotationVector = false))
        val end = walk(c, seconds = 25.0, startMs = 0L)
        val s = c.snapshot(end)

        assertEquals(
            "похибка сліпого ходу має дорівнювати пройденому шляху",
            s.pathLengthM,
            s.accuracyM,
            0.5,
        )
        assertTrue("сліпий хід не може бути інформативним", !s.informative)
    }

    @Test
    fun `магнітна аномалія не дає взяти курс`() {
        // Вестибюль метро: поле стрибає з 48 до 90 мкТл і назад. Азимут у такому
        // полі повернутий на десятки градусів. Довіритись йому означає зафіксувати
        // всю подальшу траєкторію поверненою — тому курс не береться взагалі.
        val c = PedestrianContinuity(sensors())
        var t = 0L
        for (i in 0..100) {
            t = i * 200L
            c.onAzimuth(t, 0.0)
            c.onMagneticField(t, if (i % 2 == 0) 48.0 else 92.0, 0.0, 0.0)
        }
        val end = walk(c, seconds = 20.0, startMs = t + 200L)
        val s = c.snapshot(end)

        assertTrue("аномалія має бути оголошена", s.magneticAnomaly)
        assertNull("курс у спотвореному полі не береться", s.headingRad)
        assertEquals(ContinuityState.BLIND, s.state)
    }

    @Test
    fun `спокійне поле дає абсолютний курс`() {
        // Те саме залізо, але поле рівне — тоді азимут вартий довіри, і хід
        // перестає бути сліпим.
        val c = PedestrianContinuity(sensors())
        val t = quietField(c, seconds = 12.0, startMs = 0L, azimuthRad = 0.0)
        val end = walk(c, seconds = 15.0, startMs = t + 200L)
        val s = c.snapshot(end)

        assertNotNull("у чистому полі курс має зʼявитись", s.headingRad)
        assertEquals(ContinuityState.HOLDING, s.state)
        assertTrue("зміщення мало зʼявитись, а вийшло ${s.displacementM}", s.displacementM > 5.0)
    }

    // ————————————————————————————————————————————————————————————————
    // Знак повороту
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `проєкція гіроскопа бере обертання навколо вертикалі, а не навколо осі екрана`() {
        // Телефон екраном угору: вертикаль збігається з віссю Z, обертання
        // навколо Z і є поворотом людини.
        assertEquals(
            1.0,
            PedestrianContinuity.yawRateAboutVertical(0.0, 0.0, 1.0, 0.0, 0.0, 9.81),
            1e-9,
        )
        // Той самий телефон, покладений на бік: «вгору» тепер по осі Y, і
        // обертання навколо Z жодного повороту людині не дає.
        assertEquals(
            0.0,
            PedestrianContinuity.yawRateAboutVertical(0.0, 0.0, 1.0, 0.0, 9.81, 0.0),
            1e-9,
        )
        // Телефон у кишені під 45°: береться саме проєкція, а не одна вісь —
        // обертання навколо Y дає рівно cos45° повороту людини.
        val tilted = PedestrianContinuity.yawRateAboutVertical(0.0, 1.0, 0.0, 0.0, 6.94, 6.94)
        assertEquals(0.70710678, tilted, 1e-6)
        // Виродження: тяжіння не виміряно — повороту не вигадуємо.
        assertEquals(0.0, PedestrianContinuity.yawRateAboutVertical(1.0, 1.0, 1.0, 0.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun `лівий поворот веде на захід, а не на схід`() {
        // Найдорожча помилка знака в усьому PDR: переплутаний знак гіроскопа дає
        // дзеркальну траєкторію, яка виглядає абсолютно правдоподібно й веде
        // людину до протилежного виходу зі станції.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(elapsedMs = 0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)

        // Північ — 15 секунд.
        var t = walk(c, seconds = 15.0, startMs = 100L)
        // Поворот ліворуч на 90°: додатна кутова швидкість навколо вертикалі.
        t = walk(c, seconds = 1.6, startMs = t + dtMs, yawRateRadPerS = 1.0)
        // Захід — ще 15 секунд.
        val end = walk(c, seconds = 15.0, startMs = t + dtMs)

        val s = c.snapshot(end)
        val deg = Math.toDegrees(s.displacementBearingRad!!)
        assertTrue(
            "після лівого повороту зміщення має піти в північно-західну чверть, а вийшло $deg°",
            deg in 271.0..359.0,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Похибка
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `похибка росте з відстанню і не росте від стояння`() {
        // Це відрізняє пішохідне числення від автомобільного: людина, що стоїть
        // на платформі десять хвилин, не стає менш відомою — кроків немає,
        // помилці нізвідки взятись. Фільтр, який «старіє» за часом, змусив би її
        // роздутись на порожньому місці.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        var t = walk(c, seconds = 30.0, startMs = 100L)
        val afterWalk = c.snapshot(t)

        t = stand(c, seconds = 60.0, startMs = t + dtMs)
        val afterStanding = c.snapshot(t)

        assertEquals(ContinuityState.STILL, afterStanding.state)
        assertEquals(
            "стояння не має роздувати похибку",
            afterWalk.accuracyM,
            afterStanding.accuracyM,
            1e-9,
        )
        assertEquals("стоячи людина не рахує кроків", 0.0, afterStanding.cadenceHz, 1e-9)
    }

    @Test
    fun `зміщення менше за власну похибку не оголошується інформативним`() {
        // Привʼязка була на ±10 м, людина пройшла шість. Стверджувати, що вона
        // змістилась, тут не можна: цілком можливо, що вона взагалі не рухалась
        // відносно тієї точки. Екран має сказати саме це, а не показати цифру.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 10.0, bearingRad = 0.0, speedMps = 1.3)
        val end = walk(c, seconds = 5.0, startMs = 100L)
        val s = c.snapshot(end)

        assertTrue("кроки мали бути", s.stepCount > 3)
        assertTrue(
            "зміщення ${s.displacementM} проти похибки ${s.accuracyM} — не інформативне",
            !s.informative,
        )
    }

    @Test
    fun `далекий хід без нової привʼязки оголошується таким, що втратив точність`() {
        // Дві хвилини ходьби від останнього супутника: систематична похибка
        // курсу вже дає десятки метрів упоперек. Показувати це як «хід» означає
        // вчити людину довіряти цифрі, яка вже нічого не означає.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        val end = walk(c, seconds = 150.0, startMs = 100L)
        val s = c.snapshot(end)

        assertEquals(ContinuityState.DRIFTED, s.state)
        assertTrue("похибка мала перерости поріг корисності: ${s.accuracyM}", s.accuracyM > 50.0)
    }

    @Test
    fun `нова привʼязка скидає накопичений дрейф`() {
        // Людина вийшла з переходу, супутник повернувся. Дрейф зобовʼязаний
        // зникнути миттєво — інакше екран довго показував би стару невпевненість
        // поверх свіжого розвʼязку.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        val t = walk(c, seconds = 150.0, startMs = 100L)
        assertTrue(c.snapshot(t).accuracyM > 50.0)

        c.onTrustedFix(t + 10L, accuracyM = 6.0, bearingRad = 0.0, speedMps = 1.3)
        val s = c.snapshot(t + 20L)

        assertEquals("похибка після привʼязки дорівнює якості привʼязки", 6.0, s.accuracyM, 0.5)
        assertEquals("відлік зміщення починається заново", 0.0, s.displacementM, 1e-9)
        assertEquals(0, s.stepCount)
        assertTrue("привʼязка супутникова — отже, до світу", s.anchoredToWorld)
    }

    @Test
    fun `початок без супутника чесно не вважається привʼязкою до світу`() {
        // Зміщення від точки старту відоме точно; невідома АБСОЛЮТНА координата
        // цієї точки. Плутати одне з іншим — це і є вигадана позиція.
        val c = PedestrianContinuity(sensors())
        val end = walk(c, seconds = 10.0, startMs = 0L)
        assertTrue("без супутника привʼязки до світу немає", !c.snapshot(end).anchoredToWorld)
    }

    // ————————————————————————————————————————————————————————————————
    // Неузгодженість «пристрій ↔ тіло»
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `неузгодженість пристрій-тіло спостерігається з курсу руху`() {
        // Телефон лежить у нагрудній кишені боком: його азимут показує на північ,
        // а людина йде на схід. Різниця в 90° — систематична, вона не зникає з
        // усередненням, і спостерегти її можна рівно поки супутник ще живий.
        val c = PedestrianContinuity(sensors())
        var t = quietField(c, seconds = 12.0, startMs = 0L, azimuthRad = 0.0)
        t = walk(c, seconds = 5.0, startMs = t + 200L)

        assertNull("до спостереження неузгодженість не вигадується", c.snapshot(t).misalignmentRad)

        repeat(8) { i ->
            c.onTrustedFix(t + i * 1000L, accuracyM = 5.0, bearingRad = PI / 2, speedMps = 1.4)
        }
        val s = c.snapshot(t + 8_000L)

        assertNotNull("після спостережень неузгодженість має бути відома", s.misalignmentRad)
        assertTrue(
            "неузгодженість мала зійтись до 90°, а вийшло ${Math.toDegrees(s.misalignmentRad!!)}°",
            s.misalignmentRad!! > 1.2,
        )
        assertTrue(
            "курс тіла має відрізнятись від азимута пристрою",
            s.headingRad!! > 1.2,
        )
    }

    @Test
    fun `невизначеність курсу враховує неузгодженість, а не лише гіроскоп`() {
        // Курс пристрою «відомий на ±10°», але як саме людина несе телефон —
        // ні. Якщо ця складова не входить у похибку, PDR обіцяє точність, якої в
        // нього немає, і саме звідти беруться впевнені кружечки не в тому місці.
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        val end = walk(c, seconds = 10.0, startMs = 100L)
        val s = c.snapshot(end)

        assertTrue(
            "1σ курсу має включати апріорну неузгодженість: ${s.headingUncertaintyRad}",
            s.headingUncertaintyRad >= PedestrianContinuity.DEFAULT_MISALIGNMENT_PRIOR_RAD,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Вертикаль
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `рівень показується лише за наявності барометра`() {
        // Тиск може «прийти» від чого завгодно в тестовому стенді; підпис
        // «рівень −2» на пристрої без барометра був би вигадкою.
        val without = PedestrianContinuity(sensors(barometer = false))
        without.onAccelerometer(0L, 0.0, 0.0, g)
        without.onPressure(0L, 1013.0)
        assertNull("без барометра рівня немає", without.snapshot(10L).level)

        val with = PedestrianContinuity(sensors(barometer = true))
        with.onAccelerometer(0L, 0.0, 0.0, g)
        with.onPressure(0L, 1013.0)
        assertNotNull("з барометром рівень є", with.snapshot(10L).level)
    }

    @Test
    fun `спуск під землю дає відʼємний рівень`() {
        // Найцінніше, що дає барометр під РЕБ: глушник забирає небо, але повітря
        // в переході забрати не може. «Ти на платформі, а не у вестибюлі» часто
        // важливіше за «ти за вісім метрів на північ».
        val c = PedestrianContinuity(sensors(barometer = true))
        c.onAccelerometer(0L, 0.0, 0.0, g)
        // 30 секунд спуску: +2.4 гПа ≈ 20 метрів униз.
        for (i in 0..60) {
            val t = i * 500L
            c.onPressure(t, 1013.0 + 2.4 * (i / 60.0))
        }
        val s = c.snapshot(30_000L)

        assertTrue("висота мала стати відʼємною: ${s.relativeAltitudeM}", s.relativeAltitudeM < -5.0)
        assertTrue("рівень мав піти вниз: ${s.level}", s.level!! < -1.0)
    }

    // ————————————————————————————————————————————————————————————————
    // Межові випадки
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `без акселерометра стан незмінно недоступний`() {
        val c = PedestrianContinuity(sensors(accelerometer = false))
        walk(c, seconds = 20.0, startMs = 0L)
        val s = c.snapshot(20_000L)
        assertEquals(ContinuityState.UNAVAILABLE, s.state)
        assertEquals(0.0, s.displacementM, 1e-9)
    }

    @Test
    fun `до першого кроку стан — «визначаю», а не «стою»`() {
        // Різниця для людини суттєва: «визначаю» означає зачекати, «стою» —
        // що система вже все зрозуміла. Плутати їх — брехати про готовність.
        val c = PedestrianContinuity(sensors())
        val end = stand(c, seconds = 10.0, startMs = 0L)
        assertEquals(ContinuityState.WARMING, c.snapshot(end).state)
    }

    @Test
    fun `сміття з датчиків не отруює стан`() {
        val c = PedestrianContinuity(sensors())
        c.onAccelerometer(0L, Double.NaN, 0.0, g)
        c.onGyroscope(0L, Double.NaN, 0.0, 0.0)
        c.onAzimuth(0L, Double.NaN)
        c.onMagneticField(0L, Double.NaN, 0.0, 0.0)
        c.onPressure(0L, Double.NaN)

        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        val end = walk(c, seconds = 20.0, startMs = 100L)
        val s = c.snapshot(end)

        assertTrue("після сміття хід має працювати", s.displacementM.isFinite())
        assertTrue("похибка має лишитись скінченною", s.accuracyM.isFinite())
        assertEquals(ContinuityState.HOLDING, s.state)
    }

    @Test
    fun `reset повертає стан до початкового`() {
        val c = PedestrianContinuity(sensors())
        c.onTrustedFix(0L, accuracyM = 5.0, bearingRad = 0.0, speedMps = 1.3)
        walk(c, seconds = 20.0, startMs = 100L)
        c.reset()
        val s = c.snapshot(30_000L)

        assertEquals(ContinuityState.WARMING, s.state)
        assertEquals(0, s.stepCount)
        assertEquals(0.0, s.pathLengthM, 1e-9)
        assertNull(s.headingRad)
        assertTrue(!s.anchoredToWorld)
    }
}
