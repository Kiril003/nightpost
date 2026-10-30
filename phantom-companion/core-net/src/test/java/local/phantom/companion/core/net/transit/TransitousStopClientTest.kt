package local.phantom.companion.core.net.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitousStopClientTest {

    private val client = TransitousStopClient()

    @Test
    fun `parseStopsJson парсить зупинки з координатами та типами транспорту`() {
        val json = """
            [
              {
                "stopId": "ua-lviv_310",
                "name": "Хоткевича (310)",
                "lat": 49.83215,
                "lon": 24.02450,
                "modes": ["BUS", "TRAM"]
              },
              {
                "stopId": "ua-lviv_311",
                "name": "Чукаріна",
                "lat": "4.984035E1",
                "lon": 24.03011,
                "modes": ["BUS"]
              }
            ]
        """.trimIndent()

        val stops = client.parseStopsJson(json)
        assertNotNull(stops)
        assertEquals(2, stops!!.size)

        val s1 = stops[0]
        assertEquals("ua-lviv_310", s1.id)
        assertEquals("Хоткевича (310)", s1.name)
        assertEquals(49.83215, s1.lat, 1e-5)
        assertEquals(24.02450, s1.lon, 1e-5)
        assertEquals(listOf("BUS", "TRAM"), s1.modes)

        // Науковий запис координат MOTIS
        val s2 = stops[1]
        assertEquals("ua-lviv_311", s2.id)
        assertEquals(49.84035, s2.lat, 1e-4)
        assertEquals(24.03011, s2.lon, 1e-5)
    }

    @Test
    fun `parseStopsJson поважає ліміт зупинок`() {
        val json = """
            [
              {"stopId": "1", "name": "A", "lat": 50.0, "lon": 30.0},
              {"stopId": "2", "name": "B", "lat": 50.1, "lon": 30.1},
              {"stopId": "3", "name": "C", "lat": 50.2, "lon": 30.2}
            ]
        """.trimIndent()

        val stops = client.parseStopsJson(json, limit = 2)
        assertNotNull(stops)
        assertEquals(2, stops!!.size)
    }

    @Test
    fun `parseStopsJson повертає null на некоректному json`() {
        assertNull(client.parseStopsJson("not json"))
        assertNull(client.parseStopsJson("""{"error": "bad request"}"""))
    }

    @Test
    fun `parseLines витягує лінії та кольори маршрутів`() {
        val json = """
            {
              "routes": [
                {
                  "routeShortName": "3A",
                  "routeLongName": "ТРЦ Victoria Gardens - пл. Різblock",
                  "mode": "BUS",
                  "routeColor": "#22C55E"
                },
                {
                  "routeShortName": "8",
                  "mode": "TRAM",
                  "color": "#F4AF25"
                }
              ]
            }
        """.trimIndent()

        val lines = client.parseLines(json)
        assertEquals(2, lines.size)
        assertEquals("3A", lines[0].ref)
        assertEquals("BUS", lines[0].mode)
        assertEquals("#22C55E", lines[0].colour)
        assertEquals("8", lines[1].ref)
        assertEquals("TRAM", lines[1].mode)
        assertEquals("#F4AF25", lines[1].colour)
    }

    @Test
    fun `parseDepartures витягує розклад відправлень та часовий пояс`() {
        val json = """
            {
              "stopTimes": [
                {
                  "routeShortName": "3A",
                  "mode": "BUS",
                  "headsign": "ТРЦ Victoria Gardens",
                  "routeColor": "#22C55E",
                  "departure": "2026-09-21T08:30:00Z",
                  "realTime": true,
                  "place": {
                    "tz": "Europe/Kyiv"
                  }
                }
              ]
            }
        """.trimIndent()

        val departures = client.parseDepartures(json)
        assertEquals(1, departures.size)
        val dep = departures[0]
        assertEquals("3A", dep.ref)
        assertEquals("BUS", dep.mode)
        assertEquals("ТРЦ Victoria Gardens", dep.direction)
        assertEquals("2026-09-21T08:30:00Z", dep.departureIso)
        assertEquals("Europe/Kyiv", dep.timeZone)
        assertTrue(dep.realTime)
    }

    @Test
    fun `ostrava board shows the sign number, not the licence id`() {
        val json = """
            {"stopTimes":[{"routeShortName":"915108","displayName":"915108","routeId":"cz-JDF-merged_-CISR-915108-1",
              "tripId":"20260928_15:07_cz-JDF-merged_CIST-915108-1-186","mode":"BUS","headsign":"Ostrava,hlavní nádraží",
              "routeLongName":"Hlavní nádraží/Mor.Ostrava,Vozovna trolejbusů - Hulváky","realTime":false,
              "place":{"departure":"2026-09-28T13:24:00Z","tz":"Europe/Prague"}}]}
        """.trimIndent()
        val dep = client.parseDepartures(json).single()
        assertEquals("108", dep.ref)
        assertTrue(!LineLabel.looksLikeId(dep.ref))
    }
}
