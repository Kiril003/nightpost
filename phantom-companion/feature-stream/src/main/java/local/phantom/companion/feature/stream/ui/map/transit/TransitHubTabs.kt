package local.phantom.companion.feature.stream.ui.map.transit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.data.transit.surface.SurfaceLine
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetChevron
import local.phantom.companion.feature.stream.ui.map.SheetField
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.SheetText
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine
import local.phantom.companion.feature.stream.ui.map.dossier.TransitLineBadge
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.feature.stream.ui.map.sheetSegment
import local.phantom.companion.feature.stream.ui.map.sheetSegmentInk
import local.phantom.companion.core.design.R as DesignR

/*
 * Вкладки хаба, 03.10 — шкіра v1.2.
 *
 * Пошук був стоковим OutlinedTextField з білим словом і бірюзовою рамкою, тож
 * у Дні набране не читалося зовсім. Фільтри й кнопка «на мапі» — бірюзові
 * заливки, яких у системі немає. Метро вигадувало кольори ліній із номера
 * («М1 — червона, М2 — синя») і обіцяло «інтервал у пік 3-4 хв», якого не
 * знало. Тепер: поле — SheetField, фільтр — мʼякий бурштин обраного, колір
 * лінії — `MetroLine.colour` / `SurfaceLine.colour`, а без них — тон виду з
 * [TransitModeTint].
 */

// ── Маршрути ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutesCatalogTab(
    surfaceFeed: SurfaceFeed?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modeFilter: String,
    onModeFilterChange: (String) -> Unit,
    onShowLineOnMap: (DossierTransitLine) -> Unit,
    onSelectStop: (DossierTarget) -> Unit,
) {
    val c = LocalPhantomColors.current
    var expandedLineRef by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SheetField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = stringResource(R.string.map_sheets_hub_search),
            leading = {
                Icon(
                    painterResource(DesignR.drawable.lc_search),
                    contentDescription = null,
                    tint = c.text3,
                    modifier = Modifier.size(PhantomSize.iconS),
                )
            },
        )

        Spacer(Modifier.height(PhantomSpace.s2))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            listOf(
                "ALL" to R.string.map_sheets_hub_filter_all,
                "bus" to R.string.map_sheets_hub_filter_bus,
                "trolleybus" to R.string.map_sheets_hub_filter_trolleybus,
                "tram" to R.string.map_sheets_hub_filter_tram,
            ).forEach { (key, label) ->
                HubFilterPill(
                    label = stringResource(label),
                    selected = modeFilter == key,
                    onClick = { onModeFilterChange(key) },
                )
            }
        }

        Spacer(Modifier.height(PhantomSpace.s2))

        val allLines = surfaceFeed?.doc?.lines.orEmpty()
        val filtered = remember(allLines, searchQuery, modeFilter) {
            allLines.filter { line ->
                val matchesMode = modeFilter == "ALL" || line.mode.equals(modeFilter, ignoreCase = true)
                val matchesQuery = searchQuery.isBlank() ||
                    line.ref.contains(searchQuery, ignoreCase = true) ||
                    line.name.contains(searchQuery, ignoreCase = true) ||
                    line.stops.any { it.name.contains(searchQuery, ignoreCase = true) }
                matchesMode && matchesQuery
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            items(filtered, key = { "${it.ref}_${it.mode}_${it.name}" }) { line ->
                val isExpanded = expandedLineRef == line.ref
                CatalogRouteCard(
                    line = line,
                    isExpanded = isExpanded,
                    onToggle = {
                        expandedLineRef = if (isExpanded) null else line.ref
                    },
                    onShowMap = {
                        val dLine = DossierTransitLine(
                            ref = line.ref,
                            mode = line.mode,
                            direction = line.name,
                            headwayMinutes = (line.headway.day / 60),
                            running = true,
                            shape = line.shape,
                        )
                        onShowLineOnMap(dLine)
                    },
                    onSelectStop = onSelectStop,
                )
            }
        }
    }
}

/**
 * Фільтр виду: обраний — той самий мʼякий бурштин, що й сегмент, решта —
 * волосина `line`. Мішень 48dp, намальована пігулка — 32dp усередині.
 */
@Composable
private fun HubFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(PhantomSize.buttonS)
                .sheetSegment(selected)
                .then(if (selected) Modifier else Modifier.border(1.dp, c.line, CircleShape))
                .padding(horizontal = PhantomSpace.s3),
            contentAlignment = Alignment.Center,
        ) {
            SheetText(
                label,
                PhantomText.footnote,
                color = sheetSegmentInk(selected),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CatalogRouteCard(
    line: SurfaceLine,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onShowMap: () -> Unit,
    onSelectStop: (DossierTarget) -> Unit,
) {
    val c = LocalPhantomColors.current
    val tint = TransitModeTint.line(line.colour, line.mode)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(PhantomSpace.s3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            TransitLineBadge(ref = line.ref, mode = line.mode, colourArgb = tint.toArgb())
            Column(modifier = Modifier.weight(1f)) {
                val dir = line.name.ifBlank { "${line.stops.firstOrNull()?.name} → ${line.stops.lastOrNull()?.name}" }
                SheetBody(dir, fontWeight = FontWeight.SemiBold, maxLines = 1)
                // `headway` у фіді — модельна стала на вид транспорту, не розклад лінії.
                SheetSmall(stringResource(R.string.map_sheets_hub_stops_count, line.stops.size), tnum = true)
            }
            SheetChevron(isExpanded, c.text3)
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = PhantomSpace.s3)) {
                HubQuietAction(
                    icon = DesignR.drawable.lc_map,
                    label = stringResource(R.string.map_sheets_hub_show_on_map),
                    onClick = onShowMap,
                )
                Spacer(Modifier.height(PhantomSpace.s3))
                SheetSection(stringResource(R.string.map_sheets_hub_stops_title, line.stops.size))
                Spacer(Modifier.height(PhantomSpace.s1))
                line.stops.forEachIndexed { idx, s ->
                    StopRow(
                        index = idx + 1,
                        name = s.name,
                        tint = tint,
                        onClick = {
                            onSelectStop(
                                DossierTarget(
                                    lat = s.lat,
                                    lon = s.lon,
                                    nameHint = s.name,
                                    categoryHint = "bus_stop",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Рядок зупинки: точка кольору лінії, номер `text3` з `tnum`, назва. */
@Composable
private fun StopRow(index: Int, name: String, tint: Color, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(PhantomSpace.s3))
        SheetSmall("$index", modifier = Modifier.width(22.dp), color = c.text3, tnum = true)
        SheetBody(name, modifier = Modifier.weight(1f), maxLines = 1)
    }
}

/** Тиха дія всередині картки: волосина `lineStrong`, знак і слово `text`. */
@Composable
private fun HubQuietAction(icon: Int, label: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(shape)
            .border(1.dp, c.lineStrong, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s3),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = c.text, modifier = Modifier.size(PhantomSize.iconS))
        Spacer(Modifier.width(PhantomSpace.s2))
        SheetBody(label, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ── Метро ─────────────────────────────────────────────────────────────────

@Composable
internal fun MetroNetworkTab(
    metroFeed: MetroFeed?,
    onSelectStation: (local.phantom.companion.core.data.transit.metro.MetroStation) -> Unit,
    onRouteTo: (Double, Double, String) -> Unit,
) {
    val c = LocalPhantomColors.current
    val lines = metroFeed?.doc?.lines.orEmpty()
    val routeLabel = stringResource(R.string.map_sheets_hub_route_to)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        items(lines, key = { it.ref }) { line ->
            // Колір лінії — з пака (`MetroLine.colour`); без нього — тон метро.
            val lineColor = TransitModeTint.line(line.colour, "subway")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .mapRaised()
                    .padding(start = PhantomSpace.s3, top = PhantomSpace.s3, bottom = PhantomSpace.s1),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                    modifier = Modifier.padding(end = PhantomSpace.s3),
                ) {
                    TransitLineBadge(ref = line.ref, mode = "subway", colourArgb = lineColor.toArgb())
                    Column(Modifier.weight(1f)) {
                        SheetBody(line.name.ifBlank { line.ref }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        SheetSmall(stringResource(R.string.map_sheets_hub_stations_count, line.stations.size), tnum = true)
                    }
                }

                Spacer(Modifier.height(PhantomSpace.s1))

                line.stations.forEach { st ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onSelectStation(st) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(lineColor),
                        )
                        Spacer(Modifier.width(PhantomSpace.s3))
                        SheetBody(st.name, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { onRouteTo(st.lat, st.lon, st.name) }) {
                            Icon(
                                painterResource(R.drawable.ms_navigation),
                                contentDescription = routeLabel,
                                tint = c.accentText,
                                modifier = Modifier.size(PhantomSize.iconS),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Живий флот ────────────────────────────────────────────────────────────

/**
 * Скільки машин зараз справді видно фіду.
 *
 * Була «телеметрія мережі»: рахувала модельні одиниці й підписувала їх
 * «синхронізація активна», хоча синхронізуватись не було з чим. Тепер сюди
 * приходить лише [glassFleet] — те саме, що їде по склу, — а вид кожної
 * машини бере з фіда, а не з тексту її ідентифікатора.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveFleetTab(transitRepository: TransitRepository?) {
    val c = LocalPhantomColors.current
    val fleet = transitRepository?.vehicles?.collectAsStateWithLifecycle()?.value.orEmpty()
    val vehicles = glassFleet(fleet)
    val degraded = transitRepository?.feedDegraded?.collectAsStateWithLifecycle()?.value ?: false
    val byMode = vehicles.groupingBy { it.mode }.eachCount().entries.sortedByDescending { it.value }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mapRaised()
                .padding(PhantomSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Живий фід — `success`; застиглий — `warning`, і слово під ним каже чому.
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (degraded) c.warning else c.success),
            )
            Spacer(Modifier.width(PhantomSpace.s3))
            Column(Modifier.weight(1f)) {
                SheetBody(
                    stringResource(R.string.map_transit_fleet_count, vehicles.size),
                    fontWeight = FontWeight.SemiBold,
                    tnum = true,
                )
                if (degraded) {
                    SheetNote(stringResource(R.string.map_transit_fleet_stale))
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            byMode.forEach { (mode, count) ->
                Row(
                    modifier = Modifier
                        .mapRaised(RoundedCornerShape(PhantomRadius.control))
                        .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                ) {
                    PhantomGlyphIcon(
                        glyph = mode.glyph(),
                        tint = TransitModeTint.of(mode),
                        modifier = Modifier.size(18.dp),
                    )
                    SheetText("$count", PhantomText.headline, color = c.text, tnum = true)
                }
            }
        }
    }
}
