package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerRepository
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.pushHazardMark
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.geo.MapGroundLayers
import local.phantom.companion.feature.stream.ui.map.nav.GenerativeOverlays

/**
 * R4.x — direct, user-driven world layers. The map menu ("світ") summons a
 * world layer WITHOUT routing through the LLM: the user taps транспорт /
 * камери / літаки and the matching tool runs at the map centre. The impl
 * lives in :app (it owns the tools); this port keeps feature-stream free of
 * core-symbiote. Returns true if a layer landed.
 */
fun interface WorldLayerSummoner {
    suspend fun summon(kind: String, lat: Double, lon: Double): Boolean
}

/**
 * The operator's hand on the phantom layer stack, extracted verbatim from
 * the MapViewModel monolith (500-line rule): «світ» summons, layer chips,
 * transit-line draws, hazard marks and the HORIZON III.2 generative
 * overlays (isochrone, danger field).
 */
internal class MapWorldLayers(
    private val scope: CoroutineScope,
    private val spatialLayers: SpatialLayerRepository?,
    private val worldSummoner: WorldLayerSummoner?,
    private val dossierClient: PlaceDossierClient,
    /** The live layer stack (the ViewModel owns the flow; this reads it). */
    private val layersNow: () -> List<SpatialLayer>,
    /** ATLAS-2 — the offline road graph the matcher already cached. */
    private val graphSnapshot: () -> RoadGraph?,
    private val postNotice: (String) -> Unit,
    private val emitCameraBounds: (MapViewModel.CameraBounds) -> Unit,
    /**
     * Build-now ground layers (towers/drainage/fords, see [MapGroundLayers])
     * — a plain Overpass client, not the AI tool registry: no DI wiring
     * needed, so this stays a default rather than growing [worldSummoner]'s
     * plumbing for every new tag family.
     */
    private val groundOverpass: OverpassClient = OverpassClient(),
) {
    /**
     * R4.x — which world layer is being summoned right now (e.g. "transit"),
     * or null when idle. Drives the spinner on the tapped "світ" chip.
     */
    private val _summoning = MutableStateFlow<String?>(null)
    val summoning: StateFlow<String?> = _summoning.asStateFlow()

    /** R4.x — whether the direct "світ" menu is available at all. */
    val worldMenuEnabled: Boolean = worldSummoner != null

    /**
     * R4.x — user taps a "світ" chip: run the matching world tool at the map
     * centre directly. The tool pushes its own layer (and requests focus), so
     * the map comes alive with no LLM turn. Ignored if no summoner is wired.
     */
    fun summonWorld(kind: String, lat: Double, lon: Double) {
        if (_summoning.value != null) return // one at a time
        if (kind in MapGroundLayers.quickAccessFor(CountryPackRegistry.showsUkraineWartime())) {
            summonGroundLayer(kind, lat, lon)
            return
        }
        val summoner = worldSummoner ?: return
        scope.launch {
            _summoning.value = kind
            try {
                // These are NETWORK tools (Overpass/OpenSky); offline they land
                // nothing. Swallowing that left a dead tap — spinner, then
                // silence («фічі є, але порожні»). Every other feed in this file
                // is honest about an empty result, so this one is too.
                val landed = runCatching { summoner.summon(kind, lat, lon) }.getOrDefault(false)
                if (!landed) postNotice(emptyWorldNotice(kind))
            } finally {
                _summoning.value = null
            }
        }
    }

    private val summonedAtMs = HashMap<String, Long>()

    /**
     * Шари, яких вимагає сам момент — вимогу рахує Lens
     * ([local.phantom.companion.feature.stream.ui.map.atlas.LensFrame.summons]),
     * а сюди приходить уже готовий список.
     *
     * Блокер гонтлету: під сиреною плашка кричала, а шар укриттів чекав, поки
     * жінка з дитиною на руках знайде капсулу і натисне чип. Тепер його ставить
     * тривога, тим самим прямим шляхом, що й палець — і, отже, з тими самими
     * чесними нотатками: «джерело мовчить» ≠ «укриттів немає».
     *
     * Три правила, і кожне тут не випадкове:
     *  - шар, який УЖЕ на землі, не ставиться вдруге — навіть якщо оператор
     *    сам його сховав: це його рука, і сирена її не перебиває;
     *  - відбій знімає епізод, але не сам шар — той доживає власний TTL;
     *  - джерело, яке промовчало, не питають частіше за [SUMMON_RETRY_MS];
     *    без цього мовчазний Overpass отримував би запит щокадру.
     */
    fun applyLensSummons(kinds: Set<String>, lat: Double, lon: Double) {
        summonedAtMs.keys.retainAll(kinds)
        if (kinds.isEmpty() || _summoning.value != null) return
        val onGround = layersNow().mapTo(HashSet()) { it.spec.kind }
        val now = System.currentTimeMillis()
        for (kind in kinds) {
            if (kind in onGround) continue
            val last = summonedAtMs[kind]
            if (last != null && now - last < SUMMON_RETRY_MS) continue
            summonedAtMs[kind] = now
            summonWorld(kind, lat, lon)
            return // «по одному за раз» — той самий замок, що й у summonWorld
        }
    }

    /**
     * Build-now sphere layers (towers/drainage/fords) — fetched and pushed
     * directly, no [worldSummoner] involved (see [MapGroundLayers]'s KDoc for
     * why: unnamed OSM infrastructure dies in the POI-search path, and this
     * has no business being an AI-callable tool).
     */
    private fun summonGroundLayer(id: String, lat: Double, lon: Double) {
        val repo = spatialLayers ?: run { postNotice(MapText.s(R.string.mapl_e_layers_unavailable)); return }
        scope.launch {
            _summoning.value = id
            try {
                val probe = runCatching { MapGroundLayers.fetch(id, groundOverpass, lat, lon) }
                    .getOrElse { GeoResult.Unavailable(SourceUnavailable.Transport(it.javaClass.simpleName)) }
                when (probe) {
                    // Відмова джерела ≠ порожній район (D8, закон 2).
                    is GeoResult.Unavailable -> postNotice(MapText.s(MapGroundLayers.unavailableNotice(id)))
                    is GeoResult.Ok -> if (probe.value.isEmpty()) {
                        postNotice(MapText.s(MapGroundLayers.emptyNotice(id)))
                    } else {
                        // `kind` — це ІДЕНТИЧНІСТЬ шару, як і в [pushPcLayer]:
                        // `repo.push` завжди кладе новий рядок із новим UUID, тож
                        // повторний виклик (палець ще раз, або сама Лінза за
                        // [applyLensSummons]) множив копії одного шару в списку
                        // «активне зараз» замість оновити наявну.
                        layersNow().filter { it.spec.kind == id }.forEach { repo.clear(it.id) }
                        repo.push(MapGroundLayers.specFor(id, probe.value))
                    }
                }
            } finally {
                _summoning.value = null
            }
        }
    }

    /**
     * Map-symbiosis — a PC national-bake extract the operator already pulled
     * (PcLayerPacksSection stores the raw GeoJSON in [local.phantom.companion.core.geo.layerpack.MapLayerPackStore]
     * so it survives a re-fetch failure, but nothing before this method ever
     * read that store back into the renderer). Pushed through the SAME sink
     * every other world layer uses, so it gets a real chip/toggle/clear for
     * free from the existing ActiveLayerRow.
     *
     * `kind` doubles as identity (see [MapGroundLayers]'s own doc): a repeat
     * pull for the same PC layer must REPLACE what is on the map, not pile a
     * second permanent (`ttlMs = null`) copy beside the first.
     */
    fun pushPcLayer(spec: SpatialLayerSpec) {
        val repo = spatialLayers ?: return
        if (spec.elementCount == 0) return
        scope.launch {
            layersNow().filter { it.spec.kind == spec.kind }.forEach { repo.clear(it.id) }
            repo.push(spec)
        }
    }

    /** Honest, kind-specific «nothing landed» line — offline is the norm here, not the exception. */
    private fun emptyWorldNotice(kind: String): String = when (kind) {
        "transit" -> MapText.s(R.string.mapl_e_empty_transit)
        "routes" -> MapText.s(R.string.mapl_e_empty_routes)
        "cameras" -> MapText.s(R.string.mapl_e_empty_cameras)
        "aircraft" -> MapText.s(R.string.mapl_e_empty_aircraft)
        else -> MapText.s(R.string.mapl_e_empty_other)
    }

    /** R4.1 — toggle a phantom layer on/off from the layer chip row. */
    fun toggleLayer(layerId: String, visible: Boolean) {
        val repo = spatialLayers ?: return
        scope.launch { repo.setVisible(layerId, visible) }
    }

    /** R4.1 — dismiss a phantom layer entirely from the chip row. */
    fun clearLayer(layerId: String) {
        val repo = spatialLayers ?: return
        scope.launch { repo.clear(layerId) }
    }

    /**
     * NAV-6 — draw one transit route relation as a living layer (tapped
     * from the dossier's line chips). Honest notice when OSM has no
     * geometry for it.
     */
    fun showTransitLine(line: PlaceDossierClient.RouteLineRef) {
        val repo = spatialLayers ?: run {
            postNotice(MapText.s(R.string.mapl_e_layers_unavailable))
            return
        }
        scope.launch {
            val segments = dossierClient.fetchRouteSegments(line.relationId)
            val pts = segments.flatten()
            if (pts.size < 2) {
                postNotice(MapText.s(R.string.mapl_e_line_absent_on_map))
                return@launch
            }
            val label = line.ref?.let { MapText.s(R.string.mapl_e_line_ref, it) }
                ?: line.name
                ?: MapText.s(R.string.mapl_e_line_fallback)
            val color = line.colour ?: "#22C55E"
            repo.push(
                SpatialLayerSpec(
                    title = MapText.s(R.string.mapl_e_line_title, label),
                    origin = LayerOrigin.WORLD,
                    kind = "transit",
                    // Кожен звʼязний шматок — власна лінія: одна ламана на всі
                    // зʼєднала б розриви прямими через місто.
                    lines = segments.map { seg ->
                        SpatialLine(
                            points = seg.map { GeoPoint(it.first, it.second) },
                            color = color,
                            width = 5f,
                            label = label,
                        )
                    },
                    color = color,
                    glyph = MapText.s(R.string.mapl_e_routes_glyph),
                    ttlMs = 60 * 60_000L,
                ),
            )
            emitCameraBounds(
                MapViewModel.CameraBounds(
                    southLat = pts.minOf { it.first }, westLon = pts.minOf { it.second },
                    northLat = pts.maxOf { it.first }, eastLon = pts.maxOf { it.second },
                ),
            )
        }
    }

    /**
     * Draw transit line from bundled offline surface/metro feed or Overpass relation.
     */
    fun showLocalTransitLine(line: local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine) {
        val repo = spatialLayers ?: run {
            postNotice(MapText.s(R.string.mapl_e_layers_unavailable))
            return
        }
        scope.launch {
            val segments: List<List<Pair<Double, Double>>> = if (line.shape != null) {
                listOfNotNull(
                    local.phantom.companion.core.data.transit.surface.Polyline5.decode(line.shape),
                )
            } else if (line.relationId != null) {
                dossierClient.fetchRouteSegments(line.relationId)
            } else emptyList()
            val pts = segments.flatten()

            if (pts.size < 2) {
                postNotice(MapText.s(R.string.mapl_e_line_no_geometry))
                return@launch
            }
            val label = if (line.ref.isNotBlank()) MapText.s(R.string.mapl_e_line_ref, line.ref) else line.direction.orEmpty()
            val colorHex = when (line.mode) {
                "subway" -> "#EF4444"
                "tram" -> "#EAB308"
                "trolleybus" -> "#3B82F6"
                "bus", "share_taxi" -> "#10B981"
                "train", "light_rail" -> "#8B5CF6"
                else -> "#14B8A6"
            }
            repo.push(
                SpatialLayerSpec(
                    title = MapText.s(R.string.mapl_e_line_title, label),
                    origin = LayerOrigin.WORLD,
                    kind = "transit",
                    lines = segments.map { seg ->
                        SpatialLine(
                            points = seg.map { GeoPoint(it.first, it.second) },
                            color = colorHex,
                            width = 6f,
                            label = label,
                        )
                    },
                    color = colorHex,
                    glyph = MapText.s(R.string.mapl_e_routes_glyph),
                    ttlMs = 60 * 60_000L,
                ),
            )
            emitCameraBounds(
                MapViewModel.CameraBounds(
                    southLat = pts.minOf { it.first }, westLon = pts.minOf { it.second },
                    northLat = pts.maxOf { it.first }, eastLon = pts.maxOf { it.second },
                ),
            )
            postNotice(MapText.s(R.string.mapl_e_line_drawn, label))
        }
    }

    /**
     * NAV-5 — manual TTL mark from the operator's finger (long-press
     * sheet). Exactly the same layer `world.report` writes — one code
     * path, two hands.
     */
    fun reportHazard(kind: String, lat: Double, lon: Double) {
        val repo = spatialLayers ?: run {
            postNotice(MapText.s(R.string.mapl_e_layers_unavailable))
            return
        }
        scope.launch {
            val res = runCatching {
                repo.pushHazardMark(kind = kind, lat = lat, lon = lon)
            }.getOrNull()
            postNotice(
                if (res != null) {
                    MapText.s(R.string.mapl_e_hazard_marked, kind)
                } else {
                    MapText.s(R.string.mapl_e_hazard_mark_failed)
                },
            )
        }
    }

    /**
     * HORIZON III.2 — «скільки встигну дійти»: colour the reachable road
     * network by walk-time from the operator, over the offline graph the
     * matcher already cached. Honest notice when nothing is reachable / no
     * graph yet loaded.
     */
    fun showShelterIsochrone(fromLat: Double, fromLon: Double) {
        val repo = spatialLayers ?: run { postNotice(MapText.s(R.string.mapl_e_layers_unavailable)); return }
        val graph = graphSnapshot() ?: run {
            postNotice(MapText.s(R.string.mapl_e_isochrone_no_roads))
            return
        }
        scope.launch {
            val spec = GenerativeOverlays.shelterIsochrone(graph, fromLat, fromLon)
            if (spec == null) {
                postNotice(MapText.s(R.string.mapl_e_isochrone_unreachable))
                return@launch
            }
            repo.push(spec)
        }
    }

    /**
     * HORIZON III.2 — a graduated danger wash from the operator's live hazard
     * marks. Reads the same hazard layer the map already carries; honest
     * notice when there are none.
     */
    fun showDangerField() {
        val repo = spatialLayers ?: run { postNotice(MapText.s(R.string.mapl_e_layers_unavailable)); return }
        val hazards = layersNow()
            .filter { it.visible && it.spec.kind == HAZARD_LAYER_KIND }
            .flatMap { it.spec.markers }
            .filterNot { local.phantom.companion.core.data.spatial.isRefusedMarkKind(it.label ?: it.glyph.orEmpty()) }
            .map {
                GenerativeOverlays.Hazard(
                    it.lat, it.lon, radiusM = 300.0, label = it.label,
                )
            }
        if (hazards.isEmpty()) {
            postNotice(MapText.s(R.string.mapl_e_danger_field_empty))
            return
        }
        scope.launch {
            val spec = GenerativeOverlays.dangerField(hazards) ?: return@launch
            repo.push(spec)
        }
    }

    private companion object {
        /** Чверть від TTL шару: досить рідко для мовчазного джерела, досить
         *  часто, щоб укриття вернулись іще за тієї самої тривоги. */
        const val SUMMON_RETRY_MS = 15 * 60_000L
    }
}
