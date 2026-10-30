package local.phantom.companion.feature.stream.ui.map.transit

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.transit.TransitCoverage
import local.phantom.companion.core.data.transit.TransitPlane
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetSegment
import local.phantom.companion.feature.stream.ui.map.SheetSegments
import local.phantom.companion.feature.stream.ui.map.SheetTitle
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine
import local.phantom.companion.feature.stream.ui.map.dossier.TransitLineBadge
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.core.design.R as DesignR
import kotlin.math.roundToInt

internal enum class HubTab(@StringRes val label: Int) {
    NEARBY(R.string.map_transit_tab_nearby),
    ROUTES(R.string.map_transit_tab_routes),
    METRO(R.string.map_sheets_hub_tab_metro),
    FLEET(R.string.map_transit_tab_fleet),
}

/**
 * Транспортний хаб: табло поруч, каталог маршрутів, мережа метро, живий флот.
 *
 * Хаб більше не заявляє «Київська транспортна мережа» над будь-якою точкою
 * планети й не пропонує вкладок, на які цей ярус не має відповіді. Що він
 * показує, вирішує [coverage]: живий фід дає флот, модель — розклади з явним
 * підписом «за розкладом (модель)», порожнеча — назване «джерело недоступне»,
 * а не мовчазний нуль.
 *
 * 03.10 — шкіра v1.2. До того хаб малював усередині аркуша власне тіло
 * `#090D16` з білим заголовком, тож у Дні з-під світлого аркуша вилазила
 * чорна плита; вкладки й фільтри були бірюзові. Тепер тіло — сам аркуш
 * ([local.phantom.companion.feature.stream.ui.map.MapSheet], `raised`),
 * вкладки — сегменти v1.2 (мʼякий бурштин на обраному, як режими маршруту),
 * а колір лінії стоїть лише на її бейджі й точках ([TransitModeTint]).
 */
@Composable
internal fun TransitHubSheet(
    surfaceFeed: SurfaceFeed?,
    metroFeed: MetroFeed?,
    transitRepository: TransitRepository?,
    coverage: TransitCoverage,
    currentLat: Double,
    currentLon: Double,
    onSelectStop: (DossierTarget) -> Unit,
    onShowLineOnMap: (DossierTransitLine) -> Unit,
    onRouteTo: (Double, Double, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val modelCovers = remember(surfaceFeed, metroFeed, currentLat, currentLon) {
        val s = surfaceFeed?.bounds
        val m = metroFeed?.bounds
        val bounds = if (s != null && m != null) s.union(m) else s ?: m
        bounds?.contains(currentLat, currentLon, TransitRepository.COVERAGE_PAD_DEG) == true
    }
    val tabs = hubTabs(
        plane = coverage.plane,
        modelCovers = modelCovers,
        hasMetro = metroFeed?.doc?.lines?.isNotEmpty() == true,
    )
    var selectedTab by remember(tabs) { mutableStateOf(tabs.firstOrNull()) }
    var routeSearchQuery by remember { mutableStateOf("") }
    var selectedModeFilter by remember { mutableStateOf("ALL") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PhantomSpace.s4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhantomGlyphIcon(glyph = PhantomGlyph.BUS, tint = c.text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(PhantomSpace.s3))
            Column(Modifier.weight(1f)) {
                SheetTitle(stringResource(R.string.map_sheets_hub_title), maxLines = 1)
                // Порожній ярус не отримує підзаголовка: причину каже
                // повідомлення в тілі, і двічі те саме — це не наголос.
                hubSubtitle(coverage, surfaceFeed, modelCovers)?.let {
                    SheetNote(it, maxLines = 2)
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(DesignR.drawable.lc_close),
                    contentDescription = stringResource(R.string.map_transit_close),
                    tint = c.text2,
                    modifier = Modifier.size(PhantomSize.iconM),
                )
            }
        }

        Spacer(Modifier.height(PhantomSpace.s3))

        val tab = selectedTab
        if (tab == null) {
            HubAbsenceNotice(coverage)
            return@Column
        }

        if (tabs.size > 1) {
            SheetSegments {
                tabs.forEach { candidate ->
                    SheetSegment(
                        label = stringResource(candidate.label),
                        selected = candidate == tab,
                        onClick = { selectedTab = candidate },
                    )
                }
            }
            Spacer(Modifier.height(PhantomSpace.s3))
        }

        when (tab) {
            HubTab.NEARBY -> NearbyTransitTab(
                surfaceFeed = surfaceFeed,
                metroFeed = metroFeed,
                currentLat = currentLat,
                currentLon = currentLon,
                onSelectStop = onSelectStop,
                onRouteTo = onRouteTo,
            )
            HubTab.ROUTES -> RoutesCatalogTab(
                surfaceFeed = surfaceFeed,
                searchQuery = routeSearchQuery,
                onSearchQueryChange = { routeSearchQuery = it },
                modeFilter = selectedModeFilter,
                onModeFilterChange = { selectedModeFilter = it },
                onShowLineOnMap = onShowLineOnMap,
                onSelectStop = onSelectStop,
            )
            HubTab.METRO -> MetroNetworkTab(
                metroFeed = metroFeed,
                onSelectStation = { st ->
                    onSelectStop(
                        DossierTarget(
                            lat = st.lat,
                            lon = st.lon,
                            nameHint = st.name,
                            categoryHint = "subway_station",
                        ),
                    )
                },
                onRouteTo = onRouteTo,
            )
            HubTab.FLEET -> LiveFleetTab(transitRepository = transitRepository)
        }
    }
}

/**
 * Один рядок про те, чиї це дані. Розклад ніколи не носить слова про флот, а
 * живий фід — слова «модель»: саме на цій підміні хаб і брехав.
 */
@Composable
private fun hubSubtitle(
    coverage: TransitCoverage,
    surfaceFeed: SurfaceFeed?,
    modelCovers: Boolean,
): String? = when {
    coverage.plane == TransitPlane.LIVE_FEED ->
        stringResource(R.string.map_transit_hub_live, coverage.liveFeed?.city.orEmpty())

    coverage.plane == TransitPlane.STATIC_SCHEDULE && !modelCovers ->
        stringResource(R.string.map_transit_hub_static)

    modelCovers -> stringResource(
        R.string.map_transit_hub_model,
        surfaceFeed?.doc?.city.orEmpty(),
        surfaceFeed?.doc?.lines?.size ?: 0,
    )

    else -> null
}

/** Названа відсутність замість порожнього табло: чому саме тут нічого немає. */
@Composable
private fun HubAbsenceNotice(coverage: TransitCoverage) {
    val text = when {
        coverage.absence is SourceUnavailable.MissingApiKey ->
            stringResource(R.string.map_transit_absent_no_key)
        coverage.absence != null -> stringResource(R.string.map_transit_absent_source)
        else -> stringResource(R.string.map_transit_absent_here)
    }
    HubQuietNotice(text)
}

/** Чесна порожнеча хаба: знак `text3` і слово `text2`, без тла. */
@Composable
internal fun HubQuietNotice(text: String) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PhantomSpace.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ms_near_me),
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(PhantomSpace.s3))
        androidx.compose.material3.Text(
            text = text,
            color = c.text2,
            style = local.phantom.companion.core.design.theme.PhantomText.callout,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Поруч ─────────────────────────────────────────────────────────────────

@Composable
private fun NearbyTransitTab(
    surfaceFeed: SurfaceFeed?,
    metroFeed: MetroFeed?,
    currentLat: Double,
    currentLon: Double,
    onSelectStop: (DossierTarget) -> Unit,
    onRouteTo: (Double, Double, String) -> Unit,
) {
    val nearbyStops = remember(surfaceFeed, metroFeed, currentLat, currentLon) {
        val list = ArrayList<NearbyStopItem>()

        // Станції метро — у радіусі 2,5 км.
        metroFeed?.doc?.lines?.forEach { line ->
            line.stations.forEach { st ->
                val distM = haversineMeters(currentLat, currentLon, st.lat, st.lon).roundToInt()
                if (distM <= 2500) {
                    list += NearbyStopItem(
                        id = st.id,
                        name = st.name,
                        mode = "subway",
                        lines = listOf(NearbyLine(line.ref, "subway", line.colour)),
                        distanceM = distM,
                        lat = st.lat,
                        lon = st.lon,
                    )
                }
            }
        }

        // Наземні зупинки — у радіусі 1,2 км; одна зупинка, кілька ліній.
        surfaceFeed?.doc?.lines?.forEach { line ->
            line.stops.forEach { st ->
                val distM = haversineMeters(currentLat, currentLon, st.lat, st.lon).roundToInt()
                if (distM <= 1200) {
                    val badge = NearbyLine(line.ref, line.mode, line.colour)
                    val existing = list.find { it.name.equals(st.name, ignoreCase = true) && it.mode != "subway" }
                    if (existing != null) {
                        if (existing.lines.none { it.ref == badge.ref && it.mode == badge.mode }) {
                            existing.lines = existing.lines + badge
                        }
                    } else {
                        list += NearbyStopItem(
                            id = st.id,
                            name = st.name,
                            mode = line.mode,
                            lines = listOf(badge),
                            distanceM = distM,
                            lat = st.lat,
                            lon = st.lon,
                        )
                    }
                }
            }
        }

        list.sortedBy { it.distanceM }
    }

    if (nearbyStops.isEmpty()) {
        HubQuietNotice(stringResource(R.string.map_sheets_hub_nearby_empty))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            items(nearbyStops, key = { "${it.id}_${it.name}" }) { item ->
                NearbyStopCard(
                    item = item,
                    onTap = {
                        onSelectStop(
                            DossierTarget(
                                lat = item.lat,
                                lon = item.lon,
                                nameHint = item.name,
                                categoryHint = if (item.mode == "subway") "subway_station" else "bus_stop",
                            ),
                        )
                    },
                    onRoute = { onRouteTo(item.lat, item.lon, item.name) },
                )
            }
        }
    }
}

/** Лінія на зупинці: номер, вид і колір джерела (якщо фід його знає). */
private class NearbyLine(val ref: String, val mode: String, val colour: String?)

private class NearbyStopItem(
    val id: String,
    val name: String,
    val mode: String,
    var lines: List<NearbyLine>,
    val distanceM: Int,
    val lat: Double,
    val lon: Double,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyStopCard(
    item: NearbyStopItem,
    onTap: () -> Unit,
    onRoute: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val walkMin = maxOf(1, (item.distanceM / 80.0).roundToInt())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .clickable(role = Role.Button, onClick = onTap)
            .padding(start = PhantomSpace.s3, top = PhantomSpace.s3, bottom = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SheetBody(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
            SheetNote(
                stringResource(R.string.map_sheets_hub_walk, item.distanceM, walkMin),
                maxLines = 1,
                tnum = true,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.lines.forEach { line ->
                    TransitLineBadge(
                        ref = line.ref,
                        mode = line.mode,
                        colourArgb = TransitModeTint.fromHex(line.colour)?.toArgb(),
                    )
                }
            }
        }
        IconButton(onClick = onRoute) {
            Icon(
                painterResource(R.drawable.ms_navigation),
                contentDescription = stringResource(R.string.map_sheets_hub_route_to),
                tint = c.accentText,
                modifier = Modifier.size(PhantomSize.iconM),
            )
        }
    }
}

