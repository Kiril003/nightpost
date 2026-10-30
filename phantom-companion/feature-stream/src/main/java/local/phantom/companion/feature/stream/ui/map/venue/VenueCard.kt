package local.phantom.companion.feature.stream.ui.map.venue

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class VenueCard(
    val hours: List<VenueHoursRow>,
    val menus: List<VenueMenu>,
    val events: List<VenueEvent>,
    val verified: Boolean,
)

data class VenueHoursRow(val day: String, val open: String, val close: String)

data class VenueMenu(val title: String?, val token: String, val items: List<VenueDish>)

data class VenueDish(val id: String, val name: String, val price: String, val note: String?)

data class VenueEvent(
    val eventId: String,
    val title: String,
    val starts: String,
    val ends: String?,
    val note: String?,
    val price: String?,
    val token: String?,
    val going: Int?,
    val capacity: Int?,
    val cancelled: Boolean,
)

fun parseVenueCard(pageJson: String, factsJson: String?): VenueCard? {
    val root = runCatching { Json.parseToJsonElement(pageJson) }.getOrNull() as? JsonObject ?: return null
    val venueObj = (root["venue"] as? JsonObject) ?: root
    val venueId = venueObj.str("id") ?: root.str("id") ?: ""
    val blocks = (venueObj["blocks"] as? JsonArray)
        ?: ((venueObj["page"] as? JsonObject)?.get("blocks") as? JsonArray)
        ?: JsonArray(emptyList())

    val hours = mutableListOf<VenueHoursRow>()
    val menus = mutableListOf<VenueMenu>()
    val pageEvents = mutableListOf<VenueEvent>()

    for (el in blocks) {
        val b = el as? JsonObject ?: continue
        when (b.str("type")) {
            "hours" -> {
                val rows = b["rows"] as? JsonArray ?: continue
                for (r in rows) {
                    val ro = r as? JsonObject ?: continue
                    val day = ro.str("day")?.takeIf { it.isNotBlank() } ?: continue
                    val open = ro.str("open") ?: ""
                    val close = ro.str("close") ?: ""
                    hours += VenueHoursRow(day, open, close)
                }
            }
            "menu" -> {
                val token = b.str("token") ?: ""
                val itemsArr = b["items"] as? JsonArray ?: JsonArray(emptyList())
                val dishes = itemsArr.mapNotNull { item ->
                    val io = item as? JsonObject ?: return@mapNotNull null
                    val id = io.str("id") ?: ""
                    val name = io.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val price = io.str("price") ?: return@mapNotNull null
                    VenueDish(id, name, price, io.str("note")?.takeIf { it.isNotBlank() })
                }
                if (dishes.isNotEmpty()) {
                    menus += VenueMenu(b.str("title")?.takeIf { it.isNotBlank() }, token, dishes)
                }
            }
            "event" -> {
                val blockId = b.str("id")?.takeIf { it.isNotBlank() } ?: continue
                val title = b.str("title")?.takeIf { it.isNotBlank() } ?: continue
                val starts = b.str("starts")?.takeIf { it.isNotBlank() } ?: continue
                val eventId = if (venueId.isNotEmpty()) "$venueId.$blockId" else blockId
                pageEvents += VenueEvent(
                    eventId = eventId,
                    title = title,
                    starts = starts,
                    ends = b.str("ends")?.takeIf { it.isNotBlank() },
                    note = b.str("note")?.takeIf { it.isNotBlank() },
                    price = b.str("price")?.takeIf { it.isNotBlank() },
                    token = b.str("token")?.takeIf { it.isNotBlank() },
                    going = null,
                    capacity = null,
                    cancelled = false,
                )
            }
        }
    }

    val factsObj = factsJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
    val verified = (factsObj?.get("verified") as? JsonPrimitive)?.booleanOrNull ?: false
    val factsEventsArr = factsObj?.get("events") as? JsonArray
    val events = if (factsEventsArr != null) {
        factsEventsArr.mapNotNull { el ->
            val eo = el as? JsonObject ?: return@mapNotNull null
            val eventId = eo.str("event_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = eo.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val starts = eo.str("starts")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VenueEvent(
                eventId = eventId,
                title = title,
                starts = starts,
                ends = eo.str("ends")?.takeIf { it.isNotBlank() },
                note = eo.str("note")?.takeIf { it.isNotBlank() },
                price = eo.str("price")?.takeIf { it.isNotBlank() },
                token = eo.str("token")?.takeIf { it.isNotBlank() },
                going = (eo["going"] as? JsonPrimitive)?.intOrNull,
                capacity = (eo["capacity"] as? JsonPrimitive)?.intOrNull,
                cancelled = (eo["cancelled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    } else {
        pageEvents
    }

    return VenueCard(hours = hours, menus = menus, events = events, verified = verified)
}

fun upcomingEvents(events: List<VenueEvent>, nowLocalIso: String, limit: Int = 5): List<VenueEvent> =
    events.asSequence()
        .filter { (it.ends ?: it.starts) >= nowLocalIso }
        .sortedWith(compareBy<VenueEvent>({ it.starts }, { it.eventId }))
        .take(limit)
        .toList()

fun applyRsvp(card: VenueCard, eventId: String, done: RsvpResult.Done): VenueCard =
    card.copy(
        events = card.events.map { ev ->
            if (ev.eventId == eventId) ev.copy(going = done.count, capacity = done.capacity) else ev
        },
    )

fun eventWhen(starts: String, locale: Locale): String {
    val dt = runCatching { LocalDateTime.parse(starts) }.getOrNull() ?: return starts
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return "${dt.format(dateFmt)} · ${dt.format(timeFmt)}"
}

private val DAY_CODES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

private fun hoursDayOf(code: String): DayOfWeek? =
    DAY_CODES.indexOf(code.trim().lowercase()).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }

fun hoursDayLabel(code: String, locale: Locale): String =
    hoursDayOf(code)?.getDisplayName(TextStyle.SHORT, locale) ?: code

fun hoursFromToday(rows: List<VenueHoursRow>, today: DayOfWeek): List<VenueHoursRow> =
    rows.sortedBy { row -> hoursDayOf(row.day)?.let { (it.value - today.value + 7) % 7 } ?: 7 }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
