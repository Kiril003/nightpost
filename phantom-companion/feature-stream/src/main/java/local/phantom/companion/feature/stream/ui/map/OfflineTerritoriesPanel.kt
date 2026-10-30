package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.geo.place.PlacePackPlanner
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.domain.OfflineCoverage
import local.phantom.companion.feature.stream.domain.OfflinePacks
import local.phantom.companion.feature.stream.domain.RouteCorridor
import local.phantom.companion.core.design.R as DesignR

internal data class OfflinePanelPack(
    val id: Long,
    val name: String,
    val state: OfflinePacks.State,
    val sizeMb: Double,
    val progressPercent: Int,
    val area: OfflineCoverage.Area?,
)

/**
 * Офлайн на мапі — лише те, що робиться З ЦИМ видом: зберегти його, зберегти
 * коридор маршруту, обрати місце за назвою, підтягнути шари з ПК. Перелік
 * усього, що вже лежить на телефоні, живе в одному місці — Профіль ›
 * Офлайн-мапи ([OfflineMapsCatalog]), — і панель веде туди одним рядком.
 *
 * Раніше тут стояв другий такий самий перелік (рух, групи, пошук по
 * збереженому, видалення), і два списки однієї власності розходились: у
 * Профілі місце рубалось шматками без підтвердження, тут — цілим.
 */
@Composable
internal fun OfflineTerritoriesPanel(
    offer: OfflinePacks.Offer,
    onSaveView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Map-symbiosis — the viewport the PC-layer section pulls for when the
     *  operator taps "Отримати"/"Оновити". `null` before the map has laid
     *  out a camera; that section shows its own honest reason rather than
     *  guessing a bounds. */
    currentView: OfflineCoverage.Area? = null,
    /**
     * Коридор активного маршруту — скільки землі, або `null`, коли маршруту
     * немає. Розмір показується ДО тапу: качати наосліп те, що може не влізти
     * в телефон, — це і є «купа дрібних», якою все закінчилось минулого разу.
     */
    routeCorridorKm2: Double? = null,
    /**
     * Ланки коридора. Порожньо — і картка чесно показує площу без мегабайтів
     * замість вигаданого числа.
     */
    routeCorridorBoxes: List<RouteCorridor.Box> = emptyList(),
    /** Куди їде маршрут. `null` — ціль без назви; UI скаже «Коридор маршруту». */
    routeLabel: String? = null,
    onSaveCorridor: (() -> Unit)? = null,
    /** Стиль, під який тягнуться пакети місць — це URL ДЖЕРЕЛА тайлів, а не
     *  той файл стилю, яким мапа зараз малюється: піраміда однакова для обох
     *  полярностей Обсидіана й для Liberty. Розбіжність не ховається — секція
     *  показує її рядком. */
    styleUrl: String = LocalMapStyleConfig.current.resolveOfflineStyleUrl(),
    /** Map-symbiosis — a freshly-pulled PC layer, parsed and ready to render
     *  (see [PcLayerPacksSection]). Default no-op keeps every other caller
     *  (and every existing test) compiling unchanged. */
    onPcLayerFetched: (local.phantom.companion.core.data.spatial.SpatialLayerSpec) -> Unit = {},
    /** «Усі офлайн-мапи» — Профіль › Офлайн-мапи. `null` — рядка немає. */
    onOpenAll: (() -> Unit)? = null,
    /**
     * Уже збережений коридор цього маршруту — показати на мапі: мапа підсвічує
     * пакет і летить до його меж. `null` — лише тихий рядок «уже збережено».
     */
    onShowPack: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current

    // ОДИН менеджер на всю панель: секція місць і підсумок читають той самий.
    val manager = remember { OfflineRegionsManager(context) }
    val regions by manager.regions.collectAsStateWithLifecycle()
    val storeBytes by manager.storeBytes.collectAsStateWithLifecycle()
    var verdict by remember { mutableStateOf(OfflineConditions.Verdict.GO) }
    // Вільне місце знімається в опиті, а не в композиції: stat диска на кожній
    // рекомпозиції — це файлова операція в кадрі.
    var usableBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        // Один раз за відкриття панелі й перед першим показом: старі пакети
        // отримують тип і назву з власного визначення — без цього картка
        // коридора не впізнала б уже збережений маршрут.
        runCatching { manager.migrateLegacyMetadata() }
        while (isActive) {
            runCatching { manager.refresh() }
            verdict = OfflineConditions.check(context, allowMetered = false)
            usableBytes = manager.usableDiskBytes()
            val pending = manager.regions.value.any { !it.isComplete }
            if (pending) runCatching { manager.resumeIncompletePlacePacks() }
            delay(if (pending) PANEL_POLL_MS else PANEL_IDLE_POLL_MS)
        }
    }

    DisposableEffect(manager) {
        onDispose { manager.detachObservers() }
    }

    val groups = remember(regions) { OfflineMapsCatalog.group(regions) }

    OfflinePanelContent(
        offer = offer,
        storeBytes = storeBytes,
        groups = groups,
        usableBytes = usableBytes,
        routeCorridorKm2 = routeCorridorKm2,
        routeCorridorBoxes = routeCorridorBoxes,
        routeLabel = routeLabel,
        onSaveCorridor = onSaveCorridor,
        onSaveView = onSaveView,
        onDismiss = onDismiss,
        onOpenAll = onOpenAll,
        onShowPack = onShowPack,
        places = { OfflinePlacesSection(manager = manager, styleUrl = styleUrl, regions = regions) },
        pcLayers = { PcLayerPacksSection(currentView = currentView, onLayerFetched = onPcLayerFetched) },
        modifier = modifier,
    )
}

/**
 * Вигляд панелі без менеджера: факти приходять параметрами, а дві живі
 * секції (вибір місця й шари з ПК) — слотами. Так панель малюється в кадрах
 * MapSurfaceLookShotsTest без MapLibre під нею.
 */
@Composable
internal fun OfflinePanelContent(
    offer: OfflinePacks.Offer,
    storeBytes: Long,
    groups: OfflineMapsGroups,
    usableBytes: Long,
    routeCorridorKm2: Double?,
    routeCorridorBoxes: List<RouteCorridor.Box>,
    routeLabel: String?,
    onSaveCorridor: (() -> Unit)?,
    onSaveView: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAll: (() -> Unit)?,
    places: @Composable () -> Unit,
    pcLayers: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onShowPack: ((Long) -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    // Аркуш не зʼїдає екран, про який розповідає: стеля — половина висоти,
    // шапка прибита, а все під нею їде однією прокруткою.
    val panelMax = (LocalConfiguration.current.screenHeightDp * PANEL_SHARE).dp
    val body = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = panelMax)
            .clip(PanelShape)
            .background(c.raised)
            .border(1.dp, c.line, PanelShape)
            .padding(top = PhantomSpace.s2, bottom = PhantomSpace.s4),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Row(
            modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.map_offline_panel_title),
                    style = PhantomText.headline,
                    color = c.text,
                )
                // Розмір — це length() файлу сховища, а не сума по регіонах.
                // Сума рахує спільні шрифти й спрайти один раз на пакет: на
                // телефоні власника вона давала 2590 МБ там, де диск несе 1828.
                Text(
                    text = if (groups.isEmpty) {
                        stringResource(R.string.map_offline_empty)
                    } else {
                        stringResource(
                            R.string.map_offline_on_disk,
                            formatBytes(storeBytes),
                            pluralStringResource(R.plurals.map_offline_maps, groups.count, groups.count),
                        )
                    }.replaceFirstChar { it.titlecase() },
                    style = PhantomText.footnote,
                    color = c.text3,
                )
            }
            MapRowAction(
                icon = DesignR.drawable.lc_close,
                contentDescription = stringResource(R.string.map_offline_close),
                onClick = onDismiss,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(body)
                .padding(horizontal = PhantomSpace.s4),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            // 1. Цей вид — головна дія панелі, тож вона одна несе бурштин.
            Text(
                text = stringResource(
                    when (offer) {
                        OfflinePacks.Offer.AlreadyHave -> R.string.map_offline_view_covered
                        OfflinePacks.Offer.SaveRest -> R.string.map_offline_view_partial
                        OfflinePacks.Offer.SaveForThisMap -> R.string.map_offline_view_other_map
                        OfflinePacks.Offer.Save -> R.string.map_offline_view_none
                    },
                ).replaceFirstChar { it.titlecase() },
                style = PhantomText.subhead,
                color = c.text2,
            )
            if (offer != OfflinePacks.Offer.AlreadyHave) {
                val saveLabel = stringResource(
                    when (offer) {
                        OfflinePacks.Offer.SaveRest -> R.string.map_offline_save_rest
                        OfflinePacks.Offer.SaveForThisMap -> R.string.map_offline_save_this_map
                        else -> R.string.map_offline_save_view
                    },
                ).replaceFirstChar { it.titlecase() }
                MapAccentButton(label = saveLabel, onClick = onSaveView, icon = DesignR.drawable.lc_download)
            }

            // 2. Те, що більше за вид: дорога маршруту й місце за назвою.
            CorridorCard(
                km2 = routeCorridorKm2,
                boxes = routeCorridorBoxes,
                routeLabel = routeLabel,
                savedId = groups.corridors.firstOrNull {
                    it.routeLabel != null && it.routeLabel == routeLabel
                }?.id,
                usableBytes = usableBytes,
                onSave = onSaveCorridor,
                onShowSaved = onShowPack,
            )

            places()

            // 3. Усе збережене — одним рядком у Профіль, а не другим списком тут.
            if (onOpenAll != null) {
                OpenAllRow(
                    ready = groups.readyCount,
                    downloading = groups.downloadingCount,
                    onClick = onOpenAll,
                )
            }

            // Map-symbiosis — a second, orthogonal kind of pack: not a basemap
            // tile region but a PC-baked national layer (cliff/scree, power
            // towers, …) pulled for the current viewport. Same sheet because
            // it answers the same operator question ("what works with no
            // network"); a divider keeps the two lists from reading as one.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PhantomSpace.s1)
                    .height(1.dp)
                    .background(c.line),
            )
            pcLayers()
        }
    }
}

/** Половина екрана — стеля аркуша. Далі він накриває те, про що розповідає. */
private const val PANEL_SHARE = 0.5f

private const val PANEL_POLL_MS = 6_000L

/** Нічого не тягнеться — будити SQLite що шість секунд нема за чим. */
private const val PANEL_IDLE_POLL_MS = 20_000L

/** Острів над мапою: кути аркуша з усіх боків, бо він не прилягає до краю. */
private val PanelShape = RoundedCornerShape(PhantomRadius.sheet)

/** Рядок-двері в Профіль › Офлайн-мапи: скільки лежить і скільки ще тягнеться. */
@Composable
private fun OpenAllRow(ready: Int, downloading: Int, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val title = stringResource(R.string.map_offline_all_maps)
    val line = stringResource(R.string.map_offline_all_maps_line, ready, downloading)
    MapRowGroup {
        MapListRow(
            title = title,
            subtitle = line,
            leading = { MapIcon(DesignR.drawable.lc_offline_maps) },
            onClick = onClick,
            spoken = "$title. $line",
            trailing = {
                Box(Modifier.padding(end = PhantomSpace.s3)) {
                    MapIcon(DesignR.drawable.lc_chevron_right, tint = c.text3, size = PhantomSize.iconS)
                }
            },
        )
    }
}

/**
 * Картка коридора замість пігулки з голим літералом.
 *
 * Мегабайти рахуються з дедуплікованих тайлів ланцюга: ланки коридора
 * перекриваються, і сума по ланках назвала б спільні тайли двічі — та сама
 * помилка, через яку підсумок панелі роками бреше в бік більшого.
 *
 * Єдиний ручний спосіб зберегти коридор активного маршруту — тому вона лишається
 * на мапі, поруч із самим маршрутом.
 */
@Composable
private fun CorridorCard(
    km2: Double?,
    boxes: List<RouteCorridor.Box>,
    routeLabel: String?,
    savedId: Long?,
    usableBytes: Long,
    onSave: (() -> Unit)?,
    onShowSaved: ((Long) -> Unit)? = null,
) {
    if (km2 == null || onSave == null) return
    val c = LocalPhantomColors.current
    val minZ = OfflineRegionsManager.MIN_DEFAULT_ZOOM.toInt()
    val maxZ = OfflineRegionsManager.MAX_DEFAULT_ZOOM.toInt()
    val bytes = remember(boxes, minZ, maxZ) {
        if (boxes.isEmpty()) 0L
        else PlacePackPlanner.corridorBytes(
            boxes.map {
                PlacePackPlanner.CorridorBox(it.northLat, it.eastLon, it.southLat, it.westLon)
            },
            minZ,
            maxZ,
        )
    }
    val title = (
        routeLabel?.let { stringResource(R.string.map_offline_corridor_to, it) }
            ?: stringResource(R.string.map_offline_corridor_untitled)
        ).replaceFirstChar { it.titlecase() }

    if (savedId != null) {
        val have = stringResource(R.string.map_offline_corridor_have).replaceFirstChar { it.titlecase() }
        if (onShowSaved == null) {
            Text(text = have, style = PhantomText.footnote, color = c.text3)
            return
        }
        // Збережений коридор не зникає з мапи мовчки: дотик підсвічує сам пакет
        // і веде камеру до його меж — те, що раніше робив рядок пакета у списку.
        val show = stringResource(R.string.map_ask_show).replaceFirstChar { it.titlecase() }
        MapRowGroup {
            MapListRow(
                title = title,
                subtitle = have,
                leading = { MapIcon(DesignR.drawable.lc_offline_maps, tint = c.success) },
                onClick = { onShowSaved(savedId) },
                spoken = "$title. $have. $show",
                trailing = {
                    MapTextAction(
                        label = stringResource(R.string.map_offline_corridor_show),
                        onClick = { onShowSaved(savedId) },
                    )
                },
            )
        }
        return
    }

    // usableBytes == 0 означає «ще не міряли», а не «диск повний»: перший кадр
    // не має права звинувачувати телефон у тому, чого ніхто не перевіряв.
    val fits = bytes == 0L || usableBytes == 0L || OfflineBudget.fitsDisk(bytes, usableBytes)
    val meta = when {
        bytes == 0L -> stringResource(
            R.string.map_offline_corridor_meta_nosize,
            formatKm2(km2),
            minZ,
            maxZ,
        )
        else -> stringResource(
            R.string.map_offline_corridor_meta,
            formatKm2(km2),
            minZ,
            maxZ,
            formatBytes(bytes),
        )
    }
    // Пакет, який не влізе, не створюється: він тягнувся б і мовчки помер із
    // повним диском. Обидва числа справжні — оцінка й вільне місце.
    MapRowGroup {
        if (!fits) {
            MapListRow(
                title = title,
                subtitle = stringResource(
                    R.string.map_offline_no_space,
                    formatBytes(bytes),
                    formatBytes(usableBytes),
                ),
                subtitleColor = c.danger,
                leading = { MapIcon(DesignR.drawable.lc_download, tint = c.text3) },
            )
        } else {
            MapListRow(
                title = title,
                subtitle = meta,
                titleColor = c.accentText,
                leading = { MapIcon(DesignR.drawable.lc_download, tint = c.accentText) },
                onClick = onSave,
                spoken = "$title. $meta",
            )
        }
    }
}
