package local.phantom.companion.feature.stream.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.source.BatchFSource
import local.phantom.companion.core.design.R as DesignR

// NAV-0 — the layer tools: system layers, quick «поруч» search, PHANTOM
// layers, the active route row and the evacuation card. Живе лише всередині
// аркуша «Шари» (MapLayersSheet): аркуш дає поверхню й прокрутку, тут —
// уміст. Окрема скляна панель із власною шапкою й прокруткою (гілка
// `embedded = false`) мертвою стояла з того дня, як аркуш зʼявився, і
// прибрана разом зі своїми параметрами.

/**
 * Context-agnostic panel for map layer toggles + quick POI search.
 * Pure UI: every action is a callback, every fact is a parameter — no
 * ViewModel/MapLibre coupling. Поверхня аркуша — raised, тож плитки тут
 * overlay, а увімкнене — accentSoft з волосиною accentLine (бурштин дії).
 */
@Composable
internal fun MapToolsPanel(
    alertsVisible: Boolean,
    alertsOffered: Boolean = true,
    onToggleAlerts: () -> Unit,
    radarVisible: Boolean,
    onToggleRadar: () -> Unit,
    transitBusy: Boolean,
    transitActive: Boolean,
    anyBusy: Boolean,
    onToggleTransit: () -> Unit,
    // Overpass-fetched, on-demand layers (cameras, towers, drainage, fords —
    // MapGroundLayers): one registry-driven row instead of a hand-maintained
    // parameter per layer. Adding the next one means adding to the list the
    // caller builds, not a new triple threaded through three files.
    worldLayers: List<WorldLayerToggle> = emptyList(),
    nearbyActive: String?,
    // NAV-Ω1 — true while a route is live: the chips search «по дорозі».
    alongRoute: Boolean = false,
    onPickNearby: (selector: String, label: String) -> Unit,
    onClearNearby: () -> Unit,
    phantomLayers: List<local.phantom.companion.core.data.spatial.SpatialLayer>,
    routeInfo: MapViewModel.RouteInfo?,
    onClearRoute: () -> Unit,
    evacuationState: EvacuationState = EvacuationState.Idle,
    operatorLocation: Pair<Double, Double>? = null,
    onToggleLayer: (String, Boolean) -> Unit,
    onClearLayer: (String) -> Unit,
    onFocusLayer: (local.phantom.companion.core.data.spatial.SpatialLayer) -> Unit,
    whoSeesCount: Int = 0,
    onOpenWhoSees: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        if (onOpenWhoSees != null) {
            BentoSectionTitle(stringResource(R.string.peer_where_section))
            WhoSeesDoor(count = whoSeesCount, onClick = onOpenWhoSees)
        }
        BentoSectionTitle(stringResource(R.string.map_section_layers))
        // Два в ряд, не три: на 411dp при кеглі ×1.1 третина смуги лишала
        // «транс…», «опори…», «канав…» — перемикач, що не називає себе,
        // нічого не перемикає (кадр A52 06.09).
        val toggles = buildList {
            if (alertsOffered) add(
                ToggleSpec(
                    label = stringResource(R.string.map_layer_alerts),
                    dotColor = c.danger,
                    active = alertsVisible,
                    onClick = onToggleAlerts,
                ),
            )
            add(
                ToggleSpec(
                    label = stringResource(R.string.map_layer_radar),
                    dotColor = MapLayerPalette.Radar,
                    active = radarVisible,
                    onClick = onToggleRadar,
                ),
            )
            add(
                ToggleSpec(
                    label = stringResource(R.string.map_layer_transit),
                    dotColor = c.accent,
                    active = transitActive,
                    busy = transitBusy,
                    enabled = transitBusy || !anyBusy,
                    onClick = onToggleTransit,
                    // Дотик відкриває транспортний хаб — вимкнути шар звідси
                    // не можна (вимикання живе у × ActiveLayerRow нижче).
                    role = Role.Button,
                ),
            )
            worldLayers.forEach { wl ->
                add(
                    ToggleSpec(
                        label = wl.label,
                        dotColor = c.warning,
                        active = wl.active,
                        busy = wl.busy,
                        enabled = wl.busy || !anyBusy,
                        onClick = wl.onClick,
                    ),
                )
            }
        }
        toggles.chunked(TOGGLE_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                row.forEach { t ->
                    BentoToggleCard(
                        label = t.label,
                        dotColor = t.dotColor,
                        active = t.active,
                        busy = t.busy,
                        enabled = t.enabled,
                        onClick = t.onClick,
                        modifier = Modifier.weight(1f),
                        role = t.role,
                    )
                }
                repeat(TOGGLE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        BentoSectionTitle(
            stringResource(
                if (alongRoute) R.string.map_section_along_route else R.string.map_section_nearby,
            ),
        )
        // Три в ряд, не чотири: при чотирьох «Супермаркет» різався б.
        val nearbyCats = nearbyCatsFor(LocalConfiguration.current.locales[0].country)
        nearbyCats.chunked(3).forEach { rowCats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                rowCats.forEach { cat ->
                    BentoPoiCard(
                        title = cat.label,
                        glyph = cat.glyph,
                        selected = nearbyActive == cat.label,
                        onClick = {
                            if (nearbyActive == cat.label) {
                                onClearNearby()
                            } else {
                                onPickNearby(cat.selector, cat.label)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad a short final row so cards keep their column width.
                repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        val hasContext = phantomLayers.isNotEmpty() || routeInfo != null || evacuationState != EvacuationState.Idle
        if (hasContext) {
            // Dynamic slot — only rendered when there is a real route, a
            // live evacuation, or a real phantom layer; no placeholder
            // text otherwise.
            Box(Modifier.fillMaxWidth().padding(vertical = PhantomSpace.s1).height(1.dp).background(c.line))
            BentoSectionTitle(stringResource(R.string.map_section_context))
            if (evacuationState != EvacuationState.Idle) {
                EvacuationCard(state = evacuationState, operatorLocation = operatorLocation, onClear = onClearRoute)
            } else {
                routeInfo?.let { info -> ActiveRouteRow(info = info, onClear = onClearRoute) }
            }
            phantomLayers.forEach { layer ->
                ActiveLayerRow(
                    layer = layer,
                    onToggle = onToggleLayer,
                    onClear = onClearLayer,
                    onFocus = onFocusLayer,
                )
            }
        }
    }
}

/**
 * One Overpass-fetched, on-demand layer's chip in the capsule's quick row
 * (cameras, towers, drainage, fords — see [MapGroundLayers]). The registry
 * decides which layers exist; this is only the UI's view of one of them, so
 * a new layer never needs a new parameter threaded through this file.
 */
internal data class WorldLayerToggle(
    val id: String,
    val label: String,
    val busy: Boolean,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun BentoSectionTitle(text: String) {
    MapSectionCaption(
        text = text.lowercase().replaceFirstChar { it.titlecase() },
        modifier = Modifier.padding(top = PhantomSpace.s2),
    )
}

/** Плитка перемикача: overlay; увімкнене — accentSoft і волосина accentLine. */
@Composable
private fun BentoToggleCard(
    label: String,
    dotColor: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    /** Транспорт не має вимикача тут — дотик відкриває транспортний хаб, а
     *  вимикання шару живе у × ActiveLayerRow. `Role.Switch` на кнопці, що не
     *  вміє вимкнути те, чим торкає, читається читачем екрана як зламаний
     *  перемикач. */
    role: Role = Role.Switch,
) {
    val c = LocalPhantomColors.current
    val busyWord = stringResource(R.string.map_layer_busy)
    val spoken = if (busy) "$label · $busyWord" else label
    val fill by animateColorAsState(
        targetValue = if (active) c.accentSoft else c.overlay,
        animationSpec = PhantomMotion.stateChangeColor,
        label = "tool-card-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (active) c.accentLine else c.overlay,
        animationSpec = PhantomMotion.stateChangeColor,
        label = "tool-card-edge",
    )
    Box(
        modifier = modifier
            .heightIn(min = MapTouch)
            .clip(TileShape)
            .background(fill)
            .border(1.dp, edge, TileShape)
            .selectable(
                selected = active,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = PhantomSpace.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            MapDot(if (active || busy) dotColor else c.text3)
            Text(
                text = label.replaceFirstChar { it.titlecase() },
                style = PhantomText.subhead,
                color = if (active) c.text else c.text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Стан «працюю» — словом. Кільце, що крутиться, нічого не називає.
            if (busy) Text(busyWord, style = PhantomText.caption, color = c.text3, maxLines = 1)
        }
    }
}

@Composable
private fun BentoPoiCard(
    title: String,
    glyph: PhantomGlyph,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val fill by animateColorAsState(
        targetValue = if (selected) c.accentSoft else c.overlay,
        animationSpec = PhantomMotion.stateChangeColor,
        label = "poi-card-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (selected) c.accentLine else c.overlay,
        animationSpec = PhantomMotion.stateChangeColor,
        label = "poi-card-edge",
    )
    Column(
        modifier = modifier
            .heightIn(min = POI_CARD_MIN)
            .clip(TileShape)
            .background(fill)
            .border(1.dp, edge, TileShape)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(vertical = PhantomSpace.s2, horizontal = PhantomSpace.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PhantomGlyphIcon(
            glyph = glyph,
            tint = if (selected) c.accentText else c.text,
            modifier = Modifier.size(PhantomSize.iconL),
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        Text(
            text = title,
            style = PhantomText.caption,
            color = if (selected) c.text else c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Рядок «що зараз на мапі»: overlay, крапка, слово, дії в кінці. */
@Composable
private fun ContextRow(
    dot: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MapRowHeight)
            .clip(TileShape)
            .background(LocalPhantomColors.current.overlay)
            .then(modifier)
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        MapDot(dot)
        content()
    }
}

@Composable
private fun ActiveRouteRow(info: MapViewModel.RouteInfo, onClear: () -> Unit) {
    val c = LocalPhantomColors.current
    ContextRow(dot = c.accent) {
        Text(
            text = stringResource(
                R.string.map_route_active,
                fmtDistance(info.distanceMeters),
                fmtDuration(info.durationSeconds),
            ),
            style = PhantomText.body,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MapRowAction(
            icon = DesignR.drawable.lc_close,
            contentDescription = stringResource(R.string.map_route_clear),
            onClick = onClear,
        )
    }
}

/**
 * M-C05/C06 — the whole Tactical Evacuation lifecycle lives here, not in the
 * generic NoticePill. One card whose content, edge and copy change per
 * [EvacuationState]: a scanning/routing progress row, the live
 * shelter/distance/ETA readout, the arrival confirmation, an honest
 * Compromised reason, or the full In-Place Defense protocol text.
 * Distance/ETA recompute from the operator's current fix (not the stale
 * OSRM snapshot) so the numbers count down as the operator walks the route.
 */
@Composable
private fun EvacuationCard(
    state: EvacuationState,
    operatorLocation: Pair<Double, Double>?,
    onClear: () -> Unit,
) {
    val c = LocalPhantomColors.current
    // Небезпека — поки людина йде до укриття; застереження — коли план
    // запасний; успіх — коли дійшла. Тон каже волосина й крапка, не заливка.
    val tone = when (state) {
        is EvacuationState.Compromised, is EvacuationState.InPlaceDefense -> c.warning
        is EvacuationState.Arrived -> c.success
        else -> c.danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(c.overlay)
            .border(1.dp, tone, TileShape)
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s1, bottom = PhantomSpace.s3),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MapTouch),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            MapDot(tone)
            Text(
                text = evacuationHeadline(state),
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MapRowAction(
                icon = DesignR.drawable.lc_close,
                contentDescription = stringResource(R.string.map_evac_cancel),
                onClick = onClear,
            )
        }

        when (state) {
            is EvacuationState.Scanning -> EvacuationLine(stringResource(R.string.map_evac_scanning))
            is EvacuationState.TacticalRouting -> EvacuationLine(stringResource(R.string.map_evac_routing))
            is EvacuationState.ActiveGuidance -> {
                val route = state.route
                val remainingMeters = operatorLocation?.let {
                    haversineMeters(it, state.shelter.lat to state.shelter.lon)
                } ?: route.distanceMeters
                val remainingFraction = if (route.distanceMeters > 0.0) {
                    (remainingMeters / route.distanceMeters).coerceIn(0.0, 1.0)
                } else 1.0
                val dynamicEtaSeconds = route.durationSeconds * remainingFraction
                val degraded = if (state.degraded) " · " + stringResource(R.string.map_evac_alt_shelter) else ""
                EvacuationLine(
                    "${fmtDistance(remainingMeters)} · ${fmtDuration(dynamicEtaSeconds)}$degraded",
                )
            }
            is EvacuationState.Arrived -> EvacuationLine(stringResource(R.string.map_evac_arrived))
            is EvacuationState.Compromised -> EvacuationLine(state.reason)
            is EvacuationState.InPlaceDefense -> EvacuationLine(stringResource(state.reasonRes))
            EvacuationState.Idle -> {}
        }
    }
}

@Composable
private fun EvacuationLine(text: String) {
    Text(
        text = text,
        style = PhantomText.footnote,
        color = LocalPhantomColors.current.text2,
        modifier = Modifier.padding(start = PhantomSpace.s2 + PhantomSpace.s3, end = PhantomSpace.s3),
    )
}

@Composable
private fun evacuationHeadline(state: EvacuationState): String = when (state) {
    EvacuationState.Idle -> ""
    EvacuationState.Scanning -> stringResource(R.string.map_evac_seeking)
    EvacuationState.TacticalRouting -> stringResource(R.string.map_evac_plotting)
    is EvacuationState.ActiveGuidance -> state.shelter.name
    is EvacuationState.Arrived -> state.shelter.name
    is EvacuationState.Compromised -> stringResource(R.string.map_evac_impossible)
    is EvacuationState.InPlaceDefense -> stringResource(R.string.map_evac_in_place)
}

@Composable
private fun ActiveLayerRow(
    layer: local.phantom.companion.core.data.spatial.SpatialLayer,
    onToggle: (String, Boolean) -> Unit,
    onClear: (String) -> Unit,
    onFocus: (local.phantom.companion.core.data.spatial.SpatialLayer) -> Unit,
) {
    val c = LocalPhantomColors.current
    // Колір шару — з його власних даних (spec.color), бо ним шар і
    // намальований на мапі. Без кольору — text2, а не вигаданий фіолет.
    val dotColor = remember(layer.spec.color, c.text2) {
        layer.spec.color
            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: c.text2
    }
    val isVisible = layer.visible
    val layerName = layer.spec.title ?: stringResource(R.string.map_layer_untitled)
    ContextRow(
        dot = if (isVisible) dotColor else c.text3,
        modifier = Modifier
            .selectable(
                selected = isVisible,
                role = Role.Switch,
            ) { onToggle(layer.id, !isVisible) }
            .semantics { contentDescription = layerName },
    ) {
        Text(
            text = layerName,
            style = PhantomText.body,
            color = if (isVisible) c.text else c.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MapRowAction(
            icon = DesignR.drawable.lc_locate,
            contentDescription = stringResource(R.string.map_layer_focus),
            onClick = { onFocus(layer) },
        )
        MapRowAction(
            icon = DesignR.drawable.lc_close,
            contentDescription = stringResource(R.string.map_layer_remove),
            onClick = { onClear(layer.id) },
        )
    }
}

/**
 * M-E01 — the capsule's «поруч» quick set, drawn from the shared
 * [MapDiscovery] catalog so the capsule and the full search surface never
 * drift. Broader than before: money, stations and state services alongside
 * the wartime essentials.
 *
 * `first`, not `firstOrNull`: a catalog rename must fail the test run, not
 * silently shrink the shelf (see [MapDiscovery.shortlist]). Internal for the
 * twelve-card contract test.
 */
internal val NEARBY_CATS_ALL: List<DiscoveryCat> = BatchFSource.NEARBY_CAT_RES
    .map { res -> MapDiscovery.all.first { it.labelRes == res } }

private val UA_ONLY_RES: Set<Int> = BatchFSource.UA_ONLY_CAT_RES

/**
 * Чиста функція, а не `val` за локаллю за замовчуванням: статичне значення
 * застигло б на локалі процесу в мить першого звернення до файла й не
 * зреагувало б на зміну мови без перезапуску. Локаль — не GPS: подорожній
 * українець за кордоном все одно побачить «Укриття» (телефон несе мову з
 * дому), а іноземець в Україні — ні, доки не зміни мову. Чесний перший крок,
 * не остаточна ознака; справжня привʼязка до місця (як [MapCanvas]'s
 * `isOccupiedGround`) — окрема робота.
 */
internal fun nearbyCatsFor(countryCode: String): List<DiscoveryCat> =
    if (countryCode.equals("UA", ignoreCase = true)) {
        NEARBY_CATS_ALL
    } else {
        NEARBY_CATS_ALL.filterNot { it.labelRes in UA_ONLY_RES }
    }

/** Плитки й рядки панелі — радіус картки, як рядки аркуша «Шари». */
private val TileShape = RoundedCornerShape(PhantomRadius.card)

private const val TOGGLE_COLUMNS = 2

/** Один перемикач системного шару — щоб три сталі й N світових лягали в одну сітку. */
private data class ToggleSpec(
    val label: String,
    val dotColor: Color,
    val active: Boolean,
    val onClick: () -> Unit,
    val busy: Boolean = false,
    val enabled: Boolean = true,
    val role: Role = Role.Switch,
)

/** Гліф над підписом у два рядки — нижче цього підпис ріжеться. */
private val POI_CARD_MIN = 64.dp

@Composable
private fun WhoSeesDoor(count: Int, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MapRowHeight)
            .clip(TileShape)
            .background(c.overlay)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        MapIcon(DesignR.drawable.lc_people)
        Text(
            stringResource(R.string.peer_where_seen_title),
            style = PhantomText.body,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (count == 0) {
                stringResource(R.string.peer_where_seen_count_none)
            } else {
                pluralStringResource(R.plurals.peer_where_seen_count, count, count)
            },
            style = PhantomText.callout,
            color = c.text3,
        )
        MapIcon(DesignR.drawable.lc_chevron_right, tint = c.text3, size = PhantomSize.iconS)
    }
}
