package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import local.phantom.companion.core.data.places.DrawnLine
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.design.layout.bottomChromeInsets
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.R as DesignR

/**
 * Пошук — шторка на півекрана, а не другий екран поверх мапи.
 *
 * Було: `fillMaxSize` + непрозоре тло + `focus.requestFocus()` на старті.
 * Тобто мапа зникала рівно тоді, коли по ній збирались щось знайти, а
 * клавіатура зʼїдала половину того, що лишилось, ще до першої літери.
 *
 * Стало: половина екрана знизу, мапа над нею — жива, з камерою і тапами.
 * Клавіатури немає, доки її не попросять дотиком по полю; тоді шторка йде
 * на повну висоту, бо там уже потрібен список, а не земля.
 *
 * Порядок першого екрана — це відповідь на «куди я ходжу»: два якорі
 * (дім і робота), свої сліди, вісім категорій воєнного побуту. Набір
 * лишає конвеєр пошуку недоторканим — офлайн-довідник і чесні відмови ті самі.
 */
@Composable
internal fun MapSearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    inFlight: Boolean,
    submitted: Boolean,
    gap: SearchGap?,
    /** Країна під якорем — рядок називає країну лише тоді, коли вона інша. */
    hereCountry: String?,
    missingPack: MissingPack? = null,
    onOpenPacks: (() -> Unit)? = null,
    results: List<NominatimResult>,
    offlineHits: List<PoiHit>,
    recent: List<String>,
    saved: List<PoiEntity>,
    here: Pair<Double, Double>?,
    alongRoute: Boolean,
    onPickCategory: (selector: String, label: String) -> Unit,
    onSearchHere: (selector: String, label: String) -> Unit,
    /** Brand/name flood — «Нова Пошта», «АТБ» → every match on the map. */
    onFloodByName: (String) -> Unit,
    onResultTap: (NominatimResult) -> Unit,
    onOfflineTap: (PoiHit) -> Unit,
    onSavedTap: (PoiEntity) -> Unit,
    onRouteDirect: ((lat: Double, lon: Double, name: String) -> Unit)? = null,
    onAskAi: (String) -> Unit,
    onPickRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    /** Дім/робота: пише ЯКІР — категорію, на яку спирається `transit.home`. */
    onSetAnchor: (category: String, label: String) -> Unit,
    onOpenTransitHub: (() -> Unit)? = null,
    /** Намальовані ламані оператора та вхід у режим малювання. */
    lines: List<DrawnLine> = emptyList(),
    onDraw: () -> Unit = {},
    onLineTap: (DrawnLine) -> Unit = {},
    inUkraine: Boolean = false,
) {
    val c = LocalPhantomColors.current
    val sheet = rememberSearchSheetState()
    var allCategories by remember { mutableStateOf(false) }
    var allSaved by remember { mutableStateOf(false) }
    val trimmed = query.trim()
    val intent = remember(query) { MapDiscovery.resolve(query) }
    val suggestedCats = remember(trimmed) { typeAheadCategories(trimmed) }
    val anchors = rememberAnchors(saved)

    // Літера прийшла ззовні (напр. з кокпіта) — списку потрібна вся висота.
    LaunchedEffect(trimmed.isNotEmpty()) {
        if (trimmed.isNotEmpty()) sheet.detent = SearchDetent.FULL
    }

    SearchSheetFrame(
        state = sheet,
        onDismiss = onClose,
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PhantomSpace.gutterPhone,
                        end = PhantomSpace.s2,
                        top = PhantomSpace.s1,
                        bottom = PhantomSpace.s2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
            ) {
                // Те саме поле, що лежить на мапі ([MapSearchField]): радіус
                // контрола, значок lc_search, підказка text3 — лише на аркуші
                // воно стоїть на `overlay`, бо аркуш сам уже `raised`.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = PhantomSize.input)
                        .clip(RoundedCornerShape(PhantomRadius.control))
                        .background(c.overlay)
                        .padding(start = PhantomSpace.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                ) {
                    MapIcon(DesignR.drawable.lc_search, tint = c.text3)
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            // Клавіатура забирає низ екрана — шторка мусить
                            // піднятись НАД нею, інакше список лишиться під склом.
                            // Фокус НЕ просимо самі: клавіатура на відкритті
                            // зʼїдала рівно ту мапу, заради якої шторка коротка.
                            .onFocusChanged { if (it.isFocused) sheet.detent = SearchDetent.FULL },
                        textStyle = PhantomText.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.map_search_hint),
                                    style = PhantomText.body,
                                    color = c.text3,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        },
                    )
                    if (inFlight) {
                        // Слово замість кільця: воно називає стан і зупиняється
                        // разом із запитом, а не крутиться вічно. Стан, не дія —
                        // тож text2, а не бурштин.
                        Text(
                            text = stringResource(R.string.map_search_busy),
                            style = PhantomText.footnote,
                            color = c.text2,
                            maxLines = 1,
                            modifier = Modifier.padding(end = PhantomSpace.s4),
                        )
                    } else if (query.isNotEmpty()) {
                        MapRowAction(
                            icon = DesignR.drawable.lc_close,
                            contentDescription = stringResource(R.string.map_search_clear_query),
                            onClick = { onQueryChange("") },
                            tint = c.text3,
                        )
                    }
                }
                CloseRound(stringResource(R.string.map_search_close), onClose)
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Один резерв низу: union рейки та IME (per-axis MAX, ніколи сума).
                .windowInsetsPadding(bottomChromeInsets()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = PhantomSpace.s4),
            ) {
                if (trimmed.isEmpty()) {
                    searchFirstScreen(
                        anchors = anchors,
                        recent = recent,
                        saved = saved,
                        here = here,
                        allCategories = allCategories,
                        allSaved = allSaved,
                        // Дотик по встановленому якорю ВЕДЕ додому/на роботу, а
                        // не відкриває досьє: якір і є «одне торкання — маршрут».
                        // Картка мітки лишається на зірці на самій мапі.
                        onAnchorTap = { a ->
                            a.place?.let { p ->
                                onRouteDirect?.invoke(p.lat, p.lon, p.name) ?: onSavedTap(p)
                            }
                        },
                        onAnchorSet = { onSetAnchor(it.category, it.label) },
                        onPickCategory = { cat ->
                            if (cat.transitHub && onOpenTransitHub != null) onOpenTransitHub()
                            else onPickCategory(cat.selector, cat.label)
                        },
                        onToggleCategories = { allCategories = !allCategories },
                        onToggleSaved = { allSaved = !allSaved },
                        onSavedTap = onSavedTap,
                        onRouteDirect = onRouteDirect,
                        onPickRecent = onPickRecent,
                        onClearRecent = onClearRecent,
                        onAskAi = onAskAi,
                        lines = lines,
                        onDraw = onDraw,
                        onLineTap = onLineTap,
                        inUkraine = inUkraine,
                    )
                } else {
                    searchResults(
                        trimmed = trimmed,
                        intent = intent,
                        suggestedCats = suggestedCats,
                        results = results,
                        offlineHits = offlineHits,
                        saved = saved,
                        here = here,
                        alongRoute = alongRoute,
                        inFlight = inFlight,
                        submitted = submitted,
                        gap = gap,
                        hereCountry = hereCountry,
                        missingPack = missingPack,
                        onOpenPacks = onOpenPacks,
                        onPickCategory = onPickCategory,
                        onSearchHere = onSearchHere,
                        onFloodByName = onFloodByName,
                        onResultTap = onResultTap,
                        onOfflineTap = onOfflineTap,
                        onSavedTap = onSavedTap,
                        onRouteDirect = onRouteDirect,
                        onAskAi = onAskAi,
                    )
                }
            }
        }
    }
}

/** Type-ahead підказки категорій — окремий каталог від `MapDiscovery`. */
private fun typeAheadCategories(
    trimmed: String,
): List<local.phantom.companion.core.nav.OsmSpatialUniverse.SpatialCategory> {
    if (trimmed.length < 2) return emptyList()
    return local.phantom.companion.core.nav.OsmSpatialUniverse.catalog.filter { cat ->
        cat.labelUk.contains(trimmed, ignoreCase = true) ||
            cat.keywords.any { it.contains(trimmed, ignoreCase = true) } ||
            trimmed.contains(cat.labelUk, ignoreCase = true)
    }.take(3)
}

/** «Закрити» — кругла `overlay`-кнопка поруч із полем: ціль 48, диск 40. */
@Composable
private fun CloseRound(cd: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .size(MapTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PhantomSize.buttonM)
                .clip(CircleShape)
                .background(c.overlay),
            contentAlignment = Alignment.Center,
        ) {
            MapIcon(DesignR.drawable.lc_close, tint = c.text)
        }
    }
}
