package local.phantom.companion.feature.stream.ui.map.venue

import java.time.DayOfWeek
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VenueCardTest {

    private val pageJson = """
        {
          "id": "0123456789abcdef",
          "name": "Kavárna",
          "blocks": [
            {
              "id": "h1",
              "type": "hours",
              "rows": [
                {"day": "mon", "open": "08:00", "close": "20:00"},
                {"day": "tue", "open": "08:00", "close": "22:00"}
              ]
            },
            {
              "id": "m1",
              "type": "menu",
              "title": "Coffee",
              "token": "USDC",
              "items": [
                {"id": "d1", "name": "Espresso", "price": "2.50", "note": "Double"},
                {"id": "d2", "name": "Filter", "price": "3.00"}
              ]
            },
            {
              "id": "e1",
              "type": "event",
              "title": "Page Jazz",
              "starts": "2026-05-01T19:00",
              "ends": "2026-05-01T22:00",
              "note": "Live",
              "price": "10",
              "token": "USDC"
            }
          ]
        }
    """.trimIndent()

    private val factsJson = """
        {
          "venue_id": "0123456789abcdef",
          "verified": true,
          "osm": {"type": "node", "id": 100},
          "events": [
            {
              "event_id": "0123456789abcdef.e1",
              "venue_id": "0123456789abcdef",
              "title": "Jazz Night",
              "starts": "2026-05-01T19:00",
              "ends": "2026-05-01T22:00",
              "note": "Trio",
              "price": "10",
              "token": "USDC",
              "going": 7,
              "capacity": 30,
              "cancelled": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses hours menu and events from the page and verified and going from facts`() {
        val card = parseVenueCard(pageJson, factsJson)!!
        assertTrue(card.verified)
        assertEquals(
            listOf(
                VenueHoursRow("mon", "08:00", "20:00"),
                VenueHoursRow("tue", "08:00", "22:00"),
            ),
            card.hours,
        )
        assertEquals(1, card.menus.size)
        val menu = card.menus.single()
        assertEquals("Coffee", menu.title)
        assertEquals("USDC", menu.token)
        assertEquals(
            listOf(
                VenueDish("d1", "Espresso", "2.50", "Double"),
                VenueDish("d2", "Filter", "3.00", null),
            ),
            menu.items,
        )
        val ev = card.events.single()
        assertEquals("0123456789abcdef.e1", ev.eventId)
        assertEquals("Jazz Night", ev.title)
        assertEquals("2026-05-01T19:00", ev.starts)
        assertEquals("2026-05-01T22:00", ev.ends)
        assertEquals("Trio", ev.note)
        assertEquals("10", ev.price)
        assertEquals("USDC", ev.token)
        assertEquals(7, ev.going)
        assertEquals(30, ev.capacity)
        assertFalse(ev.cancelled)
    }

    @Test
    fun `a page without hours menu or events is an empty card, nothing invented`() {
        val card = parseVenueCard("""{"id":"0123456789abcdef","name":"Quiet","blocks":[]}""", null)!!
        assertFalse(card.verified)
        assertTrue(card.hours.isEmpty())
        assertTrue(card.menus.isEmpty())
        assertTrue(card.events.isEmpty())
    }

    @Test
    fun `unknown blocks are ignored and bad json is null`() {
        val withUnknown = """
            {
              "id": "0123456789abcdef",
              "blocks": [
                {"id": "x1", "type": "gallery", "photos": ["a.jpg"]},
                {"id": "h1", "type": "hours", "rows": [{"day": "fri", "open": "10:00", "close": "18:00"}]}
              ]
            }
        """.trimIndent()
        val card = parseVenueCard(withUnknown, "not-json")!!
        assertEquals(listOf(VenueHoursRow("fri", "10:00", "18:00")), card.hours)
        assertFalse(card.verified)
        assertNull(parseVenueCard("not json at all", null))
        assertNull(parseVenueCard("[]", null))
    }

    @Test
    fun `missing facts keeps page events with unknown going`() {
        val card = parseVenueCard(pageJson, null)!!
        assertFalse(card.verified)
        val ev = card.events.single()
        assertEquals("0123456789abcdef.e1", ev.eventId)
        assertEquals("Page Jazz", ev.title)
        assertNull(ev.going)
        assertNull(ev.capacity)
        assertFalse(ev.cancelled)
    }

    @Test
    fun `upcoming drops finished, sorts by start, keeps cancelled flagged, caps at five`() {
        val events = listOf(
            VenueEvent("v.past", "Past", "2026-05-01T10:00", ends = "2026-05-01T12:00", null, null, null, 1, null, false),
            VenueEvent("v.ongoing", "Ongoing", "2026-05-01T11:00", ends = "2026-05-01T15:00", null, null, null, 2, null, false),
            VenueEvent("v.b", "Second", "2026-05-02T19:00", null, null, null, null, 3, 10, true),
            VenueEvent("v.a", "FirstSameTime", "2026-05-02T19:00", null, null, null, null, 4, null, false),
            VenueEvent("v.c", "Third", "2026-05-03T19:00", null, null, null, null, 5, null, false),
            VenueEvent("v.d", "Fourth", "2026-05-04T19:00", null, null, null, null, 6, null, false),
            VenueEvent("v.e", "Fifth", "2026-05-05T19:00", null, null, null, null, 7, null, false),
        )
        val got = upcomingEvents(events, nowLocalIso = "2026-05-01T14:00")
        assertEquals(listOf("v.ongoing", "v.a", "v.b", "v.c", "v.d"), got.map { it.eventId })
        assertTrue(got.first { it.eventId == "v.b" }.cancelled)
    }

    @Test
    fun `applyRsvp replaces one event only`() {
        val card = VenueCard(
            hours = emptyList(),
            menus = emptyList(),
            events = listOf(
                VenueEvent("v.1", "One", "2026-05-01T19:00", null, null, null, null, 2, 10, false),
                VenueEvent("v.2", "Two", "2026-05-02T19:00", null, null, null, null, 5, 20, false),
            ),
            verified = true,
        )
        val updated = applyRsvp(card, "v.1", RsvpResult.Done(going = true, count = 3, capacity = 12))
        assertEquals(3, updated.events[0].going)
        assertEquals(12, updated.events[0].capacity)
        assertEquals(5, updated.events[1].going)
        assertEquals(20, updated.events[1].capacity)
    }

    @Test
    fun `eventWhen formats en uk cs from the local string without zone math`() {
        val en = eventWhen("2026-05-01T19:30", Locale.ENGLISH)
        val uk = eventWhen("2026-05-01T19:30", Locale("uk"))
        val cs = eventWhen("2026-05-01T19:30", Locale("cs"))
        assertTrue(en, en.contains("1") && (en.contains("19:30") || en.contains("7:30")))
        assertTrue(uk, uk.contains("1") && uk.contains("19:30"))
        assertTrue(cs, cs.contains("1") && cs.contains("19:30"))
        assertEquals("bad-date", eventWhen("bad-date", Locale.ENGLISH))
    }

    @Test
    fun `parses the page envelope the venue server serves`() {
        val served = """
            {
              "id": "0123456789abcdef",
              "name": "Kavárna",
              "published": true,
              "rev": 3,
              "page": {
                "v": 1,
                "blocks": [
                  {"type": "title", "text": "Kavárna"},
                  {"type": "hours", "rows": [{"day": "sat", "open": "10:00", "close": "16:00"}]},
                  {"type": "event", "id": "ev1", "title": "Quiz", "starts": "2026-05-02T20:00"}
                ]
              },
              "rating": null
            }
        """.trimIndent()
        val card = parseVenueCard(served, null)!!
        assertEquals(listOf(VenueHoursRow("sat", "10:00", "16:00")), card.hours)
        assertEquals("0123456789abcdef.ev1", card.events.single().eventId)
    }

    @Test
    fun `hours day codes become localized weekday names and the week starts today`() {
        assertEquals("Mon", hoursDayLabel("mon", Locale.ENGLISH))
        assertNotEquals("mon", hoursDayLabel("mon", Locale("uk")))
        assertNotEquals("mon", hoursDayLabel("mon", Locale("cs")))
        assertEquals("xyz", hoursDayLabel("xyz", Locale.ENGLISH))
        val week = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").map { VenueHoursRow(it, "09:00", "17:00") }
        assertEquals(
            listOf("wed", "thu", "fri", "sat", "sun", "mon", "tue"),
            hoursFromToday(week, DayOfWeek.WEDNESDAY).map { it.day },
        )
        val gap = listOf("mon", "fri").map { VenueHoursRow(it, "09:00", "17:00") }
        assertEquals(listOf("fri", "mon"), hoursFromToday(gap, DayOfWeek.TUESDAY).map { it.day })
    }
}
