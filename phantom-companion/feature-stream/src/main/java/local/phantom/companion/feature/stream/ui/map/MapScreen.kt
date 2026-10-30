package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.geo.sky.SkyPhase
import local.phantom.companion.core.net.transit.GtfsRealtimeClient
import local.phantom.companion.feature.stream.ui.map.country.countryHomePoint
import local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import org.maplibre.android.geometry.LatLng

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import local.phantom.companion.core.sensor.nav.CompassAzimuth

/**
 * NAV-0 «Сцени» — the map screen as a thin composition over three parts:
 *
 *   [MapCanvas]    — the MapLibre view, style pipeline and gestures;
 *   [MapChrome]    — scene-driven controls (CALM / EXPLORE / GUIDE);
 *   [MapSheetHost] — every contextual bottom sheet.
 *
 * Shared surface state lives in one [MapUiState] holder; the scene is
 * derived from it (never stored). This file keeps only the data-side
 * work: feed clients, geocoding, sensors, shared-gaze publishing.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun MapScreen(
    providerId: TileProviderId,
    apiKey: String?,
    customUrl: String?,
    viewModel: MapViewModel,
    /**
     * W-6 — false while the camera rests above the ground. The map stays
     * composed (W-5: a descent preview must not rebuild a GL world), but the
     * pipelines nobody is watching — Filament's city, the transit prisms, the
     * radar, the compass sensor — stand down. A live guidance session is the
     * exception: §1.3, маневри важливіші за міліампери.
     */
    visible: Boolean = true,
    selfName: String? = null,
    /** Island's search slot. The map no longer carries its own search bar. */
    searchRequests: kotlinx.coroutines.flow.Flow<Unit>? = null,
    /**
     * Місце з чужого листа (`phantom://map`, `geo:`, osm.org): камера туди й
     * картка місця. Липкий стан, а не подія — намір приходить раніше, ніж
     * мапа складається.
     */
    sharedPointRequests: kotlinx.coroutines.flow.StateFlow<
        local.phantom.companion.core.geo.link.MapLink.Point?>? = null,
    onSharedPointConsumed: (() -> Unit)? = null,
    onSaveOfflineRegion: ((bounds: OfflineBounds, styleUrl: String) -> Unit)? = null,
    /** Один пакет уздовж маршруту: геометрія коридору + рамка для підпису. */
    onSaveRouteCorridor: ((
        geometryJson: String,
        bounds: OfflineBounds,
        styleUrl: String,
        routeLabel: String?,
        estimateBytes: Long,
    ) -> Unit)? = null,
    offlinePacks: List<RegionInfo> = emptyList(),
    onRefreshOfflineRegions: (() -> Unit)? = null,
    onAskAi: ((lat: Double, lon: Double, name: String) -> Unit)? = null,
    onOpenExternal: ((lat: Double, lon: Double, name: String) -> Unit)? = null,
    // MAP-15 — persist a tapped POI into the encrypted vault.
    onSavePoi: ((PoiEntity) -> Unit)? = null,
    /** Точка з мапи → чернетка зустрічі в месенджері (`WebMessengerMeetupDraft.pin`). */
    onMeetHere: ((lat: Double, lon: Double, label: String?, venueId: String?) -> Unit)? = null,
    /** Чернетка зустрічі відкрила мапу, щоб людина поставила мітку. */
    meetPicking: Boolean = false,
    // A tapped saved place can be renamed or removed straight from its card.
    onDeleteSavedPlace: ((PoiEntity) -> Unit)? = null,
    onRenameSavedPlace: ((PoiEntity, String) -> Unit)? = null,
    onRecategorizeSavedPlace: ((PoiEntity, String) -> Unit)? = null,
    // R4.2 — shared gaze: publish the live viewport. W-3 — never cleared here:
    // the gaze belongs to the session, not to this composition.
    onViewportChanged: ((local.phantom.companion.core.ai.context.MapViewportSnapshot) -> Unit)? = null,
    /** Екран завантажень пакiв — єдина дія пропозиції «країни немає в пошуку». */
    onOpenPacks: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val look = LocalPhantomColors.current
    // W-1 — the fix comes from the ViewModel, which pumps it whether or not
    // this composition exists.
    val operatorLocation by viewModel.operatorLocation.collectAsStateWithLifecycle()
    // H-02 — the last satellite check of the device clock, pumped by the same
    // session as the fix. Read here and handed down rather than resolved in the
    // readout: the readout is a leaf and this is session state.
    val clockSkew by viewModel.clockSkew.collectAsStateWithLifecycle()
    val pois by viewModel.pois.collectAsStateWithLifecycle()
    val searchGap by viewModel.searchGap.collectAsStateWithLifecycle()
    // Які країни пошук уже вміє. Читається зі сховища пакетів, а не з
    // репозиторію: пакет вносить інший екран, і це різні інстанси.
    val forgePacks by remember {
        local.phantom.companion.core.data.geocoder.ForgeGeocoderStore(context).installed
    }.collectAsStateWithLifecycle()
    val savedPois by viewModel.savedPois.collectAsStateWithLifecycle()
    val drawnLines by viewModel.drawnLines.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val maneuvers by viewModel.maneuvers.collectAsStateWithLifecycle()
    val packStopsGeoJson by viewModel.packStopsGeoJson.collectAsStateWithLifecycle()
    val packRailsGeoJson by viewModel.packRailsGeoJson.collectAsStateWithLifecycle()
    // R4.1 — the PHANTOM's live layer stack ("від Фантома").
    val phantomLayers by viewModel.layers.collectAsStateWithLifecycle()
    // R4.x — which "світ" layer is summoning right now (spinner on that chip).
    val summoning by viewModel.summoning.collectAsStateWithLifecycle()
    // M-B01 / M-E01 — routing + nearby state.
    val routeInfo by viewModel.routeInfo.collectAsStateWithLifecycle()
    val routing by viewModel.routing.collectAsStateWithLifecycle()
    // NAV-1 — how many OSRM variants the live plan has (compare chip).
    val routeOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
    // M-C05/C06 — Tactical Evacuation Engine lifecycle.
    val evacuationState by viewModel.evacuationState.collectAsStateWithLifecycle()
    val nearbyActive by viewModel.nearbyActive.collectAsStateWithLifecycle()
    val mapNotice by viewModel.notice.collectAsStateWithLifecycle()
    // ATLAS-3 — kinematic context for the chrome's motion pill.
    val motion by viewModel.motion.collectAsStateWithLifecycle()
    // APPLE-LOCK — the pin's single display authority: guidance route-snap
    // during a drive, HMM road-match in free drive, raw fused fix last.
    val displayLocation by viewModel.displayLocation.collectAsStateWithLifecycle()
    val savingRegionText = androidx.compose.ui.res.stringResource(
        local.phantom.companion.feature.stream.R.string.map_region_saving,
    )
    val noMapYetText = androidx.compose.ui.res.stringResource(
        local.phantom.companion.feature.stream.R.string.map_region_no_map,
    )
    val selectedVehicle by viewModel.selectedVehicle.collectAsStateWithLifecycle()
    // NAV-2 — live guidance lifecycle (Idle / Guiding / Arrived).
    val guidanceState by viewModel.guidance.state.collectAsStateWithLifecycle()
    // Search overhaul — recent queries (Vault-persisted).
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()

    // NAV-0 — one shared holder for every piece of surface state the
    // canvas, chrome and sheets all read and write.
    val ui = remember { MapUiState() }

    LaunchedEffect(searchRequests) {
        searchRequests?.collect { ui.searchOpen = true }
    }

    LaunchedEffect(sharedPointRequests, ui.camera) {
        val camera = ui.camera ?: return@LaunchedEffect
        sharedPointRequests?.collect { point ->
            if (point == null) return@collect
            camera.submit(
                CameraIntent.Focus(
                    target = GeoPoint(point.lat, point.lon),
                    zoom = SHARED_POINT_ZOOM,
                    issuedAtMs = android.os.SystemClock.uptimeMillis(),
                ),
            )
            // Назву дописує зворотний геокодер за `droppedPoint`: власний
            // підпис звідси все одно стерла б наступна ж стрічка ефекту.
            ui.droppedPoint = point.lat to point.lon
            onSharedPointConsumed?.invoke()
        }
    }

    // FIELD REPORT 2026-07-18 — a followed pin deserves a navigation-grade
    // chip. The shell (ContinuumHost) arms the GPS off this flag exactly as
    // it does off a live drive; without it, follow rode 5 s balanced-power
    // fixes and the map lurched once per fix.
    LaunchedEffect(ui.followMode, visible) {
        viewModel.setFollowActive(visible && ui.followMode)
    }

    // HIGHWAY DOMINANCE — Auto-recenter timer during active navigation:
    // When the driver touches the map to look around, automatically re-lock onto the vehicle
    // and resume 3D chase camera after 6 seconds of touch inactivity.
    val isGuidingNow = guidanceState is local.phantom.companion.core.nav.GuidanceState.Guiding
    LaunchedEffect(isGuidingNow, ui.followMode, ui.cameraGestureAtMs) {
        if (!isGuidingNow || ui.followMode) return@LaunchedEffect
        while (true) {
            delay(1000L)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - ui.cameraGestureAtMs >= 6000L) {
                ui.followMode = true
                break
            }
        }
    }

    // R4.x — living overlays. W-1: the air-raid sky is polled by the
    // ViewModel (NAV-5 warns from it, so it must outlive this screen); the
    // canvas and chrome read it through the surface mirror below.
    val oblastShapes = remember { OblastShapes.load(context) }
    val radarClient = remember { RadarClient() }
    val alertStates by viewModel.alertStates.collectAsStateWithLifecycle()
    val viewSpot = ui.viewportCenter?.let { it.first to it.second }
    val alertsHere = remember(operatorLocation?.lat, operatorLocation?.lon, viewSpot, oblastShapes) {
        AlertRegion.here(oblastShapes, operatorLocation?.let { it.lat to it.lon }, viewSpot)
    }
    LaunchedEffect(alertStates, alertsHere) { ui.alertStates = if (alertsHere) alertStates else emptyMap() }

    // Ground Transit Engine / NAV-6 — live GTFS-RT vehicles come from the
    // honest per-city registry: the feed nearest the current view (operator
    // fix until the camera settles), or NOTHING where no verified feed
    // exists — prisms never animate an empty promise (Law 3).
    val feedAnchor = ui.viewportCenter?.let { it.first to it.second }
        ?: operatorLocation?.let { it.lat to it.lon }
    // Key on a coarse (~11 km) grid so 1-Hz GPS noise never churns the client.
    val feedKey = feedAnchor?.let { "%.1f,%.1f".format(Locale.ROOT, it.first, it.second) }
    val transitFeed = remember(feedKey) {
        feedAnchor?.let { local.phantom.companion.core.data.transit.GtfsFeedRegistry.feedFor(it.first, it.second) }
    }
    // Польовий вердикт 23.08: модельного флоту тут немає навіть у памʼяті.
    // Без живого фіда репозиторію не існує — отже, ні пігулок, яких не
    // зустріти на вулиці, ні циклу, що перераховує їх кожні 20 секунд.
    val transitRepository = remember(transitFeed?.vehiclePositionsUrl) {
        transitFeed?.let { feed ->
            TransitRepository(
                client = GtfsRealtimeClient(feedUrl = feed.vehiclePositionsUrl),
                routeLabel = { id ->
                    viewModel.gtfsSchedule?.takeIf { it.city == feed.city }?.routeLabels?.get(id)
                },
                // Read through the flow, not the captured snapshot — this
                // lambda outlives the composition that built it.
                focus = {
                    ui.viewportCenter?.let { it.first to it.second }
                        ?: viewModel.operatorLocation.value?.let { it.lat to it.lon }
                },
            )
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // W-6 — the feed polls only while the ground is actually watched: a
    // GTFS-RT client under an opaque conversation is pure tax.
    DisposableEffect(lifecycleOwner, transitRepository, visible) {
        val repo = transitRepository?.takeIf { visible }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> repo?.start()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> repo?.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // A feed resolved AFTER resume (camera settled over a covered city)
        // must start now — ON_RESUME already fired for this screen.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            repo?.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repo?.stop()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // Висота вікна мапи в dp — на неї спирається підйом точки над аркушем.
    fun viewportHeightDp(): Double = ((ui.mapRef?.height ?: 0f) / density).toDouble()
    val placeShown = ui.dossierTarget
    val placeFlyMs = PlaceFocus.flyMs(local.phantom.companion.core.design.motion.PhantomMotion.reducedMotion())
    LaunchedEffect(placeShown?.lat, placeShown?.lon) {
        val t = placeShown ?: return@LaunchedEffect
        val map = ui.mapRef ?: return@LaunchedEffect
        if (placeOutOfView(map, t.lat, t.lon, density)) {
            ui.camera?.submit(focusResult(t.lat, t.lon, viewportHeightDp(), placeFlyMs))
        }
    }
    // GEO-LAW — null when online geocoding isn't configured (public
    // Nominatim is off-limits for systematic use); search then falls back
    // to the on-device gazetteer only, reverse returns null honestly.
    fun geocoder(): GeocodeClient = currentPlaceSearch()

    // MAP-10 — rotation sensor listener for the compass / bearing arrow.
    //
    // THERMAL 11.08 — `visible` знає лише про перемикання палат УСЕРЕДИНІ
    // застосунку; кнопка «Додому» його не міняє, композиція лишається живою,
    // і слухач продовжував працювати над робочим столом. Профіль ART намірив
    // 48% ядра у фоні: кожна подія писала стан Compose, а той будив головний
    // потік через глобальний знімок. Тому реєстрація тепер привʼязана до
    // життєвого циклу процесу, а не до внутрішньої видимості.
    val displayRotation = remember(LocalConfiguration.current) {
        @Suppress("DEPRECATION")
        (context.getSystemService(android.content.Context.WINDOW_SERVICE)
            as? android.view.WindowManager)?.defaultDisplay?.rotation
            ?: CompassAzimuth.ROTATION_0
    }
    DisposableEffect(context, visible, lifecycleOwner, displayRotation) {
        if (!visible) return@DisposableEffect onDispose { }
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private var smoothed: Float? = null

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // FIELD FIX — getOrientation()'s yaw only describes a phone
                // lying flat; upright (how anyone reads it while walking) it
                // measures rotation about an axis pointing at the sky. See
                // CompassAzimuth.
                val azimuth = CompassAzimuth.fromRotationMatrix(rotationMatrix, displayRotation)
                    ?: return
                val next = CompassAzimuth.smooth(smoothed, azimuth) ?: azimuth
                smoothed = next
                // Кожен запис стану Compose коштує глобального знімка. Стрілка
                // не бачить різниці нижче цього порога, а магнітометр у спокої
                // дрижить безперервно.
                if (kotlin.math.abs(next - ui.bearing) > COMPASS_STATE_EPS_DEG) ui.bearing = next
            }
            // Платформа сама зізнається, наскільки вірить компасу — і досі це
            // зізнання викидалось у порожній метод. Конус напрямку малювався
            // однаково вузьким і біля магніту, і в чистому полі.
            override fun onAccuracyChanged(s: Sensor, a: Int) {
                val fidelity = CompassFidelity.fromSensorStatus(a)
                if (fidelity != ui.compassFidelity) ui.compassFidelity = fidelity
            }
        }
        var live = false
        fun attach() {
            if (!live) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                live = true
            }
        }
        fun detach() {
            if (live) {
                sensorManager.unregisterListener(listener)
                live = false
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> attach()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> detach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            attach()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            detach()
        }
    }

    var lastViewportGeocode by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val styleUrls = remember(providerId, apiKey, customUrl) {
        resolveStyleUrls(providerId, apiKey, customUrl)
    }
    // СОНЦЕ — the map's day/night rides the real sun for the operator's own
    // position, not a clock bucket: dusk slides in, with season and latitude in
    // it (the equator stays bright, a northern winter goes amber by mid-afternoon).
    // No fix yet → fall back to the app theme's phase.
    val skyClock by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val sky = androidx.compose.runtime.remember(operatorLocation?.lat, operatorLocation?.lon, skyClock) {
        operatorLocation?.let { skyFor(it.lat, it.lon, skyClock) }
    }
    // Чи мовчить стрічка тривог — на тому самому годиннику сонця, бо друге
    // питання про вік знімка було б другим вироком про те саме небо. Пігулка
    // бере повний вирок сама (`MapChrome`); Лінзі досить одного біта, і саме
    // його їй бракувало: холодний старт без фіксу над непрочитаною стрічкою —
    // «не знаю», а не «тихо» (`AirAlertWatch.kt`).
    val alertSky by viewModel.sky.collectAsStateWithLifecycle()
    val skyVerdictNow = androidx.compose.runtime.remember(alertSky, skyClock, alertsHere) {
        alertSky.verdict(skyClock).onlyIf(alertsHere)
    }
    val skyFeedSilent = skyVerdictNow.feedSilent
    // Країна без стрічки тривог. Окремо від «мовчить», бо це не стан каналу:
    // Лінза мусить ЗАБОРОНИТИ пігулку, а не помʼякшити її слова.
    val skyFeedDeclared = skyVerdictNow != SkyVerdict.NO_COUNTRY_FEED
    // Полярність землі веде СОНЦЕ, а не годинник застосунку.
    //
    // Було навпаки: тема-«жива» перемикається на `dusk-ember` о 17:00 за
    // календарним відром годин, і мапа темніла разом із нею. 16 серпня в
    // Житомирі сонце сідає о ~20:15, тобто земля йшла в ніч **на три години
    // раніше за ніч** — власник побачив це першим: «мапа швидко темною стає».
    //
    // Оболонка має право на настрій: чат і скло можуть бути бурштиновими о
    // сімнадцятій. Земля — не має. Її полярність це не смак, а питання, чи
    // читається вона під тим світлом, у якому людина зараз стоїть.
    //
    // Тема лишається запасним варіантом і тільки ним: без позиції сонця не
    // порахувати, і тоді здогадка оболонки — єдине, що взагалі є.
    // Три землі, не дві: між шитвом −5° і −12° основа бере сутінковий варіант —
    // ту саму ніч, зсунуту в тепло. Оболонка запасним варіантом уміє лише день
    // або ніч, бо здогадка про сутінки була б здогадкою про час заходу.
    // The ground follows the app's Ніч/День, which itself follows the sun unless
    // the person chose one: one polarity on the whole screen.
    val skyPhase = if (look.isDark) SkyPhase.NIGHT else SkyPhase.DAY
    val isDarkTheme = skyPhase.dark
    // The URL the auto (provider-aware, theme-aware) path resolves to.
    val autoStyleUrl = styleUrls.resolve(skyPhase)
    val offlineStyleUrl = remember(providerId, apiKey, customUrl) {
        offlineStyleUrlFor(providerId, apiKey, customUrl)
    }
    val offlineBasemapStore = remember(context) {
        local.phantom.companion.core.geo.basemap.OfflineBasemapStore(context)
    }
    val offlineBasemap by offlineBasemapStore.info.collectAsStateWithLifecycle()
    val terrainPackStore = remember(context) {
        local.phantom.companion.core.geo.terrain.TerrainPackStore(context)
    }
    val terrainPack by terrainPackStore.info.collectAsStateWithLifecycle()
    val styleKey = ui.basemapOverride?.name
        ?: ("AUTO:$autoStyleUrl" + if (isDarkTheme) ":night" else ":day")

    // M-A01 — reverse-geocode the dropped point for the sheet title.
    // ATLAS-4 — and warm the route cache in the same breath: the pin-drop IS
    // the intent signal, so the FOOT/CAR corridors start their round-trip
    // while the sheet is still animating open.
    val meetPickHint = stringResource(R.string.map_meet_pick_hint)
    LaunchedEffect(meetPicking) {
        if (meetPicking) viewModel.postNotice(meetPickHint)
    }
    LaunchedEffect(ui.droppedPoint) {
        val p = ui.droppedPoint ?: return@LaunchedEffect
        operatorLocation?.let { viewModel.prefetchRoute(it.lat, it.lon, p.first, p.second) }
        ui.droppedAddress = null
        val r = geocoder().reverse(p.first, p.second)
        // Поза Україною геокодер часто мовчить, а вулиця намальована просто під
        // точкою. «Точка на мапі» — останнє слово, не перше.
        val name = r?.displayName ?: ui.mapRef?.let { nearestMapName(it, p.first, p.second) }
        if (name != null && ui.droppedPoint == p) ui.droppedAddress = name
    }

    // Transient notices (route failed / nothing nearby) auto-dismiss.
    LaunchedEffect(mapNotice) {
        if (mapNotice != null) {
            delay(3200)
            viewModel.consumeNotice()
        }
    }

    // R4.x — radar: fetch when first enabled, refresh every 5 min while on.
    // LEVIATHAN-4 — the whole reel (past + nowcast) so the canvas can play
    // the weather instead of freezing one frame. W-6 — not while nobody is
    // looking.
    LaunchedEffect(ui.radarVisible, visible) {
        if (!ui.radarVisible || !visible) return@LaunchedEffect
        while (true) {
            val frames = radarClient.fetchFrames()
            if (frames.isNotEmpty()) {
                ui.radarFrames = frames
                ui.radarOverlay = frames.last()
            }
            delay(5 * 60_000L)
        }
    }

    // M-C05/C06 — the EvacuationCard lives inside the tools capsule; force it
    // open the moment the engine leaves Idle so Scanning/Compromised/
    // InPlaceDefense are never silently buried in a collapsed pill.
    LaunchedEffect(evacuationState) {
        if (evacuationState != EvacuationState.Idle) ui.toolsExpanded = true
    }

    // W-1 — the operator's fix no longer drives ANYTHING from this
    // composition. The guidance session, NAV-5's threat watch, the NAV-Ω1
    // deadline, the evacuation arrival check and PHANTOM's nav.* verbs all
    // live in the ViewModel now, where they survive the operator looking up
    // from the map. This screen only draws what they produce.

    // R4.2 — reverse-geocode the viewport centre when it moves >200 m from
    // the last lookup (reuses the throttled NominatimClient; the operator-pin
    // geocode is separate and stays about the operator, not the camera).
    LaunchedEffect(ui.viewportCenter) {
        val c = ui.viewportCenter ?: return@LaunchedEffect
        val last = lastViewportGeocode
        if (last != null && haversineMeters(last, c.first to c.second) < 200.0) return@LaunchedEffect
        delay(1_500L)
        val now = ui.viewportCenter ?: return@LaunchedEffect
        val result = geocoder().reverse(now.first, now.second)
        if (result != null) {
            ui.viewportLocality = result.displayName.substringBefore(",").trim()
            lastViewportGeocode = now.first to now.second
        }
    }

    // M-C01 — observe request to open threat brief from AI tool
    LaunchedEffect(viewModel.threatBriefRequests) {
        viewModel.threatBriefRequests?.collect { oblast ->
            if (oblast == "auto") {
                // ATLAS-3: the excluded key is the pack's remembered-regions
                // table, not a name typed here. All three have carried one
                // uninterrupted alert since 2022, so «покажи тривогу» opening
                // on them answers a four-year-old question, not today's.
                val active = ui.alertStates.keys.firstOrNull {
                    it !in OCCUPIED_OBLASTS && ui.alertStates[it]?.active == true
                }
                if (active != null) ui.selectedThreatOblast = active
            } else {
                ui.selectedThreatOblast = oblast
            }
        }
    }

    val configuration = LocalConfiguration.current
    val appLocale = configuration.locales[0] ?: Locale.getDefault()
    val resources = context.resources

    // M-I02 — shared gaze++: fold Wave-1 interaction state (dropped pin, live
    // route, active "поруч" category) into the same per-turn snapshot so the
    // model knows not just where the camera looks, but what the operator is
    // MID-DOING on the map.
    val routeSummary = routeInfo?.let {
        routeSummaryLine(resources, it.distanceMeters, it.durationSeconds, it.profile)
    }

    // NAV-2 — the guidance line of the shared gaze: PHANTOM sees the current
    // step, remaining way, ETA and speed while the operator talks mid-drive.
    val navSnapshot = (guidanceState as? local.phantom.companion.core.nav.GuidanceState.Guiding)
        ?.progress?.let { p ->
            local.phantom.companion.core.ai.context.NavigationSnapshot(
                stepPhrase = local.phantom.companion.core.nav.maneuverShort(
                    p.maneuverType, p.maneuverModifier, p.roadName, p.exitNumber, appLocale,
                ),
                distanceToManeuverM = p.distanceToManeuverM.toInt(),
                remainingDistanceM = p.remainingDistanceM.toInt(),
                remainingDurationS = p.remainingDurationS.toInt(),
                speedKmh = p.speedKmh,
                limitKmh = p.limitKmh,
                offRoute = p.offRoute,
            )
        }

    val screenView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(isGuidingNow) {
        if (isGuidingNow) screenView.keepScreenOn = true
        onDispose {
            if (isGuidingNow) screenView.keepScreenOn = false
        }
    }

    val defaultPointLabel = stringResource(R.string.route_dest_point)
    // R4.2 — publish the composed viewport snapshot into the AI turn context.
    LaunchedEffect(
        ui.viewportCenter, ui.viewportLocality, pois.size, phantomLayers, ui.followMode,
        ui.droppedAddress, ui.droppedPoint, routeSummary, nearbyActive, ui.selectedThreatOblast,
        navSnapshot, defaultPointLabel,
    ) {
        val c = ui.viewportCenter ?: return@LaunchedEffect
        onViewportChanged?.invoke(
            local.phantom.companion.core.ai.context.MapViewportSnapshot(
                centerLat = c.first,
                centerLon = c.second,
                zoom = c.third,
                locality = ui.viewportLocality,
                visiblePoiCount = pois.size,
                activeLayerTitles = phantomLayers.filter { it.visible }.map { it.spec.title },
                followingOperator = ui.followMode,
                selectedPointLabel = ui.droppedPoint?.let { ui.droppedAddress ?: defaultPointLabel },
                activeRoute = routeSummary,
                activeNearbyCategory = nearbyActive,
                activeThreatBriefOblast = ui.selectedThreatOblast,
                navigation = navSnapshot,
            ),
        )
    }

    // W-3 — no teardown of the shared gaze and no clearing of the active
    // corridor here. Both used to be nulled by this composition's onDispose,
    // which meant that ascending to the chat mid-drive blinded PHANTOM to the
    // map it was guiding the operator across, and made `route.along` deny the
    // very route it was standing on. The ViewModel publishes the corridor;
    // the Continuum clears both when the session itself ends.

    // Підказка про заслін масштабу розвʼязується тут, бо ресурси є тут, і
    // вручається рушієві один раз — далі він сам вирішує, коли її сказати.
    val poiZoomHint = stringResource(R.string.map_poi_zoom_hint)
    LaunchedEffect(poiZoomHint) { viewModel.setPoiZoomHint(poiZoomHint) }
    val poiOfflineKindHint = stringResource(R.string.map_nearby_offline_unknown_kind)
    LaunchedEffect(poiOfflineKindHint) { viewModel.setPoiOfflineKindHint(poiOfflineKindHint) }

    // R4.x — the operator's own oblast (found offline via point-in-polygon)
    // + every oblast under alert right now, own oblast pinned first.
    val ownOblast = remember(operatorLocation, oblastShapes) {
        operatorLocation?.let { oblastShapes.oblastAt(it.lat, it.lon) }
    }
    val activeAlerts = remember(ui.alertStates, ownOblast) {
        ui.alertStates.filterValues { it.active }
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, AlertState>> { it.first == ownOblast }
                    .thenByDescending { it.second.changedEpochMs ?: 0L },
            )
    }

    // ATLAS-2: другої деривації сцени тут більше немає. Вона жила поруч із
    // ланцюжком Лінзи й читала ІНШУ вісь — що відкрито на екрані, а не що
    // людина робить, — тож на пів хвилини спокою з відкритою панеллю густота
    // й хром мали право сказати різне. Сцену виводить полотно (там Лінза),
    // хром читає `ui.sceneMode`.

    val offlinePanelPacks = androidx.compose.runtime.remember(offlinePacks, offlineStyleUrl) {
        offlinePacks.map { r ->
            OfflinePanelPack(
                id = r.id,
                name = r.name,
                state = local.phantom.companion.feature.stream.domain.OfflinePacks.stateOf(
                    complete = r.isComplete,
                    packStyleUrl = r.styleUrl,
                    currentStyleUrl = offlineStyleUrl,
                ),
                sizeMb = r.sizeMb,
                progressPercent = (r.progressFraction * 100f).roundToInt(),
                area = r.area,
            )
        }
    }
    // Коридор рахується з живої лінії маршруту — не з рамки навколо неї.
    val activeRoutePoints by viewModel.activeRoutePoints.collectAsStateWithLifecycle()
    val routeCorridorBoxes = androidx.compose.runtime.remember(activeRoutePoints) {
        local.phantom.companion.feature.stream.domain.RouteCorridor
            .boxes(activeRoutePoints.orEmpty())
            .takeIf { it.isNotEmpty() }
    }
    val mapAnswer by viewModel.mapAnswer.collectAsStateWithLifecycle()

    MapBackHandler(
        ui = ui,
        guiding = guidanceState is local.phantom.companion.core.nav.GuidanceState.Guiding,
        hasRoute = routeInfo != null,
        answerLive = mapAnswer.isLive,
        clearRoute = { viewModel.clearRoute() },
        clearAnswer = { viewModel.clearMapAnswer() },
        hideKeyboard = { keyboard?.hide() },
    )

    // Мітка маршруту — назва землі під його дальшим кінцем. Саме за нею
    // коридор дедуплікується: один коридор на маршрут, а не по одному на
    // кожну спробу зберегти.
    val routeCorridorLabel = androidx.compose.runtime.remember(activeRoutePoints) {
        activeRoutePoints?.lastOrNull()?.let {
            local.phantom.companion.core.geo.place.PlaceNaming.placeAt(it.first, it.second)
        }
    }
    // Скільки це важитиме — рахується ДО тапу, бо «завантажити» без числа
    // означає «погодься наосліп».
    val routeCorridorBytes = androidx.compose.runtime.remember(routeCorridorBoxes) {
        routeCorridorBoxes?.let { boxes ->
            local.phantom.companion.core.geo.place.PlacePackPlanner.corridorBytes(
                boxes.map {
                    local.phantom.companion.core.geo.place.PlacePackPlanner.CorridorBox(
                        it.northLat, it.eastLon, it.southLat, it.westLon,
                    )
                },
                OfflineRegionsManager.MIN_DEFAULT_ZOOM.toInt(),
                OfflineRegionsManager.MAX_DEFAULT_ZOOM.toInt(),
            )
        } ?: 0L
    }

    var offlineViewArea by remember {
        mutableStateOf<local.phantom.companion.feature.stream.domain.OfflineCoverage.Area?>(null)
    }
    LaunchedEffect(ui.offlineOpen, ui.viewportCenter) {
        if (!ui.offlineOpen) return@LaunchedEffect
        onRefreshOfflineRegions?.invoke()
        val b = ui.mapRef?.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
        offlineViewArea = local.phantom.companion.feature.stream.domain.OfflineCoverage.Area(
            northLat = b.latitudeNorth,
            eastLon = b.longitudeEast,
            southLat = b.latitudeSouth,
            westLon = b.longitudeWest,
        )
    }
    val offlineVerdict = androidx.compose.runtime.remember(
        offlineViewArea, offlinePacks, offlineStyleUrl,
    ) {
        val view = offlineViewArea
            ?: return@remember local.phantom.companion.feature.stream.domain.OfflineCoverage.Verdict.None
        local.phantom.companion.feature.stream.domain.OfflineCoverage.verdict(
            view = view,
            styleUrl = offlineStyleUrl,
            stored = offlinePacks.mapNotNull { r ->
                r.area?.let {
                    local.phantom.companion.feature.stream.domain.OfflineCoverage.Stored(
                        area = it,
                        styleUrl = r.styleUrl ?: offlineStyleUrl,
                        complete = r.isComplete,
                    )
                }
            },
        )
    }
    // Готовий пакет — це СТАН (лежить на телефоні), а не дія: зелений успіху,
    // а не бурштин, яким мапа кличе натиснути.
    val packReadyHex = hexOf(look.success)
    val packBusyHex = hexOf(look.text)
    val packOtherHex = hexOf(look.text2)
    LaunchedEffect(ui.offlineOpen, offlinePanelPacks, ui.offlineSelectedPack) {
        ui.offlinePacksGeoJson = if (!ui.offlineOpen) EMPTY_FC else {
            local.phantom.companion.feature.stream.domain.OfflinePacks.geoJson(
                offlinePanelPacks.mapNotNull { p ->
                    p.area?.let { area ->
                        local.phantom.companion.feature.stream.domain.OfflinePacks.Painted(
                            id = p.id,
                            area = area,
                            colorHex = when (p.state) {
                                local.phantom.companion.feature.stream.domain.OfflinePacks.State.Ready -> packReadyHex
                                local.phantom.companion.feature.stream.domain.OfflinePacks.State.Downloading -> packBusyHex
                                local.phantom.companion.feature.stream.domain.OfflinePacks.State.OtherMap -> packOtherHex
                            },
                            selected = p.id == ui.offlineSelectedPack,
                        )
                    }
                },
            )
        }
    }

    // NOTE: no .zBackGesture here — on the map a two-finger pinch IS the zoom
    // gesture, and zBackGesture reads pinch-in as "collapse → back", which
    // ejected the operator to the home surface every time they zoomed out.
    // The map keeps its own back affordances (top-bar ← and system back).
    Box(modifier = modifier.fillMaxSize().background(look.canvas)) {
        val altList by viewModel.altRoutes.collectAsStateWithLifecycle()
        val altRoutes = remember(altList) {
            altList.map { Triple(it.points, it.label, it.index) }
        }
        MapCanvas(
            ui = ui,
            viewModel = viewModel,
            altRoutes = altRoutes,
            // APPLE-LOCK — the DISPLAYED dot rides the route while guiding
            // (falling back to the HMM road-match, then raw); everything else
            // (chrome, dossiers, routing "from here") stays on the raw fused
            // fix. Advisory means advisory.
            operatorLocation = displayLocation ?: operatorLocation,
            pois = pois,
            savedPois = savedPois,
            routes = routes,
            maneuvers = maneuvers,
            activeManeuverStep = (guidanceState as? local.phantom.companion.core.nav.GuidanceState.Guiding)
                ?.progress?.stepIndex ?: -1,
            transitItinerary = routeInfo?.itinerary,
            packStopsGeoJson = packStopsGeoJson,
            packRailsGeoJson = packRailsGeoJson,
            phantomLayers = phantomLayers,
            oblastShapes = oblastShapes,
            skyPhase = skyPhase,
            skyFeedSilent = skyFeedSilent,
            skyFeedDeclared = skyFeedDeclared,
            autoStyleUrl = autoStyleUrl,
            styleKey = styleKey,
            transitRepository = transitRepository,
            selectedVehicle = selectedVehicle,
            // design-critic §S9 — same live pack list already threaded to the
            // offline panel; the honesty hatch and tap popover read it too.
            offlinePacks = offlinePacks,
            offlineStyleUrl = offlineStyleUrl,
            offlineBasemap = offlineBasemap,
            terrainPack = terrainPack,
            // W-6 — §1.3 exception removed (Mandate 1): The Map layer must stop
            // rendering completely when the Chat/Dialogue stratum obscures it to
            // resolve thermal & latency issues.
            active = visible && !ui.mountMode,
            skyScrim = { androidx.compose.ui.graphics.Color.Transparent },
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .background(statusBarScrimColor(look.canvas)),
        )

        // NAV-2 — chase camera while guiding (yields to a manual pan).
        local.phantom.companion.feature.stream.ui.map.nav.GuidanceCameraEffect(ui, guidanceState)

        MapChrome(
            ui = ui,
            viewModel = viewModel,
            selfName = selfName,
            operatorLocation = operatorLocation,
            skyClock = skyClock,
            clockSkew = clockSkew,
            phantomLayers = phantomLayers,
            summoning = summoning,
            routeInfo = routeInfo,
            routing = routing,
            guidance = guidanceState,
            onFlyover = {
                val pts = routes.lastOrNull()?.points?.map { it.lat to it.lon }
                if (pts != null && pts.size >= 2) {
                    ui.camera?.submit(
                        CameraIntent.Flyover(
                            path = pts.map { GeoPoint(it.first, it.second) },
                            durationMs = local.phantom.companion.feature.stream.ui.map.nav
                                .RouteFlyover.estimateDurationMs(pts),
                            issuedAtMs = android.os.SystemClock.uptimeMillis(),
                        ),
                    )
                }
            },
            evacuationState = evacuationState,
            nearbyActive = nearbyActive,
            mapNotice = mapNotice,
            motion = motion,
            selectedVehicle = selectedVehicle,
            ownOblast = ownOblast,
            activeAlerts = activeAlerts,
            offlinePackCount = offlinePacks.count { it.isComplete },
            trustFeed = viewModel.trust,
            canSaveOfflineRegion = onSaveOfflineRegion != null,
            onEnterMount = { ui.carCockpitOpen = true },
            alertsHere = alertsHere,
        )

        // Стрічка довіри і прилад РЕБ переїхали у верхній кластер [MapChrome].
        //
        // Вони стояли тут як два окремі мешканці того самого краю екрана: чип на
        // TopCenter на всю ширину, прилад на TopStart із заміряним відступом
        // 64dp. Третім у ту саму смугу малює сам MapChrome — стрічку тривоги і
        // орб шарів. Троє незалежних авторів на двісті пікселів угорі, і
        // жоден не бачить решти: на екрані власника коралова стрічка «ПІДМІНА
        // КООРДИНАТ» накрила тривогу, а прилад заїхав під кнопку шарів.
        //
        // Тепер це одна колонка з одним порядком, і перекриття неможливе не
        // тому, що числа підібрані, а тому, що складати нема чого.

        // Пошук — шторка знизу на півекрана, а не другий екран поверх мапи.
        //
        // Приліт належить самій шторці (пружина знизу), тож входу тут нема —
        // два рухи на один прихід читались би як ривок. Вихід лишається
        // фейдом: більшість закриттів — це тап по результату, після якого
        // рядок мусить згаснути, а не зникнути кадром.
        androidx.compose.animation.AnimatedVisibility(
            visible = ui.searchOpen,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = LocalBottomChromeInset.current),
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(),
        ) {
            val here: Pair<Double, Double>? = operatorLocation?.let { it.lat to it.lon }
                ?: ui.viewportCenter?.let { it.first to it.second }
                ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
            // Пошук на кожну літеру. Раніше набір ЧИСТИВ список, а шукав лише
            // Enter — довідник знаходив «B Fresh» за 56 м, але побачити це міг
            // тільки той, хто здогадався натиснути кнопку на клавіатурі.
            // Скасування LaunchedEffect на зміні запиту і є паузою.
            LaunchedEffect(ui.searchQuery) {
                val q = ui.searchQuery.trim()
                if (q.length < SEARCH_MIN_CHARS) {
                    ui.searchResults = emptyList()
                    ui.searchOfflineHits = emptyList()
                    ui.searchSubmitted = false
                    // Раніше цей вихід не гасив «шукаю»: набравши два символи
                    // й стерши один, людина лишалась із написом «шукаю» в полі
                    // назавжди — попередній прохід скасовувався ЩЕ в леті, а
                    // цей вертався до рядка, що його гасить.
                    ui.searchInFlight = false
                    return@LaunchedEffect
                }
                delay(SEARCH_DEBOUNCE_MS)
                ui.searchInFlight = true
                // ПРИСТРІЙ ПЕРШИЙ. Донедавна тут спершу чекали на публічний
                // Nominatim (4,5 + 7,5 с таймаутів і власна пауза 1,1 с між
                // запитами), і лише ПОТІМ шукали на пристрої — тобто відповідь,
                // готова за мілісекунди, стояла в черзі за чужим сервером. Це і
                // є «пошук місць не спроможний ні на що»: у тунелі, під РЕБ і
                // просто при слабкій мережі людина не бачила НІЧОГО, хоч місце
                // лежало в неї в кишені.
                ui.searchOfflineHits = (here ?: ui.viewportCenter?.let { it.first to it.second })
                    ?.let { viewModel.searchOffline(q, it.first, it.second) }.orEmpty()
                ui.searchSubmitted = true
                // Мережа — збагачення, а не воротар: доїде, коли доїде.
                val net = geocoder().search(q, near = here)
                ui.searchResults = net
                ui.searchInFlight = false
            }
            // Натиск «Пошук» і дотик до історії — теж пошук, і теж мусить
            // скасовувати попередній. Доти обидва запускали голий
            // `coroutineScope.launch` без держака: два натиски поспіль бігли
            // поруч, і той, що завершився ПІЗНІШЕ, перезаписував свіжу
            // відповідь старою.
            val submitted = remember { mutableStateOf<Job?>(null) }
            val runSearch: (String) -> Unit = { q ->
                submitted.value?.cancel()
                ui.searchInFlight = true
                ui.searchSubmitted = true
                viewModel.rememberSearch(q)
                submitted.value = coroutineScope.launch {
                    // Своє — перше (див. пояснення в автопошуку вище).
                    ui.searchOfflineHits = (here ?: ui.viewportCenter?.let { it.first to it.second })
                        ?.let { viewModel.searchOffline(q, it.first, it.second) }.orEmpty()
                    val net = geocoder().search(q, near = here)
                    ui.searchResults = net
                    ui.searchInFlight = false
                }
                keyboard?.hide()
            }
            MapSearchSheet(
                query = ui.searchQuery,
                onQueryChange = { ui.searchQuery = it },
                onSubmit = {
                    val q = ui.searchQuery.trim()
                    if (q.isNotBlank()) runSearch(q)
                },
                onClose = { ui.searchQuery = ""; ui.collapseSearch(); keyboard?.hide() },
                inFlight = ui.searchInFlight,
                submitted = ui.searchSubmitted,
                gap = searchGap,
                hereCountry = remember(here) {
                    countryCodeAt(here, here.inLocalCountry(oblastShapes))
                },
                inUkraine = remember(here) {
                    here.inLocalCountry(oblastShapes)
                },
                missingPack = remember(here, forgePacks) {
                    missingPackFor(here, forgePacks.map { it.country }, here.inLocalCountry(oblastShapes))
                },
                onOpenPacks = onOpenPacks,
                results = ui.searchResults,
                offlineHits = ui.searchOfflineHits,
                recent = recentSearches,
                saved = savedPois,
                here = here,
                alongRoute = routeInfo != null,
                onPickCategory = { selector, label ->
                    if (routeInfo != null) viewModel.showNearbyAlongRoute(selector, label)
                    else {
                        val c = here ?: countryHomePoint()
                        viewModel.showNearby(selector, label, c.first, c.second)
                    }
                    ui.collapseSearch(); keyboard?.hide()
                },
                onSearchHere = { selector, label ->
                    val c = ui.viewportCenter?.let { it.first to it.second }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                        ?: here ?: countryHomePoint()
                    viewModel.showNearby(selector, label, c.first, c.second)
                    ui.collapseSearch(); keyboard?.hide()
                },
                onFloodByName = { q ->
                    // Anchor the brand flood at what the operator is looking at,
                    // not just where he stands — «Нова Пошта» means «near this view».
                    val c = ui.viewportCenter?.let { it.first to it.second }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                        ?: here ?: countryHomePoint()
                    viewModel.showNearbyByName(q, c.first, c.second)
                    ui.collapseSearch(); keyboard?.hide()
                },
                onRouteDirect = { targetLat, targetLon, targetName ->
                    // «Звідси маршрут» довіз людину в пошук: обраний початок
                    // головніший за власну позицію й згорає після маршруту.
                    val origin = ui.routeOriginPick?.let { it.lat to it.lon }
                        ?: here ?: countryHomePoint()
                    // Три аркуші вже обирали режим за відстанню й рухом, а пошук
                    // мовчки лишався «пішки»: дотик по «Додому» за 40 км будував
                    // піший маршрут. Тепер вибір один на всі чотири поверхні.
                    viewModel.routeTo(
                        origin.first, origin.second, targetLat, targetLon,
                        RouteProfileChoice.forTrip(
                            from = origin,
                            to = targetLat to targetLon,
                            motion = motion,
                        ),
                        destName = targetName,
                    )
                    ui.routeOriginPick = null
                    ui.collapseSearch()
                    keyboard?.hide()
                },
                onResultTap = { r ->
                    ui.searchPin = r.latitude to r.longitude
                    ui.dossierTarget = local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
                        lat = r.latitude, lon = r.longitude, nameHint = r.displayName.substringBefore(","),
                    )
                    ui.searchQuery = r.displayName
                    ui.collapseSearch()
                    keyboard?.hide()
                },
                onOfflineTap = { h ->
                    ui.dossierTarget = local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
                        lat = h.lat, lon = h.lon, nameHint = h.name,
                        categoryHint = h.category, subcategoryHint = h.subcategory,
                    )
                    ui.collapseSearch(); keyboard?.hide()
                },
                onSavedTap = { p ->
                    ui.dossierTarget = local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget(
                        lat = p.lat, lon = p.lon, nameHint = p.name,
                        categoryHint = p.category, subcategoryHint = p.subcategory,
                    )
                    ui.collapseSearch(); keyboard?.hide()
                },
                onAskAi = { q ->
                    // Відповідь лишається НА МАПІ. Раніше цей рядок кликав
                    // onAskAi → onAskDialogue, тобто забирав людину в розмову
                    // рівно тоді, коли вона питала про те, на що дивиться.
                    // Піти в розмову тепер можна з самої відповіді — але це
                    // вибір, а не наслідок питання.
                    viewModel.askOnMap(
                        question = q,
                        here = here,
                        locality = ui.viewportLocality,
                    )
                    ui.collapseSearch(); keyboard?.hide()
                },
                onPickRecent = { q ->
                    ui.searchQuery = q
                    runSearch(q)
                },
                onClearRecent = { viewModel.clearRecentSearches() },
                // Питання, а не запис. Доти цей дотик мовчки зберігав центр
                // вікна — з-під шторки на пів екрана, тобто наосліп: жодного
                // слова про те, що дім тепер отут. Тепер шлях називає людина.
                onSetAnchor = { category, label ->
                    ui.anchorPick = AnchorPick(category = category, label = label)
                },
                onOpenTransitHub = {
                    ui.collapseSearch()
                    keyboard?.hide()
                    ui.transitHubOpen = true
                },
                lines = drawnLines,
                onDraw = {
                    // Шторка мусить піти: під нею пів землі, а малюють саме її.
                    ui.collapseSearch()
                    keyboard?.hide()
                    ui.drawPick = DrawPick()
                },
                onLineTap = { line ->
                    ui.collapseSearch()
                    keyboard?.hide()
                    ui.selectedLine = line
                },
            )
        }

        // Відповідь PHANTOM на питання з мапи — знизу, поверх землі, з тим
        // самим резервом під острів, що й панель територій.
        androidx.compose.animation.AnimatedVisibility(
            visible = MapBottomSurface.answerVisible(mapAnswer.isLive, ui.searchOpen),
            // Резерв острівця тут НЕ додається: аркуш бере його сам одним
            // `bottomChromeInsets()`, і подвоєння лишило б порожню смугу.
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it / 2 },
        ) {
            MapAnswerSheet(
                answer = mapAnswer,
                onClose = { viewModel.clearMapAnswer() },
                onShowOnMap = { q ->
                    val c = ui.viewportCenter?.let { it.first to it.second }
                        ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                        ?: operatorLocation?.let { it.lat to it.lon }
                        ?: countryHomePoint()
                    viewModel.showNearbyByName(q, c.first, c.second)
                    viewModel.clearMapAnswer()
                },
                onContinueInChat = { q ->
                    val c = operatorLocation?.let { it.lat to it.lon } ?: countryHomePoint()
                    viewModel.clearMapAnswer()
                    onAskAi?.invoke(c.first, c.second, q)
                },
                onRetry = {
                    viewModel.askOnMap(
                        question = mapAnswer.question,
                        here = operatorLocation?.let { it.lat to it.lon },
                        locality = ui.viewportLocality,
                    )
                },
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = ui.offlineOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it / 2 },
        ) {
            OfflineTerritoriesPanel(
                offer = local.phantom.companion.feature.stream.domain.OfflinePacks.offer(offlineVerdict),
                currentView = offlineViewArea,
                onPcLayerFetched = { spec -> viewModel.pushPcLayer(spec) },
                // A pack is only good for the style it was pulled against. The
                // panel's own default cannot see solar dusk or a manual basemap
                // override, so without this a pack pulled after dark renders nothing.
                styleUrl = offlineStyleUrl,
                // Усе збережене — один список у Профілі › Офлайн-мапи; панель
                // веде туди рядком «Усі офлайн-мапи», тим самим входом, що й
                // пропозиція пакета країни в пошуку.
                onOpenAll = onOpenPacks,
                // Уже збережений коридор маршруту: підсвітити сам пакет і
                // показати його межі. Другий дотик знімає підсвітку.
                onShowPack = { id ->
                    ui.offlineSelectedPack = if (ui.offlineSelectedPack == id) null else id
                    val area = offlinePanelPacks.firstOrNull { it.id == id }?.area
                        ?: return@OfflineTerritoriesPanel
                    ui.camera?.submit(
                        CameraIntent.FitBounds(
                            bounds = GeoBounds(area.southLat, area.westLon, area.northLat, area.eastLon),
                            padding = EdgeInsets(96, 96, 96, 96),
                            issuedAtMs = android.os.SystemClock.uptimeMillis(),
                        ),
                    )
                },
                onSaveView = {
                    val map = ui.mapRef
                    val save = onSaveOfflineRegion
                    if (map == null) {
                        viewModel.postNotice(noMapYetText)
                    } else if (save == null) {
                        // Не казати «зберігаю», коли зберігати нічим.
                        Unit
                    } else {
                        val b = map.projection.visibleRegion.latLngBounds
                        viewModel.postNotice(savingRegionText)
                        save(
                            OfflineBounds(
                                northLat = b.latitudeNorth,
                                eastLon = b.longitudeEast,
                                southLat = b.latitudeSouth,
                                westLon = b.longitudeWest,
                            ),
                            offlineStyleUrl,
                        )
                    }
                },
                // Коридор активного маршруту: одна ділянка на всю дорогу з
                // околицями, замість двадцяти дрібних, які ніхто не видалятиме.
                routeCorridorKm2 = routeCorridorBoxes?.let {
                    local.phantom.companion.feature.stream.domain.RouteCorridor.areaKm2(it)
                },
                routeCorridorBoxes = routeCorridorBoxes.orEmpty(),
                routeLabel = routeCorridorLabel,
                onSaveCorridor = routeCorridorBoxes?.let { boxes ->
                    {
                        viewModel.postNotice(savingRegionText)
                        onSaveRouteCorridor?.invoke(
                            local.phantom.companion.feature.stream.domain.RouteCorridor
                                .multiPolygonJson(boxes),
                            OfflineBounds(
                                northLat = boxes.maxOf { it.northLat },
                                eastLon = boxes.maxOf { it.eastLon },
                                southLat = boxes.minOf { it.southLat },
                                westLon = boxes.minOf { it.westLon },
                            ),
                            offlineStyleUrl,
                            routeCorridorLabel,
                            routeCorridorBytes,
                        )
                    }
                },
                onDismiss = { ui.offlineOpen = false },
                // Смуга навігації системи ПЛЮС острівець. Резерв острівця сам по
                // собі лишав нижній рядок аркуша під системною смугою — саме там
                // і різало «ПК не на звʼязку» навпіл.
                modifier = Modifier.navigationBarsPadding().padding(
                    start = MapChromeMetrics.Edge,
                    end = MapChromeMetrics.Edge,
                    bottom = LocalBottomChromeInset.current + PhantomSpace.s2,
                ),
            )
        }

        MapSheetHost(
            ui = ui,
            operatorLocation = operatorLocation,
            viewModel = viewModel,
            onAskAi = onAskAi,
            onOpenExternal = onOpenExternal,
            onSavePoi = onSavePoi,
            onMeetHere = onMeetHere,
            meetPicking = meetPicking,
            onDeleteSavedPlace = onDeleteSavedPlace,
            onRenameSavedPlace = onRenameSavedPlace,
            onRecategorizeSavedPlace = onRecategorizeSavedPlace,
            oblastAt = oblastShapes::oblastAt,
            oblastCentroid = oblastShapes::centroidOf,
            // NAV-4 — the dossier's address line rides the same throttled client.
            reverseGeocode = { lat, lon -> geocoder().reverse(lat, lon)?.displayName },
            transitRepository = transitRepository,
        )

        MountModeOverlay(ui = ui, hasFix = operatorLocation != null, isDarkTheme = isDarkTheme)
    }

    local.phantom.companion.feature.stream.ui.map.cockpit.CarCockpitSheet(
        open = ui.carCockpitOpen,
        onClose = { ui.carCockpitOpen = false },
        onLaunchMount = {
            ui.mountMode = true
        },
        onReportRoadHazard = { hazard ->
            val o = operatorLocation
            if (o != null) {
                viewModel.reportHazard(hazard, o.lat, o.lon)
            } else {
                viewModel.postNotice(context.getString(R.string.map_hazard_no_fix, hazard))
            }
        },
        onQuickSearch = { query ->
            ui.searchOpen = true
            ui.searchQuery = query
        },
    )
}

/**
 * Режим кріплення поверх усього, що малює мапа.
 *
 * Власна функція, а не `if` усередині Box: `ui.bearing` пише датчик обертання,
 * і читання його в тілі [MapScreen] перескладало б увесь екран на кожен рух
 * керма. Тут область перескладання — сам цей overlay.
 */
@Composable
private fun MountModeOverlay(ui: MapUiState, hasFix: Boolean, isDarkTheme: Boolean) {
    if (!ui.mountMode) return
    local.phantom.companion.feature.stream.ui.map.mount.MountGlanceScreen(
        bearingDeg = ui.bearing,
        hasFix = hasFix,
        isDarkTheme = isDarkTheme,
        onExit = { ui.mountMode = false },
    )
}

/** Нижче цього стрілка компаса не рухається на жоден піксель. */
private const val COMPASS_STATE_EPS_DEG = 0.5f

/** Просвіт під смугою тривоги — і під карткою маневру, коли вона є. Обидва
 *  числа поза сіткою: вони міряні від чужої висоти, а не від кроку. */
private val EW_HUD_TOP_CALM = 64.dp
private val EW_HUD_TOP_GUIDING = 208.dp

/** Коротший запит дає сотні влучань і жодної користі. */
/** Зум для місця з чужого листа: видно двір, а не квартал. */
private const val SHARED_POINT_ZOOM = 17.0

private const val SEARCH_MIN_CHARS = 2

/** Пауза після останньої літери, перш ніж питати довідник. */
private const val SEARCH_DEBOUNCE_MS = 280L

/**
 * A tapped search / offline / saved result.
 *
 * `disarmsFollow` is the default and it is the whole point: these three taps
 * flew the camera but left the follow armed, so during guidance the follow loop
 * dragged it straight back the next frame and the result never stayed on screen.
 */
private fun focusResult(
    lat: Double,
    lon: Double,
    viewportHeightDp: Double,
    durationMs: Int = local.phantom.companion.feature.stream.ui.map.atlas.CameraService.FOCUS_MS,
) = CameraIntent.Focus(
    target = GeoPoint(SheetCameraLift.liftedLatitude(lat, OPERATOR_ZOOM, viewportHeightDp), lon),
    zoom = OPERATOR_ZOOM,
    issuedAtMs = android.os.SystemClock.uptimeMillis(),
    durationMs = durationMs,
)

internal fun statusBarScrimColor(
    canvas: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.graphics.Color = canvas.copy(alpha = PhantomAlpha.Near)

/** «1.5 km · 18 min, on foot» — units and mode from resources, so in the app language. */
internal fun routeSummaryLine(
    res: android.content.res.Resources,
    distanceM: Double,
    durationS: Double,
    profile: local.phantom.companion.core.net.map.RouteProfile,
): String = "${fmtDistance(res, distanceM)} · ${fmtDuration(res, durationS)}, ${res.getString(profileLabelRes(profile))}"
