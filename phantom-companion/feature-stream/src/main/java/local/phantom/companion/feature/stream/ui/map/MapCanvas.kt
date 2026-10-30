package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.design.license.LocalEntitlements
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.geo.basemap.OfflineBasemapInfo
import local.phantom.companion.core.geo.terrain.TerrainPackInfo
import local.phantom.companion.core.geo.basemap.contains
import local.phantom.companion.core.geo.sky.SkyPhase
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPose
import local.phantom.companion.feature.stream.ui.map.atlas.CameraService
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionRegistry
import local.phantom.companion.feature.stream.ui.map.atlas.DayPhase
import local.phantom.companion.feature.stream.ui.map.atlas.DensityClass
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.LensEngine
import local.phantom.companion.feature.stream.ui.map.atlas.MapTier
import local.phantom.companion.feature.stream.ui.map.atlas.SceneSignals
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * ATLAS-0b — the canvas is a HOST, not a composer.
 *
 * It owns the MapLibre view, its lifecycle, the listeners hanging off the map,
 * and the per-frame applier. WHAT draws and in what order is
 * [buildMapRegistry]; WHO may write the camera is [CameraService]; WHAT is
 * allowed on screen is [LensEngine]. This file decides none of the three — that
 * is the whole point of the surgery, and the reason it fits on a screen again.
 */
@Composable
internal fun MapCanvas(
    ui: MapUiState,
    viewModel: MapViewModel,
    operatorLocation: LocationFix?,
    pois: List<PoiEntity>,
    savedPois: List<PoiEntity>,
    routes: List<MapPayload.PolylineData>,
    maneuvers: List<ManeuverPins.Pin> = emptyList(),
    /** Крок плану, що зараз попереду; -1 поза супроводом. */
    activeManeuverStep: Int = -1,
    /** Variants the operator did NOT pick: line, cost label, option index. */
    altRoutes: List<Triple<List<Pair<Double, Double>>, String, Int>> = emptyList(),
    /** TRANSIT GEOMETRY — the selected transit itinerary; when present the
     *  multi-colour ribbon draws and the generic route line stands down. */
    transitItinerary: local.phantom.companion.core.data.transit.Itinerary? = null,
    /** Мережа транспорту з дорожнього пакета — зупинки й колії навколо камери. */
    packStopsGeoJson: String = EMPTY_FC,
    packRailsGeoJson: String = EMPTY_FC,
    phantomLayers: List<SpatialLayer>,
    oblastShapes: OblastShapes,
    /** Під яким світлом стоїть оператор — джерело полярності на автошляху. */
    skyPhase: SkyPhase,
    /**
     * Чи стоїть вирок про тривоги на мовчазній стрічці (`SkyVerdict.feedSilent`).
     *
     * Знімає його `MapScreen` — один раз, одним `verdict()`, на одному
     * годиннику; сюди він приходить готовим, бо Лінза й пігулка не мають права
     * дійти різних висновків про те саме небо. Лінза читає це як здатність
     * `Capability.SKY_UNKNOWN`: холодний старт без фіксу — це «не знаю», і
     * пігулка з маршрутом до укриття за нею мусить бути досяжною саме тоді.
     */
    skyFeedSilent: Boolean,
    /**
     * Чи оголошує пак під фіксом стрічку неба взагалі. `false` — країна без
     * тривог: `Capability.SKY_ABSENT` гасить пігулку, а не помʼякшує її слова.
     */
    skyFeedDeclared: Boolean,
    /** The provider- and phase-resolved basemap URL for AUTO mode. */
    autoStyleUrl: String,
    styleKey: String,
    // NAV-6 — null where no verified GTFS-RT feed covers the view (honest «нема»).
    transitRepository: TransitRepository?,
    selectedVehicle: TransitVehicle?,
    offlinePacks: List<RegionInfo> = emptyList(),
    offlineStyleUrl: String = "",
    /** ОФЛАЙН-ОСНОВА — файл тайлів, внесений через «Точність». Null: мапа
     *  малюється з мережі, як завжди. */
    offlineBasemap: OfflineBasemapInfo? = null,
    /** РЕЛЬЄФ КУЗНІ — локальний DEM. Null: тіні гір беруться з мережі. */
    terrainPack: TerrainPackInfo? = null,
    /** W-6 — the ground is watched (or a live route forbids rest). */
    active: Boolean = true,
    /** СОНЦЕ — solar atmosphere wash. Read in draw, not composition: as a Color
     *  parameter its 1500ms animation recomposed this composable every frame. */
    skyScrim: () -> androidx.compose.ui.graphics.Color = { androidx.compose.ui.graphics.Color.Transparent },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Відступи вписування маршруту. Несиметричні, бо екран несиметричний:
    // угорі пігулка тривоги й вирок про позицію, унизу — смуга відповіді.
    val density = LocalDensity.current
    val fitInsets = with(density) {
        EdgeInsets(
            left = ROUTE_FIT_SIDE.roundToPx(),
            top = ROUTE_FIT_TOP.roundToPx(),
            // Праворуч — виміряна смуга деки, а не те саме «на око», що ліворуч:
            // 32dp були вужчі за саму деку, тож хвіст вписаного маршруту лягав
            // під кнопки й читався як обрізаний.
            right = MapChromeMetrics.RailBand.roundToPx(),
            bottom = (ROUTE_FIT_BAND + LocalBottomChromeInset.current + ROUTE_FIT_AIR).roundToPx(),
        )
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        // SurfaceView, not TextureView: the thermal win is real (256%→20% CPU)
        // and after D1's migration nothing is left to delaminate against it.
        // MapLibre paints its own cream until the first frame; that cream WAS the
        // "blank map". The ground's own colour instead, so loading reads as loading.
        val options = org.maplibre.android.maps.MapLibreMapOptions
            .createFromAttributes(context)
            .textureMode(false)
            .foregroundLoadColor(if (skyPhase.dark) GROUND_NIGHT else GROUND_DAY)
            .camera(firstPose(operatorLocation))
        MapView(context, options).apply {
            setMaximumFps(60)
            getMapAsync { map ->
                // ODbL — авторство лишається, але воно НАШЕ: рядок «© OpenStreetMap»
                // живе в MapInstrumentPlate (MapControls.kt), а вендорський віджет
                // гаситься тут.
                //
                // Це твердження один раз уже було хибним, і саме тому воно тепер
                // написане разом зі сторожем. З 29.08 по 30.08 платівку малювали
                // ЛИШЕ за відкритої панелі інструментів (MapChrome.kt), тож у
                // спокої авторства не було ніде: віджет вимкнено тут, а «наше
                // безумовне» до екрана не доходило. Сторожа, який це ловив
                // (MapAttributionMarginTest), прибрали разом із віджетом — тобто
                // прибрали і свідка.
                // Заміну поставлено: MapAttributionAlwaysVisibleTest. Гасити
                // віджет можна рівно доти, доки той сторож зелений.
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                // Тайли нижчих зумів наперед — десятки МБ нативної памʼяті заради швидшого віддалення.
                map.prefetchZoomDelta = 0

                // СТЕЛЯ ВІДДАЛЕННЯ. Заміряно на склі 31.08: відсунувши мапу, ми
                // доходимо до стану, де на екрані НЕ ЛИШАЄТЬСЯ НІЧОГО — чисте
                // поле кольору тла, без контуру материків, без підпису, без
                // слова пояснення, і живий хром поверх порожнечі. Смуга масштабу
                // при цьому показувала «20000».
                //
                // Це не «десь у краю світу»: перший рух людини, яка хоче
                // зрозуміти, що застосунок узагалі вміє, — відсунути.
                //
                // Число НЕ підібране на смак, а виведене: у Web Mercator висота
                // світу на масштабі z дорівнює 512·2^z незалежних точок, тож
                // світ перестає бути нижчим за екран рівно на
                // z = log2(висота_екрана_dp / 512). На полотні власника
                // (891 dp) це ≈0.80.
                //
                // Критерій саме такий, а не «заборонити порожнечу»: стеля
                // тримає камеру там, ДЕ ДАНІ Є, а не ховає симптом. Тому
                // рахуємо від конкретного екрана, а не ставимо константу —
                // на іншому пристрої правильне число інше.
                val dm = context.resources.displayMetrics
                val screenDp = dm.heightPixels / dm.density
                map.setMinZoomPreference(
                    (kotlin.math.ln(screenDp / 512.0) / kotlin.math.ln(2.0))
                        .coerceAtLeast(0.0),
                )

                ui.mapRef = map
            }
        }
    }
    MapViewLifecycle(mapView, context, active)

    // Viewport-driven OSM building prefetch. Nothing renders it: it feeds the
    // ground-register readout and RouteRegisterProfile.
    val crystalFeed = remember {
        local.phantom.companion.feature.stream.ui.map.crystal.CrystalCityFeed(
            local.phantom.companion.feature.stream.ui.map.crystal.BuildingTileStore(context.cacheDir),
        )
    }
    DisposableEffect(crystalFeed) { onDispose { crystalFeed.destroy() } }
    val crystalTiles by crystalFeed.tiles.collectAsStateWithLifecycle()
    val crystalVisibleTiles by crystalFeed.visibleTiles.collectAsStateWithLifecycle()
    val isOccupiedGround: (Double, Double) -> Boolean = remember(oblastShapes) {
        { lat, lon -> oblastShapes.oblastAt(lat, lon) in OCCUPIED_OBLASTS }
    }
    MapGroundRegisterProbe(ui, crystalTiles, crystalVisibleTiles, isOccupiedGround)

    // Обʼємність мапи — платна. Пласка мапа під нею не питає ліцензію ніколи.
    val terrainDepth = LocalEntitlements.current.has(LicenseFeature.MapTerrain3d)

    val isDarkTheme = skyPhase.dark
    // Whether the MAP is dark, which a forced basemap decides regardless of the
    // app theme. One value, read in one place, handed to every themed call.
    val mapIsDark = when (ui.basemapOverride) {
        MapBasemap.DARK -> true
        MapBasemap.LIGHT, MapBasemap.MINIMAL -> false
        else -> isDarkTheme
    }
    // Форсована основа не має фази: оператор обрав землю сам, і сонце в цьому
    // виборі не бере участі. Сутінки Лінзи бачить тільки автоматичний шлях.
    val mapPhase = when {
        ui.basemapOverride != null -> if (mapIsDark) DayPhase.NIGHT else DayPhase.DAY
        skyPhase == SkyPhase.DUSK -> DayPhase.DUSK
        skyPhase == SkyPhase.NIGHT -> DayPhase.NIGHT
        else -> DayPhase.DAY
    }

    // ── ATLAS: the three owners ───────────────────────────────────────────
    val gate = remember { MapUpdateGate() }
    val lens = remember { LensEngine() }
    var camera by remember { mutableStateOf<CameraService?>(null) }
    var registry by remember { mutableStateOf<ContributionRegistry?>(null) }
    var hatchGeoJson by remember { mutableStateOf(EMPTY_FC) }
    var poiGeoJson by remember { mutableStateOf<String?>(null) }

    DisposableEffect(ui.mapRef) {
        val map = ui.mapRef
        camera = map?.let {
            val port = MapLibreCameraPort(it)
            // The flyover keeps its own cancellable per-leg chain — through the
            // port now, so the operator's finger still kills it mid-leg.
            port.runFlyover = { path ->
                scope.launch {
                    local.phantom.companion.feature.stream.ui.map.nav.RouteFlyover
                        .fly(port, path.map { p -> p.lat to p.lon })
                }
            }
            CameraService(port = port, now = { android.os.SystemClock.uptimeMillis() })
        }
        // The chrome, the sheets and the search results all state camera
        // intents; the holder is where they already look for the map.
        ui.camera = camera
        onDispose {
            camera = null
            ui.camera = null
        }
    }

    val nearbyActive by viewModel.nearbyActive.collectAsStateWithLifecycle()
    val motionProfile by viewModel.motion.collectAsStateWithLifecycle()
    val guidanceForCamera by viewModel.guidance.state.collectAsStateWithLifecycle()
    val isGuiding = guidanceForCamera is local.phantom.companion.core.nav.GuidanceState.Guiding
    val ownOblast = operatorLocation?.let { oblastShapes.oblastAt(it.lat, it.lon) }
    // Евакуація веде так само по-справжньому, як маршрут, і сцена мусить це
    // знати ТУТ: доки вона виводилась двічі, полотно про неї не чуло взагалі.
    val evacuation by viewModel.evacuationState.collectAsStateWithLifecycle()

    // ATLAS-2 — сцену більше не вирішує те, що відкрито на екрані. Ведення,
    // сирена над власною областю, витримана хода і транзитний намір ідуть
    // ПЕРЕД «щось відкрито», а тридцять секунд без дотику дають спокій.
    //
    // Сцена ОДНА і виводиться тут, бо тут живе Лінза. Хром читає її ж через
    // `ui.sceneMode` — доки сцен було дві (густота з діяльності, хром зі стану
    // екрана), вони мали право розійтись саме там, де ця хвиля й важить.
    val scene = rememberSceneMode(
        SceneSignals(
            guiding = routes.isNotEmpty() || isGuiding ||
                evacuation is EvacuationState.ActiveGuidance,
            ownOblastAlarm = ownOblast?.let { ui.alertStates[it]?.active } == true,
            transitContext = ui.transitHubOpen || transitItinerary != null,
            motion = motionProfile?.state.toMotionClass(),
            motionTrusted = (motionProfile?.confidence ?: 0f) >= MODE_MOTION_CONFIDENCE,
            // Панелі хрому теж рахуються: інакше екран стишився б під рукою,
            // яка щойно відкрила рейку основи, і кнопки «зникли» б удруге.
            browsing = ui.dossierTarget != null || ui.selectedLayerMarker != null ||
                ui.selectedSavedPlace != null || ui.droppedPoint != null ||
                ui.selectedThreatOblast != null || nearbyActive != null || ui.searchOpen ||
                phantomLayers.isNotEmpty() || selectedVehicle != null || ui.toolsExpanded ||
                ui.offlineOpen || ui.alarmSheetOpen || ui.aroundOpen,
        ),
        interactionAtMs = { ui.cameraGestureAtMs },
    )
    // Публікація для хрому — ефектом, не в композиції: запис у спільний холдер
    // під час складання полотна читав би сусідній хром у тому ж кадрі.
    LaunchedEffect(scene) { ui.sceneMode = scene }

    LaunchedEffect(ui.viewportCenter) {
        ui.viewportCenter?.let { (lat, lon, zoom) -> MapLastCamera.write(MapLastCamera.prefs(context), lat, lon, zoom) }
    }

    val lensFrame = lens.frame(
        mapLensInput(
            ui = ui,
            scene = scene,
            zoom = ui.viewportCenter?.third ?: DEFAULT_ZOOM,
            terrainDepth = terrainDepth,
            ownOblastKnown = ownOblast != null,
            ownOblastAlarm = ownOblast?.let { ui.alertStates[it]?.active } == true,
            skyFeedSilent = skyFeedSilent,
            skyFeedDeclared = skyFeedDeclared,
            pinnedPlaces = nearbyActive != null,
            motion = motionProfile?.state,
            mapPhase = mapPhase,
        ),
    )

    // Шар, якого вимагає сам момент (сирена → укриття). Ключі: сама вимога;
    // те, чого їй БРАКУЄ на землі — щоб шар, що дожив свій TTL посеред
    // тривоги, вернувся; і ГРУБА позиція — на дрібнішій сітці кожен фікс
    // перезапускав би Overpass.
    val summonsPending = lensFrame.summons - phantomLayers.mapTo(HashSet()) { it.spec.kind }
    LaunchedEffect(
        lensFrame.summons,
        summonsPending,
        operatorLocation?.let { Math.round(it.lat * 100) to Math.round(it.lon * 100) },
    ) {
        val here = operatorLocation ?: return@LaunchedEffect
        viewModel.applyLensSummons(lensFrame.summons, here.lat, here.lon)
    }

    // FIELD FIX — the route line is re-cut as the operator advances, on a ~11 m
    // grid: fine enough that the head stays under the dot, coarse enough that a
    // long route is not re-serialised at fix rate (this file's thermal scar).
    val trimAt = operatorLocation?.let {
        Math.round(it.lat * 1e4) / 1e4 to Math.round(it.lon * 1e4) / 1e4
    }
    val degradedSegments = remember(routes, transitItinerary, crystalTiles) {
        // Selected variant is always LAST in `routes` (publishPlan draws it on
        // top) and is NOT trimmed to `here` the way the base line is.
        val activePoints = routes.lastOrNull()?.points?.map { it.lat to it.lon }.orEmpty()
        if (transitItinerary != null || activePoints.size < 2) {
            emptyList()
        } else {
            val profile = RouteRegisterProfile
                .classify(activePoints, crystalTiles, isOccupied = isOccupiedGround)
            ui.routeWorstRegister = RouteRegisterProfile.worstKnown(profile)
            RouteRegisterProfile.degradedSegments(profile)
        }
    }

    // A «поруч» flood is thousands of places; one JSONObject each plus the
    // toString is tens of ms of pure string work — built on a worker, applied
    // by the sweep on the caller's thread.
    // Підпис піна тією ж мовою, що й вулиця під ним — інакше турист дістав би
    // латинські вулиці й кирилічні вивіски на одному екрані.
    val cyrillicReader = remember(context) { deviceReadsCyrillic(context) }
    // ATLAS-2 — бюджет щільності кусається САМЕ тут, у побудові джерела.
    // Зум сюди приходить квантованим до єдиного порога, який його читає: сирий
    // зум перебирав би тисячі JSONObject на кожну зупинку камери, і це та сама
    // термальна яма, яку цей файл уже одного разу викопав.
    val placeBudget = lensFrame.budget(DensityClass.PLACE)
    val placeZoom = if ((ui.viewportCenter?.third ?: DEFAULT_ZOOM) >= PlaceLens.ON_FOOT_MIN_ZOOM) {
        PlaceLens.ON_FOOT_MIN_ZOOM
    } else {
        0.0
    }
    LaunchedEffect(pois, cyrillicReader, scene, placeBudget, placeZoom) {
        poiGeoJson = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            POIOverlay.featureCollection(
                PlaceLens.select(pinnedPlaces(pois), scene, placeZoom, placeBudget),
                latin = !cyrillicReader,
            )
        }
    }

    // NAV-5 — знаки подій живуть у шарі стилю; тут лише збірка сцени, бо
    // тільки звідси видно і загрози сесії, і POI, і вьюпорт.
    val eventMarksGeoJson = rememberEventMarksGeoJson(
        viewModel, pois, routes, ui.viewportCenter, scene,
        alertOverhead = ownOblast?.let { ui.alertStates[it]?.active } == true,
    )
    // Дихання укриттів — обмежене (ShelterBreath.CYCLES) і лише коли його є що
    // показати; нова зупинка камери дає ще один подих, а не вічний цикл.
    val breathing = "\"breathe\":true" in eventMarksGeoJson
    androidx.compose.runtime.LaunchedEffect(breathing, ui.viewportCenter) {
        if (!breathing) return@LaunchedEffect
        val breath = local.phantom.companion.feature.stream.ui.map.world.ShelterBreath
        val start = android.os.SystemClock.uptimeMillis()
        while (true) {
            val f = breath.frame(android.os.SystemClock.uptimeMillis() - start)
            ui.mapRef?.style?.getLayer(MARKS_BREATH_LAYER)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.circleRadius((f ?: breath.rest).radiusDp),
                org.maplibre.android.style.layers.PropertyFactory.circleOpacity((f ?: breath.rest).opacity),
            )
            if (f == null) break
            kotlinx.coroutines.delay(breath.FRAME_MS)
        }
    }

    // СИМБІОЗ — люди, що поділились місцем. Один рядок на батч; порожній потік
    // (месенджерний бік ще не приїхав) не коштує ані кадру, ані такту.
    val peerFiguresGeoJson =
        local.phantom.companion.feature.stream.ui.map.peers.rememberPeerFiguresGeoJson(viewModel)
    val peerRoutesGeoJson = rememberPeerRoutesGeoJson(viewModel)
    val peerWhereGeoJson = local.phantom.companion.feature.stream.ui.map.peers.rememberPeerWhereGeoJson(viewModel, ui.myWorldOnMap, ui.meeting?.let { it.lat to it.lon })

    // Builder B1 (R4): опубліковані місця Nightpost поруч — піни; питаємо там, де людина.
    val venuePins by viewModel.venuePins.collectAsStateWithLifecycle()
    val venuePinsGeoJson = remember(venuePins) { local.phantom.companion.feature.stream.ui.map.around.venuePinsGeoJson(venuePins) }
    val aroundCell = operatorLocation?.let { Math.round(it.lat * 100) to Math.round(it.lon * 100) }
    LaunchedEffect(aroundCell) { operatorLocation?.let { viewModel.loadAround(it.lat, it.lon) } }

    // PRESENCE — the band for the maneuver being approached, or nothing. The
    // geometry was baked at plan time; this only chooses which pre-cut string
    // the source holds, so a fix costs a map lookup and no arithmetic.
    val laneBands by viewModel.laneBands.collectAsStateWithLifecycle()
    val laneBandGeoJson = (guidanceForCamera as? local.phantom.companion.core.nav.GuidanceState.Guiding)
        ?.progress
        ?.takeIf { it.laneApproach }
        ?.let { p -> laneBands.firstOrNull { it.stepIndex == p.stepIndex }?.geoJson }

    val centroidOf = remember(oblastShapes) { oblastShapes::centroidOf }
    // Рядок будується лише коли змінились самі вершини: інакше кожен кадр
    // камери переписував би джерело мапи заради тієї самої ламаної.
    val drawPoints = ui.drawPick?.points
    val drawHeld = ui.drawPick?.selectedIndex
    val drawGeoJson = remember(drawPoints, drawHeld) { buildDrawGeoJson(drawPoints.orEmpty(), drawHeld) }
    val inputs = MapGroundInputs(
        context = context,
        scene = scene,
        mapIsDark = mapIsDark,
        terrainDepth = terrainDepth,
        terrainPackMaxZoom = terrainPack?.maxZoom,
        oblastGeoJson = oblastShapes.rawGeoJson,
        centroidOf = centroidOf,
        alertStates = ui.alertStates,
        radarOverlay = ui.radarOverlay,
        operatorLocation = operatorLocation,
        bearing = ui.bearing,
        compassFidelity = ui.compassFidelity,
        searchPin = ui.searchPin ?: ui.dossierTarget?.let { it.lat to it.lon },
        routes = routes,
        altRoutes = altRoutes,
        routeTrimAt = trimAt,
        degradedSegments = degradedSegments,
        maneuvers = maneuvers,
        activeManeuverStep = activeManeuverStep,
        laneBandGeoJson = laneBandGeoJson,
        eventMarksGeoJson = eventMarksGeoJson,
        peerFiguresGeoJson = peerFiguresGeoJson,
        peerRoutesGeoJson = peerRoutesGeoJson,
        peerWhereGeoJson = peerWhereGeoJson,
        transitItinerary = transitItinerary,
        packStopsGeoJson = packStopsGeoJson,
        packRailsGeoJson = packRailsGeoJson,
        phantomLayers = phantomLayers,
        pois = pois,
        poiGeoJson = poiGeoJson,
        savedPois = savedPois,
        venuePinsGeoJson = venuePinsGeoJson,
        offlinePacksGeoJson = ui.offlinePacksGeoJson,
        notDownloadedGeoJson = hatchGeoJson,
        drawGeoJson = drawGeoJson,
    )
    val liveInputs by rememberUpdatedState(inputs)
    val liveFrame by rememberUpdatedState(lensFrame)
    val liveRegistry by rememberUpdatedState(registry)

    fun composeRegistry(): ContributionRegistry = buildMapRegistry(liveInputs, gate) { id, e ->
        android.util.Log.e("PhantomMapStyle", "contribution '$id' failed: ${e.message}", e)
    }

    // MAP-1 + MAP-5 — the single source of truth for style binding. Keys on
    // (mapRef, styleKey) so it re-fires when the real map arrives, the theme
    // flips, or the operator forces a basemap. offlineBasemap?.path joins the
    // key too: a fresh import must force the same re-bind, even though none
    // of the other three changed.
    val online by rememberMapOnline()
    val localBasemap = localBasemapFor(offlineBasemap, online)
    LaunchedEffect(ui.mapRef, styleKey, localBasemap?.path, terrainPack?.path) {
        val map = ui.mapRef ?: return@LaunchedEffect
        val uri = when (ui.basemapOverride) {
            // ATLAS-1: рейка більше не перемикає чужі файли стилю — вона
            // перемикає варіант нашого. МІНІМАЛ нарешті має власний: доти він
            // віддавав день і тим брехав про те, чого не робив.
            MapBasemap.DARK -> OBSIDIAN_NIGHT_STYLE
            MapBasemap.LIGHT -> OBSIDIAN_DAY_STYLE
            MapBasemap.MINIMAL -> OBSIDIAN_MINIMAL_STYLE
            else -> autoStyleUrl
        }
        // ОФЛАЙН-ОСНОВА — лише наш власний Обсидіан несе `sources.openmaptiles`
        // у формі, яку [applyOfflineBasemap] уміє патчити; чужий постачальник
        // (провідник обрав інший) лишається на мережі, як і завжди.
        val basemap = localBasemap
        val terrain = terrainPack
        fun withLocalTerrain(json: String): String =
            if (terrain == null) json else applyLocalTerrain(json, terrain.path, terrain.maxZoom)
        val builder = if (basemap != null && uri.startsWith(OBSIDIAN_STYLE_PREFIX)) {
            val assetPath = uri.removePrefix("asset://")
            // Диск — не головний потік, навіть для одного файла на кілька
            // сотень кілобайт: `fromUri` нижче робить те саме читання сама, на
            // власному потоці, і ця гілка не має права бути гіршою.
            val styleJson = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            }
            Style.Builder().fromJson(withLocalTerrain(applyOfflineBasemap(styleJson, basemap.path)))
        } else if (uri.startsWith(OBSIDIAN_STYLE_PREFIX) && terrainPack != null) {
            // Рельєф не чекає на офлайн-основу: пакет кузні вноситься в стиль
            // і тоді, коли тайли основи ще їдуть із мережі.
            val styleJson = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open(uri.removePrefix("asset://")).bufferedReader().use { it.readText() }
            }
            Style.Builder().fromJson(withLocalTerrain(styleJson))
        } else {
            Style.Builder().fromUri(uri)
        }
        map.setStyle(builder) { mapStyle ->
            if (!ui.cameraInitialized) {
                val initial = operatorLocation
                val home = local.phantom.companion.feature.stream.ui.map.country.countryHome()
                val last = MapLastCamera.read(MapLastCamera.prefs(context))
                // Through the port, not the map: an instant placement is still a
                // camera write, and the ratchet knows exactly one writer.
                MapLibreCameraPort(map).move(
                    CameraPose(
                        lat = last?.lat ?: initial?.lat ?: home.lat,
                        lon = last?.lon ?: initial?.lon ?: home.lon,
                        zoom = last?.zoom ?: if (initial != null) OPERATOR_ZOOM else home.zoom,
                        tiltDeg = 0.0,
                        bearingDeg = 0.0,
                    ),
                )
                ui.cameraInitialized = true
            }
            // A new style has none of the old sources, so every contribution is
            // stale by definition: the gate must forget what it applied.
            gate.invalidate()
            val fresh = composeRegistry()
            val handle = StyleHandle(mapStyle)
            fresh.install(handle)
            fresh.update(handle, liveFrame)
            registry = fresh
        }
    }

    // ОФЛАЙН-ОСНОВА — межа внесеного файла. Поза нею MBTilesFileSource не має
    // тайлів, і порожній екран без слова читається як «застосунок зламався»
    // (кадр 31.08, MapChromeParts.GroundNotSavedBanner — той самий урок для
    // іншого джерела порожнечі). Перевірка виводиться сюди, а не в MapChrome,
    // бо тут одночасно видно і фікс, і файл.
    LaunchedEffect(operatorLocation?.lat, operatorLocation?.lon, localBasemap) {
        val here = operatorLocation
        ui.offlineBasemapOutOfBounds = localBasemap != null && here != null &&
            !localBasemap.contains(here.lat, here.lon)
    }

    // ONE update sweep, in ladder order. Per-contribution change gating lives in
    // MapUpdateGate, so a route re-cut every 11 m does not also re-serialise the
    // POI flood — the very heat this refactor could have re-inflicted.
    LaunchedEffect(ui.mapRef, inputs, lensFrame) {
        val style = ui.mapRef?.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val fresh = composeRegistry()
        registry = fresh
        fresh.update(StyleHandle(style), lensFrame)
    }

    // Снап до дороги бере геометрію з уже намальованих тайлів — жодної мережі,
    // тож він працює саме там, де OSRM і Overpass мовчать.
    DisposableEffect(ui.mapRef, viewModel) {
        val map = ui.mapRef
        viewModel.renderedRoadSource = map?.let {
            { local.phantom.companion.feature.stream.ui.map.nav.RenderedRoads.collect(it) }
        }
        onDispose { viewModel.renderedRoadSource = null }
    }

    // M4-C01 — the pitch rule, re-asked after the gesture cooldown so a pinch
    // actually leans the map instead of waiting for an idle that never repeats.
    // AMBIENT priority: it never argues with a drive or with the fingers.
    LaunchedEffect(ui.mapRef, ui.pitchAskedAtMs, camera) {
        val map = ui.mapRef ?: return@LaunchedEffect
        val cam = camera ?: return@LaunchedEffect
        if (ui.pitchAskedAtMs == 0L) return@LaunchedEffect
        delay(CameraService.GESTURE_FRESH_MS + 60L)
        if (isGuiding) return@LaunchedEffect
        val at = map.cameraPosition
        val target = idlePitchTarget(
            following = ui.followMode,
            gesturing = cam.gestureFresh(),
            zoom = at.zoom,
            tiltDeg = at.tilt,
        ) ?: return@LaunchedEffect
        cam.submit(CameraIntent.IdlePitch(target, issuedAtMs = android.os.SystemClock.uptimeMillis()))
    }

    val tapDispatcher = remember(ui, viewModel) {
        MapTapDispatcher(
            mapTapHandlers(ui, viewModel) { point ->
                // Opening a cluster is a browse intent, and a browse intent ends
                // the follow — otherwise the follow write drags the camera
                // straight back off the cluster the operator just opened.
                val map = ui.mapRef ?: return@mapTapHandlers
                camera?.submit(
                    CameraIntent.Focus(
                        target = GeoPoint(point.latitude, point.longitude),
                        zoom = map.cameraPosition.zoom + 2.0,
                        issuedAtMs = android.os.SystemClock.uptimeMillis(),
                    ),
                )
            },
        )
    }
    MapSurfaceListeners(
        ui = ui,
        viewModel = viewModel,
        context = context,
        camera = camera,
        tapDispatcher = tapDispatcher,
        registry = { liveRegistry },
        frame = { liveFrame },
        terrainDepth = terrainDepth,
        crystalFeed = crystalFeed,
        offlinePacks = offlinePacks,
        offlineStyleUrl = offlineStyleUrl,
        onHatch = { hatchGeoJson = it },
        mapView = mapView,
    )
    MapGestureWiring(ui, camera)

    // NOTE: no .zBackGesture here — on the map a two-finger pinch IS the zoom
    // gesture, and zBackGesture reads pinch-in as "collapse → back", which
    // ejected the operator to the home surface every time they zoomed out.
    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())

    var firstFrame by remember(mapView) { mutableStateOf(false) }
    DisposableEffect(mapView) {
        val once = object : org.maplibre.android.maps.MapView.OnDidFinishRenderingFrameListener {
            override fun onDidFinishRenderingFrame(fully: Boolean, frameEncodingTime: Double, frameRenderingTime: Double) {
                if (!fully) return
                firstFrame = true
                mapView.post { mapView.removeOnDidFinishRenderingFrameListener(this) }
            }
        }
        mapView.addOnDidFinishRenderingFrameListener(once)
        onDispose { mapView.removeOnDidFinishRenderingFrameListener(once) }
    }
    if (!firstFrame) MapLoadingLine(dark = skyPhase.dark)

    MapComposeOverlays(
        ui = ui,
        viewModel = viewModel,
        transitRepository = transitRepository,
        active = active,
    )

    // СОНЦЕ — the wash sits above every map layer and below the chrome
    // MapScreen draws next. A background-only Box holds no pointer input, so
    // map gestures pass through.
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val scrim = skyScrim()
                if (scrim.alpha > 0f) drawRect(scrim)
            },
    )

    MapRadarReel(ui, lit = lensFrame.lit(MapTier.RADAR), active = active)
    MapCameraIntents(ui, viewModel, camera, fitInsets)

    // ── the applier ───────────────────────────────────────────────────────
    val currentTrust by viewModel.trust.collectAsStateWithLifecycle()
    val latestFix by rememberUpdatedState(operatorLocation)
    val latestTrust by rememberUpdatedState(currentTrust)
    val liveIsDark by rememberUpdatedState(mapIsDark)
    val latestMotion by rememberUpdatedState(motionProfile)
    // HORIZON wave 2 — the corridor's bend ahead, from the guidance session's
    // own map-matching. 0.0 (free drive) keeps the straight-road envelope.
    val latestCurvature by rememberUpdatedState(
        (guidanceForCamera as? local.phantom.companion.core.nav.GuidanceState.Guiding)
            ?.progress?.curvatureDegPerM ?: 0.0,
    )
    val latestRouteBearing by rememberUpdatedState(
        (guidanceForCamera as? local.phantom.companion.core.nav.GuidanceState.Guiding)
            ?.progress?.bearingDeg?.toFloat(),
    )

    // ── ATLAS-2.5 — coasting under GPS denial ─────────────────────────────
    // The journal is the road drive's evidence, so it exists only where the
    // owner can pull it: a debug build, on app-files, off the frame thread.
    val journal = remember(context) {
        if (local.phantom.companion.feature.stream.BuildConfig.DEBUG) {
            local.phantom.companion.feature.stream.ui.map.nav.NavJournal(
                java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "nav-journal"),
            )
        } else {
            null
        }
    }
    LaunchedEffect(journal) { journal?.drain() }

    val coastPilot = remember(viewModel, journal) {
        local.phantom.companion.feature.stream.ui.map.nav.CoastPilot(
            guiding = { viewModel.guidance.isActive },
            planRevision = { viewModel.planRevision() },
            variant = { viewModel.selectedVariant() },
            // Contract §6 — navigation-grade, NOT trusted: the maneuver cues
            // advance through the blackout while off-route, reroute and
            // arrival stay frozen. NavigationEngine's own pump goes quiet on a
            // non-navigation-grade fix, so nothing is fed twice for one instant.
            feedGuidance = { puck ->
                val f = puck.toNavFix()
                viewModel.guidance.onFix(
                    f.lat, f.lon, f.speedMps, f.trusted, f.accuracyM, f.gait,
                    bearingDeg = f.bearingDeg,
                )
            },
            // The file is the evidence that survives the drive; the logcat
            // mirror shares `PhantomNav` with the display-source log, so one
            // filter shows both halves of the same second.
            journal = journal?.let { j ->
                { line: String ->
                    j.offer(line)
                    android.util.Log.i("PhantomNav", "nav $line")
                }
            },
        )
    }

    LaunchedEffect(ui.mapRef, camera, active) {
        val map = ui.mapRef ?: return@LaunchedEffect
        val cam = camera ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        runOperatorFluxLoop(
            map = map,
            ui = ui,
            camera = cam,
            latestFix = { latestFix },
            bearingNow = { ui.bearing },
            trustNow = { latestTrust },
            isDarkNow = { liveIsDark },
            motionNow = { latestMotion },
            curvatureNow = { latestCurvature },
            routeBearingNow = { latestRouteBearing },
            nowMsProvider = { android.os.SystemClock.uptimeMillis() },
            coastFrame = { fix, nowMs -> coastPilot.frame(fix, nowMs) },
            onCoastBadge = { ui.coastBadge = it },
        )
    }
}

/** Пігулка тривоги + вирок про позицію стоять над вписаним маршрутом. */
private val ROUTE_FIT_TOP = 112.dp
private val ROUTE_FIT_SIDE = 32.dp

/** Згорнута смуга відповіді внизу. */
private val ROUTE_FIT_BAND = 64.dp
private val ROUTE_FIT_AIR = 24.dp

private const val GROUND_NIGHT = 0xFF0A0C10.toInt()
private const val GROUND_DAY = 0xFFF8F4EE.toInt()

@Composable
private fun MapLoadingLine(dark: Boolean) {
    Box(Modifier.fillMaxSize().padding(bottom = 160.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
        androidx.compose.material3.Text(
            text = androidx.compose.ui.res.stringResource(local.phantom.companion.feature.stream.R.string.map_loading),
            style = local.phantom.companion.core.design.theme.PhantomText.caption,
            color = if (dark) local.phantom.companion.core.design.tokens.NightColors.text3
            else local.phantom.companion.core.design.tokens.DayColors.text3,
        )
    }
}

/** Tiles for the right place start loading with the style, not after it lands. */
private fun firstPose(fix: LocationFix?): org.maplibre.android.camera.CameraPosition {
    // До першого фікса — країна поясу телефона, а не центр пака (Берлін, FR-7).
    val home = local.phantom.companion.core.data.country.CountryPackRegistry.firstHome()
    return org.maplibre.android.camera.CameraPosition.Builder()
        .target(org.maplibre.android.geometry.LatLng(fix?.lat ?: home.lat, fix?.lon ?: home.lon))
        .zoom(if (fix != null) OPERATOR_ZOOM else home.zoom)
        .build()
}
