package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** СИРЕНА-2 — pins the keyless typed feed's parse + id resolution. */
class SirenAlertsTest {

    private val index = mapOf(
        "14" to RegionRef("Київська область", "Київська область", stateLevel = true),
        "31" to RegionRef("м. Київ", "м. Київ", stateLevel = true),
        "148" to RegionRef("Запорізька область", "Мелітопольський район", stateLevel = false),
        "1313" to RegionRef("Харківська область", "Вовчанська громада", stateLevel = false),
        "1293" to RegionRef("Харківська область", "м. Харків", stateLevel = false),
    )
    private val refOf: (String) -> RegionRef? = { index[it] }

    @Test
    fun `state rows set oblast-wide alert with start stamp`() {
        val body = """[
            {"regionId":"14","regionType":"State","regionName":"Київська область",
             "activeAlerts":[{"type":"AIR","lastUpdate":"2026-07-18T13:48:21.713182Z"}]}
        ]"""
        val out = parseSirenAlerts(body, refOf)!!
        val kyivska = out["Київська область"]!!
        assertTrue(kyivska.stateActive)
        assertEquals(ThreatType.UNKNOWN, kyivska.stateThreat)
        assertTrue(kyivska.startedEpochMs != null && kyivska.startedEpochMs!! > 0L)
        assertTrue(kyivska.districts.isEmpty())
    }

    @Test
    fun `district and community rows attach to their oblast without lighting it`() {
        val body = """[
            {"regionId":"148","activeAlerts":[{"type":"AIR","lastUpdate":"2026-07-18T12:00:00Z"}]},
            {"regionId":"1313","activeAlerts":[{"type":"ARTILLERY","lastUpdate":"2026-07-18T12:00:00Z"},{"type":"URBAN_FIGHTS","lastUpdate":"2026-07-18T12:01:00Z"}]}
        ]"""
        val out = parseSirenAlerts(body, refOf)!!
        val zp = out["Запорізька область"]!!
        assertFalse(zp.stateActive)
        assertEquals(listOf(DistrictAlert("Мелітопольський район", ThreatType.UNKNOWN)), zp.districts)
        val kh = out["Харківська область"]!!
        assertFalse(kh.stateActive)
        // Worst of the row's alerts wins: URBAN (sev 5) > ARTILLERY (sev 4).
        assertEquals(listOf(DistrictAlert("Вовчанська громада", ThreatType.URBAN)), kh.districts)
    }

    @Test
    fun `city-hromada pseudo-states stay district rows`() {
        val body = """[
            {"regionId":"1293","regionType":"State","activeAlerts":[{"type":"AIR","lastUpdate":"2026-07-18T12:00:00Z"}]}
        ]"""
        val out = parseSirenAlerts(body, refOf)!!
        val kh = out["Харківська область"]!!
        assertFalse(kh.stateActive)
        assertEquals("м. Харків", kh.districts.single().name)
    }

    @Test
    fun `unknown ids and INFO-only rows are dropped - never guessed`() {
        val body = """[
            {"regionId":"777777","activeAlerts":[{"type":"AIR","lastUpdate":"2026-07-18T12:00:00Z"}]},
            {"regionId":"14","activeAlerts":[{"type":"INFO","lastUpdate":"2026-07-18T12:00:00Z"}]}
        ]"""
        val out = parseSirenAlerts(body, refOf)!!
        assertTrue(out.isEmpty())
    }

    @Test
    fun `garbage body parses to null so the caller keeps its last snapshot`() {
        assertNull(parseSirenAlerts("<html>died</html>", refOf))
    }

    @Test
    fun `type mapping is the ukrainealarm contract`() {
        assertEquals(ThreatType.UNKNOWN, sirenThreat("AIR"))
        assertEquals(ThreatType.ARTILLERY, sirenThreat("ARTILLERY"))
        assertEquals(ThreatType.URBAN, sirenThreat("URBAN_FIGHTS"))
        assertEquals(ThreatType.CHEMICAL, sirenThreat("CHEMICAL"))
        assertEquals(ThreatType.NUCLEAR, sirenThreat("NUCLEAR"))
        assertNull(sirenThreat("INFO"))
        assertNull(sirenThreat(null))
    }
}
