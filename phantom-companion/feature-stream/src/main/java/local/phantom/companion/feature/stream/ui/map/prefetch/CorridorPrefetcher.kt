package local.phantom.companion.feature.stream.ui.map.prefetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.sensor.nav.CorridorPlanner
import local.phantom.companion.core.sensor.nav.RoadWay
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText
import local.phantom.companion.feature.stream.ui.map.OfflineBounds
import local.phantom.companion.feature.stream.ui.map.OfflineRegionsManager

/**
 * ПРОВИДЕЦЬ — the corridor prefetch orchestrator. Given a route polyline it
 * pulls, in the background: MapLibre tile packs (via MAP-4's
 * [OfflineRegionsManager], kill-resilient by design) and Overpass road
 * cells folded into the permanent mesh (via the host's fetchRoadWays, which
 * already persists as a by-product). If the connection dies mid-drive, the
 * phone already holds the world.
 *
 * Overpass is paced ([OVERPASS_GAP_MS]) — a prefetch must never look like an
 * attack on a free API. One run at a time; a manual run outranks the silent
 * route-triggered one.
 */
class CorridorPrefetcher(
    private val scope: CoroutineScope,
    private val fetchRoadWays: suspend (Double, Double, Double, Double) -> List<RoadWay>?,
    private val heldCells: suspend (LongArray) -> LongArray,
    private val offlineRegions: () -> OfflineRegionsManager,
    private val styleUrl: () -> String,
    /** true = мережа лімітована (мобільний трафік). Тихий коридор тягне
     *  тайли лише на безлімітній; null у тестах = вважаємо лімітованою. */
    private val metered: (() -> Boolean)? = null,
) {
    sealed interface State {
        data object Idle : State
        data class Running(
            val label: String,
            val cellsDone: Int,
            val cellsTotal: Int,
            val cellsFailed: Int,
            val tileBoxes: Int,
        ) : State

        data class Done(
            val label: String,
            val cellsFetched: Int,
            val cellsFailed: Int,
            val alreadyHeld: Int,
            val tileBoxes: Int,
            val estTiles: Long,
            val truncated: Boolean,
            val atMs: Long,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var manualRun = false
    private var lastAutoKey = 0L

    fun stateLine(): String = when (val s = _state.value) {
        State.Idle -> MapText.s(R.string.mapl_f_prefetch_idle)
        is State.Running -> {
            val failed = if (s.cellsFailed > 0) MapText.s(R.string.mapl_f_prefetch_failed_part, s.cellsFailed) else ""
            val tiles = if (s.tileBoxes > 0) MapText.s(R.string.mapl_f_prefetch_running_tiles, s.tileBoxes) else ""
            MapText.s(R.string.mapl_f_prefetch_running, s.label, s.cellsDone, s.cellsTotal, failed, tiles)
        }
        is State.Done -> {
            val failed = if (s.cellsFailed > 0) MapText.s(R.string.mapl_f_prefetch_failed_part, s.cellsFailed) else ""
            val tiles = if (s.tileBoxes > 0) MapText.s(R.string.mapl_f_prefetch_done_tiles, s.estTiles, s.tileBoxes) else ""
            val cut = if (s.truncated) MapText.s(R.string.mapl_f_prefetch_done_truncated) else ""
            MapText.s(R.string.mapl_f_prefetch_done, s.label, s.cellsFetched, s.alreadyHeld, failed, tiles, cut)
        }
    }

    suspend fun start(label: String, polyline: List<Pair<Double, Double>>, tiles: Boolean): String {
        if (polyline.size < 2) return MapText.s(R.string.mapl_f_prefetch_empty)
        if (job?.isActive == true) {
            if (manualRun) return MapText.s(R.string.mapl_f_prefetch_already_running, stateLine())
            job?.cancel()
        }
        val plan = CorridorPlanner.corridorCells(polyline)
        val held = heldCells(plan.cells).toHashSet()
        val missing = plan.cells.filter { it !in held }.toLongArray()
        val boxes = if (tiles) CorridorPlanner.tileBoxes(polyline) else emptyList()
        val est = CorridorPlanner.estimateTiles(boxes, MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        manualRun = true
        job = scope.launch { run(label, missing, held.size, boxes, est, plan.truncated) }
        val km = CorridorPlanner.lengthKm(polyline).toInt()
        return buildString {
            append(MapText.s(R.string.mapl_f_prefetch_start_main, label, km, missing.size, held.size))
            if (tiles) append(MapText.s(R.string.mapl_f_prefetch_start_tiles, est, boxes.size))
            if (plan.truncated) append(MapText.s(R.string.mapl_f_prefetch_start_truncated, plan.cells.size))
            append(MapText.s(R.string.mapl_f_prefetch_start_tail))
        }
    }

    fun meshOnly(polyline: List<Pair<Double, Double>>) {
        if (polyline.size < 2 || job?.isActive == true) return
        val key = (polyline.size.toLong() shl 40) xor
            (polyline.first().hashCode().toLong() shl 20) xor
            polyline.last().hashCode().toLong()
        if (key == lastAutoKey) return
        lastAutoKey = key
        manualRun = false
        job = scope.launch {
            val plan = CorridorPlanner.corridorCells(polyline, maxCells = AUTO_MAX_CELLS)
            val held = heldCells(plan.cells).toHashSet()
            val missing = plan.cells.filter { it !in held }.toLongArray()

            val boxes = if (metered?.invoke() == false) {
                CorridorPlanner.tileBoxes(polyline)
            } else emptyList()
            val est = if (boxes.isEmpty()) 0L
            else CorridorPlanner.estimateTiles(boxes, MIN_TILE_ZOOM, MAX_TILE_ZOOM)
            val tileBoxes = if (est in 1..AUTO_MAX_TILES) boxes else emptyList()

            if (missing.isEmpty() && tileBoxes.isEmpty()) return@launch
            run(
                MapText.s(R.string.mapl_f_prefetch_active_route), missing, held.size, tileBoxes,
                if (tileBoxes.isEmpty()) 0L else est, plan.truncated,
            )
        }
    }

    private suspend fun run(
        label: String,
        missing: LongArray,
        alreadyHeld: Int,
        boxes: List<CorridorPlanner.Box>,
        estTiles: Long,
        truncated: Boolean,
    ) {
        if (boxes.isNotEmpty()) {
            val mgr = runCatching { offlineRegions() }.getOrNull()
            val style = runCatching { styleUrl() }.getOrNull()
            if (mgr != null && style != null) {
                runCatching { mgr.refresh() }
                // ОДИН пакет на маршрут, а не по одному на клітинку.
                //
                // Раніше тут стояв цикл, який створював «Коридор: активний
                // маршрут 1/8», 2/8, 3/8… — і кожна поїздка лишала в панелі
                // стільки карток, скільки клітинок налічив планувальник.
                // Власник побачив у себе 59 таких і сказав прямо: «програма
                // сама десятки карток територій показує, які сама ж і ставить
                // при побудові маршруту». Ніхто ніколи не видалятиме їх
                // поштучно — і не мусить: це ОДНА річ, коридор однієї дороги.
                //
                // Геометрія — той самий мультиполігон, що й у ручного
                // збереження, тож пакет має тип `corridor` у метаданих і
                // потрапляє у свою групу, а не в «КЛАПТІ».
                val corridor = boxes.map {
                    local.phantom.companion.feature.stream.domain.RouteCorridor.Box(
                        northLat = it.maxLat,
                        eastLon = it.maxLon,
                        southLat = it.minLat,
                        westLon = it.minLon,
                    )
                }
                val hull = OfflineBounds(
                    northLat = boxes.maxOf { it.maxLat },
                    eastLon = boxes.maxOf { it.maxLon },
                    southLat = boxes.minOf { it.minLat },
                    westLon = boxes.minOf { it.minLon },
                )
                val name = local.phantom.companion.core.geo.place.PlaceNaming.forBox(
                    northLat = hull.northLat,
                    eastLon = hull.eastLon,
                    southLat = hull.southLat,
                    westLon = hull.westLon,
                )
                runCatching {
                    mgr.createCorridor(
                        name = name,
                        geometryJson = local.phantom.companion.feature.stream.domain
                            .RouteCorridor.multiPolygonJson(corridor),
                        bounds = hull,
                        styleUrl = style,
                        minZoom = MIN_TILE_ZOOM.toDouble(),
                        maxZoom = MAX_TILE_ZOOM.toDouble(),
                        // Мітка маршруту — ключ дедуплікації: та сама дорога
                        // не заводить другий пакет, скільки б разів її не
                        // перепрокладали.
                        routeLabel = label,
                    )
                }
            }
        }
        var ok = 0
        var failed = 0
        for ((i, cell) in missing.withIndex()) {
            _state.value = State.Running(label, i, missing.size, failed, boxes.size)
            val b = CorridorPlanner.cellBounds(cell)
            val got = runCatching { fetchRoadWays(b.minLat, b.minLon, b.maxLat, b.maxLon) }.getOrNull()
            if (got != null) ok++ else failed++
            if (i < missing.lastIndex) delay(OVERPASS_GAP_MS)
        }
        _state.value = State.Done(
            label, ok, failed, alreadyHeld, boxes.size, estTiles, truncated,
            System.currentTimeMillis(),
        )
    }

    companion object {
        const val OVERPASS_GAP_MS = 1_800L
        const val MIN_TILE_ZOOM = 9
        const val MAX_TILE_ZOOM = 14
        const val AUTO_MAX_CELLS = 220

        /** Стеля тихого стягування тайлів. Довший коридор оператор може
         *  забрати вручну через map.prefetch — але не мовчки і не раптом. */
        const val AUTO_MAX_TILES = 6_000L
    }
}
