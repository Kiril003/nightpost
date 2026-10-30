package local.phantom.companion.feature.stream.ui.map.dossier

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetChevron
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetPill
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.feature.stream.ui.map.transit.TransitModeTint
import local.phantom.companion.core.design.R as DesignR

/**
 * Зупинка в досьє: зручності, лінії з відліками, пересадки, години роботи.
 *
 * 03.10 — шкіра v1.2. Бірюзова рамка секції, небесні кнопки й фіолетові
 * пересадки пішли: на денному аркуші вони не читались зовсім. Колір лишився
 * там, де він несе дані, — бейдж лінії (правдивий колір джерела або тон виду
 * з [TransitModeTint]). Найближчий відлік — `success`, «не курсує» — `danger`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DossierTransitSection(
    dossier: PlaceDossier,
    accent: Color,
    onShowLine: (PlaceDossierClient.RouteLineRef) -> Unit,
    onShowLocalLine: ((DossierTransitLine) -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    val tags = dossier.tags
    val isTransit = looksLikeTransitStop(tags, dossier.target) || dossier.transit != null || dossier.transitLines.isNotEmpty()
    if (!isTransit) return

    val transit = dossier.transit
    val rawLines = dossier.transitLines
    var expandedLineKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PhantomSpace.s2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            modifier = Modifier.padding(bottom = PhantomSpace.s2),
        ) {
            PhantomGlyphIcon(glyph = PhantomGlyph.BUS, tint = c.text2, modifier = Modifier.size(18.dp))
            SheetSection(stringResource(R.string.map_sheets_stop_lines_title))
        }

        // 1. Amenities & Facilities
        val benchLabel = stringResource(R.string.mapl_e_amenity_bench)
        val shelterLabel = stringResource(R.string.mapl_e_amenity_shelter)
        val displayLabel = stringResource(R.string.mapl_e_amenity_display)
        val tactileLabel = stringResource(R.string.mapl_e_comfort_tactile)
        val binLabel = stringResource(R.string.mapl_e_amenity_bin)
        val litLabel = stringResource(R.string.mapl_e_amenity_lit)
        val wheelchairLabel = stringResource(R.string.mapl_e_comfort_wheelchair)
        val amenities = buildList {
            if (tags["bench"] == "yes") add(benchLabel)
            if (tags["shelter"] == "yes") add(shelterLabel)
            if (tags["passenger_information_display"] == "yes") add(displayLabel)
            if (tags["tactile_paving"] == "yes") add(tactileLabel)
            if (tags["bin"] == "yes") add(binLabel)
            if (tags["lit"] == "yes") add(litLabel)
            if (tags["wheelchair"] == "yes") add(wheelchairLabel)
        }
        if (amenities.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = PhantomSpace.s3),
            ) {
                amenities.forEach { text -> SheetPill(text) }
            }
        }

        // Вартості проїзду тут НЕМАЄ навмисно: ані OSM, ані фіди не несуть
        // поля ціни, а «Проїзд: 8.00 ₴ • Kyiv Smart Card…» був літералом на
        // кожній зупинці країни («звідки воно 8 грн взяло?» — власник).
        // Немає джерела — немає рядка.

        // Lines List & Departures
        if (transit != null && !transit.isEmpty) {
            if (transit.bothDirections) {
                SheetNote(
                    stringResource(R.string.mapl_e_both_directions),
                    modifier = Modifier.padding(bottom = PhantomSpace.s2),
                )
            }

            transit.lines.forEach { line ->
                val lineKey = "${line.ref}_${line.mode}_${line.direction}"
                val isExpanded = expandedLineKey == lineKey
                val relation = line.relationId?.let { id ->
                    dossier.transitLines.firstOrNull { it.relationId == id }
                }
                val onShowMap: (() -> Unit)? = when {
                    relation != null -> ({ onShowLine(relation) })
                    line.shape != null && onShowLocalLine != null -> ({ onShowLocalLine(line) })
                    else -> null
                }

                TransitLineCard(
                    line = line,
                    accent = accent,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedLineKey = if (isExpanded) null else lineKey
                    },
                    onShowOnMap = onShowMap,
                )
                Spacer(Modifier.height(PhantomSpace.s2))
            }

            // Metro Interchanges
            transit.interchanges.forEach { link ->
                val text = stringResource(
                    R.string.dossier_metro_interchange,
                    link.station,
                    link.lineRef,
                    link.walkMinutes,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = PhantomSpace.s1)
                        .mapRaised()
                        .padding(horizontal = PhantomSpace.s3, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                ) {
                    PhantomGlyphIcon(
                        glyph = PhantomGlyph.METRO,
                        tint = TransitModeTint.of("subway"),
                        modifier = Modifier.size(GLYPH),
                    )
                    SheetBody(text, modifier = Modifier.weight(1f))
                }
            }

            // Operating window
            val start = transit.serviceStartSec
            val end = transit.serviceEndSec
            if (start != null && end != null) {
                SheetSmall(
                    stringResource(R.string.mapl_e_operating_hours, fmtClockSec(start), fmtClockSec(end)),
                    modifier = Modifier.padding(top = PhantomSpace.s2),
                    color = c.text2,
                    tnum = true,
                )
            }
        } else if (rawLines.isNotEmpty()) {
            rawLines.forEach { line ->
                val trLine = DossierTransitLine(
                    ref = line.ref ?: line.name ?: "?",
                    mode = line.mode,
                    direction = listOfNotNull(line.from, line.to).joinToString(" → ").takeIf { it.isNotBlank() },
                    headwayMinutes = null,
                    relationId = line.relationId,
                )
                // Той самий ключ розгортання, що й у transit.lines вище: без
                // цього шеврон обіцяв подробиці (малювався завжди), а дотик
                // нічого не робив — рейс на вокзалі показувався, а даних про
                // нього дістати не можна було.
                val lineKey = "raw_${trLine.ref}_${trLine.mode}_${trLine.direction}"
                val isExpanded = expandedLineKey == lineKey
                TransitLineCard(
                    line = trLine,
                    accent = accent,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedLineKey = if (isExpanded) null else lineKey
                    },
                    onShowOnMap = { onShowLine(line) },
                )
                Spacer(Modifier.height(PhantomSpace.s2))
            }
        } else {
            SheetNote(stringResource(R.string.dossier_transit_routes_empty))
        }
    }
}

/**
 * Чи цій картці лінії взагалі є що розгортати.
 *
 * **Скарга власника 15.09: «натискаю на транспорт і картка не
 * розгортається».** Вона розгорталась — просто відкривала ПОРОЖНЕЧУ. Під
 * шевроном лежать рівно дві речі: кнопка «показати на мапі» і послідовність
 * зупинок. Кнопка існує, лише коли в лінії є `relationId` або `shape`;
 * зупинки приходять тільки з тих джерел, що їх дають, а `stops` за
 * замовчуванням порожній. Лінія без жодного з двох давала `AnimatedVisibility`
 * над порожнім `Column` із відступом: шеврон обертався, висота не
 * змінювалась — і це читається як «не працює».
 *
 * Сусідній коментар у гілці `rawLines` уже ловив цю ваду й полагодив ЇЇ
 * ПОЛОВИНУ — дав сирим лініям власний ключ розгортання. Але причина була не в
 * ключі: шеврон малювався БЕЗУМОВНО, тобто картка обіцяла подробиці, не
 * питаючи себе, чи вони в неї є.
 *
 * Винесено з `@Composable` окремою функцією рівно для того, щоб цю умову
 * можна було довести тестом: усередині композиції вона доводиться лише оком,
 * а вада, яку вона лікує, оком і не видна — картка виглядає однаково до й
 * після дотику. Та сама дисципліна, що в `cameraVerdict`/`mediaVerdict`.
 */
internal fun transitLineHasDetail(stopCount: Int, canShowOnMap: Boolean): Boolean =
    stopCount > 0 || canShowOnMap

/** Бейдж лінії: залитий її кольором, знак виду й номер — чорнилом, що читається на цьому тоні. */
@Composable
internal fun TransitLineBadge(ref: String, mode: String, colourArgb: Int?, modifier: Modifier = Modifier) {
    val fill = TransitModeTint.line(colourArgb, mode)
    val ink = TransitModeTint.inkOn(fill)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PhantomRadius.chip))
            .background(fill)
            .padding(horizontal = PhantomSpace.s2, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        PhantomGlyphIcon(glyph = modeGlyph(mode), tint = ink, modifier = Modifier.size(14.dp))
        SheetBody(ref.ifBlank { "?" }, color = ink, fontWeight = FontWeight.Bold, maxLines = 1, tnum = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransitLineCard(
    line: DossierTransitLine,
    accent: Color,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onShowOnMap: (() -> Unit)?,
) {
    val c = LocalPhantomColors.current
    val hasDetail = transitLineHasDetail(line.stops.size, onShowOnMap != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .then(
                if (hasDetail) Modifier.clickable(role = Role.Button, onClick = onToggleExpand)
                else Modifier,
            )
            .padding(PhantomSpace.s3),
    ) {
        // Line Title Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TransitLineBadge(line.ref, line.mode, line.colourArgb)

            // Direction & Mode label
            Column(Modifier.weight(1f)) {
                val dir = line.direction
                if (!dir.isNullOrBlank()) {
                    SheetBody(dir, maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
                val headway = line.headwayMinutes
                val subtitle = when {
                    line.running == false -> stringResource(R.string.mapl_e_not_running)
                    // Інтервал із моделі (стала на вид транспорту), не з розкладу
                    // лінії — тож і називається моделлю.
                    headway != null -> stringResource(R.string.mapl_e_headway_subtitle, modeLabelUk(line.mode), headway)
                    else -> modeLabelUk(line.mode)
                }
                SheetNote(
                    subtitle,
                    color = if (line.running == false) c.danger else c.text2,
                    tnum = true,
                )
            }

            // Шеврон — ЛИШЕ коли за ним щось є. Див. [hasDetail] вище:
            // намальований безумовно, він був обіцянкою, яку картка не мала
            // чим виконати.
            if (hasDetail) {
                SheetChevron(isExpanded, c.text2)
            }
        }

        // Відліки з РОЗКЛАДУ, не з фіда: підпис говорить це прямо, бо зелена
        // пігулка «2 хв» інакше читається як спостережене прибуття.
        if (line.departures.isNotEmpty() && line.running != false) {
            Spacer(Modifier.height(PhantomSpace.s2))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Два яруси — два різні твердження, і підпис мусить їх
                // розрізняти. «12:08» з розкладу міста і «12:08», яке
                // модель щойно порахувала, виглядають однаково; єдине, що
                // не дає їх сплутати, — оцей рядок.
                SheetSmall(
                    stringResource(
                        if (line.departures.any { it.modelled }) {
                            R.string.map_dossier_departures_model
                        } else {
                            R.string.map_dossier_departures_real
                        },
                    ),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                line.departures.forEachIndexed { idx, dep ->
                    // Найближчий, що от-от, — `success`; перший — чорнилом
                    // тексту з волосиною; решта — тихі.
                    val imminent = idx == 0 && dep.isImminent
                    val pillBg = if (imminent) c.success else c.surface
                    val pillFg = when {
                        imminent -> TransitModeTint.inkOn(c.success)
                        idx == 0 -> c.text
                        else -> c.text2
                    }
                    val pillShape = RoundedCornerShape(PhantomRadius.chip)
                    Box(
                        modifier = Modifier
                            .clip(pillShape)
                            .background(pillBg)
                            .then(if (imminent) Modifier else Modifier.border(1.dp, c.line, pillShape))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        SheetNote(
                            dep.timeClock?.let { stringResource(R.string.mapl_e_dep_min_clock, dep.inMinutes, it) }
                                ?: stringResource(R.string.mapl_e_dep_min, dep.inMinutes),
                            color = pillFg,
                            fontWeight = if (idx == 0) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            tnum = true,
                        )
                    }
                }
            }
        }

        // Expanded Stop Timeline & Map button.
        // `isExpanded && hasDetail`, а не саме лише `isExpanded`: стан
        // розгортання живе вище і переживає зміну даних, тож картка, яка
        // втратила подробиці вже РОЗГОРНУТОЮ, інакше лишилась би з відкритою
        // порожнечею — тією самою, проти якої стоїть [hasDetail].
        AnimatedVisibility(
            visible = isExpanded && hasDetail,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = PhantomSpace.s2)) {
                // Map Trace Button — тиха дія словом у бурштині-чорнилі.
                if (onShowOnMap != null) {
                    val showLabel = stringResource(R.string.dossier_transit_show_on_map)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = PhantomTouchFloor)
                            .clickable(role = Role.Button, onClick = onShowOnMap)
                            .semantics(mergeDescendants = true) { contentDescription = showLabel },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(DesignR.drawable.lc_map),
                            contentDescription = null,
                            tint = c.accentText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(PhantomSpace.s2))
                        SheetBody(showLabel, color = c.accentText, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Stop Sequence Timeline
                if (line.stops.isNotEmpty()) {
                    SheetSmall(
                        stringResource(R.string.mapl_e_stop_sequence_header, line.stops.size),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PhantomRadius.control))
                            .background(c.surface)
                            .padding(PhantomSpace.s2),
                    ) {
                        line.stops.forEachIndexed { idx, stop ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                            ) {
                                // Bullet Indicator
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (stop.isCurrent) c.success else c.lineStrong),
                                )
                                Spacer(Modifier.width(PhantomSpace.s2))

                                SheetBody(
                                    stop.name,
                                    color = if (stop.isCurrent) c.success else c.text,
                                    fontWeight = if (stop.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )

                                if (stop.isCurrent) {
                                    SheetSmall(stringResource(R.string.mapl_e_you_are_here), color = c.success)
                                } else if (stop.travelMinutesFromCurrent > 0) {
                                    SheetSmall(
                                        stringResource(R.string.mapl_e_plus_min, stop.travelMinutesFromCurrent),
                                        color = c.text2,
                                        tnum = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modeGlyph(mode: String): PhantomGlyph = when (mode) {
    "subway" -> PhantomGlyph.METRO
    "tram" -> PhantomGlyph.TRAM
    "bus", "share_taxi", "trolleybus" -> PhantomGlyph.BUS
    "train", "light_rail" -> PhantomGlyph.TRAIN
    else -> PhantomGlyph.STOP
}

private fun modeLabelUk(mode: String): String = when (mode) {
    "subway" -> MapText.s(R.string.mapl_e_mode_metro)
    "tram" -> MapText.s(R.string.mapl_e_mode_tram)
    "trolleybus" -> MapText.s(R.string.mapl_e_mode_trolleybus)
    "bus" -> MapText.s(R.string.mapl_e_mode_bus)
    "share_taxi" -> MapText.s(R.string.mapl_e_mode_share_taxi)
    "train", "light_rail" -> MapText.s(R.string.mapl_e_mode_train_dossier)
    else -> MapText.s(R.string.mapl_e_mode_other)
}

private val GLYPH = 20.dp
