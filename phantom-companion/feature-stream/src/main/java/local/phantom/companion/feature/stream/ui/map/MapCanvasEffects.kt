package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPriority
import local.phantom.companion.feature.stream.ui.map.atlas.CameraService
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionRegistry
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalCityFeed
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalGeo
import local.phantom.companion.feature.stream.ui.map.crystal.CityTileData
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalTileKey
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** LEVIATHAN-4 — radar reel pace: RainViewer frames are 10 min apart. */
private const val RADAR_FRAME_MS = 750L

/** Smallest bearing change worth waking the compass for, mid-gesture. */
private const val COMPASS_LIVE_EPS_DEG = 0.5f

/** How far up a dropped point is lifted, as a fraction of screen height —
 *  clear of the action sheet that rises over the lower half. */
private const val DROP_LIFT_FRACTION = 0.22f
private const val DROP_CENTRE_MS = 420

/**
 * Наскільки близько до вершини палець уже означає «беру її».
 *
 * Ширше за поріг тапу (12dp) навмисне: там палець шукає, у що влучив, серед
 * усього намальованого, а тут вибір лише між двома відповідями — вершина або
 * земля, і промах коштує зайвої вершини посеред лінії.
 */
private const val DRAW_VERTEX_GRAB_DP = 22f

/**
 * W-7 — MapLibre obeys the Activity, not the composition. This once fired
 * onStart/onResume at composition and only paused on disposal, so a
 * backgrounded app kept a RESUMED MapView: GL context live, tiles fetching,
 * location engine running behind a dead window.
 */
@Composable
internal fun MapViewLifecycle(mapView: MapView, context: Context, active: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (active) mapView.onStart()
                Lifecycle.Event.ON_RESUME -> if (active) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> if (active) mapView.onPause()
                Lifecycle.Event.ON_STOP -> if (active) mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (active) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (active) {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            }
            // onDestroy lives in its own effect: the MapView outlives `active`,
            // and this one only halts the rendering threads.
        }
    }
    DisposableEffect(mapView) { onDispose { mapView.onDestroy() } }

    // The OS asking for memory back is not a suggestion. MapLibre frees its
    // tile caches here; nothing else in this tree was forwarding it.
    val callbacks = remember(mapView) {
        object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    mapView.onLowMemory()
                }
            }
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        }
    }
    DisposableEffect(callbacks) {
        context.applicationContext.registerComponentCallbacks(callbacks)
        onDispose { context.applicationContext.unregisterComponentCallbacks(callbacks) }
    }
}

/**
 * ux-map-design.md §5.1 — the register readout. Nothing renders the crystal
 * feed; it exists so the map can say how much of what it draws is measured,
 * derived, guessed or merely remembered.
 */
@Composable
internal fun MapGroundRegisterProbe(
    ui: MapUiState,
    tiles: Map<CrystalTileKey, CityTileData>,
    visibleTiles: Collection<CrystalTileKey>,
    isOccupiedGround: (Double, Double) -> Boolean,
) {
    LaunchedEffect(tiles, visibleTiles) {
        // Occupied status checked once per TILE (its centre), not per building:
        // point-in-polygon against 26 oblast rings is not free, and a tile never
        // straddles an oblast border by enough to matter at this grain.
        val occupiedByTile = HashMap<CrystalTileKey, Boolean>()
        val registers = visibleTiles.mapNotNull { key -> tiles[key]?.let { key to it } }
            .flatMap { (key, tile) ->
                val occupied = occupiedByTile.getOrPut(key) {
                    val b = CrystalGeo.boundsOf(key)
                    isOccupiedGround((b.south + b.north) / 2.0, (b.west + b.east) / 2.0)
                }
                tile.buildings.map { b -> if (occupied) GroundRegister.REMEMBERED else b.register }
            }
        ui.registerComposition = if (registers.isEmpty()) null else RegisterComposition.of(registers)
    }
}

/**
 * A style swap is not a new map. These hang off the [MapLibreMap], which
 * outlives every style — registered inside `setStyle` they multiplied per
 * basemap change, and every tap then ran N full query chains on the main
 * thread, which is a map that stops answering the finger.
 */
// Слухач висить на самій мапі, а вона не є клікабельною мішенню: у неї
// немає ані performClick, ані ролі кнопки — лінт тут говорить про інший
// клас віджетів.
@android.annotation.SuppressLint("ClickableViewAccessibility")
@Composable
internal fun MapSurfaceListeners(
    ui: MapUiState,
    viewModel: MapViewModel,
    context: Context,
    camera: CameraService?,
    tapDispatcher: MapTapDispatcher,
    registry: () -> ContributionRegistry?,
    frame: () -> LensFrame,
    terrainDepth: Boolean,
    crystalFeed: CrystalCityFeed,
    offlinePacks: List<RegionInfo>,
    offlineStyleUrl: String,
    onHatch: (String) -> Unit,
    mapView: MapView,
) {
    // Дотик до вершини перехоплюється ЛИШЕ поки правлять збережену лінію:
    // поза правкою слухач знімається, і мапа отримує дотик тим самим шляхом,
    // яким отримувала завжди.
    val editing = ui.drawPick?.editingId != null
    DisposableEffect(mapView, ui.mapRef, editing) {
        val map = ui.mapRef
        if (!editing || map == null) return@DisposableEffect onDispose { }
        mapView.setOnTouchListener(
            DrawVertexDrag(
                map = map,
                pick = { ui.drawPick },
                tolerancePx = context.resources.displayMetrics.density * DRAW_VERTEX_GRAB_DP,
                onHold = { i -> ui.drawPick = ui.drawPick?.copy(selected = i) },
                onDrag = { i, at ->
                    // Перевірка меж не зайва: «Прибрати вершину» можна встигнути
                    // натиснути другим пальцем, доки перший тягне цю саму.
                    val draw = ui.drawPick
                    if (draw != null && i in draw.points.indices) {
                        ui.drawPick = draw.copy(
                            points = draw.points.toMutableList().also { it[i] = at },
                            selected = i,
                        )
                    }
                },
            ),
        )
        onDispose { mapView.setOnTouchListener(null) }
    }

    DisposableEffect(mapView) {
        val failed = MapView.OnDidFailLoadingMapListener { ui.basemapStyleFailed = true }
        val loaded = MapView.OnDidFinishLoadingStyleListener { ui.basemapStyleFailed = false }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.addOnDidFinishLoadingStyleListener(loaded)
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failed)
            mapView.removeOnDidFinishLoadingStyleListener(loaded)
        }
    }

    val packsNow by rememberUpdatedState(offlinePacks)
    val styleNow by rememberUpdatedState(offlineStyleUrl)
    val hatchNow by rememberUpdatedState(onHatch)
    fun recheckGround(map: MapLibreMap) {
        val hatch = runCatching {
            notDownloadedGeoJsonFor(map, context, packsNow, styleNow)
        }.getOrDefault(EMPTY_FC)
        hatchNow(hatch)
        // Той самий факт, але словом. Візерунок бачить око, а зрозуміти
        // його не може ніхто: діагональна смужка не каже, що робити.
        ui.groundNotSavedHere = hatch != EMPTY_FC
    }

    // Keyed on the map only: pack progress ticks must not re-register the callback.
    // onCapabilitiesChanged is left out on purpose: it fires on every signal-strength tick.
    DisposableEffect(ui.mapRef) {
        val map = ui.mapRef ?: return@DisposableEffect onDispose { }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val net = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { main.post { recheckGround(map) } }
            override fun onLost(network: android.net.Network) { main.postDelayed({ recheckGround(map) }, NET_SETTLE_MS) }
        }
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(net, main) }.isSuccess
        onDispose {
            main.removeCallbacksAndMessages(null)
            if (registered) runCatching { cm?.unregisterNetworkCallback(net) }
        }
    }

    val tileHarvest = remember { TilePlaceHarvest() }
    DisposableEffect(ui.mapRef, tapDispatcher, offlinePacks, offlineStyleUrl) {
        val map = ui.mapRef ?: return@DisposableEffect onDispose { }

        val onIdle = MapLibreMap.OnCameraIdleListener {
            val b = map.projection.visibleRegion.latLngBounds
            val cp = map.cameraPosition
            viewModel.updateCameraBounds(
                OfflineBounds(
                    northLat = b.latitudeNorth,
                    eastLon = b.longitudeEast,
                    southLat = b.latitudeSouth,
                    westLon = b.longitudeWest,
                ),
                zoom = cp.zoom,
            )
            cp.target?.let { ui.viewportCenter = Triple(it.latitude, it.longitude, cp.zoom) }
            cp.target?.let { t ->
                val now = android.os.SystemClock.uptimeMillis()
                if (tileHarvest.due(t.latitude, t.longitude, cp.zoom, now)) {
                    val seen = TilePlaceHarvest.read(map)
                    if (seen.isNotEmpty()) {
                        tileHarvest.mark(t.latitude, t.longitude, now)
                        viewModel.rememberTilePlaces(seen)
                    }
                }
            }
            // W-9 — the MAP's bearing, the only thing a compass may speak about.
            // (ui.bearing is the DEVICE's azimuth — a different fact.)
            ui.mapBearing = cp.bearing.toFloat()
            if (terrainDepth) crystalFeed.onViewport(
                north = b.latitudeNorth,
                east = b.longitudeEast,
                south = b.latitudeSouth,
                west = b.longitudeWest,
                zoom = cp.zoom,
            )
            recheckGround(map)
            // Idle fires ONCE, and it fires while the just-released gesture is
            // still inside its cooldown, so the pitch rule refused and no second
            // idle ever came — the map stayed flat however far you zoomed. Mark
            // it and let the watcher re-ask once the fingers are forgotten.
            ui.pitchAskedAtMs = android.os.SystemClock.uptimeMillis()
        }

        val onClick = MapLibreMap.OnMapClickListener { point ->
            val drawing = ui.drawPick
            if (drawing != null) {
                // Дотик у режимі малювання ставить вершину й НЕ йде далі: інакше
                // кожна вершина відкривала б досьє того, у що влучив палець.
                // Повне імʼя свідомо: у цьому файлі `GeoPoint` — камерний, з
                // atlas, а вершини ламаної живуть у типі сховища.
                // Сюди дотик доходить лише повз вершини: ті забирає
                // [DrawVertexDrag] ще на ACTION_DOWN. Тож нова вершина — і
                // обрана відпускається, бо палець показав на інше місце.
                ui.drawPick = drawing.copy(
                    points = drawing.points +
                        local.phantom.companion.core.data.spatial.GeoPoint(
                            point.latitude,
                            point.longitude,
                        ),
                    selected = null,
                )
                return@OnMapClickListener true
            }
            viewModel.selectVehicle(null)
            val reg = registry()
            val hit = reg != null && tapDispatcher.onTap(
                map = map,
                registry = reg,
                frame = frame(),
                point = point,
                tolerancePx = context.resources.displayMetrics.density * TAP_TOLERANCE_DP,
            )
            if (!hit && ui.dossierTarget != null) {
                ui.dossierTarget = null
                return@OnMapClickListener true
            }
            hit
        }

        // M-A01 — long-press drops a point and opens its action sheet. FIELD FIX:
        // the sheet rises over the LOWER half, exactly where a finger usually
        // lands, so the point the operator just placed sat hidden behind it.
        val onLongClick = MapLibreMap.OnMapLongClickListener { point ->
            // Довгий дотик під час малювання мусить мовчати: аркуш точки поверх
            // напівнамальованої лінії ховає саме те, що людина зараз веде.
            if (ui.drawPick != null) return@OnMapLongClickListener true
            ui.droppedPoint = point.latitude to point.longitude
            ui.droppedAddress = null
            ui.searchPin = point.latitude to point.longitude
            val lifted = liftedDropTarget(map, point, DROP_LIFT_FRACTION)
            camera?.submit(
                CameraIntent.Focus(
                    target = GeoPoint(lifted.latitude, lifted.longitude),
                    zoom = null,
                    durationMs = DROP_CENTRE_MS,
                    issuedAtMs = android.os.SystemClock.uptimeMillis(),
                ),
            )
            true
        }

        val onMove = MapLibreMap.OnCameraMoveListener {
            val b = map.cameraPosition.bearing.toFloat()
            if (kotlin.math.abs(b - ui.mapBearing) > COMPASS_LIVE_EPS_DEG) ui.mapBearing = b
        }
        map.addOnCameraIdleListener(onIdle)
        map.addOnCameraMoveListener(onMove)
        map.addOnMapClickListener(onClick)
        map.addOnMapLongClickListener(onLongClick)
        onDispose {
            map.removeOnCameraIdleListener(onIdle)
            map.removeOnCameraMoveListener(onMove)
            map.removeOnMapClickListener(onClick)
            map.removeOnMapLongClickListener(onLongClick)
        }
    }
}

/**
 * MAP-10 — a PAN ends the follow; pinch, rotate and shove do NOT (Google's
 * grammar: zooming while followed is still following) but all four pause every
 * camera authority while the fingers are down, or the per-frame write fights
 * the gesture and the map «freezes».
 */
@Composable
internal fun MapGestureWiring(ui: MapUiState, camera: CameraService?) {
    DisposableEffect(ui.mapRef, camera) {
        val map = ui.mapRef ?: return@DisposableEffect onDispose { }
        // Every callback (begin, progress, end) refreshes the mark — the guard
        // stays honest even if a detector eats its end event.
        val touch = {
            ui.cameraGestureAtMs = android.os.SystemClock.uptimeMillis()
            camera?.onGesture()
            Unit
        }
        val onMove = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                touch()
                if (ui.followMode) ui.followMode = false
            }
            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = touch()
            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = touch()
        }
        val onScale = object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = touch()
            override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = touch()
            override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = touch()
        }
        val onRotate = object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) = touch()
            override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) = touch()
            override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) = touch()
        }
        val onShove = object : MapLibreMap.OnShoveListener {
            override fun onShoveBegin(detector: org.maplibre.android.gestures.ShoveGestureDetector) = touch()
            override fun onShove(detector: org.maplibre.android.gestures.ShoveGestureDetector) = touch()
            override fun onShoveEnd(detector: org.maplibre.android.gestures.ShoveGestureDetector) = touch()
        }
        map.addOnMoveListener(onMove)
        map.addOnScaleListener(onScale)
        map.addOnRotateListener(onRotate)
        map.addOnShoveListener(onShove)
        onDispose {
            map.removeOnMoveListener(onMove)
            map.removeOnScaleListener(onScale)
            map.removeOnRotateListener(onRotate)
            map.removeOnShoveListener(onShove)
        }
    }
}

/**
 * The three camera writers that arrive as flows. All three used to call
 * `animateCamera` directly and set `followMode = false` beside it; now they
 * state an intent and the service decides whose frame it is.
 */
@Composable
internal fun MapCameraIntents(
    ui: MapUiState,
    viewModel: MapViewModel,
    camera: CameraService?,
    fitInsets: EdgeInsets,
) {
    // R4.1 — `map.focus` from the model. ASSISTANT priority: it may not cut
    // across the operator's own hand or a live drive.
    LaunchedEffect(camera) {
        val cam = camera ?: return@LaunchedEffect
        val requests = viewModel.focusRequests ?: return@LaunchedEffect
        requests.collect { target ->
            // A replayed request from an hour ago is not an instruction.
            if (!target.isFresh(System.currentTimeMillis())) return@collect
            val map = ui.mapRef ?: return@collect
            // An explicit target IS the camera's initialisation; without this the
            // style block's «start at the operator» placement ate the fly and the
            // map opened at the operator's own dot whatever had been asked for.
            ui.cameraInitialized = true
            cam.submit(
                CameraIntent.Focus(
                    target = GeoPoint(target.lat, target.lon),
                    zoom = target.zoom ?: map.cameraPosition.zoom.coerceAtLeast(OPERATOR_ZOOM),
                    priority = CameraPriority.ASSISTANT,
                    issuedAtMs = android.os.SystemClock.uptimeMillis(),
                ),
            )
        }
    }

    // M-C05/C06 — frame an evacuation route. Закон вписування: жоден піксель
    // маршруту не лежить ані під пігулкою вгорі, ані під смугою внизу.
    LaunchedEffect(camera, fitInsets) {
        val cam = camera ?: return@LaunchedEffect
        viewModel.cameraFlyToBounds.collect { b ->
            cam.submit(
                CameraIntent.FitBounds(
                    bounds = GeoBounds(b.southLat, b.westLon, b.northLat, b.eastLon),
                    padding = fitInsets,
                    issuedAtMs = android.os.SystemClock.uptimeMillis(),
                ),
            )
        }
    }
}

/**
 * LEVIATHAN-4 — the radar PLAYS. With a reel (≥2 frames) this flips
 * pre-installed frame layers' visibility at reel pace — weather history
 * sweeping across the map, then the nowcast. A single frame degrades to the
 * still overlay the radar contribution owns; dark = all frames gone, loop
 * parked.
 */
@Composable
internal fun MapRadarReel(ui: MapUiState, lit: Boolean, active: Boolean) {
    LaunchedEffect(ui.mapRef, lit, ui.radarFrames, active) {
        val map = ui.mapRef ?: return@LaunchedEffect
        val frames = ui.radarFrames
        if (!lit || !active || frames.size < 2) {
            map.style?.let { s ->
                mapStyleStep("radarReelOff") { applyRadarFrames(s, emptyList(), 0, false) }
            }
            return@LaunchedEffect
        }
        // The reel replaces the still layer entirely.
        map.style?.let { s -> mapStyleStep("radarStaticOff") { applyRadar(s, null, false) } }
        var i = 0
        while (true) {
            val style = map.style
            if (style != null && style.isFullyLoaded) {
                mapStyleStep("radarFrame") { applyRadarFrames(style, frames, i % frames.size, true) }
                // Hold the newest observation twice as long — the "now" frame is
                // the one that matters; the sweep is context.
                val holdMs = if (i % frames.size == frames.size - 1) RADAR_FRAME_MS * 2 else RADAR_FRAME_MS
                i++
                delay(holdMs)
            } else {
                delay(RADAR_FRAME_MS)
            }
        }
    }
}

private const val NET_SETTLE_MS = 1_500L
