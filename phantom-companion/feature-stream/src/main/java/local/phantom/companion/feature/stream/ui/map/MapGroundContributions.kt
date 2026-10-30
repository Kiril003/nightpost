package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionInstall
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionRegistry
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionUpdate
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.atlas.MapContribution
import local.phantom.companion.feature.stream.ui.map.atlas.MapStyleHandle
import local.phantom.companion.feature.stream.ui.map.atlas.MapTier
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.atlas.TapGeometry
import local.phantom.companion.feature.stream.ui.map.atlas.TapRank
import local.phantom.companion.feature.stream.ui.map.atlas.TapSpec
import local.phantom.companion.feature.stream.ui.map.nav.lanes.installLaneBand
import local.phantom.companion.feature.stream.ui.map.nav.lanes.updateLaneBand
import local.phantom.companion.feature.stream.ui.map.peers.PEERS_LAYER
import local.phantom.companion.feature.stream.ui.map.peers.PEER_ROUTE_LAYER
import local.phantom.companion.feature.stream.ui.map.peers.PEER_WHERE_FILL
import local.phantom.companion.feature.stream.ui.map.peers.PEER_WHERE_MARK
import local.phantom.companion.feature.stream.ui.map.peers.installPeerFigures
import local.phantom.companion.feature.stream.ui.map.peers.installPeerWhere
import local.phantom.companion.feature.stream.ui.map.peers.updatePeerWhere
import local.phantom.companion.feature.stream.ui.map.peers.installPeerRoutes
import local.phantom.companion.feature.stream.ui.map.peers.updatePeerFigures
import local.phantom.companion.feature.stream.ui.map.peers.updatePeerRoutes
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import org.maplibre.android.maps.Style

/**
 * ATLAS-0b (D3) — every ground layer of the map, declared once.
 *
 * This file is the whole of what used to be the ~20-step `setStyle { … }`
 * chain plus the eight scattered `LaunchedEffect`s that updated those same
 * layers. Z-order is [MapTier]'s ordinal, not the order somebody happened to
 * type the calls in, and three tiers that used to install lazily from an
 * update pass (radar, maneuver pins, offline packs) now get their skeleton at
 * install time — data arrival moves sources, never layers.
 *
 * MapDepthFirewallTest and MapThemeWiringTest read THIS file: the licence
 * guard and the live polarity now live where the calls live.
 */
internal data class MapGroundInputs(
    val context: Context,
    val mapIsDark: Boolean,
    /** ATLAS-2 — режим сцени. Кадр Лінзи його вже несе; тут він потрібен як
     *  КЛЮЧ: без нього прохід оновлення не помітив би зміни режиму. */
    val scene: SceneMode,
    val terrainDepth: Boolean,
    /** Стеля рельєфу з внесеного пакета кузні; null — публічні тайли мережі. */
    val terrainPackMaxZoom: Int? = null,
    val oblastGeoJson: String,
    val centroidOf: (String) -> Pair<Double, Double>?,
    val alertStates: Map<String, AlertState>,
    val radarOverlay: RadarOverlay?,
    val operatorLocation: LocationFix?,
    val bearing: Float,
    val compassFidelity: CompassFidelity,
    val searchPin: Pair<Double, Double>?,
    val routes: List<MapPayload.PolylineData>,
    val altRoutes: List<Triple<List<Pair<Double, Double>>, String, Int>>,
    val routeTrimAt: Pair<Double, Double>?,
    val degradedSegments: List<Pair<List<Pair<Double, Double>>, GroundRegister>>,
    val maneuvers: List<ManeuverPins.Pin>,
    val activeManeuverStep: Int,
    /** PRESENCE — смуги підходу до наступного маневру; null = даних нема. */
    val laneBandGeoJson: String? = null,
    /** Знаки подій, уже просіяні воротарем доречності та серіалізовані. */
    val eventMarksGeoJson: String,
    /** СИМБІОЗ — фігурки контактів. Порожня колекція — легітимний стан. */
    val peerFiguresGeoJson: String = EMPTY_FC,
    val peerRoutesGeoJson: String = EMPTY_FC,
    val peerWhereGeoJson: String = EMPTY_FC,
    val transitItinerary: Itinerary?,
    /** Мережа з дорожнього пакета: зупинки й колії навколо камери. Порожньо —
     *  або пакет їх не несе (усе до v3), або камера надто далеко. */
    val packStopsGeoJson: String,
    val packRailsGeoJson: String,
    val phantomLayers: List<SpatialLayer>,
    val pois: List<PoiEntity>,
    /** Serialised off the main thread by the canvas; null until the first build. */
    val poiGeoJson: String?,
    val savedPois: List<PoiEntity>,
    /** Місця Nightpost поруч (Builder B1 R4); порожня колекція — легітимний стан. */
    val venuePinsGeoJson: String = EMPTY_FC,
    val offlinePacksGeoJson: String,
    val notDownloadedGeoJson: String,
    /** Ламана в роботі; порожня колекція — режим малювання вимкнено. */
    val drawGeoJson: String = EMPTY_FC,
) {
    /** The transit ribbon owns the drawing while an itinerary is selected. */
    val drawnRoutes: List<MapPayload.PolylineData>
        get() = if (transitItinerary != null) emptyList() else routes

    val drawnAltRoutes: List<Triple<List<Pair<Double, Double>>, String, Int>>
        get() = if (transitItinerary != null) emptyList() else altRoutes

    val drawnManeuvers: List<ManeuverPins.Pin>
        get() = if (transitItinerary != null) emptyList() else maneuvers
}

/**
 * Per-contribution change detection.
 *
 * One sweep now runs every contribution, so without this a route re-cut every
 * 11 m would also re-serialise thousands of POIs — this file's own thermal
 * scar, re-inflicted by the very refactor meant to tidy it. A style reload
 * [invalidate]s everything: the new style has none of the old sources.
 */
internal class MapUpdateGate {
    private val last = HashMap<String, Any?>()

    fun changed(id: String, key: Any?): Boolean {
        if (last.containsKey(id) && last[id] == key) return false
        last[id] = key
        return true
    }

    fun invalidate() = last.clear()
}

private fun MapStyleHandle.style(): Style = (this as StyleHandle).style

/**
 * The catch-all's rules, which used to be two chained `firstOrNull` lambdas
 * inside the click listener. A named thing beats a merely tagged one; a
 * polygon under the finger is terrain (the country-wide alert rings once ate
 * every tap in Ukraine); admin and boundary layers are not places.
 */
private val GROUND_FEATURE_TAP = TapSpec(
    rank = TapRank.GROUND_FEATURE,
    catchAll = true,
    geometry = TapGeometry.POINTS_ONLY,
    excludesLayers = setOf("admin", "boundary", "alert"),
    prefers = listOf(
        setOf("name", "name:uk", "name:en"),
        setOf(
            "building", "amenity", "shop", "addr:housenumber", "housenumber",
            "ref", "parking", "barrier", "highway",
        ),
    ),
)

internal fun buildMapRegistry(
    inputs: MapGroundInputs,
    gate: MapUpdateGate,
    onStepFailure: (String, Throwable) -> Unit,
): ContributionRegistry {
    val i = inputs
    val mapIsDark = i.mapIsDark
    val registry = ContributionRegistry(onStepFailure)
    // Підлога підписів — одна на стиль, кладеться довкола КОЖНОГО гака, а не в
    // самих встановлювачах: див. MapLabelFloor. Нижче за це місце жоден внесок
    // не знає про неї — і не мусить.
    val floor = LabelFloor()

    fun add(
        id: String,
        tier: MapTier,
        lensTiers: Set<MapTier> = setOf(tier),
        tap: TapSpec? = null,
        install: ((Style) -> Unit)? = null,
        updateKey: Any? = Unit,
        update: ((Style, LensFrame) -> Unit)? = null,
    ) {
        registry.register(
            MapContribution(
                id = id,
                tier = tier,
                lensTiers = lensTiers,
                tap = tap,
                install = install?.let { hook ->
                    ContributionInstall { handle ->
                        val s = handle.style()
                        floor.capture(s)
                        hook(s)
                        floor.sink(s)
                    }
                },
                update = update?.let { hook ->
                    ContributionUpdate { handle, frame ->
                        if (gate.changed(id, updateKey to frame.lit(tier))) {
                            val s = handle.style()
                            // Шари, що народжуються лениво з оновлення (кадри
                            // радара), опускаються тим самим шляхом, що й решта.
                            floor.capture(s)
                            hook(s, frame)
                            floor.sink(s)
                        }
                    }
                },
            ),
        )
    }

    add(
        id = "groundCartography",
        tier = MapTier.GROUND_CARTOGRAPHY,
        // Ґрунт найперше: прохід перебирає ВСЕ, що знайде в чужому стилі, тож
        // нижче нього наші власні шари пішли б йому на перефарб.
        install = { s -> applyGroundCartography(s, mapIsDark) },
    )
    // ATLAS-2 — голос стежок за режимом. Ярус ґрунту, після картографії: вона
    // кладе базову рампу альфи, а це переписує рівно її.
    add(
        id = "footpathVoice",
        tier = MapTier.GROUND_CARTOGRAPHY,
        updateKey = i.scene,
    ) { s, frame -> applyFootpathVoice(s, frame.scene) }

    add(
        id = "labelVoice",
        tier = MapTier.LABEL_VOICE,
        // Тільки за ґрунтом: ґрунт кладе поле підпису й стек шрифту, голос —
        // кегель і ореол. Зворотний порядок зніс би кегель.
        install = { s -> applyLabelVoice(s, mapIsDark) },
    )
    add(
        id = "labelLocale",
        tier = MapTier.LABEL_VOICE,
        // Останнім із трьох проходів над підписом: ґрунт кладе поле, голос —
        // кегель, а мова переписує САМЕ ПОЛЕ. Раніше за голос вона зняла б
        // кегель разом із собою; окремого ярусу не заводимо — у межах ярусу
        // порядок реєстрації і є порядком.
        install = { s -> applyLabelLocale(s, deviceReadsCyrillic(i.context)) },
    )
    add(
        id = "groundBackfill",
        tier = MapTier.GROUND_BACKFILL,
        install = { s -> installGroundBackfill(s, mapIsDark, i.context) },
    )
    add(
        id = "groundFacts",
        tier = MapTier.GROUND_FACTS,
        install = { s -> installGroundFacts(s, mapIsDark) },
    )

    add("hillshade", MapTier.HILLSHADE, install = { s ->
        if (i.terrainDepth) installHillshade(s, mapIsDark, i.terrainPackMaxZoom ?: NETWORK_DEM_MAX_ZOOM)
    })

    add(
        id = "radar",
        tier = MapTier.RADAR,
        updateKey = Triple(i.radarOverlay?.urlTemplate, i.radarOverlay?.frameEpochSec, mapIsDark),
    ) { s, frame ->
        // The reel loop owns the animated frames; this keeps the still overlay
        // honest when the Lens darkens the tier or the fetch gave no reel.
        applyRadar(s, i.radarOverlay, frame.lit(MapTier.RADAR))
    }

    add(
        id = "alertOverlay",
        tier = MapTier.ALERT_WASH,
        install = { s -> installAlertOverlay(s, i.oblastGeoJson, i.context, mapIsDark) },
        updateKey = i.alertStates,
    ) { s, frame ->
        updateAlertOverlay(s, i.alertStates, frame.lit(MapTier.ALERT_WASH), i.centroidOf)
    }

    add(
        id = "notDownloadedHatch",
        tier = MapTier.COVERAGE_HATCH,
        // FREE — «тут не завантажено» ліцензія ховати не може.
        install = { s -> installNotDownloadedHatch(s, i.context, mapIsDark) },
        updateKey = i.notDownloadedGeoJson,
    ) { s, frame ->
        updateNotDownloadedHatch(
            s,
            if (frame.lit(MapTier.COVERAGE_HATCH)) i.notDownloadedGeoJson else EMPTY_FC,
        )
    }

    // Under the arrow, on the arrow's own tier: EW_TOPOLOGY is the trail the
    // owner cleared off the ground, and the halo is not it.
    add("trustHalo", MapTier.OPERATOR_PIN, install = { s -> installTrustHalo(s) })
    add(
        id = "operatorPin",
        tier = MapTier.OPERATOR_PIN,
        install = { s -> installOperatorPin(s, i.context) },
        // The per-frame flux loop paints the moving dot; this only has to make
        // a freshly loaded style show the fix it already has.
        updateKey = i.operatorLocation?.let { Triple(it.lat, it.lon, it.bearing) },
    ) { s, _ ->
        updateOperatorPin(s, i.operatorLocation, i.bearing, i.compassFidelity, mapIsDark)
    }

    add(
        id = "searchPin",
        tier = MapTier.SEARCH_PIN,
        install = { s -> installSearchPin(s, mapIsDark) },
        updateKey = i.searchPin,
    ) { s, frame ->
        updateSearchPin(s, i.searchPin.takeIf { frame.lit(MapTier.SEARCH_PIN) }, mapIsDark)
    }

    // PRESENCE — перед стрічкою маршруту НАВМИСНЕ: у межах ярусу порядок
    // реєстрації є порядком укладання, тож смуги лягають під облямівку лінії.
    add(
        id = "laneBand",
        tier = MapTier.ROUTE_LINE,
        install = { s -> installLaneBand(s, mapIsDark) },
        updateKey = i.laneBandGeoJson,
    ) { s, frame ->
        updateLaneBand(s, i.laneBandGeoJson?.takeIf { frame.lit(MapTier.ROUTE_LINE) })
    }

    add(
        id = "routeLayer",
        tier = MapTier.ROUTE_LINE,
        install = { s ->
            installRouteLayer(s, mapIsDark, i.context)
            installRouteEnds(s, mapIsDark, i.context)
        },
        updateKey = Triple(i.drawnRoutes, i.routeTrimAt, mapIsDark),
    ) { s, _ ->
        updateRouteLayer(s, i.drawnRoutes, mapIsDark, trimAt = i.routeTrimAt)
        updateRouteEnds(s, i.drawnRoutes, guiding = i.routeTrimAt != null)
    }
    // Alt routes used to appear wherever the update pass first reached them;
    // the skeleton is now laid at install time, on the tier of the base line.
    add(
        id = "altRoutes",
        tier = MapTier.ROUTE_LINE,
        install = { s -> installAltRouteLayer(s, mapIsDark) },
        updateKey = i.drawnAltRoutes,
    ) { s, _ -> updateAltRoutes(s, i.drawnAltRoutes, mapIsDark) }

    add(
        id = "routeRegisterLayer",
        tier = MapTier.ROUTE_REGISTER,
        install = { s -> installRouteRegisterLayer(s, mapIsDark) },
        updateKey = i.degradedSegments,
    ) { s, _ -> updateRouteRegisterLayer(s, i.degradedSegments, mapIsDark) }

    add(
        id = "maneuverPins",
        tier = MapTier.MANEUVER_PINS,
        // Decoration without the line they annotate — both tiers must be lit.
        lensTiers = setOf(MapTier.MANEUVER_PINS, MapTier.ROUTE_LINE),
        install = { s -> installManeuverLayer(s, i.context) },
        updateKey = i.drawnManeuvers to i.activeManeuverStep,
    ) { s, _ -> updateManeuverLayer(s, i.drawnManeuvers, i.activeManeuverStep) }

    // Чужий надісланий маршрут — ПІД фігурками того самого ярусу: лінія
    // веде до людини, а не навпаки, і закрити собою того, хто її надіслав,
    // вона не має права. Той самий ранг пальця, тож фігурка (точка) виграє
    // палець у лінії за відстанню, і тап по людині лишається тапом по людині.
    add(
        id = "peerRoutes",
        tier = MapTier.PEER_FIGURES,
        tap = TapSpec(
            TapRank.PEER_POINT,
            listOf(PEER_ROUTE_LAYER),
            geometry = setOf(TapGeometry.LINE),
        ),
        install = { s -> installPeerRoutes(s, mapIsDark) },
        updateKey = i.peerRoutesGeoJson,
    ) { s, frame ->
        updatePeerRoutes(s, i.peerRoutesGeoJson, frame.lit(MapTier.PEER_FIGURES), mapIsDark)
    }

    // СИМБІОЗ — люди на землі. Власний ярус і власний ранг пальця: фігурка
    // контакту не є ні знаком події, ні міткою шару, і плутати їх у тапі
    // означало б відкривати досьє OSM на людині.
    add(
        id = "peerFigures",
        tier = MapTier.PEER_FIGURES,
        tap = TapSpec(TapRank.PEER_POINT, listOf(PEERS_LAYER), geometry = TapGeometry.POINTS_ONLY),
        install = { s -> installPeerFigures(s, i.context, mapIsDark) },
        updateKey = i.peerFiguresGeoJson,
    ) { s, frame ->
        updatePeerFigures(s, i.peerFiguresGeoJson, frame.lit(MapTier.PEER_FIGURES), mapIsDark)
    }

    add(
        id = "peerWhere",
        tier = MapTier.PEER_FIGURES,
        tap = TapSpec(TapRank.PEER_POINT, listOf(PEER_WHERE_MARK, PEER_WHERE_FILL), geometry = setOf(TapGeometry.POINT, TapGeometry.AREA)),
        install = { s -> installPeerWhere(s, mapIsDark) },
        updateKey = i.peerWhereGeoJson,
    ) { s, frame ->
        updatePeerWhere(s, i.context, i.peerWhereGeoJson, frame.lit(MapTier.PEER_FIGURES), mapIsDark)
    }

    add(
        id = "eventMarks",
        tier = MapTier.EVENT_MARKS,
        tap = TapSpec(TapRank.EVENT_MARK, listOf(MARKS_LAYER), geometry = TapGeometry.POINTS_ONLY),
        install = { s -> installEventMarks(s, i.context, mapIsDark) },
        updateKey = i.eventMarksGeoJson,
    ) { s, frame ->
        updateEventMarks(s, i.eventMarksGeoJson, frame.lit(MapTier.EVENT_MARKS), mapIsDark)
    }

    // Мережа лягає ПЕРЕД стрічкою обраного маршруту: обидві — транспорт, але
    // стрічка є відповіддю, а мережа тлом, на якому питання ставлять.
    add(
        id = "packTransit",
        tier = MapTier.TRANSIT,
        // Ранг POI, а не TRANSIT_STOP: зупинка з пакета — це місце на землі,
        // а не вузол обраного маршруту. З однаковим рангом вони стояли б у
        // тих самих координатах і вирішувала б відстань, тобто випадок —
        // а картка «Посадка: тролейбус 14» мусить вигравати завжди.
        tap = TapSpec(TapRank.POI, listOf(PACK_STOPS_LAYER), geometry = TapGeometry.POINTS_ONLY),
        install = { s -> installPackTransitLayers(s, mapIsDark) },
        updateKey = i.packStopsGeoJson to i.packRailsGeoJson,
    ) { s, frame ->
        val lit = frame.lit(MapTier.TRANSIT)
        updatePackTransitLayers(
            s,
            if (lit) i.packStopsGeoJson else EMPTY_FC,
            if (lit) i.packRailsGeoJson else EMPTY_FC,
            mapIsDark,
        )
    }

    add(
        id = "transitLayers",
        tier = MapTier.TRANSIT,
        tap = TapSpec(TapRank.TRANSIT_STOP, listOf(TRANSIT_STOPS_LAYER)),
        install = { s -> installTransitLayers(s, mapIsDark) },
        updateKey = i.transitItinerary,
    ) { s, frame ->
        // Ярус нарешті читається: за кермом стрічка транзиту гасне цілком.
        updateTransitLayers(s, i.transitItinerary.takeIf { frame.lit(MapTier.TRANSIT) }, mapIsDark)
    }

    add(
        id = "phantomLayers",
        tier = MapTier.PHANTOM_LAYERS,
        tap = TapSpec(TapRank.LAYER_MARKER, listOf(LAYER_MARKERS_DOT)),
        install = { s -> installPhantomLayers(s, mapIsDark) },
        updateKey = i.phantomLayers,
    ) { s, _ -> updatePhantomLayers(s, i.phantomLayers, mapIsDark) }

    // Ламана, яку саме ведуть пальцем. Той самий ярус, що й решта власних
    // шарів, і одразу за ними: вона мусить лежати НАД ними, бо це єдине, чим
    // людина зараз керує.
    add(
        id = "drawLine",
        tier = MapTier.PHANTOM_LAYERS,
        install = { s -> installDrawLine(s, mapIsDark) },
        updateKey = i.drawGeoJson,
    ) { s, _ -> updateDrawLine(s, i.drawGeoJson) }

    add(
        id = "buildingExtrusions",
        tier = MapTier.BUILDING_EXTRUSION,
        install = { s ->
            if (i.terrainDepth) installBuildingExtrusions(s, mapIsDark)
        },
        updateKey = i.terrainDepth,
    ) { s, frame ->
        setBuildingExtrusionsVisible(s, frame.lit(MapTier.BUILDING_EXTRUSION))
    }

    add(
        id = "poiOverlay",
        tier = MapTier.POI,
        tap = TapSpec(
            TapRank.POI,
            POIOverlay.PIN_LAYER_IDS + POIOverlay.POI_CLUSTER_LAYER_ID,
        ),
        install = { s -> POIOverlay.install(s, i.context, mapIsDark) },
        updateKey = i.poiGeoJson ?: i.pois,
    ) { s, frame ->
        val visible = frame.lit(MapTier.POI)
        val json = i.poiGeoJson
        if (json != null) POIOverlay.apply(s, json, visible) else POIOverlay.update(s, i.pois, visible)
    }

    add(
        id = "savedPlacesOverlay",
        tier = MapTier.SAVED_PLACES,
        // Підпис — теж мітка під пальцем. Обидва шари сидять на ОДНОМУ джерелі,
        // тож ознаки однакові, а дотик по імені більше не влучає в порожнечу.
        tap = TapSpec(
            TapRank.SAVED_PLACE,
            listOf(
                SavedPlacesOverlay.SAVED_POI_LAYER_ID,
                SavedPlacesOverlay.SAVED_POI_LABEL_LAYER_ID,
            ),
        ),
        install = { s -> SavedPlacesOverlay.install(s, i.context, mapIsDark) },
        updateKey = i.savedPois,
    ) { s, frame ->
        SavedPlacesOverlay.update(s, i.savedPois, frame.lit(MapTier.SAVED_PLACES))
    }

    add(
        id = "venuePins",
        tier = MapTier.POI,
        tap = TapSpec(TapRank.SAVED_PLACE, listOf(VenuePinsOverlay.LAYER_ID, VenuePinsOverlay.LABEL_LAYER_ID)),
        install = { s -> VenuePinsOverlay.install(s, i.context, mapIsDark) },
        updateKey = i.venuePinsGeoJson,
    ) { s, frame ->
        VenuePinsOverlay.update(s, i.venuePinsGeoJson, frame.lit(MapTier.POI))
    }

    add(
        id = "offlinePacks",
        tier = MapTier.OFFLINE_PACKS,
        install = { s -> installOfflinePacks(s) },
        updateKey = i.offlinePacksGeoJson,
    ) { s, _ -> updateOfflinePacks(s, i.offlinePacksGeoJson) }

    // The alternative the operator did NOT pick is still a choice, and the map
    // is where the difference is legible — so it outranks everything.
    registry.register(
        MapContribution(
            id = "routeAlternatives",
            tier = MapTier.ROUTE_LINE,
            tap = TapSpec(
                TapRank.ROUTE_ALTERNATIVE,
                listOf(ALT_LABEL_LAYER_ID, ALT_ROUTE_LAYER_ID),
            ),
        ),
    )
    registry.register(
        MapContribution(
            id = "groundFeature",
            tier = MapTier.GROUND_CARTOGRAPHY,
            tap = GROUND_FEATURE_TAP,
        ),
    )
    return registry
}
