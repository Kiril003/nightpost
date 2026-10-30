package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.RouteProfile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.peers.tapped
import local.phantom.companion.core.data.places.MarkName
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector

// NAV-0 — the sheet host: ONE home for every contextual bottom sheet the
// map opens (tapped POI, tapped phantom-layer marker, long-pressed point,
// threat brief). The cards themselves moved verbatim from the MapScreen
// monolith; the host reads/writes selection state on [MapUiState] so the
// canvas (which sets selections from tap handlers) and the chrome never
// have to thread callbacks through each other.
//
// 03.10 — кожен аркуш тут відкривається через [MapSheet]: одна поверхня v1.2
// (`raised`, кути `sheet`, пелена `scrim`, ручка `lineStrong`, смуги вікна з
// теми PHANTOM). Вміст говорить ролями PhantomText і кольорами токенів;
// бурштин як чорнило — `accentText`, бо заливний `accent` на денному аркуші
// не читається як штрих.

/** Поля вмісту аркуша: ручка вже дала 12dp зверху. */
@Composable
private fun Modifier.sheetBody(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = readingGutter(PhantomSpace.gutterPhone))
    .padding(top = PhantomSpace.s1, bottom = PhantomSpace.s6)

/**
 * R4.2 — place-card for a tapped phantom-layer marker. Renders only the
 * facts the marker actually carries (Law 1). Actions route the point back
 * into the conversation (ask / route) or out to an external map.
 */
@Composable
internal fun LayerMarkerCard(
    marker: TappedLayerMarker,
    accentColor: Color,
    onAskAi: () -> Unit,
    onRoute: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .sheetBody()
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1)) {
            SheetTitle(marker.label)
            val line = listOfNotNull(marker.layerTitle, marker.detail?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            if (line.isNotEmpty()) SheetNote(line, maxLines = 3)
        }
        MapPrimaryAction(
            PhantomGlyph.ROUTE_TO.asImageVector(),
            stringResource(R.string.map_sheet_route_here),
            onRoute,
        )
        MapTileRow {
            MapActionTile(
                PhantomGlyph.ASK_HERE.asImageVector(),
                stringResource(R.string.map_sheet_ask_about_this),
                onAskAi,
            )
            MapActionTile(
                PhantomGlyph.HANDOFF.asImageVector(),
                stringResource(R.string.map_sheet_in_maps),
                onOpenExternal,
            )
        }
    }
}

/** Кинута точка в аркуші «Кому надіслати»: підпис — той, яким точка зветься в аркуші. */
internal fun pointSendTarget(point: Pair<Double, Double>, address: String?) =
    local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
        lat = point.first,
        lon = point.second,
        nameHint = address?.let { pointHeadline(it).first }?.takeIf { it.isNotBlank() },
    )

/** «У чат» відкриває аркуш розмов месенджера (люди, групи, «Збережене»); без нього — аркуш людей поруч. */
internal fun openPlaceSend(
    port: local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort,
    target: local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget,
    fallbackName: String,
    onFallback: (local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget) -> Unit,
) {
    val name = target.nameHint?.takeIf { it.isNotBlank() } ?: fallbackName
    if (!port.sendPlaceToChat(target.lat, target.lon, name)) onFallback(target)
}

/** M-A01/A02 — action sheet for a long-pressed point. */
@Composable
internal fun PointActionsSheet(
    lat: Double,
    lon: Double,
    address: String?,
    here: Pair<Double, Double>?,
    accentColor: Color,
    onWhatHere: () -> Unit,
    onRoute: () -> Unit,
    // Фішка власника: ця точка стає ПОЧАТКОМ, кінець назве наступний дотик.
    onRouteFrom: () -> Unit,
    // NAV-1 (M-B05) — thread the ACTIVE route through this point; only
    // offered while a route is live.
    onVia: (() -> Unit)?,
    // NAV-5 — drop a manual TTL mark (патруль/ДТП) at this point.
    onMark: ((kind: String) -> Unit)? = null,
    // HORIZON III.2 — walk-time isochrone from this point over the cached
    // road graph; the VM speaks an honest notice when no graph is loaded.
    onIsochrone: (() -> Unit)? = null,
    // HORIZON III.2 — graduated danger wash from the live hazard marks.
    onDangerField: (() -> Unit)? = null,
    onSaveMark: (name: String, category: String) -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    /** Імʼя якоря, який зараз ставлять («Дім»); null — звичайний аркуш точки. */
    anchorLabel: String? = null,
    anchorGlyph: PhantomGlyph = PhantomGlyph.HOME,
    onSetAnchorHere: (() -> Unit)? = null,
    hereNow: local.phantom.companion.feature.stream.ui.map.here.HereNowState? = null,
    onHereReport: (kindWire: String, readingWire: String) -> Unit = { _, _ -> },
    /** «Зустрітися тут»: точка йде в чернетку зустрічі месенджера. */
    onMeetHere: (() -> Unit)? = null,
    /** Чернетка зустрічі чекає саме цю мітку — тоді це перша дія аркуша. */
    meetPicking: Boolean = false,
    /** «У чат»: точка йде людині тим самим аркушем, що й місце з досьє. */
    onSendToChat: (() -> Unit)? = null,
) {
    // Ключ по точці: аркуш нової точки не має прокидатись у режимі імені старої.
    var naming by remember(lat, lon) { mutableStateOf(false) }
    var more by remember(lat, lon) { mutableStateOf(false) }
    val (head, place) = pointHeadline(address)
    Column(
        modifier = Modifier
            .sheetBody()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1)) {
            SheetTitle(head ?: stringResource(R.string.map_sheet_point_title))
            val away = local.phantom.companion.feature.stream.ui.map.dossier.proximity(here, lat, lon)?.let {
                val words = walkWords()
                stringResource(
                    R.string.map_sheet_away_walk,
                    local.phantom.companion.feature.stream.ui.map.dossier.formatDistance(it.meters),
                    local.phantom.companion.feature.stream.ui.map.dossier.walkEtaText(it.walkMinutes, words),
                )
            }
            val line = listOfNotNull(place, away).joinToString(" · ")
            if (line.isNotEmpty()) SheetNote(line, maxLines = 2, tnum = true)
        }

        if (naming) {
            MarkNamingBlock(
                address = address,
                onSave = { name, category ->
                    naming = false
                    onSaveMark(name, category)
                },
                onCancel = { naming = false },
            )
            return@Column
        }

        // Поки ставлять якір, залитою стає саме він: людина прийшла назвати дім.
        val anchoring = anchorLabel != null && onSetAnchorHere != null
        val meetFirst = meetFirst(anchoring, meetPicking, onMeetHere != null)
        if (meetFirst) {
            MapPrimaryAction(
                PhantomGlyph.PERSON.asImageVector(),
                stringResource(R.string.map_sheet_meet_here),
                onMeetHere!!,
            )
        } else if (anchoring) {
            MapPrimaryAction(
                anchorGlyph.asImageVector(),
                stringResource(R.string.map_anchor_set_here, anchorLabel!!),
                onSetAnchorHere!!,
            )
        } else {
            MapPrimaryAction(
                PhantomGlyph.ROUTE_TO.asImageVector(),
                stringResource(R.string.map_sheet_route_here),
                onRoute,
            )
        }
        MapTileRow {
            when {
                anchoring -> MapActionTile(
                    PhantomGlyph.ROUTE_TO.asImageVector(),
                    stringResource(R.string.map_sheet_tile_route),
                    onRoute,
                )
                onVia != null -> MapActionTile(
                    PhantomGlyph.VIA.asImageVector(),
                    stringResource(R.string.map_sheet_tile_via),
                    onVia,
                )
                else -> MapActionTile(
                    PhantomGlyph.ROUTE_FROM.asImageVector(),
                    stringResource(R.string.map_sheet_tile_from),
                    onRouteFrom,
                )
            }
            MapActionTile(
                PhantomGlyph.WHAT_HERE.asImageVector(),
                stringResource(R.string.map_sheet_what_here),
                onWhatHere,
            )
            MapActionTile(
                PhantomGlyph.KEEP.asImageVector(),
                stringResource(R.string.map_sheet_save),
                { naming = true },
            )
            MapActionTile(
                PhantomGlyph.HANDOUT.asImageVector(),
                stringResource(R.string.map_sheet_share),
                onShare,
            )
        }

        if (onSendToChat != null) {
            MapQuietRow(
                stringResource(R.string.place_send_chat),
                onSendToChat,
                icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(
                    local.phantom.companion.core.design.R.drawable.lc_chats,
                ),
            )
        }
        if (onMeetHere != null && !meetFirst) {
            MapQuietRow(
                stringResource(R.string.map_sheet_meet_here),
                onMeetHere,
                icon = PhantomGlyph.PERSON.asImageVector(),
            )
        }

        if (hereNow != null) {
            local.phantom.companion.feature.stream.ui.map.here.HereNowBlock(
                state = hereNow,
                nowMs = remember(hereNow) { System.currentTimeMillis() },
                onReport = onHereReport,
            )
        }

        Column {
            MapMoreToggle(expanded = more, onToggle = { more = !more })
            if (more) {
                if (onDangerField != null) {
                    MapQuietRow(stringResource(R.string.map_sheet_danger_field), onDangerField)
                }
                if (onIsochrone != null) {
                    MapQuietRow(stringResource(R.string.map_sheet_isochrone), onIsochrone)
                }
                if (onVia != null && !anchoring) {
                    MapQuietRow(stringResource(R.string.map_sheet_route_from), onRouteFrom)
                }
                MapQuietRow(stringResource(R.string.map_sheet_open_external), onOpenExternal)
                if (hereNow != null) {
                    local.phantom.companion.feature.stream.ui.map.here.HereReportRow(hereNow, onHereReport)
                }
                if (onMark != null) {
                    Spacer(Modifier.height(PhantomSpace.s2))
                    HazardMarkRow(onMark)
                }
                Spacer(Modifier.height(PhantomSpace.s2))
                SheetNote("${"%.5f".format(lat)}, ${"%.5f".format(lon)}", tnum = true)
            }
        }
    }
}

/** Перша дія аркуша точки — «Зустрітися тут», лише коли чернетка чекає мітку і якір не ставлять. */
internal fun meetFirst(anchoring: Boolean, meetPicking: Boolean, canMeet: Boolean): Boolean =
    canMeet && meetPicking && !anchoring

/**
 * Геокодер дає ланцюг «вулиця, будинок, місто, область, індекс, країна».
 * Заголовок — вулиця з будинком; під ним лише місто. Решта ланок — шум.
 */
internal fun pointHeadline(address: String?): Pair<String?, String?> {
    val parts = address?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (parts.isEmpty()) return null to null
    val house = parts.getOrNull(1)?.takeIf { it.firstOrNull()?.isDigit() == true }
    val head = if (house != null) "${parts[0]}, $house" else parts[0]
    val place = parts.drop(if (house != null) 2 else 1).firstOrNull { p -> p.none { it.isDigit() } }
    return head to place
}

/**
 * Брама імені на склі — та сама, що в сховищі ([MarkName]): безіменного
 * збереження не існує, і замість сірої мовчазної кнопки причина сказана
 * словом. Категорія — три слова власника: дім, робота, своє.
 */
@Composable
private fun MarkNamingBlock(
    address: String?,
    onSave: (name: String, category: String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalPhantomColors.current
    var draft by remember { mutableStateOf(MarkName.candidate(null, address)) }
    var category by remember { mutableStateOf(SavedPlaceCategory.CUSTOM) }
    val nameFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { nameFocus.requestFocus() }
    val named = MarkName.isNamed(draft)
    val fieldLabel = stringResource(R.string.map_sheet_place_name)

    SheetField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = fieldLabel,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { if (named) onSave(MarkName.clean(draft), category) },
        ),
        fieldModifier = Modifier
            .focusRequester(nameFocus)
            .semantics { contentDescription = MapText.s(R.string.kt_sheet_mark_name) },
    )
    CategorySegments(category) { category = it }
    if (named) {
        PointRow(
            PhantomGlyph.CONFIRM.asImageVector(),
            stringResource(R.string.map_sheet_save_mark),
            { onSave(MarkName.clean(draft), category) },
            c.accentText,
        )
    } else {
        // Кнопки нема, і причина сказана словом — не сірий силует без пояснень.
        SheetNote(stringResource(R.string.map_sheet_name_empty))
    }
    PointRow(
        PhantomGlyph.DISMISS.asImageVector(),
        stringResource(R.string.map_sheet_save_cancel),
        onCancel,
        c.text2,
    )
}

/**
 * Категорія мітки — сегменти, як режими маршруту й вкладки хаба: обраний —
 * мʼякий бурштин із волосиною, а не друга залита кнопка на аркуші.
 */
@Composable
private fun CategorySegments(current: String, onPick: (String) -> Unit) {
    SheetSegments {
        listOf(
            SavedPlaceCategory.HOME to stringResource(R.string.map_search_anchor_home),
            SavedPlaceCategory.WORK to stringResource(R.string.map_search_anchor_work),
            SavedPlaceCategory.CUSTOM to stringResource(R.string.map_sheet_cat_custom),
        ).forEach { (cat, label) ->
            SheetSegment(label = label, selected = current == cat, onClick = { onPick(cat) })
        }
    }
}

/**
 * NAV-5 — one compact row of mark chips. TTL ~2 год is fixed here on
 * purpose (marks must fade); no fake feed behind them.
 */
@Composable
private fun HazardMarkRow(onMark: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
        MapGroupTitle(stringResource(R.string.map_sheet_mark_2h))
        MapTileRow {
            // `kind` — ключ шару позначок, не підпис: переклад змінив би дані.
            SHEET_MARKS.forEach { mark ->
                val kind = mark.kind
                MapActionTile(mark.glyph.asImageVector(), stringResource(mark.label), { onMark(kind) })
            }
        }
    }
}

internal data class SheetMark(val kind: String, @androidx.annotation.StringRes val label: Int, val glyph: PhantomGlyph)

internal val SHEET_MARKS = listOf(
    SheetMark("ДТП", R.string.map_sheet_mark_crash, PhantomGlyph.ACCIDENT),
)

/** Розмір глифа дії в аркуші. Мішень лишається 48dp — це лише малюнок. */
private val GLYPH = 22.dp
private val SAVED_GLYPH = 20.dp

@Composable
private fun PointRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    /** Чорнило глифа: бурштин-слово (`accentText`), тихе `text2` чи `danger`. */
    tint: Color,
    /** Незадане — звичайне чорнило. Стан несе слово, тож руйнівна дія фарбує
     *  і глиф, І підпис: сам червоний значок нічого не каже. */
    labelColor: Color = Color.Unspecified,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button) { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Значок, а не медальйон: вісім залитих кружків стовпчиком нічого не
        // ранжували. Колір лишається на глифі — прибрати треба було вагу
        // (залитий диск 32dp), а не життя («точка на мапі чому тепер сіра?»).
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(start = PhantomSpace.s1)
                .size(GLYPH),
        )
        Spacer(Modifier.width(PhantomSpace.s4))
        SheetBody(label, color = if (labelColor == Color.Unspecified) c.text else labelColor)
    }
}

/**
 * «Задати дім» — питання, а не тихий запис.
 *
 * Доти дотик по порожньому якорю мовчки писав ЦЕНТР ВІКНА, та ще й з-під
 * шторки пошуку на пів екрана: людина не бачила ані що збережено, ані що
 * взагалі щось сталось, і не мала способу сказати «я зараз тут».
 *
 * Без живої позиції рядок «я тут» не сіріє, а зникає, і замість нього стоїть
 * причина словом: недоступна кнопка змушує гадати, чи вона зламана.
 */
@Composable
internal fun AnchorPickSheet(
    label: String,
    glyph: PhantomGlyph,
    hasFix: Boolean,
    accentColor: Color,
    onHere: () -> Unit,
    onPickOnMap: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .sheetBody()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                glyph.asImageVector(),
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(SAVED_GLYPH),
            )
            Spacer(Modifier.width(PhantomSpace.s2))
            SheetTitle(stringResource(R.string.map_anchor_sheet_title, label))
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        if (hasFix) {
            AnchorWayRow(
                icon = PhantomGlyph.LOCATE_LOCKED.asImageVector(),
                title = stringResource(R.string.map_anchor_here),
                subtitle = stringResource(R.string.map_anchor_here_sub),
                onClick = onHere,
            )
        } else {
            SheetNote(stringResource(R.string.map_anchor_no_fix))
        }
        AnchorWayRow(
            icon = PhantomGlyph.PLACE.asImageVector(),
            title = stringResource(R.string.map_anchor_on_map),
            subtitle = stringResource(R.string.map_anchor_on_map_sub),
            onClick = onPickOnMap,
        )
    }
}

/** Рядок шляху: підпис каже, ЩО буде, а не лише як зветься дія. */
@Composable
private fun AnchorWayRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" }
            .padding(vertical = PhantomSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = c.accentText,
            modifier = Modifier
                .padding(start = PhantomSpace.s1)
                .size(GLYPH),
        )
        Spacer(Modifier.width(PhantomSpace.s4))
        Column {
            SheetBody(title)
            SheetNote(subtitle)
        }
    }
}

/**
 * NAV-0 — presents whichever contextual sheet the current selection on
 * [MapUiState] implies: tapped POI, tapped phantom-layer marker,
 * long-pressed point, or an active-alert oblast threat brief.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MapSheetHost(
    ui: MapUiState,
    operatorLocation: local.phantom.companion.core.ai.context.LocationFix?,
    viewModel: MapViewModel,
    onAskAi: ((lat: Double, lon: Double, name: String) -> Unit)?,
    onOpenExternal: ((lat: Double, lon: Double, name: String) -> Unit)?,
    onSavePoi: ((PoiEntity) -> Unit)?,
    onMeetHere: ((lat: Double, lon: Double, label: String?, venueId: String?) -> Unit)? = null,
    meetPicking: Boolean = false,
    // A saved place the operator tapped: delete it from, or rename it in, the
    // Vault directly — no detour through the settings-style manager panel.
    onDeleteSavedPlace: ((PoiEntity) -> Unit)? = null,
    onRenameSavedPlace: ((PoiEntity, String) -> Unit)? = null,
    onRecategorizeSavedPlace: ((PoiEntity, String) -> Unit)? = null,
    // NAV-1 — offline point-in-polygon oblast lookup (OblastShapes) for
    // the compare sheet's «перетинає тривогу» line.
    oblastAt: ((Double, Double) -> String?)? = null,
    // СИРЕНА — oblast centroid for the alarm sheet's «показати на мапі».
    oblastCentroid: ((String) -> Pair<Double, Double>?)? = null,
    // NAV-4 — the map's throttled Nominatim, for the dossier's address line.
    reverseGeocode: suspend (Double, Double) -> String? = { _, _ -> null },
    // Живий флот транспортного хаба. Null там, де перевіреного GTFS-RT фіда
    // немає, — тоді хаб живе розкладами й не заявляє жодних машин.
    transitRepository: local.phantom.companion.core.data.transit.TransitRepository? = null,
) {
    val c = LocalPhantomColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val layerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pointSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val peerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val whoSeesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val peerGroupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val myWorldSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val threatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val placeSendSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // NAV-1 — route variants (compare moved into MapCommandSheet.VariantSegments).
    val routeOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
    val routeSelected by viewModel.routeSelectedIndex.collectAsStateWithLifecycle()
    val routeInfo by viewModel.routeInfo.collectAsStateWithLifecycle()

    // СИРЕНА — the shelter verb both alarm surfaces share.
    val routeToShelter: () -> Unit = {
        val loc = operatorLocation
        if (loc != null) {
            viewModel.routeToNearestShelter(loc.lat, loc.lon)
        } else {
            viewModel.reportEvacuationBlocked(MapText.s(R.string.kt_sheet_unknown_loc))
        }
    }

    // СИРЕНА-4 — this operator's own oblast, live, not the country at large:
    // every «Маршрут сюди» on this sheet reads it so a long trip defaults to
    // FOOT instead of TRANSIT the moment a siren is actually overhead
    // (RouteProfileChoice.forTrip's `siren` branch) — transit is also the
    // first thing to break offline, which is exactly the failure this fixes.
    val sirenLoud = operatorLocation
        ?.let { oblastAt?.invoke(it.lat, it.lon) }
        ?.let { ui.alertStates[it]?.active } == true

    // СИРЕНА — «Небо»: the alarm command center (opened from the alert pill).
    if (ui.alarmSheetOpen) {
        val alarmSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val journal by viewModel.alertJournal.collectAsStateWithLifecycle()
        val detailLive by viewModel.alertDetailLive.collectAsStateWithLifecycle()
        val fetchedAt by viewModel.alertFetchedAtMs.collectAsStateWithLifecycle()
        AlarmSheet(
            ownOblast = operatorLocation?.let { oblastAt?.invoke(it.lat, it.lon) },
            alerts = ui.alertStates,
            journal = journal,
            detailLive = detailLive,
            fetchedAtMs = fetchedAt,
            sheetState = alarmSheetState,
            onFocusOblast = { name ->
                val c = oblastCentroid?.invoke(name)
                if (c != null) {
                    ui.alarmSheetOpen = false
                    ui.camera?.submit(
                        local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent.Focus(
                            target = local.phantom.companion.feature.stream.ui.map.atlas
                                .GeoPoint(c.first, c.second),
                            zoom = 6.8,
                            issuedAtMs = android.os.SystemClock.uptimeMillis(),
                        ),
                    )
                }
            },
            onOpenBrief = { name ->
                ui.alarmSheetOpen = false
                ui.selectedThreatOblast = name
            },
            onRouteToShelter = {
                ui.alarmSheetOpen = false // Close — the EvacuationCard takes over from here.
                routeToShelter()
            },
            onDismiss = { ui.alarmSheetOpen = false },
            circle = {
                val points by viewModel.peerPoints.collectAsStateWithLifecycle()
                val seeMe by viewModel.whoSeesMeRows.collectAsStateWithLifecycle()
                val listeners = seeMe.filter { it.precision != null && it.eligible }
                val nowMs = System.currentTimeMillis()
                local.phantom.companion.feature.stream.ui.map.peers.SafetyCircleBlock(
                    rows = local.phantom.companion.feature.stream.ui.map.peers.safetyCircle(
                        points, nowMs,
                        oblastAt = { lat, lon -> oblastAt?.invoke(lat, lon) },
                        alertOn = { ui.alertStates[it]?.active == true },
                    ),
                    listeners = listeners.size,
                    onOpen = { p ->
                        ui.alarmSheetOpen = false
                        ui.selectedPeer = p
                    },
                    onSafe = {
                        val words = context.getString(R.string.circle_safe_words)
                        val told = listeners.count { viewModel.people.say(it.peerId, words) }
                        viewModel.postNotice(
                            if (told > 0) context.resources.getQuantityString(R.plurals.circle_safe_told, told, told)
                            else context.getString(R.string.peer_notice_not_sent),
                        )
                    },
                )
            },
        )
    }

    // M-C01 — Threat Brief Sheet
    ui.selectedThreatOblast?.let { obl ->
        val st = ui.alertStates[obl]
        ThreatBriefSheet(
            oblast = obl,
            state = st,
            sheetState = threatSheetState,
            onDismiss = { ui.selectedThreatOblast = null },
            onRouteToShelter = {
                ui.selectedThreatOblast = null // Close sheet — the EvacuationCard takes over from here.
                routeToShelter()
            }
        )
    }

    // NAV-2 — Maneuver List Sheet (turn-by-turn itinerary)
    if (ui.maneuverListOpen) {
        val guidanceState by viewModel.guidance.state.collectAsStateWithLifecycle()
        val activeVariant = routeOptions.getOrNull(routeSelected)?.variant
        if (activeVariant != null) {
            val curStep = (guidanceState as? local.phantom.companion.core.nav.GuidanceState.Guiding)?.progress?.stepIndex ?: 0
            MapSheet(
                onDismissRequest = { ui.maneuverListOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                local.phantom.companion.feature.stream.ui.map.nav.ManeuverListSheet(
                    variant = activeVariant,
                    currentStepIndex = curStep,
                    onClose = { ui.maneuverListOpen = false },
                )
            }
        }
    }

    // ТРАНСПОРТНИЙ ХАБ — departures radar, lines catalog, schedule & metro
    if (ui.transitHubOpen) {
        val home = local.phantom.companion.feature.stream.ui.map.country.countryHomePoint()
        val hereLat = operatorLocation?.lat ?: ui.viewportCenter?.first ?: home.first
        val hereLon = operatorLocation?.lon ?: ui.viewportCenter?.second ?: home.second
        MapSheet(
            onDismissRequest = { ui.transitHubOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            local.phantom.companion.feature.stream.ui.map.transit.TransitHubSheet(
                surfaceFeed = viewModel.surfaceFeed,
                metroFeed = viewModel.metroFeed,
                transitRepository = transitRepository,
                coverage = androidx.compose.runtime.remember(hereLat, hereLon) {
                    viewModel.transitCoverageAt(hereLat, hereLon)
                },
                currentLat = hereLat,
                currentLon = hereLon,
                onSelectStop = { stopTarget ->
                    ui.transitHubOpen = false
                    ui.dossierTarget = stopTarget
                },
                onShowLineOnMap = { line ->
                    ui.transitHubOpen = false
                    viewModel.showLocalTransitLine(line)
                },
                onRouteTo = { destLat, destLon, _ ->
                    ui.transitHubOpen = false
                    val loc = operatorLocation
                    if (loc != null) {
                        viewModel.routeTo(loc.lat, loc.lon, destLat, destLon, RouteProfile.TRANSIT)
                    } else {
                        viewModel.postNotice(MapText.s(R.string.kt_sheet_no_pos))
                    }
                },
                onClose = { ui.transitHubOpen = false },
            )
        }
    }

    // «Що тут зараз» — одна клітинка на обидва аркуші: точка, а коли її немає — досьє.
    val hereDesk = viewModel.hereNow
    val herePoint = ui.droppedPoint ?: ui.dossierTarget?.let { it.lat to it.lon }
    if (herePoint != null && hereDesk != null) {
        androidx.compose.runtime.DisposableEffect(herePoint) {
            hereDesk.open(herePoint.first, herePoint.second)
            onDispose { hereDesk.close() }
        }
    }
    val hereDeskState = hereDesk?.state?.collectAsStateWithLifecycle()?.value

    // NAV-4 — «досьє місця»: any tapped place opens the full dossier card
    // (live hours, phone, wiki, building, transit lines, similar nearby).
    val dossierTarget = ui.dossierTarget
    if (dossierTarget != null) {
        MapPlaceSheet(onDismissRequest = { ui.dossierTarget = null }) {
            var target by androidx.compose.runtime.remember(dossierTarget) {
                androidx.compose.runtime.mutableStateOf(dossierTarget)
            }
            var dossier by androidx.compose.runtime.remember(dossierTarget) {
                androidx.compose.runtime.mutableStateOf<local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier?>(null)
            }
            // Streamed, not awaited. loadDossier collects dossierFlow to its
            // LAST snapshot, throwing away every intermediate one — including
            // the zero-IO peek at T+0 — so a tap resolved into a nameless grey
            // skeleton for as long as the slowest slot took.
            androidx.compose.runtime.LaunchedEffect(target) {
                dossier = null
                viewModel.dossierFlow(target, reverseGeocode).collect { dossier = it }
            }
            // Огляд живе в картці: токени течуть сюди, камера лишається на мапі.
            val briefings by viewModel.briefings.collectAsStateWithLifecycle()
            val briefing = dossier?.let {
                briefings[local.phantom.companion.feature.stream.ui.map.dossier.dossierKey(it.target)]
            }
            local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossierContent(
                dossier = dossier,
                // The card was built to draw a peek from the tap target alone —
                // name, category and a working «Маршрут» in hand at once — and
                // the call site never passed it, so it fell through to the
                // skeleton its own KDoc calls the fallback.
                target = target,
                origin = operatorLocation?.let { it.lat to it.lon },
                accentColor = c.accent,
                briefing = briefing,
                onBrief = {
                    dossier?.let {
                        viewModel.askDossierBriefing(
                            it,
                            operatorLocation?.let { fix -> fix.lat to fix.lon },
                        )
                    }
                },
                onBriefRetry = { dossier?.let { viewModel.retryDossierBriefing(it) } },
                onRoute = {
                    // Досьє — четверта поверхня, що називає КІНЕЦЬ, тож обраний
                    // початок мусить важити й тут. Доти «звідси маршрут» гинув
                    // мовчки, якщо ціль назвали через «Що тут?».
                    val pick = ui.routeOriginPick
                    val from = pick?.let { it.lat to it.lon }
                        ?: operatorLocation?.let { it.lat to it.lon }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                    if (from != null) {
                        // Режим більше не прибитий до «пішки»: обираємо за
                        // відстанню і за тим, чим людина ЗАРАЗ їде.
                        viewModel.routeTo(
                            from.first, from.second, target.lat, target.lon,
                            RouteProfileChoice.forTrip(
                                from = from,
                                to = target.lat to target.lon,
                                motion = viewModel.motion.value,
                                siren = sirenLoud,
                            ),
                            // Імʼя досьє, коли воно вже приїхало; інакше хінт із
                            // самого тапу. Обидва можуть бути порожні — тоді
                            // панель скаже координати, а не «місце».
                            destName = dossier?.title ?: target.nameHint,
                        )
                        ui.routeOriginPick = null
                    } else {
                        viewModel.postNotice(MapText.s(R.string.kt_sheet_no_pos))
                    }
                    ui.dossierTarget = null
                },
                // NAV-Ω1 — «заїхати по дорозі»: replan the LIVE route
                // through this place (a failed replan keeps the old route).
                onVia = if (routeInfo != null) {
                    {
                        viewModel.addViaPoint(target.lat, target.lon)
                        ui.dossierTarget = null
                    }
                } else null,
                onSave = {
                    val poi = PoiEntity(
                        id = -System.currentTimeMillis(),
                        lat = target.lat,
                        lon = target.lon,
                        // Без вигаданого «Місце»: безіменне досьє чесно впаде на
                        // браму імені, і смуга внизу скаже це словом.
                        name = dossier?.title ?: target.nameHint ?: "",
                        category = target.categoryHint ?: "other",
                        subcategory = target.subcategoryHint,
                        tags = dossier?.tags ?: emptyMap(),
                    )
                    onSavePoi?.invoke(poi)
                    // Вердикт каже смуга ContinuumHost за фактом запису; свій
                    // «✓ Збережено» тут устигав збрехати раніше за відмову брами.
                },
                onShare = {
                    val label = dossier?.title ?: target.nameHint ?: MapText.s(R.string.kt_sheet_place)
                    val text = local.phantom.companion.core.geo.link.MapLink.shareText(
                        local.phantom.companion.core.geo.link.MapLink.Point(target.lat, target.lon, label),
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(send, MapText.s(R.string.kt_sheet_share_place)))
                    }
                },
                onOpenExternal = {
                    onOpenExternal?.invoke(target.lat, target.lon, dossier?.title ?: target.nameHint ?: MapText.s(R.string.kt_sheet_place))
                    ui.dossierTarget = null
                },
                onOpenUrl = { url ->
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                        )
                    }
                },
                onDial = { phone ->
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")),
                        )
                    }
                },
                onShowLine = { line ->
                    viewModel.showTransitLine(line)
                    ui.dossierTarget = null
                },
                onShowLocalLine = { line ->
                    viewModel.showLocalTransitLine(line)
                    ui.dossierTarget = null
                },
                onRetarget = { target = it },
                hereNow = if (ui.droppedPoint == null) hereDeskState else null,
                onHereReport = { kind, reading -> hereDesk?.report(kind, reading) },
                onMeetHere = onMeetHere?.let { meet ->
                    {
                        meet(target.lat, target.lon, dossier?.title ?: target.nameHint, target.venueId)
                        ui.dossierTarget = null
                    }
                },
                onSendToChat = {
                    openPlaceSend(
                        viewModel.people,
                        target.copy(nameHint = dossier?.title ?: target.nameHint),
                        context.getString(R.string.place_generic),
                    ) { ui.placeSend = it }
                    ui.dossierTarget = null
                },
            )
        }
    }

    val placeSend = ui.placeSend
    if (placeSend != null) {
        val rows by viewModel.whoSeesMeRows.collectAsStateWithLifecycle()
        val name = placeSend.nameHint?.takeIf { it.isNotBlank() } ?: context.getString(R.string.place_generic)
        MapSheet(
            onDismissRequest = { ui.placeSend = null },
            sheetState = placeSendSheetState,
        ) {
            local.phantom.companion.feature.stream.ui.map.dossier.PlaceSendSheet(
                place = name,
                people = local.phantom.companion.feature.stream.ui.map.dossier.placeSendPeople(rows),
            ) { id, who ->
                val sent = viewModel.people.sendPlace(id, placeSend.lat, placeSend.lon, name)
                viewModel.postNotice(
                    if (sent) context.getString(R.string.place_sent, who)
                    else context.getString(R.string.peer_notice_not_sent),
                )
                ui.placeSend = null
            }
        }
    }

    // R4.2 — place-card for a tapped phantom-layer marker. Real detail
    // only (Law 1: absent tag = absent row). Actions route back into the
    // conversation so the map and the dialogue share one gaze.
    val tapped = ui.selectedLayerMarker
    if (tapped != null) {
        MapSheet(
            onDismissRequest = { ui.selectedLayerMarker = null },
            sheetState = layerSheetState,
        ) {
            LayerMarkerCard(
                marker = tapped,
                accentColor = c.accent,
                onAskAi = {
                    onAskAi?.invoke(tapped.lat, tapped.lon, tapped.label)
                    ui.selectedLayerMarker = null
                },
                // ЄДИНА кнопка цієї картки, залита бурштином, — і єдина з
                // чотирьох поверхонь «маршрут сюди», що ніколи не будувала
                // маршруту. Вона складала речення «проклади маршрут до …» і
                // віддавала його `onAskAi` — тобто в РОЗМОВУ: людина тисне
                // «Маршрут сюди» на камері чи опорі ЛЕП, мапа лишається як
                // була, а в чаті зʼявляється чужий рядок. Без ключа моделі не
                // ставалось і того. Тепер це той самий `routeTo`, що в аркуші
                // точки, досьє й збереженого місця — з тим самим обраним
                // початком і тим самим вибором режиму.
                onRoute = {
                    val pick = ui.routeOriginPick
                    val from = pick?.let { it.lat to it.lon }
                        ?: operatorLocation?.let { it.lat to it.lon }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                    if (from != null) {
                        viewModel.routeTo(
                            from.first, from.second, tapped.lat, tapped.lon,
                            RouteProfileChoice.forTrip(
                                from = from,
                                to = tapped.lat to tapped.lon,
                                motion = viewModel.motion.value,
                                siren = sirenLoud,
                            ),
                            destName = tapped.label,
                        )
                        ui.routeOriginPick = null
                    } else {
                        viewModel.postNotice(context.getString(R.string.map_route_no_origin))
                    }
                    ui.selectedLayerMarker = null
                },
                onOpenExternal = {
                    onOpenExternal?.invoke(tapped.lat, tapped.lon, tapped.label)
                    ui.selectedLayerMarker = null
                },
            )
        }
    }

    // СИМБІОЗ — картка людини. Дій тут немає свідомо: маршрут до контакту
    // будує вузол одержувача, і це хвиля 3, а не «забули кнопку».
    val peer = ui.selectedPeer
    if (peer != null) {
        val routeToPeer: () -> Unit = {
            val from = operatorLocation?.let { it.lat to it.lon }
            if (from != null) {
                viewModel.routeTo(
                    from.first, from.second, peer.lat, peer.lon,
                    RouteProfileChoice.forTrip(
                        from = from,
                        to = peer.lat to peer.lon,
                        motion = viewModel.motion.value,
                        siren = sirenLoud,
                    ),
                    destName = peer.name,
                )
            } else {
                viewModel.postNotice(context.getString(R.string.map_route_no_origin))
            }
            ui.selectedPeer = null
        }
        MapSheet(
            onDismissRequest = { ui.selectedPeer = null },
            sheetState = peerSheetState,
        ) {
            if (peer.precision != null) {
                local.phantom.companion.feature.stream.ui.map.peers.PeerWhereCard(
                    peer = peer,
                    accentColor = c.accent,
                    onWrite = {
                        ui.selectedPeer = null
                        viewModel.onWritePeer(peer.threadId)
                    },
                    onWhoSees = {
                        ui.selectedPeer = null
                        ui.whoSeesOpen = true
                    },
                    origin = operatorLocation?.let { it.lat to it.lon },
                    // Лише до точного місця: маршрут до центру району вів би в нікуди.
                    onRoute = if (peer.precision == "EXACT") {
                        {
                            viewModel.people.tellOnArrival(listOf(peer.threadId), peer.lat, peer.lon, context.getString(R.string.peer_words_arrived))
                            viewModel.postNotice(context.getString(R.string.peer_notice_arrival, peer.name))
                            routeToPeer()
                        }
                    } else null,
                    onWave = {
                        val sent = viewModel.people.say(peer.threadId, context.getString(R.string.peer_words_wave))
                        viewModel.postNotice(context.getString(if (sent) R.string.peer_notice_waved else R.string.peer_notice_not_sent, peer.name))
                        ui.selectedPeer = null
                    },
                    onCallHere = operatorLocation?.let { me ->
                        {
                            val sent = viewModel.people.sendPlace(peer.threadId, me.lat, me.lon, context.getString(R.string.peer_words_call_here))
                            viewModel.postNotice(if (sent) context.getString(R.string.peer_notice_called, peer.name) else context.getString(R.string.peer_notice_not_sent))
                            ui.selectedPeer = null
                        }
                    },
                    // Посередині — лише коли відомо, де я; інакше «середина» вигадана.
                    onMeetMiddle = operatorLocation?.let { me ->
                        {
                            val (lat, lon) = local.phantom.companion.feature.stream.ui.map.peers.peerMidpoint(me.lat to me.lon, peer)
                            val sent = viewModel.people.sendPlace(peer.threadId, lat, lon, context.getString(R.string.peer_words_meet_middle))
                            if (sent) {
                                viewModel.people.tellOnArrival(listOf(peer.threadId), lat, lon, context.getString(R.string.peer_words_arrived))
                                viewModel.routeTo(
                                    me.lat, me.lon, lat, lon,
                                    RouteProfileChoice.forTrip(from = me.lat to me.lon, to = lat to lon, motion = viewModel.motion.value, siren = sirenLoud),
                                    destName = context.getString(R.string.peer_act_meet_middle),
                                )
                                viewModel.postNotice(context.getString(R.string.peer_notice_meet, peer.name))
                            } else {
                                viewModel.postNotice(context.getString(R.string.peer_notice_not_sent))
                            }
                            ui.selectedPeer = null
                        }
                    },
                )
            } else {
                local.phantom.companion.feature.stream.ui.map.peers.PeerPointCard(
                    peer = peer,
                    origin = operatorLocation?.let { it.lat to it.lon },
                    accentColor = c.accent,
                    onWrite = if (peer.threadId.isNotBlank()) {
                        {
                            ui.selectedPeer = null
                            viewModel.onWritePeer(peer.threadId)
                        }
                    } else null,
                    onRoute = routeToPeer,
                    onShowMe = {
                        ui.selectedPeer = null
                        ui.whoSeesOpen = true
                    },
                )
            }
        }
    }

    if (ui.whoSeesOpen) {
        val rows by viewModel.whoSeesMeRows.collectAsStateWithLifecycle()
        MapSheet(
            onDismissRequest = { ui.whoSeesOpen = false },
            sheetState = whoSeesSheetState,
        ) {
            val self by viewModel.people.self.collectAsStateWithLifecycle()
            val world by viewModel.people.world.collectAsStateWithLifecycle()
            val points by viewModel.peerPoints.collectAsStateWithLifecycle()
            val hidden by viewModel.people.hidden.collectAsStateWithLifecycle()
            local.phantom.companion.feature.stream.ui.map.peers.WhoSeesMeSheet(
                rows = rows.map { r -> hidden[r.peerId]?.let { r.copy(hiddenUntilMs = it) } ?: r },
                accentColor = c.accent,
                onSet = { id, p -> viewModel.onSetWhoSees(id, p) },
                onVanish = viewModel.onVanish,
                self = self,
                onHide = { id, until -> viewModel.people.hide(id, until) },
                onLowBattery = { viewModel.people.shareLowBattery(it) },
                near = local.phantom.companion.feature.stream.ui.map.peers.peersNear(points, System.currentTimeMillis()),
                onOpenPeer = { p ->
                    ui.whoSeesOpen = false
                    ui.selectedPeer = p
                },
                world = world,
                onOpenWorld = {
                    ui.whoSeesOpen = false
                    ui.myWorldOpen = true
                },
                onMeet = {
                    ui.whoSeesOpen = false
                    ui.meetPickOpen = true
                },
                onAddPerson = {
                    ui.whoSeesOpen = false
                    viewModel.onAddPerson()
                },
            )
        }
    }

    val group = ui.peerGroup
    if (group != null) {
        val points by viewModel.peerPoints.collectAsStateWithLifecycle()
        val nowMs = System.currentTimeMillis()
        val members = points.filter { it.nodeId in group }.map { it.tapped(nowMs) }
        MapSheet(
            onDismissRequest = { ui.peerGroup = null },
            sheetState = peerGroupSheetState,
        ) {
            local.phantom.companion.feature.stream.ui.map.peers.PeerGroupSheet(members, nowMs) { p ->
                ui.peerGroup = null
                ui.selectedPeer = p
            }
        }
    }

    val liveShareController = remember(viewModel.people) {
        local.phantom.companion.feature.stream.ui.map.peers.LiveShareController(
            port = viewModel.people,
            shareWords = context.getString(R.string.map_share_my_location),
            arrivedWords = context.getString(R.string.peer_words_arrived),
        )
    }
    val activeShareSession = liveShareController.activeShare
    if (activeShareSession != null) {
        androidx.compose.runtime.LaunchedEffect(activeShareSession) {
            val exp = activeShareSession.expiresAtMs
            if (exp != null) {
                val waitMs = (exp - System.currentTimeMillis()).coerceAtLeast(0L)
                kotlinx.coroutines.delay(waitMs)
                liveShareController.onLocationOrTick(null, System.currentTimeMillis())
            }
        }
        androidx.compose.runtime.LaunchedEffect(operatorLocation, activeShareSession) {
            val me = operatorLocation?.let { it.lat to it.lon }
            if (me != null) {
                liveShareController.onLocationOrTick(me, System.currentTimeMillis())
            }
        }
    }

    if (ui.meetPickOpen || ui.meetOpen) {
        val rows by viewModel.whoSeesMeRows.collectAsStateWithLifecycle()
        val hidden by viewModel.people.hidden.collectAsStateWithLifecycle()
        val points by viewModel.peerPoints.collectAsStateWithLifecycle()
        val nowMs = System.currentTimeMillis()
        val meeting = ui.meeting
        var selectedTimeChip by remember { mutableStateOf("") }
        MapSheet(
            onDismissRequest = {
                ui.meetPickOpen = false
                ui.meetOpen = false
            },
            sheetState = meetSheetState,
        ) {
            val me = operatorLocation?.let { it.lat to it.lon }
            val recipients = local.phantom.companion.feature.stream.ui.map.peers.shareRecipients(rows, hidden)
            local.phantom.companion.feature.stream.ui.map.peers.PeoplePeekContent(
                people = local.phantom.companion.feature.stream.ui.map.peers.peersNear(points, nowMs),
                origin = me,
                nowMs = nowMs,
                sharingUntilLabel = liveShareController.sharingLabel(nowMs, recipients, hidden),
                audiences = rows.filter { it.eligible }.map { it.peerId to it.name },
                onStartShare = { audienceId, duration ->
                    liveShareController.start(
                        recipients = recipients,
                        audienceId = audienceId,
                        duration = duration,
                        me = me,
                        nowMs = System.currentTimeMillis(),
                    )
                },
                onStopShare = { liveShareController.stop(recipients) },
                onOpenPeer = { p ->
                    ui.meetPickOpen = false
                    ui.meetOpen = false
                    ui.selectedPeer = p
                },
                onMeet = { ui.meetPickOpen = true },
            )
            if (ui.meetOpen && meeting != null) {
                local.phantom.companion.feature.stream.ui.map.peers.MeetSheet(
                    rows = local.phantom.companion.feature.stream.ui.map.peers.meetRows(meeting, points, nowMs),
                    nowMs = nowMs,
                    onOpen = { p ->
                        ui.meetOpen = false
                        ui.selectedPeer = p
                    },
                    onEnd = {
                        ui.meeting = null
                        ui.meetOpen = false
                    },
                )
            } else {
                val participants = local.phantom.companion.feature.stream.ui.map.peers.buildFairMeetParticipants(
                    selfLabel = context.getString(local.phantom.companion.feature.stream.ui.map.peers.fairMeetSelfLabelRes()),
                    me = me,
                    points = points,
                    nowMs = nowMs,
                )
                val venues = points
                    .filter { it.precision == null && it.name.isNotBlank() }
                    .map { p ->
                        local.phantom.companion.feature.stream.ui.map.around.AroundPlace(
                            name = p.name,
                            lat = p.lat,
                            lon = p.lon,
                        )
                    }
                val fairCandidates = local.phantom.companion.feature.stream.ui.map.peers.pickFairMeetVenues(
                    participants = participants,
                    venues = venues,
                )
                if (fairCandidates.isNotEmpty()) {
                    local.phantom.companion.feature.stream.ui.map.peers.FairMeetPeekContent(
                        candidates = fairCandidates,
                        selectedTimeChip = selectedTimeChip,
                        onSelectTime = { selectedTimeChip = it },
                        onSendToChat = { cand, timeChip ->
                            val startedMeeting = local.phantom.companion.feature.stream.ui.map.peers.dispatchFairMeet(
                                port = viewModel.people,
                                candidate = cand,
                                timeLabel = timeChip,
                                meetAtLabel = context.getString(R.string.map_meet_at, cand.name),
                                arrivedWords = context.getString(R.string.peer_words_arrived),
                                eligiblePeers = rows.filter { it.eligible }.map { it.peerId to it.name },
                                nowMs = nowMs,
                            )
                            if (startedMeeting != null) {
                                ui.meeting = startedMeeting
                                viewModel.postNotice(
                                    context.getString(
                                        R.string.meet_notice,
                                        startedMeeting.members.joinToString(", ") { it.second },
                                    ),
                                )
                            }
                            ui.meetPickOpen = false
                        },
                    )
                }
                local.phantom.companion.feature.stream.ui.map.peers.MeetPickSheet(
                    people = rows.filter { it.eligible }.map { it.peerId to it.name },
                    canMiddle = { ids -> me != null && local.phantom.companion.feature.stream.ui.map.peers.meetMiddle(me, ids, points, nowMs) != null },
                    onInvite = { chosen, middle ->
                        val at = if (middle && me != null) local.phantom.companion.feature.stream.ui.map.peers.meetMiddle(me, chosen.map { it.first }, points, nowMs) else me
                        if (at == null) {
                            viewModel.postNotice(context.getString(R.string.map_route_no_origin))
                        } else {
                            val words = context.getString(R.string.meet_words)
                            val sent = chosen.filter { (id, _) -> viewModel.people.sendPlace(id, at.first, at.second, words) }
                            if (sent.isEmpty()) {
                                viewModel.postNotice(context.getString(R.string.peer_notice_not_sent))
                            } else {
                                ui.meeting = local.phantom.companion.feature.stream.ui.map.peers.Meeting(at.first, at.second, sent, nowMs)
                                viewModel.people.tellOnArrival(sent.map { it.first }, at.first, at.second, context.getString(R.string.peer_words_arrived))
                                viewModel.postNotice(context.getString(R.string.meet_notice, sent.joinToString(", ") { it.second }))
                            }
                        }
                        ui.meetPickOpen = false
                    },
                )
            }
        }
    }

    if (ui.myWorldOpen) {
        val world by viewModel.people.world.collectAsStateWithLifecycle()
        MapSheet(
            onDismissRequest = { ui.myWorldOpen = false },
            sheetState = myWorldSheetState,
        ) {
            local.phantom.companion.feature.stream.ui.map.peers.MyWorldSheet(
                world = world,
                onShowOnMap = { ui.myWorldOnMap = !ui.myWorldOnMap },
                onMap = ui.myWorldOnMap,
                onForget = { viewModel.people.forgetWorld() },
            )
        }
    }

    // M-A01/A02 — action sheet for a long-pressed point. Every action is
    // real: ask PHANTOM, route here (OSRM), save to Vault, share coords.
    val dp = ui.droppedPoint
    val hereState = hereDeskState
    if (dp != null) {
        MapSheet(
            onDismissRequest = { ui.droppedPoint = null },
            sheetState = pointSheetState,
        ) {
            PointActionsSheet(
                lat = dp.first,
                lon = dp.second,
                address = ui.droppedAddress,
                hereNow = hereState,
                onHereReport = { kind, reading -> hereDesk?.report(kind, reading) },
                here = operatorLocation?.let { it.lat to it.lon },
                accentColor = c.accent,
                onWhatHere = {
                    // NAV-4 — «Що тут?» opens the dossier (reverse-geocode +
                    // Overpass around + building card), not just a chat turn.
                    ui.dossierTarget = local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
                        lat = dp.first, lon = dp.second,
                    )
                    ui.droppedPoint = null
                },
                onRoute = {
                    // Обраний палцем початок («звідси маршрут») головніший за
                    // власну позицію — і згорає після одного маршруту.
                    val pick = ui.routeOriginPick
                    val from = pick?.let { it.lat to it.lon }
                        ?: operatorLocation?.let { it.lat to it.lon }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                    if (from != null) {
                        // Те саме, що й для досʼє: найкращий режим, а не «пішки».
                        viewModel.routeTo(
                            from.first, from.second, dp.first, dp.second,
                            RouteProfileChoice.forTrip(
                                from = from,
                                to = dp.first to dp.second,
                                motion = viewModel.motion.value,
                                siren = sirenLoud,
                            ),
                            // Той самий короткий підпис, яким ця точка вже
                            // зветься в аркуші; порожньо — коли адреси немає, і
                            // тоді панель покаже координати замість вигадки.
                            destName = ui.droppedAddress?.substringBefore(",")?.trim(),
                        )
                        ui.routeOriginPick = null
                    } else {
                        viewModel.postNotice(MapText.s(R.string.kt_sheet_no_pos))
                    }
                    ui.droppedPoint = null
                },
                onRouteFrom = {
                    val label = ui.droppedAddress?.substringBefore(",")?.trim()
                    ui.routeOriginPick = RouteOriginPick(dp.first, dp.second, label)
                    viewModel.postNotice(
                        context.getString(
                            R.string.map_route_origin_notice,
                            label ?: "%.5f, %.5f".format(dp.first, dp.second),
                        ),
                    )
                    ui.droppedPoint = null
                },
                // NAV-1 — only while a route is live: replan through here.
                onVia = if (routeInfo != null) {
                    {
                        viewModel.addViaPoint(dp.first, dp.second)
                        ui.droppedPoint = null
                    }
                } else null,
                // NAV-5 — manual TTL mark: the finger writes the same layer
                // PHANTOM's world.report writes (one code path, two hands).
                onMark = { kind ->
                    viewModel.reportHazard(kind, dp.first, dp.second)
                    ui.droppedPoint = null
                },
                // HORIZON III.2 — the finger summons the same generative
                // overlays PHANTOM can push (one code path, two hands).
                onIsochrone = {
                    viewModel.showShelterIsochrone(dp.first, dp.second)
                    ui.droppedPoint = null
                },
                onDangerField = {
                    viewModel.showDangerField()
                    ui.droppedPoint = null
                },
                // Імʼя і категорію назвав палець в аркуші — вигаданого
                // «Точка на мапі» брама імені однаково не пускала.
                onSaveMark = { name, category ->
                    onSavePoi?.invoke(
                        PoiEntity(
                            id = -System.currentTimeMillis(),
                            lat = dp.first,
                            lon = dp.second,
                            name = name,
                            category = category,
                            subcategory = ui.droppedAddress,
                            tags = emptyMap(),
                        ),
                    )
                    ui.droppedPoint = null
                },
                onShare = {
                    val label = ui.droppedAddress?.substringBefore(",")?.trim() ?: MapText.s(R.string.kt_sheet_point)
                    val text = local.phantom.companion.core.geo.link.MapLink.shareText(
                        local.phantom.companion.core.geo.link.MapLink.Point(dp.first, dp.second, label),
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, MapText.s(R.string.kt_sheet_share_coords)),
                        )
                    }
                    ui.droppedPoint = null
                },
                onOpenExternal = {
                    onOpenExternal?.invoke(dp.first, dp.second, ui.droppedAddress ?: MapText.s(R.string.kt_sheet_point))
                    ui.droppedPoint = null
                },
                onMeetHere = onMeetHere?.let { meet ->
                    {
                        meet(dp.first, dp.second, ui.droppedAddress?.let { pointHeadline(it).first }, null)
                        ui.droppedPoint = null
                    }
                },
                meetPicking = meetPicking,
                onSendToChat = {
                    openPlaceSend(viewModel.people, pointSendTarget(dp, ui.droppedAddress), context.getString(R.string.place_generic)) {
                        ui.placeSend = it
                    }
                    ui.droppedPoint = null
                },
                // Якір ставлять саме зараз: перша дія аркуша — назвати цю
                // землю домом, а не поїхати з неї.
                anchorLabel = ui.anchorPick?.takeIf { it.placing }?.label,
                anchorGlyph = if (ui.anchorPick?.category == SavedPlaceCategory.WORK) {
                    PhantomGlyph.WORK
                } else {
                    PhantomGlyph.HOME
                },
                onSetAnchorHere = ui.anchorPick?.takeIf { it.placing }?.let { pick ->
                    {
                        viewModel.setPlaceAnchor(pick.category, pick.label, dp.first, dp.second)
                        viewModel.postNotice(
                            context.getString(R.string.map_anchor_saved, pick.label),
                        )
                        ui.anchorPick = null
                        ui.droppedPoint = null
                    }
                },
            )
        }
    }

    // Порожній якір спитали, як його ставити. Поки [AnchorPick.placing] хибне,
    // питання на склі; істинне — аркуш пішов, слово тримає пілюля хрому.
    val anchor = ui.anchorPick
    if (anchor != null && !anchor.placing) {
        MapSheet(
            onDismissRequest = { ui.anchorPick = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AnchorPickSheet(
                label = anchor.label,
                glyph = if (anchor.category == SavedPlaceCategory.WORK) {
                    PhantomGlyph.WORK
                } else {
                    PhantomGlyph.HOME
                },
                hasFix = operatorLocation != null,
                accentColor = c.accent,
                onHere = {
                    operatorLocation?.let { fix ->
                        viewModel.setPlaceAnchor(anchor.category, anchor.label, fix.lat, fix.lon)
                        viewModel.postNotice(
                            context.getString(R.string.map_anchor_saved, anchor.label),
                        )
                    }
                    ui.anchorPick = null
                    ui.collapseSearch()
                },
                onPickOnMap = {
                    // Шторка мусить піти: під нею пів землі, а обирати треба саме її.
                    ui.anchorPick = anchor.copy(placing = true)
                    ui.collapseSearch()
                },
            )
        }
    }

    // Ламану домалювали — аркуш просить імʼя, бо без імені сховище її не візьме.
    val naming = ui.drawNaming
    if (naming != null) {
        MapSheet(
            onDismissRequest = { ui.drawNaming = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DrawNamingSheet(
                points = naming,
                accentColor = c.accent,
                onSave = { name ->
                    viewModel.saveDrawnLine(
                        label = name,
                        points = naming,
                        savedMsg = { context.getString(R.string.map_draw_saved, it) },
                        unnamedMsg = context.getString(R.string.map_draw_unnamed),
                    )
                    ui.drawNaming = null
                },
                onCancel = { ui.drawNaming = null },
            )
        }
    }

    // Дотик по збереженій лінії — її картка.
    val line = ui.selectedLine
    if (line != null) {
        MapSheet(
            onDismissRequest = { ui.selectedLine = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DrawnLineCard(
                line = line,
                accentColor = c.accent,
                onRouteAlong = {
                    viewModel.routeAlongLine(line.points)
                    ui.selectedLine = null
                },
                onEdit = {
                    // Той самий режим малювання, лише з уже покладеними вершинами.
                    ui.drawPick = DrawPick(points = line.points, editingId = line.id)
                    ui.selectedLine = null
                },
                onDelete = {
                    viewModel.deleteDrawnLine(line.id)
                    viewModel.postNotice(
                        context.getString(R.string.map_line_deleted, line.label),
                    )
                    ui.selectedLine = null
                },
            )
        }
    }

    // Чужий маршрут під пальцем: чий він, з чого складається — і єдина дія.
    //
    // Картка перечитує ЖИВИЙ список, а не тримає копію з миті дотику: інакше
    // вона пережила б лист, з якого зібрана, і пропонувала б їхати маршрутом,
    // якого на землі вже немає. Строк — межа листа, а не порада рендеру.
    val livePeerRoutes by viewModel.peerRoutes.collectAsStateWithLifecycle()
    val peerRoute = ui.selectedPeerRoute?.peerId
        ?.let { id -> livePeerRoutes.firstOrNull { it.peerId == id } }
    androidx.compose.runtime.LaunchedEffect(peerRoute) {
        if (peerRoute == null) ui.selectedPeerRoute = null
    }
    if (peerRoute != null) {
        MapSheet(
            onDismissRequest = { ui.selectedPeerRoute = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .sheetBody()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                SheetTitle(
                    local.phantom.companion.feature.stream.ui.map.peers.peerRouteLabel(
                        context.resources,
                        peerRoute.name,
                    ),
                )
                // Та сама бульбашка, що й у розмові: одне речення про чужий
                // маршрут на весь застосунок, а не друге, схоже й розбіжне.
                local.phantom.companion.feature.stream.ui.people.PeerRouteBubble(
                    route = peerRoute.route,
                    mine = false,
                    onTake = { route ->
                        val from = operatorLocation?.let { it.lat to it.lon }
                            ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                        if (from != null) viewModel.takePeerRoute(from.first, from.second, route)
                        ui.selectedPeerRoute = null
                    },
                )
            }
        }
    }

    // A tapped saved place — its own light card, straight to the actions the
    // operator wants on his own star.
    val saved = ui.selectedSavedPlace
    if (saved != null) {
        MapSheet(
            onDismissRequest = { ui.selectedSavedPlace = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SavedPlaceCard(
                place = saved,
                here = operatorLocation?.let { it.lat to it.lon },
                accentColor = c.accent,
                sendTargets = viewModel.shareTargets.collectAsStateWithLifecycle().value,
                onSendToPeer = { peerId -> viewModel.sharePlace(peerId, saved.lat, saved.lon) },
                sendHandoff = viewModel.placeHandoff.collectAsStateWithLifecycle().value,
                onSendHandoffSeen = { viewModel.consumePlaceHandoff() },
                onRoute = {
                    val pick = ui.routeOriginPick
                    val from = pick?.let { it.lat to it.lon }
                        ?: operatorLocation?.let { it.lat to it.lon }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                    if (from != null) {
                        viewModel.routeTo(
                            from.first, from.second, saved.lat, saved.lon,
                            RouteProfileChoice.forTrip(
                                from = from,
                                to = saved.lat to saved.lon,
                                motion = viewModel.motion.value,
                                siren = sirenLoud,
                            ),
                            destName = saved.name,
                        )
                        ui.routeOriginPick = null
                    } else {
                        viewModel.postNotice(MapText.s(R.string.kt_sheet_no_pos))
                    }
                    ui.selectedSavedPlace = null
                },
                onRouteFrom = {
                    ui.routeOriginPick = RouteOriginPick(saved.lat, saved.lon, saved.name)
                    viewModel.postNotice(
                        context.getString(R.string.map_route_origin_notice, saved.name),
                    )
                    ui.selectedSavedPlace = null
                },
                onRename = { newName ->
                    onRenameSavedPlace?.invoke(saved, newName)
                    ui.selectedSavedPlace = null
                },
                onRecategorize = { category ->
                    onRecategorizeSavedPlace?.invoke(saved, category)
                    ui.selectedSavedPlace = null
                },
                onDelete = {
                    onDeleteSavedPlace?.invoke(saved)
                    ui.selectedSavedPlace = null
                },
                onShare = {
                    val text = local.phantom.companion.core.geo.link.MapLink.shareText(
                        local.phantom.companion.core.geo.link.MapLink.Point(saved.lat, saved.lon, saved.name),
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(send, MapText.s(R.string.kt_sheet_share_place)))
                    }
                    ui.selectedSavedPlace = null
                },
            )
        }
    }
}

/** Скільки живе речення про надсилання місця, доки саме себе не прибере. */
private const val PLACE_HANDOFF_LIFE_MS = 6_000L

/**
 * Чим скінчився дотик — словом, і завжди словом.
 *
 * Жодної гілки без речення: мовчання на дотик і є тим, що робить застосунок
 * схожим на зламаний.
 */
@Composable
private fun placeHandoffLine(handoff: MapViewModel.PlaceHandoff): String = when (handoff) {
    is MapViewModel.PlaceHandoff.Sent ->
        stringResource(R.string.peer_place_sent, handoff.name)
    is MapViewModel.PlaceHandoff.NoKey ->
        stringResource(R.string.peer_place_no_key, handoff.name)
    MapViewModel.PlaceHandoff.NoPlace ->
        stringResource(R.string.peer_place_no_place)
}

/**
 * The light card for the operator's OWN saved place. No dossier fetch, no
 * network — the star is already known; this is the four things you do with
 * one: go there, rename it, share it, drop it. Rename opens an inline field
 * rather than a second dialog, so the whole interaction stays in one sheet.
 */
@Composable
private fun SavedPlaceCard(
    place: PoiEntity,
    here: Pair<Double, Double>?,
    accentColor: Color,
    onRoute: () -> Unit,
    onRouteFrom: () -> Unit,
    onRename: (String) -> Unit,
    onRecategorize: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    /**
     * Кому це місце можна НАДІСЛАТИ — поіменно, з вироком «чи дійде» біля
     * кожного. Порожній перелік — рядка немає зовсім: дія, що відкриває
     * порожній список, обіцяє спроможність, якої нема.
     */
    sendTargets: List<local.phantom.companion.core.net.peer.PeerLiveShare.Target> = emptyList(),
    onSendToPeer: ((String) -> Unit)? = null,
    sendHandoff: MapViewModel.PlaceHandoff? = null,
    onSendHandoffSeen: () -> Unit = {},
) {
    val c = LocalPhantomColors.current
    // Піднято зі `semantics {}`: там не композиція, тож рядок стояв літералом
    // і англомовному читачу екрана поле звалось українською.
    val placeNameLabel = stringResource(R.string.map_sheet_place_name)
    // Видалення — його дані назавжди, тож між пальцем і рядком стоїть друге слово.
    var confirmingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var draft by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(place.name)
    }
    val nameFocus = androidx.compose.runtime.remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(renaming) {
        if (renaming) nameFocus.requestFocus()
    }
    Column(
        modifier = Modifier
            .sheetBody()
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Мальований значок, не ⭐: емодзі несло власний колір і власний
            // кегль посеред однотонних іконок аркуша.
            Icon(
                PhantomGlyph.KEEP.asImageVector(),
                contentDescription = stringResource(R.string.map_sheet_saved_place),
                tint = c.accentText,
                modifier = Modifier.size(SAVED_GLYPH),
            )
            Spacer(Modifier.width(PhantomSpace.s2))
            if (renaming) {
                SheetField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = placeNameLabel,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { draft.trim().takeIf { it.isNotEmpty() }?.let(onRename) },
                    ),
                    fieldModifier = Modifier
                        .focusRequester(nameFocus)
                        .semantics { contentDescription = placeNameLabel },
                )
            } else {
                SheetTitle(place.name, modifier = Modifier.weight(1f))
            }
        }
        SheetNote("${"%.5f".format(place.lat)}, ${"%.5f".format(place.lon)}", tnum = true)
        // Відстань лише коли є власна позиція — без неї рядка немає, не «0 м».
        distanceLabel(here, place.lat, place.lon)?.let {
            SheetNote(stringResource(R.string.map_sheet_distance_from_you, it), tnum = true)
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        if (renaming) {
            val name = draft.trim()
            if (name.isEmpty()) {
                // Раніше кнопка була, а натиск не робив нічого — тепер причина
                // сказана словом, а дії немає.
                SheetNote(stringResource(R.string.map_sheet_name_empty))
            } else {
                PointRow(
                    PhantomGlyph.CONFIRM.asImageVector(),
                    stringResource(R.string.map_sheet_name_save),
                    { onRename(name) },
                    c.accentText,
                )
            }
            PointRow(
                PhantomGlyph.DISMISS.asImageVector(),
                stringResource(R.string.map_sheet_name_cancel),
                {
                    draft = place.name
                    renaming = false
                },
                c.text2,
            )
        } else {
            MapPrimaryAction(
                PhantomGlyph.ROUTE_TO.asImageVector(),
                stringResource(R.string.map_sheet_route_here),
                onRoute,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            PointRow(
                PhantomGlyph.ROUTE_FROM.asImageVector(),
                stringResource(R.string.map_sheet_route_from),
                onRouteFrom,
                c.accentText,
            )
            PointRow(
                PhantomGlyph.RENAME.asImageVector(),
                stringResource(R.string.map_sheet_rename),
                { renaming = true },
                c.accentText,
            )
            PointRow(
                PhantomGlyph.HANDOUT.asImageVector(),
                stringResource(R.string.map_sheet_share_place),
                onShare,
                c.accentText,
            )
            // Надіслати місце ЛЮДИНІ, а не назовні.
            //
            // Рядок вище віддає точку чужим застосункам андроїдовим «поділитись»;
            // цей кладе кадр у розмову тим самим дротом, яким їде маршрут. Дві
            // різні дії, і жодна з них не заступає другу: досі існувала лише
            // перша, тож «поділитись місцем» у застосунку для меша означало
            // «віддати точку назовні».
            if (onSendToPeer != null && sendTargets.isNotEmpty()) {
                var picking by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                PointRow(
                    PhantomGlyph.HANDOUT.asImageVector(),
                    stringResource(R.string.map_sheet_send_place),
                    { picking = !picking },
                    c.accentText,
                )
                if (sendHandoff != null) {
                    // Речення живе стільки, скільки триває подія: «надіслано»,
                    // що висить до закриття аркуша, бреше про теперішнє.
                    androidx.compose.runtime.LaunchedEffect(sendHandoff) {
                        kotlinx.coroutines.delay(PLACE_HANDOFF_LIFE_MS)
                        onSendHandoffSeen()
                    }
                    SheetNote(
                        placeHandoffLine(sendHandoff),
                        color = if (sendHandoff is MapViewModel.PlaceHandoff.Sent) c.success else c.danger,
                    )
                }
                if (picking) {
                    sendTargets.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PhantomTouchFloor)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    picking = false
                                    onSendToPeer(peer.peerId)
                                }
                                .padding(horizontal = PhantomSpace.s2),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SheetBody(peer.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                            SheetSmall(
                                stringResource(
                                    when (peer.reach) {
                                        local.phantom.companion.core.net.peer.PeerLiveShare.Reach.MESH ->
                                            R.string.peer_share_reach_nearby
                                        local.phantom.companion.core.net.peer.PeerLiveShare.Reach.RELAY ->
                                            R.string.peer_share_reach_relay
                                        else -> R.string.peer_share_reach_queued
                                    },
                                ),
                                color = c.text2,
                            )
                        }
                    }
                }
            }
            // Категорія — прикметник мітки: дім і робота стають якорями
            // («додому» в транспорті спирається саме на них).
            val current = SavedPlaceCategory.canonical(place.category)
            Spacer(Modifier.height(PhantomSpace.s1))
            CategorySegments(current) { cat -> if (current != cat) onRecategorize(cat) }
            Spacer(Modifier.height(PhantomSpace.s1))
            if (confirmingDelete) {
                SheetNote(
                    stringResource(R.string.map_sheet_delete_confirm_q, place.name),
                    color = c.danger,
                )
                PointRow(
                    PhantomGlyph.DISCARD.asImageVector(),
                    stringResource(R.string.map_sheet_delete_yes),
                    onDelete,
                    c.danger,
                    labelColor = c.danger,
                )
                PointRow(
                    PhantomGlyph.DISMISS.asImageVector(),
                    stringResource(R.string.map_sheet_delete_no),
                    { confirmingDelete = false },
                    c.text2,
                )
            } else {
                PointRow(
                    PhantomGlyph.DISCARD.asImageVector(),
                    stringResource(R.string.map_sheet_delete),
                    { confirmingDelete = true },
                    c.danger,
                    labelColor = c.danger,
                )
            }
        }
    }
}
