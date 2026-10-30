package local.phantom.companion.feature.precision

import local.phantom.companion.core.gnss.Band
import local.phantom.companion.core.gnss.Constellation
import local.phantom.companion.core.gnss.FixSource
import local.phantom.companion.core.gnss.GnssEpoch
import local.phantom.companion.core.gnss.GnssIntegrity
import local.phantom.companion.core.gnss.SatObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести моделі стану екрана чесної позиції.
 *
 * Весь сенс екрана — показати правду про позицію в момент, коли решта
 * застосунку показує брехню сусіда. Тому головне, що тут перевіряється, —
 * ПОРЯДОК ПРІОРИТЕТУ загроз: підміна має перекривати все інше, бо вона
 * найнебезпечніша (координата бреше, виглядаючи здоровою), а не тому, що її
 * зручно перевіряти першою.
 *
 * Стан — чиста функція від даних, тож увесь екран перевіряється тут, на JVM,
 * без пристрою й без виїзду в поле під РЕБ.
 */
class PrecisionUiStateTest {

    private fun sat(
        svid: Int,
        constellation: Constellation = Constellation.GPS,
        band: Band = Band.L1,
        cn0: Double = 42.0,
        used: Boolean = true,
    ) = SatObservation(
        svid = svid,
        constellation = constellation,
        band = band,
        cn0DbHz = cn0,
        pseudorangeRateMps = 0.0,
        usedInFix = used,
    )

    private fun epoch(vararg obs: SatObservation) =
        GnssEpoch(elapsedRealtimeNanos = 0L, observations = obs.toList())

    private fun integrity(
        jam: Double = 0.0,
        spoof: Double = 0.0,
        source: FixSource = FixSource.GNSS_FUSED,
        excluded: Map<String, String> = emptyMap(),
        usable: Int = 8,
    ) = GnssIntegrity(
        jammingScore = jam,
        spoofingScore = spoof,
        usableSats = usable,
        excluded = excluded,
        source = source,
    )

    // ————————————————————————————————————————————————————————————————
    // Пріоритет вердиктів
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `здорове небо дає вердикт «надійна»`() {
        val st = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3), sat(4), sat(5), sat(6)),
            integrity = integrity(),
        )
        assertEquals(PrecisionVerdict.HEALTHY, st.verdict)
        assertFalse("надійний стан не тривожний", st.verdict.alarming)
    }

    @Test
    fun `підміна перекриває навіть здорові супутники`() {
        // Найпідступніший випадок: супутників багато й C/N0 висока, тобто всі
        // «кількісні» ознаки в нормі — а сигнал підмінений. Вердикт має бути
        // саме про підміну, а не «надійна».
        val st = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3), sat(4), sat(5), sat(6), sat(7), sat(8)),
            integrity = integrity(spoof = 0.9),
        )
        assertEquals(PrecisionVerdict.SPOOFED, st.verdict)
        assertTrue(st.verdict.alarming)
    }

    @Test
    fun `розбіжність із мережею сама по собі означає підміну`() {
        // Це саме те, що сталося 21.07: бал підміни міг бути ще не порахований,
        // але 12 270 км між супутниковим і мережевим розвʼязком не пояснити
        // нічим, крім брехні.
        val st = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3), sat(4), sat(5)),
            integrity = integrity(spoof = 0.0),
            networkDivergenceKm = 12_270.0,
        )
        assertEquals(PrecisionVerdict.SPOOFED, st.verdict)
    }

    @Test
    fun `помірна розбіжність не піднімає хибної тривоги`() {
        // Десяток кілометрів — у межах похибки стільникової привʼязки на краю
        // міста. Тривожити тут означає навчити людину ігнорувати тривогу.
        val st = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3), sat(4), sat(5), sat(6)),
            integrity = integrity(),
            networkDivergenceKm = 8.0,
        )
        assertEquals(PrecisionVerdict.HEALTHY, st.verdict)
    }

    @Test
    fun `придушення поступається підміні, але випереджає деградацію`() {
        val jammedOnly = PrecisionUiState.from(
            epoch = epoch(sat(1, cn0 = 22.0), sat(2, cn0 = 20.0)),
            integrity = integrity(jam = 0.8, source = FixSource.GNSS_FUSED),
        )
        assertEquals(PrecisionVerdict.JAMMED, jammedOnly.verdict)

        // Якщо одночасно є ознаки підміни — перемагає підміна.
        val both = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3), sat(4), sat(5)),
            integrity = integrity(jam = 0.8, spoof = 0.8),
        )
        assertEquals(PrecisionVerdict.SPOOFED, both.verdict)
    }

    @Test
    fun `числення шляху показується як окремий чесний стан`() {
        val st = PrecisionUiState.from(
            epoch = null,
            integrity = integrity(source = FixSource.DEAD_RECKONING),
            fixSource = FixSource.DEAD_RECKONING,
        )
        assertEquals(PrecisionVerdict.DEAD_RECKONING, st.verdict)
    }

    @Test
    fun `приміщення розпізнається за джерелом RTT`() {
        val st = PrecisionUiState.from(
            epoch = null,
            integrity = null,
            fixSource = FixSource.WIFI_RTT,
            indoorLevel = -2.0,
        )
        assertEquals(PrecisionVerdict.INDOOR, st.verdict)
        assertEquals(-2.0, st.indoorLevel!!, 1e-9)
    }

    @Test
    fun `мало супутників — деградація, а не удавана надійність`() {
        val st = PrecisionUiState.from(
            epoch = epoch(sat(1), sat(2), sat(3)),
            integrity = integrity(usable = 3),
        )
        assertEquals(PrecisionVerdict.DEGRADED, st.verdict)
    }

    @Test
    fun `порожній вхід — це «позиції немає», а не порожня надійність`() {
        val st = PrecisionUiState()
        assertEquals(PrecisionVerdict.NO_FIX, st.verdict)
        assertTrue(st.verdict.alarming)
    }

    // ————————————————————————————————————————————————————————————————
    // Складання таблиці супутників
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `виключені супутники несуть причину дослівно`() {
        val excludedKey = SatObservation(
            svid = 11, constellation = Constellation.GPS, band = Band.L1,
            cn0DbHz = 48.0, pseudorangeRateMps = 0.0, usedInFix = true,
        ).key
        val reason = "невʼязка 20 м проти інерціального вектора"

        val st = PrecisionUiState.from(
            epoch = epoch(
                sat(1), sat(2), sat(3), sat(4),
                sat(11, cn0 = 48.0),
            ),
            integrity = integrity(spoof = 0.9, excluded = mapOf(excludedKey to reason)),
        )

        val row = st.satellites.first { it.svid == 11 }
        assertFalse("виключений супутник не використовується", row.used)
        assertEquals("причина має дійти до екрана дослівно", reason, row.exclusionReason)
        assertEquals("рівно один виключений", 1, st.excludedCount)
    }

    @Test
    fun `супутники впорядковані за системою, потім за силою сигналу`() {
        val st = PrecisionUiState.from(
            epoch = epoch(
                sat(5, Constellation.GALILEO, cn0 = 30.0),
                sat(1, Constellation.GPS, cn0 = 20.0),
                sat(2, Constellation.GPS, cn0 = 44.0),
            ),
            integrity = integrity(),
        )
        // GPS перед GALILEO (порядок enum), усередині GPS — сильніший вище.
        assertEquals(Constellation.GPS, st.satellites[0].constellation)
        assertEquals("сильніший GPS має бути першим", 2, st.satellites[0].svid)
        assertEquals(Constellation.GPS, st.satellites[1].constellation)
        assertEquals(Constellation.GALILEO, st.satellites[2].constellation)
    }

    @Test
    fun `двочастотність визначається за наявністю L5`() {
        val single = PrecisionUiState.from(epoch(sat(1), sat(2)), integrity())
        assertFalse("лише L1 — одна частота", single.dualFrequency)

        val dual = PrecisionUiState.from(
            epoch(sat(1, band = Band.L1), sat(2, band = Band.L5)),
            integrity(),
        )
        assertTrue("L1+L5 — дві частоти", dual.dualFrequency)
    }

    @Test
    fun `сила сигналу нормується у межах нуль-один`() {
        val weak = SatelliteRow("k", 1, Constellation.GPS, Band.L1, cn0DbHz = 10.0, null, null, used = false)
        val strong = SatelliteRow("k", 1, Constellation.GPS, Band.L1, cn0DbHz = 55.0, null, null, used = true)
        val mid = SatelliteRow("k", 1, Constellation.GPS, Band.L1, cn0DbHz = 32.5, null, null, used = true)

        assertEquals("слабкий сигнал притискається до нуля", 0f, weak.strength, 1e-6f)
        assertEquals("сильний — до одиниці", 1f, strong.strength, 1e-6f)
        assertTrue("середній — десь усередині", mid.strength in 0.3f..0.7f)
    }

    @Test
    fun `порожня епоха при живому вердикті цілісності не падає`() {
        // Захист від NaN/порожнечі: числення шляху існує без жодного супутника.
        val st = PrecisionUiState.from(
            epoch = null,
            integrity = integrity(source = FixSource.DEAD_RECKONING, usable = 0),
            fixSource = FixSource.DEAD_RECKONING,
        )
        assertEquals(0, st.usedCount)
        assertNull(st.accuracyM)
        assertEquals(PrecisionVerdict.DEAD_RECKONING, st.verdict)
    }
}
