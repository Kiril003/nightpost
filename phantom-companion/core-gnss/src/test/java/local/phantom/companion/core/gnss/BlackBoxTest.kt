package local.phantom.companion.core.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBoxTest {

    private val out = mutableListOf<String>()

    private fun box(
        preTrigger: Int = 10,
        holdOff: Int = 3,
        maxRecords: Int = 1_000,
    ) = GnssBlackBox(preTrigger, holdOff, maxRecords) { out += it }

    private fun epoch(ns: Long, sats: Int = 8, cn0: Double = 38.0, bias: Double? = null) = GnssEpoch(
        elapsedRealtimeNanos = ns,
        observations = (1..sats).map {
            SatObservation(
                svid = it,
                constellation = Constellation.GPS,
                band = if (it % 3 == 0) Band.L5 else Band.L1,
                cn0DbHz = cn0,
                pseudorangeRateMps = 100.0 + it,
                elevationDeg = 20.0 + it,
                usedInFix = true,
            )
        },
        clockBiasNanos = bias,
    )

    private fun clean() = GnssIntegrity(0.0, 0.0, usableSats = 8, source = FixSource.GNSS_FUSED)
    private fun spoofed(score: Double = 1.0) = GnssIntegrity(
        jammingScore = 0.0,
        spoofingScore = score,
        usableSats = 8,
        source = FixSource.GNSS_FUSED,
        notes = listOf("Супутникова позиція розходиться з мережею на 12270 км — це підміна."),
    )

    @Test fun `silent while the sky is clean`() {
        val bb = box()
        repeat(50) { bb.offer(epoch(it.toLong()), clean()) }
        assertTrue("чисте небо не має писати нічого — got ${out.size}", out.isEmpty())
        assertFalse(bb.isArmed)
    }

    @Test fun `arming flushes the epochs that came before the attack`() {
        val bb = box(preTrigger = 10)
        repeat(25) { bb.offer(epoch(it.toLong()), clean()) }
        assertTrue(out.isEmpty())

        bb.offer(epoch(25), spoofed())

        // Заголовок + рівно preTrigger попередніх епох. Це і є суть скриньки:
        // на диску має бути видно, як виглядало небо ДО генератора.
        assertTrue("перший рядок — заголовок: ${out.first()}", out.first().contains(""""t":"header""""))
        assertEquals("заголовок + 10 попередніх епох + сама епоха-тригер", 12, out.size)
        assertTrue(bb.isArmed)
    }

    @Test fun `pre-trigger keeps the newest epochs, not the oldest`() {
        val bb = box(preTrigger = 5)
        repeat(20) { bb.offer(epoch(it.toLong()), clean()) }
        bb.offer(epoch(20), spoofed())

        val body = out.drop(1)
        assertEquals("5 епох до події + епоха-тригер", 6, body.size)
        assertTrue("має лишитись епоха 15, найстаріша з вікна", body.first().contains("\"ns\":15"))
        assertTrue("останній рядок — сама епоха-тригер", body.last().contains("\"ns\":20"))
        assertTrue("епоха 14 має вже випасти з вікна", body.none { it.contains("\"ns\":14") })
    }

    @Test fun `a flickering attack stays one recording, not ten`() {
        val bb = box(preTrigger = 2, holdOff = 3)
        repeat(5) { bb.offer(epoch(it.toLong()), clean()) }

        // Підміна миготить: атака, чисто, атака, чисто, атака.
        bb.offer(epoch(10), spoofed())
        val headersAfterFirst = out.count { it.contains(""""t":"header"""") }
        bb.offer(epoch(11), clean())
        bb.offer(epoch(12), spoofed())
        bb.offer(epoch(13), clean())
        bb.offer(epoch(14), spoofed())

        assertTrue("миготіння не має роззброювати скриньку", bb.isArmed)
        assertEquals(
            "один інцидент — один заголовок",
            headersAfterFirst,
            out.count { it.contains(""""t":"header"""") },
        )
    }

    @Test fun `disarms only after a sustained clean streak`() {
        val bb = box(preTrigger = 2, holdOff = 3)
        bb.offer(epoch(0), clean())
        bb.offer(epoch(1), spoofed())
        assertTrue(bb.isArmed)

        bb.offer(epoch(2), clean())
        bb.offer(epoch(3), clean())
        assertTrue("двох чистих епох замало", bb.isArmed)

        bb.offer(epoch(4), clean())
        assertFalse("три чисті поспіль — інцидент закінчено", bb.isArmed)
    }

    @Test fun `the session cap holds`() {
        val bb = box(preTrigger = 2, maxRecords = 12)
        bb.offer(epoch(0), clean())
        repeat(200) { bb.offer(epoch(it + 1L), spoofed()) }
        assertEquals("запис не може перелити стелю", 12, out.size)
        assertTrue(out.size <= 12)
    }

    @Test fun `every emitted line is valid single-line json`() {
        val bb = box(preTrigger = 3)
        repeat(5) { bb.offer(epoch(it.toLong(), bias = 1234.5), clean()) }
        bb.offer(epoch(5, bias = 99_000.0), spoofed())
        repeat(3) { bb.offer(epoch(6L + it), clean()) }

        assertTrue(out.isNotEmpty())
        out.forEach { line ->
            assertFalse("JSONL не терпить переносу рядка: $line", '\n' in line)
            assertTrue("рядок має бути обʼєктом: $line", line.startsWith("{") && line.endsWith("}"))
            assertEquals("дужки мають сходитись: $line", line.count { it == '{' }, line.count { it == '}' })
            assertEquals("квадратні теж: $line", line.count { it == '[' }, line.count { it == ']' })
            assertEquals(
                "лапки мають бути парними: $line",
                0,
                line.count { it == '"' } % 2,
            )
        }
    }

    @Test fun `excluded satellites and their reasons survive to disk`() {
        val bb = box(preTrigger = 1)
        bb.offer(epoch(0), clean())
        bb.offer(
            epoch(1),
            GnssIntegrity(
                jammingScore = 0.0,
                spoofingScore = 0.8,
                usableSats = 4,
                excluded = mapOf("GPS-3-L1" to "суперечить руху", "GPS-6-L5" to "фаза розходиться"),
                source = FixSource.DEAD_RECKONING,
            ),
        )
        val joined = out.joinToString("\n")
        assertTrue("виключені супутники мають бути в записі", "GPS-3-L1" in joined)
        assertTrue("причина виключення теж", "суперечить руху" in joined)
    }

    @Test fun `quotes in a reason cannot break the json`() {
        val bb = box(preTrigger = 1)
        bb.offer(epoch(0), clean())
        bb.offer(
            epoch(1),
            GnssIntegrity(
                jammingScore = 0.0,
                spoofingScore = 1.0,
                usableSats = 0,
                excluded = mapOf("""GPS-"1"-L1""" to """причина з "лапками" і \слешем"""),
            ),
        )
        out.forEach { line ->
            assertEquals("екранування має тримати парність лапок: $line", 0, line.count { it == '"' } % 2)
        }
    }

    @Test fun `forceArm records a clean field run for the test report`() {
        val bb = box(preTrigger = 4)
        repeat(10) { bb.offer(epoch(it.toLong()), clean()) }
        assertTrue("до примусу тиша", out.isEmpty())

        bb.forceArm(epoch(10))
        assertTrue(bb.isArmed)
        assertTrue("заголовок помічений як ручний", out.first().contains("manual-"))
        assertEquals("заголовок + 4 буферні епохи", 5, out.size)

        bb.offer(epoch(11), clean())
        assertEquals(6, out.size)
    }

    /**
     * Київ, 21.07.2026 — те, заради чого скринька існує.
     *
     * Спокійне небо, потім супутники раптом кажуть Ліму, а мережа лишається в
     * Києві. Перевіряємо не детектор (він має власні тести), а те, що інцидент
     * лишає по собі файл, у якому видно і атаку, і хвилину до неї.
     */
    @Test fun `the Kyiv 21 July incident leaves a file with the minute before it`() {
        val bb = box(preTrigger = 60, holdOff = 5)

        repeat(120) { bb.offer(epoch(it.toLong(), cn0 = 34.0 + (it % 7)), clean()) }
        assertTrue("до атаки на диск не лягає нічого", out.isEmpty())

        val divergence = 12_270.0
        repeat(10) {
            bb.offer(
                epoch(120L + it, cn0 = 47.0, bias = 500_000.0),
                spoofed(),
                networkDivergenceKm = divergence,
            )
        }

        val joined = out.joinToString("\n")
        assertTrue("розбіжність із мережею має бути записана", "12270" in joined)
        assertTrue("джерело розвʼязку теж", "GNSS_FUSED" in joined)
        assertEquals(
            "хвилина до атаки має бути на диску",
            60,
            out.count { it.contains(""""t":"epoch"""") && !it.contains(""""spoof":1""") },
        )
        assertTrue("сама атака теж", out.count { it.contains(""""spoof":1""") } >= 10)
    }
}
