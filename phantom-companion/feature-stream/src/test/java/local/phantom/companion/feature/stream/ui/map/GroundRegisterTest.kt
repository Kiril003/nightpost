package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroundRegisterTest {

    @Test
    fun `worst-of picks the higher-severity register`() {
        assertEquals(
            GroundRegister.GUESSED,
            GroundRegister.MEASURED.worstWith(GroundRegister.GUESSED),
        )
        assertEquals(
            GroundRegister.REMEMBERED,
            listOf(GroundRegister.MEASURED, GroundRegister.DERIVED, GroundRegister.REMEMBERED).worstOrNull(),
        )
    }

    @Test
    fun `empty collection has no worst`() {
        assertNull(emptyList<GroundRegister>().worstOrNull())
    }

    @Test
    fun `composition fractions sum to one and match counts`() {
        val comp = RegisterComposition.of(
            listOf(
                GroundRegister.MEASURED,
                GroundRegister.GUESSED, GroundRegister.GUESSED, GroundRegister.GUESSED,
            ),
        )
        assertEquals(4, comp.total)
        assertEquals(0.25f, comp.measured, 0.0001f)
        assertEquals(0.75f, comp.guessed, 0.0001f)
        assertEquals(0f, comp.derived, 0.0001f)
        assertEquals(0f, comp.remembered, 0.0001f)
    }

    @Test
    fun `empty composition is absence, not confidence`() {
        val comp = RegisterComposition.of(emptyList())
        assertEquals(RegisterComposition.EMPTY, comp)
        assertEquals(0, comp.total)
    }

    @Test
    fun `occupied oblast forces REMEMBERED regardless of tag presence`() {
        assertEquals(
            GroundRegister.REMEMBERED,
            GroundRegister.MEASURED.downgradeIfOccupied("Донецька область"),
        )
        assertEquals(
            GroundRegister.MEASURED,
            GroundRegister.MEASURED.downgradeIfOccupied("Львівська область"),
        )
        assertEquals(
            GroundRegister.MEASURED,
            GroundRegister.MEASURED.downgradeIfOccupied(null),
        )
    }
}
