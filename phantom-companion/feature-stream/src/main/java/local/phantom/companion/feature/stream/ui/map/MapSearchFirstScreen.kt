package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import local.phantom.companion.core.data.places.DrawnLine
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.core.design.R as DesignR

/** Показується 5; зберігається все — це поріг ПОКАЗУ, не даних. */
internal const val RECENT_SHOWN = 5

/** Скільки власних міток лягає на перший екран під якорями. */
private const val SAVED_SHOWN = 4

/**
 * Якір оператора: дім і робота — не «улюблене», а два місця, від яких
 * рахується решта. Категорія тут ЄДИНА ознака: `transit.home` шукає
 * рядок із `category == home`, а не з іменем «Дім», тож англомовний
 * оператор із міткою «Home» лишається знайденим.
 */
internal data class SearchAnchor(
    val category: String,
    val glyph: PhantomGlyph,
    val label: String,
    val place: PoiEntity?,
)

/**
 * Обидва якорі стоять ЗАВЖДИ — і порожній теж. Ховати невстановлений
 * означало б, що поставити його ніде: саме так «Дім» і лишався
 * недосяжним із дотику, бо єдиний шлях збереження зашивав «улюблене».
 */
internal fun searchAnchors(
    saved: List<PoiEntity>,
    homeLabel: String,
    workLabel: String,
): List<SearchAnchor> = listOf(
    SavedPlaceCategory.HOME to (PhantomGlyph.HOME to homeLabel),
    SavedPlaceCategory.WORK to (PhantomGlyph.WORK to workLabel),
).map { (category, face) ->
    val (glyph, label) = face
    SearchAnchor(
        category = category,
        glyph = glyph,
        label = label,
        // `category` у PoiEntity вже канонізований (MapPoiSearch.asPin), тож
        // порівняння тут пряме, без повторного нормалізування.
        place = saved.firstOrNull { it.category == category },
    )
}

@Composable
internal fun rememberAnchors(saved: List<PoiEntity>): List<SearchAnchor> = searchAnchors(
    saved = saved,
    homeLabel = stringResource(R.string.map_search_anchor_home),
    workLabel = stringResource(R.string.map_search_anchor_work),
)

/**
 * Перший екран — те, що видно ДО жодної літери.
 *
 * Порядок тут і є відповіддю на «що людині треба з мапи найчастіше»:
 * два якорі, тоді власні сліди (нещодавнє, мітки), тоді вісім категорій
 * воєнного побуту. Решта каталогу чекає за «всі категорії» — стіна
 * ярликів це не вибір, це робота для ока.
 *
 * СИРЕНА-4 — коли своїх слідів нема ЖОДНОГО (нещодавнє, мітки й лінії всі
 * порожні), ці вісім категорій встають одразу під якорями, а не в кінці
 * списку: свіжий оператор інакше бачив три способи сказати «нема нічого»
 * («Дім — задати», «Робота — задати», «Ліній ще нема») і жодної кнопки,
 * яка щось робить, доти, доки не прокрутить повз усі порожні.
 */
internal fun LazyListScope.searchFirstScreen(
    anchors: List<SearchAnchor>,
    recent: List<String>,
    saved: List<PoiEntity>,
    here: Pair<Double, Double>?,
    allCategories: Boolean,
    allSaved: Boolean,
    onAnchorTap: (SearchAnchor) -> Unit,
    onAnchorSet: (SearchAnchor) -> Unit,
    onPickCategory: (DiscoveryCat) -> Unit,
    onToggleCategories: () -> Unit,
    onToggleSaved: () -> Unit,
    onSavedTap: (PoiEntity) -> Unit,
    onRouteDirect: ((lat: Double, lon: Double, name: String) -> Unit)?,
    onPickRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onAskAi: (String) -> Unit = {},
    lines: List<DrawnLine> = emptyList(),
    onDraw: () -> Unit = {},
    onLineTap: (DrawnLine) -> Unit = {},
    inUkraine: Boolean = false,
) {
    val otherSaved = saved.filter { it.category !in ANCHOR_CATEGORIES }
    val nothingOfOwn = recent.isEmpty() && otherSaved.isEmpty()
    val categoriesBlock: LazyListScope.() -> Unit = {
        if (allCategories) {
            MapDiscovery.byGroupFor(inUkraine).forEach { (group, cats) ->
                item(key = "group-${group.name}") { SectionLabel(group.title, tight = true) }
                item(key = "group-cats-${group.name}") {
                    CategoryGrid(cats = cats, onPick = onPickCategory)
                }
            }
        } else {
            item(key = "cats-head") { SectionLabel(stringResource(R.string.map_search_quick)) }
            item(key = "cats") {
                CategoryGrid(
                    MapDiscovery.shortlistFor(inUkraine),
                    onPickCategory,
                )
            }
        }
        item(key = "cats-toggle") {
            ShelfRow {
                MapChip(
                    label = stringResource(
                        if (allCategories) R.string.map_search_categories_less
                        else R.string.map_search_categories_all,
                    ),
                    onClick = onToggleCategories,
                )
            }
        }
    }

    item(key = "anchors") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhantomSpace.gutterPhone, vertical = PhantomSpace.s2),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            anchors.forEach { anchor ->
                AnchorCard(
                    anchor = anchor,
                    here = here,
                    modifier = Modifier.weight(1f),
                    onTap = { onAnchorTap(anchor) },
                    onSet = { onAnchorSet(anchor) },
                )
            }
        }
    }

    if (nothingOfOwn) categoriesBlock()

    if (recent.isNotEmpty()) {
        item(key = "recent-head") { RecentHeader(onClearRecent) }
        val shownRecent = recent.take(RECENT_SHOWN)
        itemsIndexed(shownRecent, key = { _, it -> "recent-$it" }) { i, q ->
            LineRow(title = q, subtitle = null, index = i, count = shownRecent.size) { onPickRecent(q) }
        }
    }

    if (otherSaved.isEmpty()) {
        item(key = "saved-empty") {
            Column {
                SectionLabel(stringResource(R.string.map_search_saved))
                EmptyLine(stringResource(R.string.map_search_saved_empty))
            }
        }
    }
    if (otherSaved.isNotEmpty()) {
        item(key = "saved-head") { SectionLabel(stringResource(R.string.map_search_saved)) }
        val shownSaved = if (allSaved) otherSaved else otherSaved.take(SAVED_SHOWN)
        itemsIndexed(shownSaved, key = { _, it -> "saved-${it.id}" }) { i, p ->
            LineRow(
                title = p.name,
                subtitle = distanceLabel(here, p.lat, p.lon),
                leading = { MapIcon(DesignR.drawable.lc_bookmark) },
                index = i,
                count = shownSaved.size,
                onRoute = onRouteDirect?.let { fn -> { fn(p.lat, p.lon, p.name) } },
            ) { onSavedTap(p) }
        }
        if (otherSaved.size > SAVED_SHOWN) {
            item(key = "saved-toggle") {
                ShelfRow {
                    MapChip(
                        label = if (allSaved) {
                            stringResource(R.string.map_search_saved_less)
                        } else {
                            stringResource(R.string.map_search_saved_all, otherSaved.size)
                        },
                        onClick = onToggleSaved,
                    )
                }
            }
        }
    }

    if (!nothingOfOwn) categoriesBlock()
}

private val ANCHOR_CATEGORIES = setOf(SavedPlaceCategory.HOME, SavedPlaceCategory.WORK)
internal val LINES_EMPTY_RES = R.string.map_lines_empty

/** Порожньо, але словами: footnote text3 під підписом секції, без картки. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = PhantomText.footnote,
        color = LocalPhantomColors.current.text3,
        modifier = Modifier.padding(
            start = PhantomSpace.gutterPhone + PhantomSpace.s4,
            end = PhantomSpace.gutterPhone,
            bottom = PhantomSpace.s2,
        ),
    )
}

/**
 * Порожній якір не ховається і не вдає повний: він показує те саме імʼя і
 * СЛОВОМ каже, чого бракує та що станеться від дотику. Мовчазна сіра
 * картка змусила б гадати, чи вона зламана.
 */
@Composable
private fun AnchorCard(
    anchor: SearchAnchor,
    here: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onSet: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val place = anchor.place
    val subtitle = place?.let { anchorSubtitle(here, it.lat, it.lon) }
        ?: stringResource(R.string.map_search_anchor_set)
    val spoken = if (place == null) {
        stringResource(R.string.map_search_anchor_set_desc, anchor.label)
    } else {
        listOfNotNull(anchor.label, subtitle).joinToString(", ")
    }
    Row(
        modifier = modifier
            .heightIn(min = SearchMetrics.AnchorMin)
            .clip(CardShape)
            .background(c.overlay)
            .clickable(role = Role.Button) { if (place == null) onSet() else onTap() }
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                role = Role.Button
            }
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        PhantomGlyphIcon(
            glyph = anchor.glyph,
            // Заданий якір — місце, до якого ведуть; незаданий — лише назва.
            tint = if (place == null) c.text3 else c.text2,
            modifier = Modifier.size(PhantomSize.iconL),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = place?.name ?: anchor.label,
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                // «задати» — стан, не заклик: бурштином він кричав нарівні з «Маршрут».
                style = PhantomText.footnote,
                color = c.text3,
                maxLines = 1,
            )
        }
    }
}

/** Дві колонки: ярлик категорії читається з руки, а не з лупи. */
@Composable
private fun CategoryGrid(cats: List<DiscoveryCat>, onPick: (DiscoveryCat) -> Unit) {
    if (cats.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.gutterPhone),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        cats.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                pair.forEach { cat ->
                    CategoryCard(cat, Modifier.weight(1f)) { onPick(cat) }
                }
                // Непарний хвіст не має розтягувати останню картку на дві колонки.
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Плитка категорії — та сама `overlay`-плитка, що й «Поруч» в аркуші «Шари». */
@Composable
private fun CategoryCard(cat: DiscoveryCat, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .heightIn(min = MapRowHeight)
            .clip(CardShape)
            .background(c.overlay)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        PhantomGlyphIcon(
            glyph = cat.glyph,
            tint = c.text2,
            modifier = Modifier.size(PhantomSize.iconM),
        )
        Text(
            text = cat.label,
            style = PhantomText.subhead,
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun anchorSubtitle(here: Pair<Double, Double>?, lat: Double, lon: Double): String? {
    val (hLat, hLon) = here ?: return null
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat - hLat)
    val dLon = Math.toRadians(lon - hLon)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(hLat)) * Math.cos(Math.toRadians(lat)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val distM = 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    return if (distM <= ANCHOR_LOCAL_LIMIT_M) {
        val mins = (distM / ANCHOR_METRES_PER_MIN).roundToInt().coerceAtLeast(1)
        "$mins min"
    } else {
        val km = (distM / 1_000.0).roundToInt()
        "$km km"
    }
}

private const val ANCHOR_LOCAL_LIMIT_M = 30_000.0
private const val ANCHOR_METRES_PER_MIN = 250.0

private val CardShape = RoundedCornerShape(PhantomRadius.card)
