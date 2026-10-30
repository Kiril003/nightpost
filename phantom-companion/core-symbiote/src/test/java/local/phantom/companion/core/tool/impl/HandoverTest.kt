package local.phantom.companion.core.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-08 — the handover card's math, checked against fixed points published with
 * each scheme. These are the only two claims in the whole medevac path that
 * are exact, so they are checked as exact strings, not tolerances.
 */
class HandoverTest {

    // ---- MGRS ----------------------------------------------------------

    @Test
    fun `MGRS at the origin is the published fixed point`() {
        assertEquals("31N AA 66021 00000", Mgrs.of(0.0, 0.0, 5))
    }

    /**
     * Zone 36 is EVEN, so the row alphabet is offset by five letters. Kyiv is
     * the case that catches a dropped offset: without it the reference lands
     * 500 km away with a letter that still looks plausible.
     */
    @Test
    fun `an even zone carries the five-letter row offset`() {
        assertEquals("36U UA 24182 91607", Mgrs.of(50.4501, 30.5234, 5))
    }

    @Test
    fun `an odd zone uses the unshifted row alphabet`() {
        assertEquals("35U KR 86572 25035", Mgrs.of(49.8397, 24.0316, 5))
    }

    @Test
    fun `fewer digits truncate, they do not round`() {
        assertEquals("36U UA 241 916", Mgrs.of(50.4501, 30.5234, 3))
        assertEquals("36U UA 24 91", Mgrs.of(50.4501, 30.5234, 2))
    }

    @Test
    fun `MGRS is refused outside the UTM band instead of being invented`() {
        assertNull(Mgrs.of(85.0, 30.0, 5))
        assertNull(Mgrs.of(-81.0, 30.0, 5))
    }

    @Test
    fun `the southern hemisphere gets its ten-million-metre offset`() {
        // Without the offset the northing goes negative and the row letter is
        // taken from a modulo of a negative number.
        val south = Mgrs.of(-33.8688, 151.2093, 5)
        assertNotNull(south)
        assertTrue(south!!.startsWith("56H "))
    }

    // ---- Open Location Code -------------------------------------------

    @Test
    fun `plus codes match the published reference vectors`() {
        assertEquals("8FVC2222+22", PlusCode.encode(47.0000625, 8.0000625, 10))
        assertEquals("7FG49QCJ+2V", PlusCode.encode(20.3700625, 2.7821875, 10))
        assertEquals("7FG49QCJ+2VX", PlusCode.encode(20.3701125, 2.782234375, 11))
        assertEquals("7FG49QCJ+2VXGJ", PlusCode.encode(20.3701135, 2.78223535, 13))
        assertEquals("4VCPPQGP+Q9", PlusCode.encode(-41.2730625, 174.7859375, 10))
        assertEquals("22222222+22", PlusCode.encode(-89.9999375, -179.9999375, 10))
        assertEquals("62G20000+", PlusCode.encode(0.5, -179.5, 4))
    }

    // ---- precision discipline -----------------------------------------

    @Test
    fun `printed precision never exceeds the fix's own doubt`() {
        assertEquals(5, precisionFor(3f).mgrsDigits)
        assertEquals(4, precisionFor(25f).mgrsDigits)
        assertEquals(3, precisionFor(200f).mgrsDigits)
        assertEquals(2, precisionFor(900f).mgrsDigits)
    }

    @Test
    fun `a fix that cannot state its doubt does not earn metre digits`() {
        val p = precisionFor(null)
        assertEquals(3, p.mgrsDigits)
        assertEquals(100, p.mgrsMeters)
        assertTrue(p.reason.contains("невідома"))
    }

    // ---- bearing, compass, spoken distance -----------------------------

    @Test
    fun `sixteen-point compass reads in Ukrainian and rounds to the nearest point`() {
        assertEquals("Пн", compassAbbrevUk(0.0))
        assertEquals("Пн", compassAbbrevUk(354.0))
        assertEquals("ПнСх", compassAbbrevUk(45.0))
        assertEquals("ПдСх", compassAbbrevUk(135.0))
        assertEquals("Зх", compassAbbrevUk(270.0))
        assertEquals("SE", compassAbbrevEn(135.0))
        assertEquals("південний схід", compassSpokenUk(135.0))
    }

    @Test
    fun `bearing is measured from the named place toward the operator`() {
        // Operator sits due east of the settlement.
        val b = bearingDegrees(50.0, 30.0, 50.0, 30.5)
        assertTrue("bearing was $b", b in 89.0..91.0)
    }

    @Test
    fun `distance is rounded to steps a voice channel survives`() {
        assertEquals("50 м", voiceDistance(47.0))
        assertEquals("1200 м", voiceDistance(1234.0))
        assertEquals("2 км", voiceDistance(2000.0))
        assertEquals("2,5 км", voiceDistance(2347.0))
    }

    // ---- settlement choice --------------------------------------------

    @Test
    fun `a nameable village beats a nearer anonymous locality`() {
        val pick = pickRecognisableSettlement(
            50.0000, 30.0000,
            listOf(
                SettlementCandidate("Урочище Глинка", 50.0027, 30.0000, "locality"), // ~300 m
                SettlementCandidate("Іванівка", 50.0063, 30.0000, "village"), // ~700 m
            ),
        )
        assertEquals("Іванівка", pick?.name)
    }

    @Test
    fun `an untagged raion is said out loud, not papered over`() {
        val line = nameSettlementLine(
            50.0063, 30.0000,
            SettlementCandidate("Іванівка", 50.0000, 30.0000, "village"),
        )
        assertTrue(line, line.contains("Іванівка"))
        assertTrue(line, line.contains("Район не позначено"))
    }

    @Test
    fun `a tagged raion rides with the name and the warning disappears`() {
        val line = nameSettlementLine(
            50.0063, 30.0000,
            SettlementCandidate(
                "Іванівка", 50.0000, 30.0000, "village",
                mapOf("addr:district" to "Бориспільський район"),
            ),
        )
        assertTrue(line, line.contains("Бориспільський район"))
        assertTrue(line, !line.contains("Район не позначено"))
    }

    @Test
    fun `no place data is stated as missing, never guessed`() {
        val line = nameSettlementLine(50.0, 30.0, null)
        assertTrue(line, line.contains("не визначено"))
    }

    // ---- the assembled card -------------------------------------------

    @Test
    fun `the card carries all three registers and its own precision`() {
        val card = buildHandoverCard(
            50.4501, 30.5234, 3f,
            listOf(SettlementCandidate("Київ", 50.4501, 30.5234, "city")),
        )
        assertEquals("36U UA 24182 91607", card.mgrs)
        assertEquals("9G2GFG2F+29R", card.plusCode)
        val text = card.lines().joinToString("\n")
        assertTrue(text, text.contains("MGRS: 36U UA 24182 91607"))
        assertTrue(text, text.contains("Plus Code"))
        assertTrue(text, text.contains("Київ"))
        assertTrue(text, text.contains("Прочитай назад"))
    }
}
