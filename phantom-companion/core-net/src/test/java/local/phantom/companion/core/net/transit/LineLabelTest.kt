package local.phantom.companion.core.net.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineLabelTest {

    @Test
    fun `czech licence numbers become the number on the sign`() {
        assertEquals("108", LineLabel.of("915108", "Hlavní nádraží - Hulváky", "cz-JDF-merged_-CISR-915108-1"))
        assertEquals("12", LineLabel.of("915012"))
        assertEquals("1", LineLabel.of("915001"))
        assertEquals("403", LineLabel.of("876403"))
    }

    @Test
    fun `public numbers pass through untouched`() {
        listOf("4", "N95", "R8", "S6", "Ex1", "EC 143", "А15", "Т02", "1A", "M1", "U3", "103", "7К", "А08а", "D 40347", "IC 8331 Ślązak", "FlixBus N1290", "Ш 36")
            .forEach { assertEquals(it, LineLabel.of(it, "whatever", "id-$it")) }
    }

    @Test
    fun `an id is never a label`() {
        val ids = listOf(
            "cz-JDF-merged_-CISR-915108-1", "ua-lviv_1006", "sk-zsr_22",
            "czptt:route:line:8006", "1234567", "20260928_14:24_trip",
            "KADR100725", "3f2a9c1e-77aa-4b1c-9d2e-0a1b2c3d4e5f",
        )
        ids.forEach { id ->
            assertTrue(id, LineLabel.looksLikeId(id))
            assertEquals(id, "", LineLabel.of(id, null))
        }
    }

    @Test
    fun `a short name equal to the route id falls back to the long name`() {
        assertEquals("Сихів - Центр", LineLabel.of("2285", "Сихів  -  Центр", "2285"))
    }

    @Test
    fun `an empty short name falls back to a cleaned long name, never to the id`() {
        assertEquals("Ottakring - Simmering", LineLabel.of(null, " Ottakring -\tSimmering ", "at-PTA_1"))
        assertEquals("", LineLabel.of("", "", "ua-lviv_1006"))
        assertEquals("", LineLabel.of(null, "ua-lviv_1006", "ua-lviv_1006"))
    }

    @Test
    fun `station prose with slashes and colons is not an id`() {
        assertFalse(LineLabel.looksLikeId("Hlavní nádraží/Mor.Ostrava,Plynárny - Dubina"))
        assertFalse(LineLabel.looksLikeId("výluka: Centrum 1. etapa"))
    }
}
