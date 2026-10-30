package local.phantom.companion.core.gnss

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести розділених метрик глушіння та підміни.
 *
 * Перевіряється головне твердження модуля: глушіння й підміна — протилежні
 * атаки з протилежними почерками, і детектор мусить розрізняти їх, а не звести
 * до одного «щось не так». Сценарій підміни відтворює подію 21.07.2026.
 */
class JamSpoofMetricsTest {

    private fun sat(svid: Int, cn0: Double, band: Band = Band.L1) = SatObservation(
        svid = svid,
        constellation = Constellation.GPS,
        band = band,
        cn0DbHz = cn0,
        pseudorangeRateMps = 0.0,
    )

    /** Здорове строкате небо: різні супутники — різна C/N0. */
    private fun healthyEpoch(t: Long, clockBias: Double? = 0.0) = GnssEpoch(
        elapsedRealtimeNanos = t,
        observations = listOf(
            sat(1, 44.0, Band.L1), sat(2, 38.0, Band.L1), sat(3, 41.0, Band.L5),
            sat(4, 33.0, Band.L1), sat(5, 47.0, Band.L1), sat(6, 29.0, Band.L1),
            sat(7, 36.0, Band.L5), sat(8, 42.0, Band.L1),
        ),
        clockBiasNanos = clockBias,
    )

    /** Прогріти базу здоровими епохами, щоб було з чим порівнювати. */
    private fun warmUp(m: JamSpoofMetrics, epochs: Int = 6) {
        for (i in 0 until epochs) m.evaluate(healthyEpoch(i * 1_000_000_000L))
    }

    // ————————————————————————————————————————————————————————————————
    // Норма
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `здорове небо не піднімає жодної тривоги`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        val v = m.evaluate(healthyEpoch(10_000_000_000L))
        assertTrue("глушіння не мало спрацювати: ${v.jammingScore}", v.jammingScore < 0.3)
        assertTrue("підміна не мала спрацювати: ${v.spoofingScore}", v.spoofingScore < 0.3)
        assertTrue("здорове небо не скомпрометоване", !v.compromised)
    }

    // ————————————————————————————————————————————————————————————————
    // Глушіння: сигнал слабшає
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `узгоджений обвал C-N0 читається як глушіння, не підміна`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        // Наближення до джерела завад: усі супутники різко просіли.
        val jammed = GnssEpoch(
            elapsedRealtimeNanos = 10_000_000_000L,
            observations = listOf(sat(1, 24.0), sat(2, 21.0), sat(3, 23.0)),
        )

        val v = m.evaluate(jammed)

        assertTrue("обвал C/N0 + втрата супутників — це глушіння: ${v.jammingScore}", v.jammingScore >= 0.6)
        assertTrue("це саме глушіння, а не підміна", v.jammingScore > v.spoofingScore)
    }

    @Test
    fun `втрата більшості супутників читається як глушіння`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        val fewLeft = GnssEpoch(
            elapsedRealtimeNanos = 10_000_000_000L,
            observations = listOf(sat(1, 40.0), sat(2, 38.0)), // було 8, лишилось 2
        )
        val v = m.evaluate(fewLeft)
        assertTrue("втрата 3/4 неба — глушіння: ${v.jammingScore}", v.jammingScore >= 0.6)
    }

    // ————————————————————————————————————————————————————————————————
    // Підміна: сигнал сильний, але бреше
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `розбіжність із мережею 12270 км — беззаперечна підміна`() {
        // Точний сценарій 21.07.2026: GPS показав Ліму, мережа лишилась у Києві.
        val m = JamSpoofMetrics()
        warmUp(m)

        val v = m.evaluate(
            healthyEpoch(10_000_000_000L),
            networkDivergenceKm = 12_270.0,
        )

        assertTrue("розбіжність у тисячі км — повний бал підміни: ${v.spoofingScore}", v.spoofingScore >= 0.9)
        assertTrue("сигнал сильний, тож глушіння НЕ мало спрацювати: ${v.jammingScore}", v.jammingScore < 0.3)
        assertTrue("стан скомпрометовано", v.compromised)
        assertTrue("причина має бути людською мовою", v.notes.any { "підміна" in it })
    }

    @Test
    fun `неприродно однорідний сильний сигнал підозрілий`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        // Генератор ллє всі супутники з майже однаковою високою потужністю —
        // строкатості справжнього неба немає.
        val uniform = GnssEpoch(
            elapsedRealtimeNanos = 10_000_000_000L,
            observations = listOf(sat(1, 45.0), sat(2, 45.2), sat(3, 44.8), sat(4, 45.1), sat(5, 44.9)),
        )
        val v = m.evaluate(uniform)
        assertTrue("однорідність сильного сигналу — ознака підміни: ${v.spoofingScore}", v.spoofingScore >= 0.5)
    }

    @Test
    fun `велика частка виключених FDE супутників свідчить про підміну`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        val excluded = mapOf(
            "GPS-1-L1" to "невʼязка проти IMU", "GPS-2-L1" to "невʼязка проти IMU",
            "GPS-3-L5" to "невʼязка проти IMU", "GPS-4-L1" to "невʼязка проти IMU",
        )
        val v = m.evaluate(healthyEpoch(10_000_000_000L), excludedByFde = excluded)
        assertTrue("половину неба виключено як брехливе: ${v.spoofingScore}", v.spoofingScore >= 0.6)
    }

    @Test
    fun `база не отруюється під час атаки`() {
        // Ключова властивість: якщо повільна підміна триває, вона НЕ має стати
        // новою нормою. Прогнавши кілька підмінених епох, детектор мусить і далі
        // їх ловити, а не звикнути.
        val m = JamSpoofMetrics()
        warmUp(m)
        var last = 0.0
        repeat(5) {
            last = m.evaluate(healthyEpoch(20_000_000_000L), networkDivergenceKm = 9_000.0).spoofingScore
        }
        assertTrue("після кількох атакованих епох підміна все ще ловиться: $last", last >= 0.9)
    }

    @Test
    fun `холодний старт не вигадує тривог`() {
        // Без історії неможливо сказати, обвал це чи норма. Чесна відповідь —
        // «поки не знаю», а не хибна тривога.
        val m = JamSpoofMetrics()
        val v = m.evaluate(healthyEpoch(0L))
        assertTrue("на першій епосі глушіння невизначене → 0", v.jammingScore < 0.3)
    }

    @Test
    fun `reset повертає детектор у невизначений стан`() {
        val m = JamSpoofMetrics()
        warmUp(m)
        m.reset()
        val v = m.evaluate(
            GnssEpoch(0L, listOf(sat(1, 20.0), sat(2, 21.0))),
        )
        assertTrue("після reset немає бази → глушіння не спрацьовує", v.jammingScore < 0.3)
    }
}
