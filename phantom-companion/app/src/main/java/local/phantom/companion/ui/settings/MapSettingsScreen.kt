package local.phantom.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.feature.stream.ui.map.OfflineConditions
import local.phantom.companion.feature.stream.ui.map.OfflineMapsCatalog
import local.phantom.companion.feature.stream.ui.map.OfflinePlaceEntry
import local.phantom.companion.feature.stream.ui.map.PackKind
import local.phantom.companion.feature.stream.ui.map.RegionInfo
import local.phantom.companion.feature.stream.ui.map.TileProviderId
import local.phantom.companion.feature.stream.ui.map.offlineBytesLabel
import local.phantom.companion.feature.stream.ui.map.offlinePlaceLine
import local.phantom.companion.feature.stream.ui.map.offlineRegionLine
import local.phantom.companion.feature.stream.ui.map.offlineRegionTitle
import local.phantom.companion.feature.stream.ui.map.providerRequiresKey
import local.phantom.companion.ui.home.NavRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.feature.stream.R as StreamR

/**
 * Профіль › Офлайн-мапи.
 *
 * Колись тут питали ключ постачальника тайлів, свою адресу стилю, OSRM і
 * геокодер — пʼять питань продукту, закон якого «питань немає». Лишилось те,
 * з чим людина справді може щось зробити: які ділянки лежать на телефоні, і
 * чесне «на чому зараз мапа».
 *
 * Вигляд — той самий, що й решта Профілю ([SubScreen], [RowGroup]): старий
 * екран із моноширинним шрифтом, помаранчевим «МАПА · ОФЛАЙН» і пігулкою
 * «← налаштування» стояв посеред нового Профілю чужим (A52, 02.10).
 *
 * Це ЄДИНИЙ список офлайн-мап. Офлайн-панель на мапі вела другий такий самий
 * (групи, пауза, видалення), і два списки однієї власності розійшлись: тут
 * місце рубалось шматок за шматком одним дотиком, там — цілим і з
 * підтвердженням. Тепер тут три групи власності ([OfflineMapsCatalog]): місця
 * йдуть цілими, кожне видалення — двома дотиками, у пакета в дорозі є пауза,
 * продовження й «спробувати ще» з рядком помилки.
 */
@Composable
fun MapSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val c = LocalPhantomColors.current

    val provider by container.mapPrefs.providerId.collectAsState()
    val apiKey by container.mapPrefs.apiKey.collectAsState()
    val catalog = remember(container) { OfflineMapsCatalog(container.offlineRegions) }
    val regions by catalog.regions.collectAsState()
    val errors by catalog.errors.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf(OfflineConditions.Verdict.GO) }

    // Поки екран відкрито, ділянка в дорозі доходить до «Готово», а не стоїть
    // на тому, що прочиталось при вході. Перед першим опитом — міграція старих
    // метаданих: без неї давній коридор лежав би серед «ділянок мапи».
    LaunchedEffect(catalog) {
        runCatching { catalog.migrateLegacyMetadata() }
        while (true) {
            runCatching { catalog.refresh() }
            verdict = OfflineConditions.check(context, allowMetered = false)
            delay(5_000L)
        }
    }

    val groups = remember(regions, errors) { OfflineMapsCatalog.group(regions, errors) }

    // Друге торкання — протягом ARM_MS, інакше озброєння гасне саме.
    var armed by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf<PackKind?>(null) }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(ARM_MS)
            armed = null
        }
    }
    fun arm(key: String, action: () -> Unit) {
        if (armed == key) {
            armed = null
            action()
        } else {
            armed = key
        }
    }

    val sourceStarved = providerRequiresKey(provider) && apiKey.isNullOrBlank()
    val onlineGeo = GeoEndpoints.config.routingTemplate != null ||
        GeoEndpoints.config.geocodeBaseUrl != null

    SubScreen(title = stringResource(R.string.profile_offline_maps), onBack = onBack) {
        if (regions.isEmpty()) {
            RowGroup(caption = stringResource(R.string.map_regions_title)) {
                InfoRow(title = stringResource(R.string.map_regions_empty))
                RowDivider()
                NavRow(
                    label = stringResource(R.string.map_regions_open_map),
                    icon = DesignR.drawable.lc_map,
                    onClick = { container.mapOpenRequests.tryEmit(Unit) },
                )
            }
        }

        if (groups.places.isNotEmpty()) {
            RowGroup(caption = groupCaption(StreamR.string.map_offline_group_places)) {
                groups.places.forEachIndexed { i, place ->
                    if (i > 0) RowDivider()
                    val key = "place:${place.placeId}"
                    PackRow(
                        title = place.nameUa,
                        line = offlinePlaceLine(place, verdict),
                        failed = place.failed,
                        progress = place.takeIf { !it.complete }?.let { it.percent / 100f },
                        armed = armed == key,
                        confirm = stringResource(
                            StreamR.string.map_offline_delete_confirm_sized,
                            offlineBytesLabel(place.completedBytes),
                        ),
                        busy = place.busyAction(
                            onRetry = { coroutineScope.launch { runCatching { catalog.retryPlace(place.placeId) } } },
                            onPause = { coroutineScope.launch { runCatching { catalog.pausePlace(place.placeId) } } },
                            onResume = { coroutineScope.launch { runCatching { catalog.resumePlace(place.placeId) } } },
                        ),
                        // Місце — одна одиниця: усі частини йдуть разом.
                        onDeleteTap = {
                            arm(key) { coroutineScope.launch { runCatching { catalog.deletePlace(place.placeId) } } }
                        },
                    )
                }
            }
        }

        listOf(
            PackKind.Corridor to groups.corridors,
            PackKind.View to groups.views,
        ).forEach { (kind, rows) ->
            if (rows.isEmpty()) return@forEach
            RowGroup(
                caption = groupCaption(
                    if (kind == PackKind.Corridor) StreamR.string.map_offline_group_corridors
                    else StreamR.string.map_offline_group_views,
                ),
            ) {
                rows.forEachIndexed { i, region ->
                    if (i > 0) RowDivider()
                    val key = "region:${region.id}"
                    val error = errors[region.id]
                    PackRow(
                        title = offlineRegionTitle(region),
                        line = if (clearing == kind) {
                            stringResource(StreamR.string.map_offline_clearing)
                        } else {
                            offlineRegionLine(region, error, verdict)
                        },
                        failed = error != null,
                        progress = region.takeIf { !it.isComplete && it.requiredResourceCount > 0L }
                            ?.progressFraction,
                        armed = armed == key,
                        confirm = stringResource(
                            StreamR.string.map_offline_delete_confirm_sized,
                            offlineBytesLabel(region.completedResourceSizeBytes),
                        ),
                        busy = region.busyAction(
                            failed = error != null,
                            onRetry = { coroutineScope.launch { runCatching { catalog.retry(region.id) } } },
                            onPauseToggle = {
                                coroutineScope.launch {
                                    runCatching { catalog.setPaused(region.id, !region.pausedByOperator) }
                                }
                            },
                        ),
                        onDeleteTap = {
                            arm(key) { coroutineScope.launch { runCatching { catalog.delete(region.id) } } }
                        },
                    )
                }
                RowDivider()
                // «Прибрати всі» — для тимчасового (коридори) і запасного
                // (ділянки). Назване місце групою не зноситься.
                val groupKey = "group:${kind.name}"
                ClearGroupRow(
                    count = rows.size,
                    bytes = rows.sumOf { it.completedResourceSizeBytes },
                    armed = armed == groupKey,
                    clearing = clearing == kind,
                    onTap = {
                        arm(groupKey) {
                            clearing = kind
                            coroutineScope.launch {
                                runCatching { catalog.deleteAll(rows.map { it.id }) }
                                clearing = null
                            }
                        }
                    },
                )
            }
        }

        RowGroup(caption = stringResource(R.string.map_source_title)) {
            InfoRow(title = stringResource(provider.displayNameRes))
            if (sourceStarved) {
                RowDivider()
                NavRow(
                    label = stringResource(R.string.map_source_reset),
                    onClick = { container.mapPrefs.setProvider(TileProviderId.OBSIDIAN) },
                )
            }
        }

        RowGroup {
            InfoRow(
                title = stringResource(R.string.map_online_title),
                value = stringResource(if (onlineGeo) R.string.map_online_on else R.string.map_online_off),
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String? = null) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = PhantomText.body, color = c.text, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(text = value, style = PhantomText.callout, color = c.text3)
        }
    }
}

private const val ARM_MS = 4_000L

@Composable
private fun groupCaption(res: Int): String = stringResource(res).replaceFirstChar { it.titlecase() }

/** Дія пакета в дорозі: «спробувати ще», «пауза» або «продовжити». */
private class BusyAction(val label: Int, val onClick: () -> Unit, val loud: Boolean = false)

private fun OfflinePlaceEntry.busyAction(
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
): BusyAction? = when {
    failed -> BusyAction(StreamR.string.map_offline_action_retry, onRetry, loud = true)
    complete -> null
    downloading -> BusyAction(StreamR.string.map_places_action_pause, onPause)
    else -> BusyAction(StreamR.string.map_places_action_resume, onResume)
}

private fun RegionInfo.busyAction(
    failed: Boolean,
    onRetry: () -> Unit,
    onPauseToggle: () -> Unit,
): BusyAction? = when {
    failed -> BusyAction(StreamR.string.map_offline_action_retry, onRetry, loud = true)
    isComplete -> null
    pausedByOperator -> BusyAction(StreamR.string.map_places_action_resume, onPauseToggle)
    else -> BusyAction(StreamR.string.map_places_action_pause, onPauseToggle)
}

/**
 * Рядок пакета: назва, стан словами (помилка — кольором небезпеки), смужка
 * поступу, поки тягнеться. Кошик — перший дотик; другий — це слово з вагою
 * («видалити і звільнити 1,2 ГБ?») кольором небезпеки. Одним дотиком тут не
 * зникає нічого: пакет країни — це гігабайти й години на Wi-Fi.
 */
@Composable
private fun PackRow(
    title: String,
    line: String,
    failed: Boolean,
    progress: Float?,
    armed: Boolean,
    confirm: String,
    busy: BusyAction?,
    onDeleteTap: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .padding(start = PhantomSpace.s4, top = PhantomSpace.rowV, bottom = PhantomSpace.rowV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PhantomText.body,
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = line.replaceFirstChar { it.titlecase() },
                    style = PhantomText.footnote,
                    color = if (failed) c.danger else c.text3,
                )
            }
            if (armed) {
                RowTextAction(label = confirm, color = c.danger, onClick = onDeleteTap)
            } else {
                busy?.let {
                    RowTextAction(
                        label = stringResource(it.label),
                        color = if (it.loud) c.accentText else c.text2,
                        onClick = it.onClick,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(PhantomSpace.touchMinAndroid)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDeleteTap),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(DesignR.drawable.lc_trash),
                        contentDescription = stringResource(R.string.map_region_drop),
                        tint = c.text2,
                        modifier = Modifier.size(PhantomSize.iconM),
                    )
                }
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(PhantomSpace.s2))
            Box(
                modifier = Modifier
                    .padding(end = PhantomSpace.s4)
                    .fillMaxWidth()
                    .height(PhantomSpace.s1)
                    .clip(CircleShape)
                    .background(c.line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(PhantomSpace.s1)
                        .clip(CircleShape)
                        .background(if (failed) c.danger else c.accent),
                )
            }
        }
    }
}

/** «Прибрати всі» групи — той самий закон двох дотиків, із числом і вагою. */
@Composable
private fun ClearGroupRow(count: Int, bytes: Long, armed: Boolean, clearing: Boolean, onTap: () -> Unit) {
    val c = LocalPhantomColors.current
    val label = when {
        clearing -> stringResource(StreamR.string.map_offline_clearing)
        armed -> stringResource(StreamR.string.map_offline_clear_group_confirm, count, offlineBytesLabel(bytes))
        else -> stringResource(StreamR.string.map_offline_clear_group)
    }.replaceFirstChar { it.titlecase() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clickable(enabled = !clearing, role = Role.Button, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = PhantomText.body,
            color = if (armed) c.danger else c.text2,
        )
    }
}

/** Дія словом у рядку: 48dp, без заливки. */
@Composable
private fun RowTextAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.replaceFirstChar { it.titlecase() },
            style = PhantomText.subhead,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
