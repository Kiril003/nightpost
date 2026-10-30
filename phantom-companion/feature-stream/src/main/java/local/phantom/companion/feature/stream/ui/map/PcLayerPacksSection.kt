package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.geo.layerpack.MapLayerPackStore
import local.phantom.companion.core.geo.layerpack.StoredLayerPack
import local.phantom.companion.core.net.maplayer.PcLayerBounds
import local.phantom.companion.core.net.maplayer.PcLayerCatalogClient
import local.phantom.companion.core.net.maplayer.PcLayerLink
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.domain.OfflineCoverage
import local.phantom.companion.feature.stream.domain.PcLayerPacks
import local.phantom.companion.feature.stream.domain.PcLayerRow
import local.phantom.companion.feature.stream.domain.PcLayerRowState
import local.phantom.companion.core.design.R as DesignR

/**
 * Map-symbiosis — the general intake for PC-baked national layers
 * (cliff/scree, power towers, and whatever else lands behind the same
 * `local_db` + bbox shape, see [PcLayerCatalogClient]). Sits inside
 * [OfflineTerritoriesPanel], the same sheet the base-tile territories
 * already live in, because it answers the same question — "what does this
 * phone hold without a network" — for a different kind of pack.
 *
 * Self-contained by design, the same doctrine `RoadPackCard`
 * (`:feature-precision`) already proved: it reads [PcLayerLink.session]
 * directly rather than having pairing state threaded through
 * `MapUiState`/nav-graph plumbing, so wiring this into the panel is one
 * call, not a chain of new parameters through `MapScreen`.
 *
 * [currentView] is the one piece of state this section cannot own itself —
 * it is the live map's viewport, already computed by `MapScreen` for the
 * base-tile territories list. Passing it in (rather than re-reading the
 * MapLibre camera here) keeps this section from needing a `MapView`
 * reference of its own.
 */
@Composable
internal fun PcLayerPacksSection(
    currentView: OfflineCoverage.Area?,
    /** Map-symbiosis — a freshly-pulled extract, parsed and ready for
     *  [local.phantom.companion.core.data.spatial.SpatialLayerRepository.push].
     *  Fires once per successful fetch, never on mere recomposition: the
     *  caller owns dedup against what is already on the map. */
    onLayerFetched: (local.phantom.companion.core.data.spatial.SpatialLayerSpec) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()
    val store = remember { MapLayerPackStore(context) }

    // Hoisted here because startFetch/refreshCatalog are plain closures, not
    // @Composable — stringResource() can only be called from this body.
    val disconnectedErrorMsg = stringResource(R.string.map_pc_layers_error_disconnected)
    val noViewErrorMsg = stringResource(R.string.map_pc_layers_error_no_view)
    val linkFailedFallbackMsg = stringResource(R.string.map_pc_layers_link_failed_fallback)

    var storedPacks by remember { mutableStateOf(store.list()) }
    var catalogState by remember { mutableStateOf<PcCatalogUi>(PcCatalogUi.Disconnected) }
    var inFlightLayerId by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var armedDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(armedDelete) {
        if (armedDelete != null) {
            delay(4000)
            armedDelete = null
        }
    }

    suspend fun refreshCatalog() {
        val session = PcLayerLink.session
        val baseUrl = session?.baseUrl()
        val jwt = session?.deviceJwt()
        if (session == null || baseUrl == null || jwt == null) {
            catalogState = PcCatalogUi.Disconnected
            return
        }
        catalogState = PcCatalogUi.Loading
        catalogState = runCatching { session.client.listPullableLayers(baseUrl, jwt) }.fold(
            onSuccess = { PcCatalogUi.Available(it) },
            onFailure = { PcCatalogUi.LinkFailed(it.message ?: linkFailedFallbackMsg) },
        )
    }

    // Same shape as RoadPackCard's poll: connectivity is read locally (a
    // StateFlow value, no network) every few seconds so "ПК не на звʼязку"
    // clears itself on mDNS/WS reconnect with no user action; the catalog
    // itself is only re-fetched on the disconnected → connected edge, never
    // on every tick.
    LaunchedEffect(Unit) {
        var wasConnected = false
        while (isActive) {
            val session = PcLayerLink.session
            val isConnected = session != null && session.connected()
            if (isConnected != wasConnected) {
                wasConnected = isConnected
                if (isConnected) refreshCatalog() else catalogState = PcCatalogUi.Disconnected
            }
            delay(PC_LAYER_POLL_MS)
        }
    }

    fun startFetch(layerId: String, labelUa: String, category: String) {
        val session = PcLayerLink.session
        val baseUrl = session?.baseUrl()
        val jwt = session?.deviceJwt()
        if (session == null || baseUrl == null || jwt == null) {
            errors = errors + (layerId to disconnectedErrorMsg)
            return
        }
        val view = currentView
        if (view == null || !view.valid) {
            errors = errors + (layerId to noViewErrorMsg)
            return
        }
        inFlightLayerId = layerId
        scope.launch {
            val bounds = PcLayerBounds(
                southLat = view.southLat,
                westLon = view.westLon,
                northLat = view.northLat,
                eastLon = view.eastLon,
            )
            when (val result = session.client.fetchExtract(baseUrl, jwt, layerId, bounds)) {
                is PcLayerCatalogClient.ExtractResult.Success -> {
                    val fresh = StoredLayerPack(
                        layerId = layerId,
                        labelUa = labelUa,
                        category = category,
                        northLat = view.northLat,
                        southLat = view.southLat,
                        eastLon = view.eastLon,
                        westLon = view.westLon,
                        featureCount = result.featureCount,
                        bytes = result.bytes,
                        fetchedAtMs = System.currentTimeMillis(),
                        geoJson = result.rawJson,
                    )
                    store.upsert(fresh)
                    storedPacks = store.list()
                    errors = errors - layerId
                    // Дані реальні (щойно з ПК) — тепер вони й на землі, тим
                    // самим шляхом, яким туди приходить кожен інший шар світу.
                    onLayerFetched(PcLayerPacks.specFromGeoJson(fresh))
                }
                is PcLayerCatalogClient.ExtractResult.Failure -> {
                    // The old row (if any) is untouched — `store.upsert` was
                    // never called, so a failed refresh cannot make a
                    // working layer look gone.
                    errors = errors + (layerId to result.message)
                }
            }
            inFlightLayerId = null
        }
    }

    fun deleteLayer(layerId: String) {
        store.delete(layerId)
        storedPacks = store.list()
        errors = errors - layerId
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        MapSectionCaption(stringResource(R.string.map_pc_layers_title))
        when (val cs = catalogState) {
            PcCatalogUi.Disconnected -> Note(stringResource(R.string.map_pc_layers_disconnected))
            PcCatalogUi.Loading -> Note(stringResource(R.string.map_pc_layers_loading))
            is PcCatalogUi.LinkFailed -> {
                Note(cs.message, color = c.danger)
                MapTextAction(
                    label = stringResource(R.string.map_pc_layers_retry),
                    onClick = { scope.launch { refreshCatalog() } },
                )
            }
            is PcCatalogUi.Available -> {
                val rows = remember(cs.layers, storedPacks, inFlightLayerId, errors) {
                    PcLayerPacks.mergeRows(cs.layers, storedPacks, inFlightLayerId, errors)
                }
                if (rows.isEmpty()) {
                    Note(stringResource(R.string.map_pc_layers_empty))
                } else {
                    if (rows.all { it.state is PcLayerRowState.Have }) {
                        Note(stringResource(R.string.map_pc_layers_all_have))
                    }
                    MapRowGroup {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) MapRowDivider()
                            PcLayerRowView(
                                row = row,
                                armed = armedDelete == row.layerId,
                                onFetch = { startFetch(row.layerId, row.labelUa, row.category) },
                                onDeleteTap = {
                                    if (armedDelete == row.layerId) {
                                        armedDelete = null
                                        deleteLayer(row.layerId)
                                    } else {
                                        armedDelete = row.layerId
                                    }
                                },
                            )
                        }
                    }
                    val total = storedPacks.sumOf { it.bytes }
                    if (total > 0) {
                        Note(stringResource(R.string.map_pc_layers_on_device, PcLayerPacks.totalSizeLabel(total)))
                    }
                }
            }
        }
    }
}

/** Стан секції словами: footnote text3, без скла. */
@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = LocalPhantomColors.current.text3) {
    Text(text = text, style = PhantomText.footnote, color = color)
}

private sealed interface PcCatalogUi {
    data object Disconnected : PcCatalogUi
    data object Loading : PcCatalogUi
    data class Available(val layers: List<PcLayerCatalogClient.PcLayerManifest>) : PcCatalogUi
    data class LinkFailed(val message: String) : PcCatalogUi
}

private const val PC_LAYER_POLL_MS = 4_000L

@Composable
private fun PcLayerRowView(
    row: PcLayerRow,
    armed: Boolean,
    onFetch: () -> Unit,
    onDeleteTap: () -> Unit,
) {
    val c = LocalPhantomColors.current
    // Крапка — стан пакета: лежить (успіх), пропонується (тихо), їде (текст),
    // впав (небезпека). Бурштин лишається діям.
    val dotColor = when (row.state) {
        is PcLayerRowState.Have -> c.success
        is PcLayerRowState.Offered -> c.text3
        is PcLayerRowState.Transferring -> c.text
        is PcLayerRowState.Failed -> c.danger
    }
    val nowMs = System.currentTimeMillis()
    val line = when (val s = row.state) {
        is PcLayerRowState.Have -> stringResource(
            R.string.map_pc_layers_feature_count,
            s.featureCount,
            PcLayerPacks.sizeLabel(s.bytes),
            PcLayerPacks.ageLabel(s.fetchedAtMs, nowMs),
        )
        PcLayerRowState.Offered -> stringResource(R.string.map_pc_layers_offered)
        PcLayerRowState.Transferring -> stringResource(R.string.map_pc_layers_downloading)
        is PcLayerRowState.Failed -> s.message
    }
    MapListRow(
        title = row.labelUa,
        subtitle = line,
        subtitleColor = if (row.state is PcLayerRowState.Failed) c.danger else c.text3,
        leading = { MapDot(dotColor) },
        trailing = {
            when (row.state) {
                is PcLayerRowState.Transferring -> Unit
                PcLayerRowState.Offered -> MapRowAction(
                    icon = DesignR.drawable.lc_download,
                    contentDescription = stringResource(R.string.map_pc_layers_action_fetch),
                    onClick = onFetch,
                    tint = c.accentText,
                )
                is PcLayerRowState.Have, is PcLayerRowState.Failed -> if (armed) {
                    // Другий дотик руйнівної дії — словом і кольором небезпеки.
                    MapTextAction(
                        label = stringResource(R.string.map_pc_layers_action_delete_confirm),
                        onClick = onDeleteTap,
                        tone = MapTone.Danger,
                    )
                } else {
                    MapRowAction(
                        icon = DesignR.drawable.lc_download,
                        contentDescription = stringResource(R.string.map_pc_layers_action_update),
                        onClick = onFetch,
                    )
                    MapRowAction(
                        icon = DesignR.drawable.lc_trash,
                        contentDescription = stringResource(R.string.map_pc_layers_action_remove),
                        onClick = onDeleteTap,
                    )
                }
            }
        },
    )
}
