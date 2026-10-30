package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.phantom.companion.core.design.component.fillDialogRoom
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.geo.place.NamedPlace
import local.phantom.companion.core.geo.place.PlaceGazetteer
import local.phantom.companion.core.geo.place.PlaceKind
import local.phantom.companion.core.geo.place.PlacePackPlanner
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.R as DesignR

/**
 * Вибір території за назвою, а не за квадратом на сітці.
 *
 * Власне вікно ([Dialog]), а не ще одна секція в панелі: тут є поле вводу, а
 * панель офлайну живе притиснутою до низу екрана — клавіатура накрила б і
 * поле, і половину списку.
 *
 * Розмір кожного варіанта рахується тут-таки з реальної рамки місця
 * ([PlacePackPlanner]); жодне число в цьому вікні не написане від руки.
 */
@Composable
internal fun OfflinePlacePickerDialog(
    deviceAverage: PlacePackPlanner.DeviceAverage?,
    onDismiss: () -> Unit,
    onDownload: (PlacePackPlanner.Plan) -> Unit,
    usableBytes: Long = 0L,
) {
    val c = LocalPhantomColors.current
    var query by remember { mutableStateOf("") }
    var openPlaceId by remember { mutableStateOf<String?>(null) }
    // Де ми в дереві «Світ / Польща / …». Порожньо — верхній рівень.
    var trail by remember { mutableStateOf<List<NamedPlace>>(emptyList()) }

    val searching = query.isNotBlank()
    val results = remember(query, trail) {
        when {
            searching -> PlaceGazetteer.searchEverywhere(query)
            trail.isEmpty() -> PlaceGazetteer.countries()
            trail.size == 1 -> PlaceGazetteer.oblastsOf(trail.last())
            else -> PlaceGazetteer.childrenOf(trail.last())
        }
    }
    val here = trail.lastOrNull()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Вікно — той самий піднятий аркуш, що й решта мапи: raised, кути
        // аркуша, назва headline, підпис footnote, рядки групою на overlay.
        Column(
            modifier = Modifier
                .fillDialogRoom()
                .padding(PhantomSpace.s4)
                .clip(RoundedCornerShape(PhantomRadius.sheet))
                .background(c.raised)
                .padding(top = PhantomSpace.s2, bottom = PhantomSpace.s4),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            Row(
                modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.map_places_title),
                        style = PhantomText.headline,
                        color = c.text,
                    )
                    Text(
                        text = stringResource(R.string.map_places_note),
                        style = PhantomText.footnote,
                        color = c.text3,
                    )
                }
                MapRowAction(
                    icon = DesignR.drawable.lc_close,
                    contentDescription = stringResource(R.string.map_places_close),
                    onClick = onDismiss,
                )
            }

            SearchField(query = query, onQuery = { query = it })

            // Хлібні крихти лише в браузингу: під час пошуку глибина не має
            // сенсу, бо видача змішує рівні.
            if (!searching && trail.isNotEmpty()) {
                Breadcrumb(trail = trail, onBack = { trail = trail.dropLast(1) })
            }

            if (results.isEmpty()) {
                Text(
                    text = stringResource(
                        if (here != null) R.string.map_places_no_detail
                        else R.string.map_places_search_empty,
                    ),
                    style = PhantomText.footnote,
                    color = c.text3,
                    modifier = Modifier.padding(horizontal = PhantomSpace.s4),
                )
                // Країна без ярусу B — це не глухий кут: сама країна лишається
                // доступною цілком, і глибини для неї показуються тут-таки.
                here?.let {
                    Column(Modifier.padding(horizontal = PhantomSpace.s4)) {
                        DepthOptions(place = it, usableBytes = usableBytes, onDownload = onDownload)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(results, key = { _, it -> it.id }) { i, place ->
                        val drillable = !searching &&
                            (PlaceGazetteer.hasDetail(place) ||
                                (place.kind == PlaceKind.Oblast && trail.size == 1))
                        GroupSlot(index = i, count = results.size) {
                            PlaceRow(
                                place = place,
                                expanded = openPlaceId == place.id,
                                usableBytes = usableBytes,
                                drillable = drillable,
                                onDrill = { trail = trail + place },
                                onToggle = {
                                    openPlaceId = if (openPlaceId == place.id) null else place.id
                                },
                                onDownload = onDownload,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(trail: List<NamedPlace>, onBack: () -> Unit) {
    val c = LocalPhantomColors.current
    val back = stringResource(R.string.map_places_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MapTouch)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics(mergeDescendants = true) { contentDescription = back }
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        MapIcon(DesignR.drawable.lc_back, tint = c.text2)
        Text(
            text = (
                stringResource(R.string.map_places_world) +
                    trail.joinToString("") { " / " + it.nameUa }
                ).replaceFirstChar { it.titlecase() },
            style = PhantomText.subhead,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Поле — те саме, що в шторці пошуку: overlay, радіус контрола, lc_search. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val c = LocalPhantomColors.current
    val hint = stringResource(R.string.map_places_search_hint)
    Row(
        modifier = Modifier
            .padding(horizontal = PhantomSpace.s4)
            .fillMaxWidth()
            .heightIn(min = PhantomSize.input)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.overlay)
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        MapIcon(DesignR.drawable.lc_search, tint = c.text3)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = hint },
            textStyle = PhantomText.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(text = hint, style = PhantomText.body, color = c.text3, maxLines = 1)
                }
                inner()
            },
        )
    }
}

@Composable
private fun PlaceRow(
    place: NamedPlace,
    expanded: Boolean,
    usableBytes: Long,
    drillable: Boolean,
    onDrill: () -> Unit,
    onToggle: () -> Unit,
    onDownload: (PlacePackPlanner.Plan) -> Unit,
) {
    val c = LocalPhantomColors.current
    val kindWord = stringResource(
        when (place.kind) {
            PlaceKind.Country -> R.string.map_places_kind_country
            PlaceKind.Oblast -> R.string.map_places_kind_oblast
            PlaceKind.Raion -> R.string.map_places_kind_raion
            PlaceKind.City -> R.string.map_places_kind_city
        },
    )
    val area = stringResource(R.string.map_places_box_area, formatKm2(place.boxAreaKm2))
    val sub = listOfNotNull(kindWord, place.parentUa, area).joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        MapListRow(
            title = place.nameUa,
            subtitle = sub,
            onClick = onToggle,
            spoken = "${place.nameUa}. $sub",
            trailing = if (drillable) {
                {
                    // Заглибитися і завантажити — це два різні наміри, тож дві
                    // різні цілі. Один рядок, який робив і те й те, змушував гадати.
                    MapRowAction(
                        icon = DesignR.drawable.lc_chevron_right,
                        contentDescription = stringResource(R.string.map_places_open_inside, place.nameUa),
                        onClick = onDrill,
                    )
                }
            } else {
                null
            },
        )
        if (expanded) {
            Column(Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s3)) {
                DepthOptions(place = place, usableBytes = usableBytes, onDownload = onDownload)
            }
        }
    }
}

@Composable
private fun DepthOptions(
    place: NamedPlace,
    usableBytes: Long,
    onDownload: (PlacePackPlanner.Plan) -> Unit,
) {
    val c = LocalPhantomColors.current
    // Різання прямокутника на частини — не робота для кожної рекомпозиції;
    // рахується один раз на розгорнутий рядок.
    val depths = remember(place.id) { PlacePackPlanner.depths(place) }
    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
        val notes = buildList {
            place.landAreaKm2?.let { add(stringResource(R.string.map_places_land_area, formatKm2(it))) }
            if (PlaceGazetteer.isWorldRow(place)) add(stringResource(R.string.map_places_country_only))
            if (PlaceGazetteer.isMainlandFramed(place)) add(stringResource(R.string.map_places_mainland_frame))
        }
        notes.forEach { Text(text = it, style = PhantomText.footnote, color = c.text3) }
        for (depth in depths) {
            when (depth) {
                is PlacePackPlanner.Depth.Available -> {
                    val plan = depth.plan
                    // Пакет, який не влізе, не пропонується: він тягнувся б і
                    // мовчки помер із повним диском. Обидва числа справжні.
                    val fits = usableBytes == 0L ||
                        OfflineBudget.fitsDisk(plan.bytes, usableBytes)
                    if (!fits) {
                        Text(
                            text = stringResource(
                                R.string.map_places_depth_no_space,
                                plan.maxZoom,
                                formatBytes(plan.bytes),
                                formatBytes(usableBytes),
                            ),
                            style = PhantomText.footnote,
                            color = c.danger,
                        )
                        continue
                    }
                    val label = stringResource(
                        R.string.map_places_depth_option,
                        plan.maxZoom,
                        formatBytes(plan.bytes),
                        plan.chunkCount,
                    )
                    // Глибина — чип аркуша з цифрами Manrope tnum і дією словом:
                    // бурштин лише на «завантажити», бо це дія, а не стан.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MapTouch)
                            .clip(RoundedCornerShape(PhantomRadius.control))
                            .background(c.accentSoft)
                            .clickable(role = Role.Button) { onDownload(plan) }
                            .semantics(mergeDescendants = true) { contentDescription = label }
                            .padding(horizontal = PhantomSpace.s3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                    ) {
                        MapIcon(DesignR.drawable.lc_download, tint = c.accentText, size = PhantomSize.iconS)
                        Text(
                            text = label,
                            style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
                            color = c.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.map_places_action_download),
                            style = PhantomText.subhead,
                            color = c.accentText,
                            maxLines = 1,
                        )
                    }
                }
                is PlacePackPlanner.Depth.Refused -> Text(
                    text = stringResource(
                        R.string.map_places_depth_refused,
                        depth.reason.maxZoom,
                        depth.reason.chunksNeeded,
                    ),
                    style = PhantomText.footnote,
                    color = c.text3,
                )
            }
        }
    }
}

@Composable
internal fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    val id = when {
        gb >= 1.0 -> R.string.map_places_size_gb
        mb >= 1.0 -> R.string.map_places_size_mb
        else -> R.string.map_places_size_kb
    }
    val value = when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f", gb)
        mb >= 10.0 -> String.format(java.util.Locale.US, "%.0f", mb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f", mb)
        else -> String.format(java.util.Locale.US, "%.1f", kb)
    }
    return stringResource(id, value)
}

internal fun formatKm2(km2: Double): String = when {
    km2 >= 10_000 -> String.format(java.util.Locale.US, "%,d", km2.toLong()).replace(',', ' ')
    km2 >= 100 -> km2.toLong().toString()
    else -> String.format(java.util.Locale.US, "%.1f", km2)
}
