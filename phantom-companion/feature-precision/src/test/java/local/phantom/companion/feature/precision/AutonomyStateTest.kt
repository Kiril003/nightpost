package local.phantom.companion.feature.precision

import local.phantom.companion.core.gnss.Band
import local.phantom.companion.core.gnss.Constellation
import local.phantom.companion.core.gnss.FixSource
import local.phantom.companion.core.gnss.GnssEpoch
import local.phantom.companion.core.gnss.GnssIntegrity
import local.phantom.companion.core.gnss.SatObservation
import local.phantom.companion.core.indoor.pdr.ContinuityState
import local.phantom.companion.core.indoor.pdr.PedestrianSensors
import local.phantom.companion.core.indoor.pdr.PedestrianSnapshot
import local.phantom.companion.core.indoor.rtt.RttAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Тести автономного ходу на екрані чесної позиції.
 *
 * Перевіряються дві речі, і обидві — про межу між даними і брехнею.
 *
 * **Перша: до чого можна привʼязати числення шляху.** Привʼязка переживає саму
 * атаку. Якщо привʼязати хід до підміненої координати, брехня поїде далі як
 * «власне числення пристрою» — уже без жодної ознаки підміни, за яку її можна
 * було б упіймати. Тому вимоги до привʼязки суворіші, ніж до показу позиції.
 *
 * **Друга: що саме побачить людина.** Там, де оцінка не впевнена, на екрані має
 * стояти слово, а не цифра — рівно як класифікатор режиму друкує «Визначаю…»
 * замість вигаданого режиму.
 */
class AutonomyStateTest {

    private fun sat(svid: Int, cn0: Double = 42.0) = SatObservation(
        svid = svid,
        constellation = Constellation.GPS,
        band = Band.L1,
        cn0DbHz = cn0,
        pseudorangeRateMps = 0.0,
        usedInFix = true,
    )

    private fun epoch(vararg obs: SatObservation) =
        GnssEpoch(elapsedRealtimeNanos = 0L, observations = obs.toList())

    private fun healthySky() = epoch(sat(1), sat(2), sat(3), sat(4), sat(5), sat(6))

    private fun integrity(jam: Double = 0.0, spoof: Double = 0.0, usable: Int = 8) = GnssIntegrity(
        jammingScore = jam,
        spoofingScore = spoof,
        usableSats = usable,
        source = FixSource.GNSS_FUSED,
    )

    private fun walk(
        state: ContinuityState = ContinuityState.HOLDING,
        stepCount: Int = 120,
        pathLengthM: Double = 90.0,
        displacementM: Double = 72.0,
        displacementBearingRad: Double? = PI / 4,
        accuracyM: Double = 11.0,
        informative: Boolean = true,
        anchoredToWorld: Boolean = true,
        headingRad: Double? = PI / 4,
        headingUncertaintyRad: Double = 0.2,
        magneticAnomaly: Boolean = false,
        level: Double? = null,
        relativeAltitudeM: Double = 0.0,
        inVerticalTransit: Boolean = false,
        verticalRateMps: Double = 0.0,
        sensors: PedestrianSensors = PedestrianSensors(
            accelerometer = true,
            gyroscope = true,
            rotationVector = true,
            magnetometer = true,
            barometer = false,
        ),
    ) = PedestrianSnapshot(
        state = state,
        stepCount = stepCount,
        pathLengthM = pathLengthM,
        displacementM = displacementM,
        displacementBearingRad = displacementBearingRad,
        accuracyM = accuracyM,
        informative = informative,
        anchoredToWorld = anchoredToWorld,
        headingRad = headingRad,
        headingUncertaintyRad = headingUncertaintyRad,
        magneticAnomaly = magneticAnomaly,
        level = level,
        relativeAltitudeM = relativeAltitudeM,
        verticalRateMps = verticalRateMps,
        inVerticalTransit = inVerticalTransit,
        sensors = sensors,
    )

    // ————————————————————————————————————————————————————————————————
    // До чого можна привʼязати числення шляху
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `здорове небо з малою похибкою годиться за привʼязку`() {
        val st = PrecisionUiState.from(healthySky(), integrity(), accuracyM = 8.0)
        assertTrue(st.trustedForAnchor)
    }

    @Test
    fun `підмінений розвʼязок не годиться за привʼязку, хоч і виглядає точним`() {
        // Найнебезпечніший випадок: підміна дає СИЛЬНИЙ сигнал і МАЛУ заявлену
        // похибку — саме тому вона й небезпечна. Привʼязати до неї хід означало б
        // зберегти брехню після того, як атаку виявлено й супутники відкинуто.
        val st = PrecisionUiState.from(healthySky(), integrity(spoof = 0.9), accuracyM = 3.0)
        assertFalse(st.trustedForAnchor)
    }

    @Test
    fun `розбіжність із мережею знімає довіру до привʼязки`() {
        val st = PrecisionUiState.from(
            healthySky(),
            integrity(),
            accuracyM = 4.0,
            networkDivergenceKm = 12_270.0,
        )
        assertFalse(st.trustedForAnchor)
    }

    @Test
    fun `придушений сигнал не годиться за привʼязку`() {
        val st = PrecisionUiState.from(healthySky(), integrity(jam = 0.8), accuracyM = 5.0)
        assertFalse(st.trustedForAnchor)
    }

    @Test
    fun `груба похибка не годиться за привʼязку`() {
        // Небо здорове, але розвʼязок «десь у сорока метрах». Ця похибка стала б
        // початковою умовою числення й тягнулась би крізь усю автономну ділянку.
        val st = PrecisionUiState.from(healthySky(), integrity(), accuracyM = 40.0)
        assertFalse(st.trustedForAnchor)
    }

    @Test
    fun `розвʼязок без заявленої похибки не годиться за привʼязку`() {
        val st = PrecisionUiState.from(healthySky(), integrity(), accuracyM = null)
        assertFalse("невідома похибка — не мала похибка", st.trustedForAnchor)
    }

    @Test
    fun `мало супутників — не привʼязка, навіть із гарною цифрою точності`() {
        val st = PrecisionUiState.from(epoch(sat(1), sat(2), sat(3)), integrity(usable = 3), accuracyM = 4.0)
        assertFalse(st.trustedForAnchor)
    }

    // ————————————————————————————————————————————————————————————————
    // Хід як джерело розвʼязку
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `привʼязаний хід несе позицію, непривʼязаний — ні`() {
        assertTrue(walk(state = ContinuityState.HOLDING, anchoredToWorld = true).carriesPosition)
        assertTrue("людина стоїть — позиція від цього не зникає", walk(state = ContinuityState.STILL).carriesPosition)
        assertFalse(
            "без привʼязки до світу зміщення не є позицією",
            walk(state = ContinuityState.HOLDING, anchoredToWorld = false).carriesPosition,
        )
        assertFalse("дрейф уже нічого не несе", walk(state = ContinuityState.DRIFTED).carriesPosition)
        assertFalse("сліпий хід не має напрямку", walk(state = ContinuityState.BLIND).carriesPosition)
        assertFalse(walk(state = ContinuityState.WARMING).carriesPosition)
    }

    @Test
    fun `числення стає джерелом розвʼязку і дає власний вердикт`() {
        // Це те, заради чого все й робилось: вердикт «Числення шляху» досі був
        // написаний, але недосяжний — жоден код не виставляв DEAD_RECKONING.
        val st = PrecisionUiState.from(
            epoch = null,
            integrity = null,
            fixSource = FixSource.DEAD_RECKONING,
            pedestrian = walk(),
        )
        assertEquals(PrecisionVerdict.DEAD_RECKONING, st.verdict)
    }

    @Test
    fun `підміна перекриває числення`() {
        // Порядок пріоритету незмінний: спершу людина має дізнатись про атаку, і
        // лише потім — чим саме тримається позиція.
        val st = PrecisionUiState.from(
            epoch = healthySky(),
            integrity = integrity(spoof = 0.9),
            fixSource = FixSource.DEAD_RECKONING,
            pedestrian = walk(),
        )
        assertEquals(PrecisionVerdict.SPOOFED, st.verdict)
    }

    // ————————————————————————————————————————————————————————————————
    // Що бачить людина
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `кожен стан має власний заголовок, а відсутність знімка — «визначаю»`() {
        // Межа чесності, задля якої цей тест і існує: `null` (джерело ще не
        // підписане, частка секунди) НЕ дорівнює UNAVAILABLE (акселерометра
        // немає взагалі, назавжди). Сказати «немає акселерометра» там, де
        // насправді «ще не почав», означало б збрехати про залізо пристрою.
        assertEquals("Визначаю…", continuityTitle(null))
        assertEquals("Недоступний", continuityTitle(walk(state = ContinuityState.UNAVAILABLE)))
        assertEquals("Визначаю…", continuityTitle(walk(state = ContinuityState.WARMING)))
        assertEquals("Стою", continuityTitle(walk(state = ContinuityState.STILL)))
        assertEquals("Кроки без напрямку", continuityTitle(walk(state = ContinuityState.BLIND)))
        assertEquals("Тримаю хід", continuityTitle(walk(state = ContinuityState.HOLDING)))
        assertEquals("Хід втратив точність", continuityTitle(walk(state = ContinuityState.DRIFTED)))
    }

    @Test
    fun `сліпий хід називає саме свою причину`() {
        // «Не працює» — не відповідь. Людині потрібна дія, а дія різна: чекати,
        // вийти з-під заліза або взагалі не сподіватись на курс на цьому пристрої.
        val noMag = continuityExplanation(
            walk(
                state = ContinuityState.BLIND,
                sensors = PedestrianSensors(accelerometer = true, gyroscope = true, magnetometer = false),
            ),
        )
        assertTrue("має бути названо саме відсутність магнітометра: $noMag", noMag.contains("магнітометра"))

        val anomaly = continuityExplanation(walk(state = ContinuityState.BLIND, magneticAnomaly = true))
        assertTrue("має бути названо спотворене поле: $anomaly", anomaly.contains("поле"))
    }

    @Test
    fun `зміщення менше за похибку показується словами, а не цифрою`() {
        // Головна лінія всього екрана. Цифра «6 м» тут була б прочитана як факт
        // руху, якого могло не бути взагалі.
        assertEquals("менше за похибку", displacementLabel(walk(informative = false)))
        assertEquals("напрямок невідомий", displacementLabel(walk(state = ContinuityState.BLIND)))
        assertEquals("—", displacementLabel(walk(state = ContinuityState.WARMING)))
        assertEquals("—", displacementLabel(null))
    }

    @Test
    fun `інформативне зміщення показує і відстань, і румб`() {
        val label = displacementLabel(walk(displacementM = 72.0, displacementBearingRad = PI / 4))
        assertEquals("72 м · на північний схід", label)
    }

    @Test
    fun `румби покривають коло без розриву на півночі`() {
        assertEquals("на північ", cardinalPhrase(0.0))
        assertEquals("на схід", cardinalPhrase(PI / 2))
        assertEquals("на південь", cardinalPhrase(PI))
        assertEquals("на захід", cardinalPhrase(3 * PI / 2))
        // Найпідступніше місце: 359° — це північ, а не «−1-й румб».
        assertEquals("на північ", cardinalPhrase(2 * PI - 0.02))
    }

    @Test
    fun `курс завжди йде разом зі своєю невизначеністю`() {
        // «47°» і «47° ±25°» — різні твердження, і друге в кишені трапляється
        // частіше. Ховати σ означає обіцяти точність, якої немає.
        assertEquals("45° ±11°", headingLabel(walk(headingRad = PI / 4, headingUncertaintyRad = 0.2)))
        assertEquals("невідомий", headingLabel(walk(headingRad = null)))
        assertEquals("невідомий", headingLabel(null))
    }

    @Test
    fun `відсутність барометра називається, а не показується нулем`() {
        val without = walk(
            sensors = PedestrianSensors(accelerometer = true, gyroscope = true, barometer = false),
        )
        assertEquals("барометра немає", levelLabel(without))

        val with = walk(
            level = -2.0,
            relativeAltitudeM = -18.0,
            sensors = PedestrianSensors(accelerometer = true, gyroscope = true, barometer = true),
        )
        val label = levelLabel(with)
        assertTrue("рівень має бути видно: $label", label.startsWith("-2"))
        assertTrue("сирий вимір висоти має стояти поруч: $label", label.contains("18 м"))
        assertTrue("напрямок глибини має бути названий: $label", label.contains("вниз"))
    }

    @Test
    fun `вертикальний рух показується лише коли він є`() {
        assertEquals(null, verticalTransitLabel(walk(inVerticalTransit = false)))
        assertEquals("спуск", verticalTransitLabel(walk(inVerticalTransit = true, verticalRateMps = -0.6)))
        assertEquals("підйом", verticalTransitLabel(walk(inVerticalTransit = true, verticalRateMps = 0.6)))
    }

    @Test
    fun `Wi-Fi RTT не оголошується робочим там, де він не працює`() {
        // Ключове — гілка SUPPORTED: апаратура є, але дальності без координат
        // точок доступу позиції не дають. Написати тут «підтримується» означало
        // б пообіцяти можливість, якої в застосунку немає.
        assertEquals("пристрій не підтримує", rttCapabilityLabel(RttAvailability.NO_HARDWARE))
        assertEquals("пристрій не підтримує", rttCapabilityLabel(null))
        assertEquals("Wi-Fi вимкнено", rttCapabilityLabel(RttAvailability.DISABLED))
        assertTrue(rttCapabilityLabel(RttAvailability.PERMISSION_MISSING).contains("дозвіл"))
        val supported = rttCapabilityLabel(RttAvailability.SUPPORTED)
        assertTrue("має бути названо справжню перешкоду: $supported", supported.contains("координат"))
    }

    @Test
    fun `відстані читаються людиною, а не приймачем`() {
        assertEquals("94 м", metersLabel(94.4))
        assertEquals("0 м", metersLabel(0.0))
        assertTrue("кілометри — з одиницею «км»", metersLabel(1_500.0).endsWith("км"))
        assertEquals("—", metersLabel(Double.NaN))
        assertEquals("±11 м", accuracyLabel(walk(accuracyM = 11.0)))
        assertEquals("—", accuracyLabel(null))
    }
}
