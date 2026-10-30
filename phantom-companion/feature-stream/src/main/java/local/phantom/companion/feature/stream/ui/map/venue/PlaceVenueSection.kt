package local.phantom.companion.feature.stream.ui.map.venue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapPillPrimary
import local.phantom.companion.feature.stream.ui.map.MapPillQuiet
import local.phantom.companion.feature.stream.ui.map.MapRaisedCard
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetPill
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.dossier.DossierSlotFailure

@Composable
internal fun PlaceVenueSection(venueId: String) {
    val port = LocalVenuePort.current
    var load by remember(venueId) { mutableStateOf<VenueLoad?>(null) }
    var reloadTick by remember(venueId) { mutableIntStateOf(0) }
    val goingMine = remember(venueId) { mutableStateMapOf<String, Boolean>() }
    val eventStatus = remember(venueId) { mutableStateMapOf<String, RsvpResult>() }
    val scope = rememberCoroutineScope()
    val c = LocalPhantomColors.current

    LaunchedEffect(venueId, reloadTick) {
        load = port.load(venueId)
    }

    when (val current = load) {
        null, VenueLoad.Off, VenueLoad.Missing -> return
        VenueLoad.Failed -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PhantomSpace.s1)
                    .testTag("venue-section"),
            ) {
                DossierSlotFailure(
                    text = stringResource(R.string.venue_card_load_failed),
                    accent = c.accentText,
                    onRetry = { reloadTick++ },
                )
            }
        }
        is VenueLoad.Ready -> {
            val card = current.card
            val nowIso = remember(card) {
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            }
            val events = remember(card.events, nowIso) { upcomingEvents(card.events, nowIso) }
            val menu = card.menus.firstOrNull { it.items.isNotEmpty() }
            if (!card.verified && card.hours.isEmpty() && menu == null && events.isEmpty()) return

            val orderedHours = remember(card.hours) { hoursFromToday(card.hours, LocalDate.now().dayOfWeek) }
            val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PhantomSpace.s1)
                    .testTag("venue-section"),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
            ) {
                if (card.verified) {
                    SheetPill(
                        text = stringResource(R.string.venue_card_verified),
                        fill = c.accentSoft,
                        ink = c.accentText,
                    )
                }

                if (orderedHours.isNotEmpty()) {
                    MapRaisedCard {
                        SheetSection(stringResource(R.string.venue_card_hours))
                        orderedHours.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SheetNote(hoursDayLabel(row.day, locale))
                                SheetBody("${row.open}–${row.close}", tnum = true)
                            }
                        }
                    }
                }

                if (menu != null) {
                    MapRaisedCard {
                        SheetSection(menu.title ?: stringResource(R.string.venue_card_menu))
                        menu.items.take(MENU_DISH_LIMIT).forEach { dish ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SheetBody(dish.name, modifier = Modifier.weight(1f), maxLines = 1)
                                    val priceText = if (menu.token.isNotBlank()) "${dish.price} ${menu.token}" else dish.price
                                    SheetBody(priceText, fontWeight = FontWeight.Medium, tnum = true)
                                }
                                dish.note?.let { SheetSmall(it) }
                            }
                        }
                        val extra = menu.items.size - MENU_DISH_LIMIT
                        if (extra > 0) {
                            SheetNote(stringResource(R.string.venue_card_menu_more, extra), tnum = true)
                        }
                    }
                }

                if (events.isNotEmpty()) {
                    MapRaisedCard {
                        SheetSection(stringResource(R.string.venue_card_events))
                        events.forEach { ev ->
                            VenueEventRow(
                                event = ev,
                                locale = locale,
                                going = goingMine[ev.eventId] == true,
                                status = eventStatus[ev.eventId],
                                onToggleRsvp = { nextGoing ->
                                    scope.launch {
                                        when (val r = port.rsvp(ev.eventId, nextGoing)) {
                                            is RsvpResult.Done -> {
                                                goingMine[ev.eventId] = r.going
                                                eventStatus.remove(ev.eventId)
                                                val latest = (load as? VenueLoad.Ready)?.card ?: card
                                                load = VenueLoad.Ready(applyRsvp(latest, ev.eventId, r))
                                            }
                                            else -> eventStatus[ev.eventId] = r
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(PhantomSpace.s2))
        }
    }
}

@Composable
private fun VenueEventRow(
    event: VenueEvent,
    locale: Locale,
    going: Boolean,
    status: RsvpResult?,
    onToggleRsvp: (Boolean) -> Unit,
) {
    val c = LocalPhantomColors.current
    val cancelled = event.cancelled || status == RsvpResult.Cancelled
    val ended = status == RsvpResult.Over
    val full = !going && (status == RsvpResult.Full || (event.capacity != null && event.going != null && event.going >= event.capacity))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhantomSpace.s0_5)) {
            SheetBody(event.title, fontWeight = FontWeight.Medium)
            SheetNote(eventWhen(event.starts, locale), tnum = true)
            if (event.going != null) {
                val countLine = if (event.capacity != null) {
                    stringResource(R.string.venue_card_going_of, event.going, event.capacity)
                } else {
                    stringResource(R.string.venue_card_going_count, event.going)
                }
                SheetSmall(countLine, tnum = true)
            }
            event.note?.let { SheetSmall(it) }
        }
        when {
            cancelled -> SheetPill(stringResource(R.string.venue_card_cancelled), ink = c.danger)
            ended -> SheetPill(stringResource(R.string.venue_card_ended))
            event.going == null -> Unit
            full -> SheetPill(stringResource(R.string.venue_card_full))
            going -> MapPillQuiet(
                label = stringResource(R.string.venue_card_youre_going),
                onClick = { onToggleRsvp(false) },
            )
            else -> MapPillPrimary(
                label = stringResource(R.string.venue_card_going),
                onClick = { onToggleRsvp(true) },
            )
        }
    }
}

private const val MENU_DISH_LIMIT = 6
