package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.poi.PoiEntity
import androidx.compose.foundation.shape.CircleShape
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.feature.stream.ui.map.sheetSegment
import local.phantom.companion.feature.stream.ui.map.sheetSegmentInk
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph

/** Знак родини в рядку «всередині» — врівні з написом поруч. */
private val INSIDE_GLYPH = 16.dp

/**
 * ТРЦ, пасажі, вокзали, кластери — інтерактивний каталог закладів з розбиттям за категоріями,
 * поверхами та швидким фокусом на мапі.
 */
internal data class InsidePlace(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: DossierCategory,
    val categoryHint: String?,
    val subcategoryHint: String?,
    /** OSM `level` — поверх розміщення. */
    val level: String?,
)

internal data class InsideGroup(val category: DossierCategory, val places: List<InsidePlace>)

internal fun insidePlaceOf(poi: PoiEntity): InsidePlace = InsidePlace(
    id = poi.id,
    name = poi.name,
    lat = poi.lat,
    lon = poi.lon,
    category = dossierCategoryOf(poi.tags, poi.category, poi.subcategory),
    categoryHint = poi.category,
    subcategoryHint = poi.subcategory,
    level = poi.tags["level"]?.takeIf { it.isNotBlank() },
)

internal fun groupInside(places: List<InsidePlace>): List<InsideGroup> =
    places.distinctBy { it.id }
        .groupBy { it.category }
        .map { (category, list) -> InsideGroup(category, list.sortedBy { it.name.lowercase() }) }
        .sortedWith(compareByDescending<InsideGroup> { it.places.size }.thenBy { it.category.labelUk })

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DossierInsideSection(
    dossier: PlaceDossier,
    accent: Color,
    onRetarget: (DossierTarget) -> Unit,
) {
    if (dossier.inside.isEmpty()) return
    val c = LocalPhantomColors.current
    var expanded by remember(dossier.target) { mutableStateOf(true) } // Open by default for immediate utility
    val count = dossier.inside.size
    val levels = dossier.building?.levels

    var selectedCategoryFilter by remember(dossier.target) { mutableStateOf<DossierCategory?>(null) }
    var selectedLevelFilter by remember(dossier.target) { mutableStateOf<String?>(null) }

    val availableCategories = remember(dossier.inside) {
        dossier.inside.map { it.category }.distinct().sortedBy { it.labelUk }
    }
    val availableLevels = remember(dossier.inside) {
        dossier.inside.mapNotNull { it.level }.distinct().sorted()
    }

    val filteredPlaces = remember(dossier.inside, selectedCategoryFilter, selectedLevelFilter) {
        dossier.inside.filter { place ->
            (selectedCategoryFilter == null || place.category == selectedCategoryFilter) &&
                (selectedLevelFilter == null || place.level == selectedLevelFilter)
        }.sortedBy { it.name.lowercase() }
    }

    val title = stringResource(R.string.dossier_inside_title)
    val summary = buildList {
        add(pluralStringResource(R.plurals.dossier_inside_places, count, count))
        levels?.let { add(pluralStringResource(R.plurals.dossier_inside_levels, it, it)) }
    }.joinToString(" · ")

    Spacer(Modifier.height(PhantomSpace.s2))

    // Заголовок групи — піднятий рядок із тим самим знаком, що й на мапі.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = PhantomSpace.s3, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: $summary" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        PhantomGlyphIcon(
            glyph = PhantomGlyph.SHOP,
            tint = c.text2,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            SheetBody(stringResource(R.string.mapl_b_inside_header, count), fontWeight = FontWeight.SemiBold, tnum = true)
            SheetNote(summary, tnum = true)
        }
        DossierDisclosureChevron(expanded, c.text2)
    }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(top = PhantomSpace.s2)) {
            if (availableCategories.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val isAllSelected = selectedCategoryFilter == null
                    InsideFilterPill(
                        label = stringResource(R.string.mapl_b_inside_all_count, dossier.inside.size),
                        glyph = PhantomGlyph.STAR,
                        selected = isAllSelected,
                        onClick = { selectedCategoryFilter = null },
                    )

                    availableCategories.forEach { cat ->
                        val isSelected = selectedCategoryFilter == cat
                        val catCount = dossier.inside.count { it.category == cat }
                        InsideFilterPill(
                            label = "${cat.labelUk} ($catCount)",
                            glyph = cat.glyph(),
                            selected = isSelected,
                            onClick = { selectedCategoryFilter = if (isSelected) null else cat },
                        )
                    }
                }
            }

            if (availableLevels.size > 1) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val isAllLvl = selectedLevelFilter == null
                    InsideFilterPill(
                        label = stringResource(R.string.mapl_b_inside_all_floors),
                        glyph = null,
                        selected = isAllLvl,
                        onClick = { selectedLevelFilter = null },
                    )

                    availableLevels.forEach { lvl ->
                        val isSelected = selectedLevelFilter == lvl
                        val lvlCount = dossier.inside.count { it.level == lvl }
                        val label = floorLabel(lvl)
                        InsideFilterPill(
                            label = "$label ($lvlCount)",
                            glyph = null,
                            selected = isSelected,
                            onClick = { selectedLevelFilter = if (isSelected) null else lvl },
                        )
                    }
                }
            }

            Spacer(Modifier.height(PhantomSpace.s2))

            // Venue Items List
            filteredPlaces.forEach { place ->
                InsideRow(place, dossier, accent, onRetarget)
            }
        }
    }
}

/** Пігулка фільтра: обрана — мʼякий бурштин, решта — волосина `line` і чорнило `text2`. */
@Composable
private fun InsideFilterPill(
    label: String,
    glyph: PhantomGlyph?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val ink = sheetSegmentInk(selected)
    Row(
        modifier = Modifier
            .then(
                if (selected) Modifier.sheetSegment(true)
                else Modifier.clip(CircleShape).border(1.dp, c.lineStrong, CircleShape),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (glyph != null) {
            PhantomGlyphIcon(glyph = glyph, tint = ink, modifier = Modifier.size(INSIDE_GLYPH))
        }
        SheetNote(
            label,
            color = ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            tnum = true,
        )
    }
}

@Composable
private fun floorLabel(level: String): String {
    val display = when (level) {
        "0" -> "1"
        "1" -> "2"
        "2" -> "3"
        "-1" -> "-1"
        else -> level
    }
    return stringResource(R.string.mapl_b_inside_floor, display)
}

@Composable
private fun InsideRow(
    place: InsidePlace,
    dossier: PlaceDossier,
    accent: Color,
    onRetarget: (DossierTarget) -> Unit,
) {
    val c = LocalPhantomColors.current
    val floorText = place.level?.let { floorLabel(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button) {
                onRetarget(
                    DossierTarget(
                        lat = place.lat,
                        lon = place.lon,
                        nameHint = place.name,
                        categoryHint = place.categoryHint,
                        subcategoryHint = place.subcategoryHint,
                    ),
                )
            }
            .padding(horizontal = PhantomSpace.s1, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(place.name, floorText).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        PhantomGlyphIcon(
            glyph = place.category.glyph(),
            tint = c.text2,
            modifier = Modifier.size(INSIDE_GLYPH),
        )

        // Venue Name & Category
        Column(Modifier.weight(1f)) {
            SheetBody(place.name, fontWeight = FontWeight.Medium, maxLines = 1)
            place.subcategoryHint?.let { sub ->
                SheetSmall(sub.replaceFirstChar { it.uppercase() }, color = c.text2)
            }
        }

        // Поверх — тиха пігулка, не бурштин: бурштин на аркуші означає дію.
        if (floorText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(PhantomRadius.chip))
                    .background(c.overlay)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                SheetSmall(floorText, color = c.text2, tnum = true)
            }
        }

        SheetSmall(stringResource(R.string.mapl_b_inside_on_map), color = c.accentText)
    }
}
