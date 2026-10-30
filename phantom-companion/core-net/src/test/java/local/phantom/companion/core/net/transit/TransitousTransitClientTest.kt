package local.phantom.companion.core.net.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitousTransitClientTest {

    @Test
    fun decodePolyline_handlesPrecision7() {
        // Berlin sample polyline from Transitous MOTIS
        val encoded = "or|vh^kibu~FjMv`@znBr{E"
        val points = TransitousTransitClient.decodePolyline(encoded, 7)
        assertTrue(points.isNotEmpty())
        // Coordinates should be in Berlin range ~52.52 lat, ~13.40 lon
        val first = points.first()
        assertTrue("lat ${first.first} should be around 52.5", first.first in 52.0..53.0)
        assertTrue("lon ${first.second} should be around 13.4", first.second in 13.0..14.0)
    }

    @Test
    fun decodePolyline_handlesEmptyString() {
        val points = TransitousTransitClient.decodePolyline("", 7)
        assertTrue(points.isEmpty())
    }

    @Test
    fun parsePlanJson_extractsItinerariesAndLegs() {
        val json = """
            {
              "itineraries": [
                {
                  "duration": 1140,
                  "startTime": "2026-09-07T10:57:00Z",
                  "endTime": "2026-09-07T11:16:00Z",
                  "transfers": 0,
                  "legs": [
                    {
                      "mode": "WALK",
                      "routeShortName": null,
                      "routeLongName": null,
                      "from": { "name": "START", "lat": 52.5200, "lon": 13.4050 },
                      "to": { "name": "Spandauer Str.", "lat": 52.5204, "lon": 13.4055 },
                      "duration": 240,
                      "distance": 150.0,
                      "legGeometry": { "points": "", "precision": 7 }
                    },
                    {
                      "mode": "TRAM",
                      "routeShortName": "M5",
                      "routeLongName": "Zingster Str. - Hauptbahnhof",
                      "routeColor": "EAB308",
                      "from": { "name": "Spandauer Str.", "lat": 52.5204, "lon": 13.4055 },
                      "to": { "name": "U Naturkundemuseum", "lat": 52.5302, "lon": 13.3835 },
                      "duration": 600,
                      "distance": 2500.0,
                      "legGeometry": { "points": "", "precision": 7 }
                    },
                    {
                      "mode": "WALK",
                      "routeShortName": null,
                      "routeLongName": null,
                      "from": { "name": "U Naturkundemuseum", "lat": 52.5302, "lon": 13.3835 },
                      "to": { "name": "END", "lat": 52.5303, "lon": 13.3853 },
                      "duration": 300,
                      "distance": 180.0,
                      "legGeometry": { "points": "", "precision": 7 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val client = TransitousTransitClient()
        val plan = client.parsePlanJson(json)

        assertNotNull(plan)
        assertEquals(1, plan!!.itineraries.size)

        val itin = plan.itineraries.first()
        assertEquals(1140, itin.durationSeconds)
        assertEquals(0, itin.transfers)
        assertEquals(3, itin.legs.size)

        val leg0 = itin.legs[0]
        assertEquals("WALK", leg0.mode)
        assertEquals("START", leg0.fromName)
        assertEquals(240, leg0.durationSeconds)

        val leg1 = itin.legs[1]
        assertEquals("TRAM", leg1.mode)
        assertEquals("M5", leg1.routeShortName)
        assertEquals("EAB308", leg1.routeColor)
        assertEquals(600, leg1.durationSeconds)
    }

    /**
     * Форма — справжня: знята з api.transitous.org 20.09.2026, Кельн → Штутгарт.
     * Пауза на пересадці в Мангаймі живе НЕ в `duration`, а в розриві між
     * `endTime` попередньої ноги (06:31) і `startTime` наступної (06:33).
     */
    @Test
    fun `розбирач читає справжні часи ніг і проміжні зупинки`() {
        val json = """
            {
              "itineraries": [
                {
                  "duration": 9300,
                  "startTime": "2026-09-20T04:43:00Z",
                  "endTime": "2026-09-20T07:18:00Z",
                  "transfers": 2,
                  "legs": [
                    {
                      "mode": "WALK",
                      "startTime": "2026-09-20T06:26:00Z",
                      "endTime": "2026-09-20T06:31:00Z",
                      "scheduledStartTime": "2026-09-20T06:26:00Z",
                      "scheduledEndTime": "2026-09-20T06:31:00Z",
                      "from": { "name": "Mannheim, Hauptbahnhof", "lat": 49.479355, "lon": 8.468921 },
                      "to": { "name": "Mannheim, Hauptbahnhof", "lat": 49.47901, "lon": 8.469757 },
                      "duration": 300,
                      "distance": 110.0,
                      "realTime": false,
                      "legGeometry": { "points": "", "precision": 7 }
                    },
                    {
                      "mode": "HIGHSPEED_RAIL",
                      "startTime": "2026-09-20T06:33:00Z",
                      "endTime": "2026-09-20T07:11:00Z",
                      "scheduledStartTime": "2026-09-20T06:33:00Z",
                      "scheduledEndTime": "2026-09-20T07:11:00Z",
                      "routeShortName": "ICE 591",
                      "routeColor": null,
                      "from": { "name": "Mannheim, Hauptbahnhof", "lat": 49.47901, "lon": 8.469757 },
                      "to": { "name": "Hauptbahnhof (oben)", "lat": 48.78488, "lon": 9.183953 },
                      "duration": 2280,
                      "realTime": true,
                      "intermediateStops": [
                        { "name": "Heidelberg Hbf" },
                        { "name": "Vaihingen (Enz)" }
                      ],
                      "legGeometry": { "points": "", "precision": 7 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val plan = TransitousTransitClient().parsePlanJson(json)
        assertNotNull(plan)
        val legs = plan!!.itineraries.first().legs

        assertEquals("2026-09-20T06:26:00Z", legs[0].startTimeIso)
        assertEquals("2026-09-20T06:31:00Z", legs[0].endTimeIso)
        // Пішохідна нога про проміжні зупинки нічого не каже — це null, не 0.
        assertNull(legs[0].intermediateStopCount)

        assertEquals("2026-09-20T06:33:00Z", legs[1].startTimeIso)
        assertEquals("2026-09-20T07:11:00Z", legs[1].endTimeIso)
        assertEquals(2, legs[1].intermediateStopCount)
        // JsonNull.content дорівнює рядку "null": колір мусить лишитись null.
        assertNull(legs[1].routeColor)
    }

    @Test
    fun `розкладний час підхоплюється коли реального немає`() {
        val json = """
            {
              "itineraries": [{
                "duration": 600, "transfers": 0,
                "legs": [{
                  "mode": "BUS",
                  "scheduledStartTime": "2026-09-20T08:00:00Z",
                  "scheduledEndTime": "2026-09-20T08:10:00Z",
                  "routeShortName": "7",
                  "from": { "name": "A", "lat": 50.0, "lon": 30.0 },
                  "to": { "name": "B", "lat": 50.1, "lon": 30.1 },
                  "duration": 600
                }]
              }]
            }
        """.trimIndent()

        val leg = TransitousTransitClient().parsePlanJson(json)!!.itineraries.first().legs.first()
        assertEquals("2026-09-20T08:00:00Z", leg.startTimeIso)
        assertEquals("2026-09-20T08:10:00Z", leg.endTimeIso)
    }

    /**
     * «Рейсів нема» і «мережі нема» — різні речення до людини, тож і тут
     * вони різні значення: порожній план проти null.
     */
    @Test
    fun `порожня відповідь планувальника це порожній план а не null`() {
        val plan = TransitousTransitClient().parsePlanJson("""{"itineraries": []}""")
        assertNotNull(plan)
        assertTrue(plan!!.itineraries.isEmpty())
    }

    @Test
    fun `документ без плану це null`() {
        assertNull(TransitousTransitClient().parsePlanJson("""{"error": "bad request"}"""))
        assertNull(TransitousTransitClient().parsePlanJson("not json at all"))
    }
}
