package local.phantom.companion.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.data.mapPeople
import local.phantom.companion.core.design.component.ConfirmKind
import local.phantom.companion.core.design.component.PhantomInlineConfirm
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import local.phantom.companion.core.sensor.sources.NavCadencePlanner
import local.phantom.companion.feature.stream.ui.map.MapScreen
import local.phantom.companion.feature.stream.ui.map.MapViewModel

private data class PoiConfirm(val message: String, val kind: ConfirmKind, val at: Long)

/**
 * Everything the map does while its tab is not on screen: guidance voice, the
 * active corridor for the chat, the screen-on flag and the GPS cadence. Lives
 * with the [MapViewModel] at the home entry, so a drive keeps talking from Чати.
 */
@Composable
internal fun MapSession(mapVm: MapViewModel, mapOnScreen: Boolean) {
    val container = remember { PhantomApp.container() }
    val view = LocalView.current
    LaunchedEffect(mapVm) {
        val routed = container.ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts
        val speaker = local.phantom.companion.core.nav.NavCueSpeaker(
            scope = this,
            speak = { text, urgent ->
                runCatching {
                    val norm = local.phantom.companion.core.voice.UaTtsNormalizer.normalize(text)
                    if (urgent && routed != null) {
                        routed.speakUrgent(norm)
                    } else {
                        container.ttsEngine.speak(norm)
                    }
                }
            },
            stopSpeaking = { runCatching { container.ttsEngine.stop() } },
        )
        speaker.start()
        // ON-TIME VOICE — the moment a maneuver becomes «next», its final
        // words go to the premium engine's synthesis cache, so the beautiful
        // voice can speak the turn with ~zero first-audio latency instead of
        // ceding it to System TTS.
        launch {
            mapVm.voicePrewarms.collect { text ->
                runCatching {
                    routed?.prewarm(
                        local.phantom.companion.core.voice.UaTtsNormalizer.normalize(text),
                    )
                }
            }
        }
        // Cues are spoken by the container's process-scoped speaker (it keeps
        // talking with no screen). Collecting them here too would say every
        // turn twice.
        kotlinx.coroutines.awaitCancellation()
    }

    // W-3 — NAV-Ω1's «по дорозі» is asked from the CHAT. The active corridor
    // is therefore published from the ViewModel, not from the map's
    // composition, and cleared only when the Continuum itself dies.
    LaunchedEffect(mapVm) {
        mapVm.activeRoutePoints.collect { pts -> container.activeRoutePoints.value = pts }
    }
    DisposableEffect(Unit) {
        onDispose {
            container.activeRoutePoints.value = null
            container.mapViewport.value = null
        }
    }

    // W-4 — the screen does not sleep while PHANTOM leads. The flag follows
    // the drive, not the stratum: it is held for exactly as long as a
    // guidance session is live, wherever the camera happens to be.
    val guiding by mapVm.isGuiding.collectAsStateWithLifecycle()
    DisposableEffect(guiding, view) {
        if (guiding) view.keepScreenOn = true
        onDispose { if (guiding) view.keepScreenOn = false }
    }

    // PHANTOM-EW — the chip runs at navigation grade (1 Hz+, high accuracy)
    // for exactly as long as someone is being LED or WATCHED: a live drive,
    // follow armed, OR the map itself is the visible stratum. The last case is
    // the everyday one — you open the map to see WHERE you are, and a balanced
    // (Wi-Fi/cell) fix drops a jumpy ~25 m blob until you happen to arm follow.
    // Map-foreground is user-initiated and short, so the battery stays honest.
    // THERMAL 11.08 — `resting == WORLD` тримався й тоді, коли застосунок
    // пішов у фон: палата не міняється від кнопки «Додому». Тому після
    // одного заходу на мапу телефон назавжди лишався в навігаційному класі —
    // профіль ART намірив 34% ядра з погаслим екраном. Стеження й «мапа
    // внизу» тепер вимагають, щоб застосунок був хоча б STARTED; живий
    // супровід (`guiding`) — ні, він має право рахувати у фоні.
    val followArmed by mapVm.followActive.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var appStarted by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> appStarted = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> appStarted = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // ТЕМП ФІКСІВ 29.08 — щаблів три, і перемикає їх ФАКТ РУХУ.
    //
    // Умова, що стояла тут, знала три речі: супровід, стеження і палату
    // камери. Жодна з них не є рухом. Наслідок з запису власника (30 хв
    // їзди): 165 оновлень позиції, медіана 9 с, найдовший проміжок 526 с —
    // «я їду, а точка стоїть на місці, тільки телепортується». Ще один
    // наслідок у зворотний бік: людина, що стоїть з відкритою мапою,
    // тримала повний 1 Гц, бо стояння тут ніяк не виражалось.
    //
    // Про рух питаємо не свій новий поріг, а `KinematicsEngine` — той самий
    // профіль, який локатор уже публікує як `motion` і який малює пігулку
    // руху в хромі мапи. `container.locator` уже створено вище (його читає
    // фабрика MapViewModel), тож жодного нового живого обʼєкта тут нема.
    //
    // Витримку і самі щаблі тримає [NavCadencePlanner] у `:core-sensor` —
    // поруч із чипом, чий темп він призначає, і під тестом.
    val cadencePlanner = remember { NavCadencePlanner() }
    val motionFeed = remember(container) { container.locator?.motion }
    LaunchedEffect(guiding, followArmed, mapOnScreen, appStarted, motionFeed) {
        val mapOnGlass = appStarted && (followArmed || mapOnScreen)
        // Немає локатора — немає свідка руху. `null` планувальник читає як
        // рух: краще зайвий герц, ніж застигла точка.
        val feed: StateFlow<KinematicsEngine.MotionProfile?> =
            motionFeed ?: MutableStateFlow(null)
        // collectLatest, а не collect: усередині є `delay` на витримку спуску,
        // і нова звістка про рух мусить його ПЕРЕБИТИ, а не стати за ним у
        // чергу. Скасування не чіпає стан планувальника, тож годинник витримки
        // продовжує йти крізь перезапуски — саме цього ми й хочемо.
        feed.collectLatest { motion ->
            fun pushCadence() {
                val next = cadencePlanner.update(
                    leading = guiding,
                    foreground = appStarted,
                    mapOnGlass = mapOnGlass,
                    motion = motion,
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                )
                // МЕЖА, яку тут не подолати: труба до чипа
                // (`AppContainer.setNavigationGrade` → `MobileSensorAdapter`)
                // несе Boolean, а обидва файли — чужі. Тому STANDSTILL поки
                // округлюється ВГОРУ до навігаційного темпу. Округлити вниз
                // не можна: на ambient класифікатор руху сліпне (2–3 зразки
                // швидкості на 12-секундне вікно замість чотирьох), і
                // телефон, що стоїть із відкритою мапою, більше не помітить,
                // що поїхав. Шов — по одному рядку прокидання в кожному з
                // двох файлів, і щабель спокою стане живим.
                // Труба тепер несе САМ ЩАБЕЛЬ, а не двійкове «так/ні»: доти
                // щаблі існували, мали тести й не доходили до чипа.
                container.setCadence(next)
            }
            pushCadence()
            // Спуск, який планувальник притримав, мусить долетіти навіть тоді,
            // коли більше нічого не станеться: людина заглушила мотор, і
            // жодної нової події може вже не бути.
            var dueMs = cadencePlanner.demotionDueInMs(android.os.SystemClock.elapsedRealtime())
            while (dueMs > 0L) {
                delay(dueMs)
                pushCadence()
                dueMs = cadencePlanner.demotionDueInMs(android.os.SystemClock.elapsedRealtime())
            }
        }
    }
    LaunchedEffect(mapOnScreen) { mapVm.setWorldVisible(mapOnScreen) }
}

@Composable
internal fun MapTab(vm: MapViewModel, onOpenPacks: () -> Unit) {
    var poiConfirm by remember { mutableStateOf<PoiConfirm?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        WorldStratum(
            vm = vm,
            visible = true,
            onAskDialogue = { prompt -> askPhantom(prompt) },
            onPoiConfirm = { message, kind ->
                poiConfirm = PoiConfirm(message, kind, System.currentTimeMillis())
            },
            onOpenPacks = onOpenPacks,
        )
        PhantomInlineConfirm(
            message = poiConfirm?.message,
            kind = poiConfirm?.kind ?: ConfirmKind.Success,
            visibleAt = poiConfirm?.at,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun rememberMapViewModel(): local.phantom.companion.feature.stream.ui.map.MapViewModel {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val container = remember { appContext as PhantomApp }.container
    // Brief 03 defect #1 — neither bundled geo asset is parsed here any more.
    // This runs at the shell's FIRST composition, so `remember { load(...) }`
    // put 708 KB of oblast geojson + 156 KB of region index on the UI thread
    // before the first frame: 100–300 ms of jank on a weak phone, and the boot
    // warm-up thread could only ever race it, never reliably win. Both are now
    // pulled behind a lambda that no UI thread invokes.
    //
    return viewModel(
        factory = viewModelFactory {
            initializer {
                local.phantom.companion.feature.stream.ui.map.MapViewModel(
                    poiRepository = container.poiRepository,
                    spatialLayers = container.spatialLayerRepository,
                    worldSummoner = container.worldSummoner,
                    vaultStore = container.vaultStore,
                    savedPlaceStore = container.savedPlaceStore,
                    drawnLineStore = container.drawnLineStore,
                    threatBriefRequests = container.threatBriefRequests,
                    shelterRepository = container.shelterRepository,
                    locationSource = { container.locationProvider.current() },
                    oblastShapesLoad = {
                        local.phantom.companion.feature.stream.ui.map.OblastShapes.load(appContext)
                    },
                    oblastAt = { lat, lon ->
                        local.phantom.companion.feature.stream.ui.map.OblastShapes.load(appContext)
                            .oblastAt(lat, lon)
                    },
                    navCommands = container.navCommands,
                    navEngine = container.navEngine,
                    motionFeed = container.locator?.motion,
                    roadWaysFetch = { s, w, n, e -> container.fetchRoadWays(s, w, n, e) },
                    packTransitFetch = { s, w, n, e -> container.fetchPackTransit(s, w, n, e) },
                    roadSnapMatch = { trace -> container.snapToRoad(trace) },
                    coverFetch = { s, w, n, e -> container.fetchCoverGrid(s, w, n, e) },
                    // HORIZON II.1 — один прошарок неба на процес: цей екран
                    // його годує вироками довіри, а читають ЙОГО обидва
                    // маршрутизатори — і евакуаційний, і головний.
                    sharedGnssIntegrity = container.gnssIntegrity,
                    trustFeed = container.locator?.trust,
                    airAlertWatch = container.airAlertWatch,
                    // H-02 — the satellite clock check has to outlive the
                    // process: the blackout that wrecks a phone's clock is also
                    // what kills the process, so an in-memory witness would be
                    // erased by the exact event it exists to catch.
                    clockSkewStore = container.clockSkewStore,
                    // ARCH: PRECISION PIVOT — structured position diagnostics, in
                    // debug builds only. `adb logcat -s PhantomNav` now shows every
                    // change of the displayed-position source (raw / road-snap /
                    // guidance / frozen) with speed, accuracy, heading and snap
                    // state. Release ships silent.
                    positionLog = if (local.phantom.companion.BuildConfig.DEBUG) {
                        { line -> android.util.Log.i("PhantomNav", line) }
                    } else {
                        null
                    },
                    breadcrumbTracker = container.breadcrumbTracker,
                    sentinelFenceEngine = container.sentinelFenceEngine,
                    surfaceFeedLoader = { container.surfaceFeed },
                    metroFeedLoader = { container.metroFeed },
                    gtfsScheduleLoader = { container.freshSchedule() },
                    // ПОЛЬОВА ПОМИЛКА, 23.08 — the stationary lock used to
                    // release on the fix's own Doppler speed, which a spoofer
                    // supplies for free. The body gets a veto.
                    bodyMoving = container.bodyMoving,
                    peerPointsRepository = container.peerPoints,
                    liveShareBanners = container.liveShare.banners,
                    onStopShare = { peerId -> container.liveShare.stop(peerId) },
                    routeShareTargets = container.routeShareTargets,
                    sendPeerLetter = { peerId, body -> container.mesh.send(peerId, body) },
                    peerRouteMarks = container.liveShare.peerRoutes,
                    sweepLive = { container.liveShare.sweep() },
                    whoSeesMe = container.whoSeesMe,
                    onSetWhoSees = { id, p -> container.setWhoSeesMe(id, p) },
                    onVanish = { container.peopleWhere.vanish() },
                    onWritePeer = { id -> container.peerChatRequest.value = id },
                    onAddPerson = { container.events.routeRequest.value = local.phantom.companion.navigation.Routes.Scan.path },
                    people = container.mapPeople,
                    // Був null: «Огляд від PHANTOM» і запит з пошуку на мапі
                    // мовчки падали в AI_OFFLINE, хоча aiRouter живий і той
                    // самий, що вже відповідає в чаті (AgencySessionManager).
                    aiComplete = { container.aiRouter.complete(it) },
                    herePort = local.phantom.companion.feature.stream.ui.map.here.HereTruthHttp(
                        baseUrl = local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
                        prove = { cell, kind, reading ->
                            container.mesh.proveTruthObserve(cell, kind, reading)?.let {
                                local.phantom.companion.feature.stream.ui.map.here.TruthProof(
                                    it.pubKey, it.nonce, it.issuedMs, it.signature,
                                )
                            }
                        },
                    ),
                    aroundPort = local.phantom.companion.data.MapAroundSource(
                        venues = container.venues,
                        localPlaces = { lat, lon ->
                            container.poiRepository.getNearbyResult(
                                lat, lon,
                                local.phantom.companion.data.MapAroundSource.RADIUS_M,
                                local.phantom.companion.data.MapAroundSource.SELECTOR,
                            )
                        },
                    ),
                )
            }
        },
    )
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun WorldStratum(
    vm: local.phantom.companion.feature.stream.ui.map.MapViewModel,
    /** W-6 — false while the camera rests above the ground: the map stays
     *  composed (W-5) but its heavy pipelines stand down. */
    visible: Boolean,
    onAskDialogue: (String) -> Unit,
    /** Wave 1 §5 Executor C — replaces the four `Toast.makeText` calls below. */
    onPoiConfirm: (message: String, kind: ConfirmKind) -> Unit,
    onOpenPacks: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }

    // Resolved here, in composition — the callbacks below run inside
    // `coroutineScope.launch { }`, off the composition, where stringResource()
    // cannot be called.
    val poiSavedMsg = stringResource(R.string.map_poi_saved)
    val poiSaveFailedMsg = stringResource(R.string.map_poi_save_failed)
    val poiDeletedMsg = stringResource(R.string.map_poi_deleted)
    val poiDeleteFailedMsg = stringResource(R.string.map_poi_delete_failed)
    val poiRenamedMsg = stringResource(R.string.map_poi_renamed)
    val poiRenameFailedMsg = stringResource(R.string.map_poi_rename_failed)
    val poiRecategorizedMsg = stringResource(R.string.map_poi_recategorized)

    val providerId by container.mapPrefs.providerId.collectAsStateWithLifecycle()
    val apiKey by container.mapPrefs.apiKey.collectAsStateWithLifecycle()
    val customUrl by container.mapPrefs.customUrl.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val offlinePacks by container.offlineRegions.regions.collectAsStateWithLifecycle()
    val meetPicking by container.events.meetupPinPick.collectAsStateWithLifecycle()
    val venuePort = remember(container) { local.phantom.companion.data.VenuePageClient(baseUrl = local.phantom.companion.data.VenueClient.sceneBase(local.phantom.companion.BuildConfig.SCENE_SERVER, local.phantom.companion.BuildConfig.LICENSE_BASE_URL), signer = local.phantom.companion.data.VenueClient.deviceSigner(container.claims::prove), http = local.phantom.companion.data.VenueClient.urlConnection()) }
    androidx.compose.runtime.CompositionLocalProvider(local.phantom.companion.feature.stream.ui.map.venue.LocalVenuePort provides venuePort) {
    val selfName by produceState<String?>(null, container) { value = runCatching { container.profileStore.getActive()?.displayName }.getOrNull() }
    MapScreen(
        providerId = providerId,
        apiKey = apiKey,
        customUrl = customUrl,
        viewModel = vm,
        visible = visible,
        selfName = selfName,
        sharedPointRequests = container.events.mapPointRequest,
        onSharedPointConsumed = { container.events.mapPointRequest.value = null },
        onAskAi = { _, _, name ->
            onAskDialogue(context.getString(R.string.map_poi_ask_prompt, name))
        },
        onOpenPacks = onOpenPacks,
        onOpenExternal = { lat, lon, name ->
            val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon($name)")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            runCatching { context.startActivity(intent) }
        },
        // Мітка йде в `saved_places` — те саме сховище, що бачить чат через
        // `place.save/list/remove`. Доти було два: Vault-рядок малювався на
        // мапі й був невидимий чату, рядок чату був невидимий мапі.
        //
        meetPicking = meetPicking,
        onMeetHere = { lat, lon, label, venueId ->
            local.phantom.companion.ui.messenger.WebMessengerMeetupDraft.meetHere(lat, lon, label, venueId = venueId)
            container.events.meetupPinPick.value = false
            container.homeTabRequest.value = HomeTab.CHATS
        },
        // Брама імені стоїть у сховищі ([SavedPlaceStore.saveMark]): без імені
        // мітки не буває. Гілка відмови поки говорить загальним рядком —
        // чесний текст «дай імʼя» приходить разом з полем імені в аркуші.
        onSavePoi = { poi ->
            coroutineScope.launch {
                val result = runCatching {
                    container.savedPlaceStore.saveMark(
                        label = poi.name,
                        lat = poi.lat,
                        lon = poi.lon,
                        // Категорію тепер називає аркуш (дім/робота/своє) —
                        // приліплене тут «улюблене» ховало якорі від transit.home.
                        category = poi.category,
                        address = poi.subcategory,
                    )
                }.getOrNull()
                if (result is local.phantom.companion.core.data.places
                        .SavedPlaceStore.MarkResult.Saved
                ) {
                    onPoiConfirm(poiSavedMsg, ConfirmKind.Success)
                } else {
                    onPoiConfirm(poiSaveFailedMsg, ConfirmKind.Refusal)
                }
            }
        },
        // Картка мітки діє на той самий рядок таблиці. Ключ — імʼя: `PoiEntity.id`
        // на мапі є похідним хешем рядка і адресувати ним запис не можна.
        onDeleteSavedPlace = { poi ->
            coroutineScope.launch {
                val removed = runCatching {
                    container.savedPlaceStore.removeByLabel(poi.name)
                }.getOrDefault(false)
                if (removed) {
                    onPoiConfirm(poiDeletedMsg, ConfirmKind.Success)
                } else {
                    onPoiConfirm(poiDeleteFailedMsg, ConfirmKind.Refusal)
                }
            }
        },
        onRenameSavedPlace = { poi, newName ->
            coroutineScope.launch {
                val renamed = runCatching {
                    val existing = container.savedPlaceStore.byLabel(poi.name)
                    existing != null && container.savedPlaceStore.rename(existing.id, newName)
                }.getOrDefault(false)
                if (renamed) {
                    onPoiConfirm(poiRenamedMsg, ConfirmKind.Success)
                } else {
                    onPoiConfirm(poiRenameFailedMsg, ConfirmKind.Refusal)
                }
            }
        },
        onRecategorizeSavedPlace = { poi, category ->
            coroutineScope.launch {
                val changed = runCatching {
                    val existing = container.savedPlaceStore.byLabel(poi.name)
                    existing != null &&
                        container.savedPlaceStore.recategorize(existing.id, category)
                }.getOrDefault(false)
                if (changed) {
                    onPoiConfirm(poiRecategorizedMsg, ConfirmKind.Success)
                } else {
                    onPoiConfirm(poiRenameFailedMsg, ConfirmKind.Refusal)
                }
            }
        },
        // R4.2 — shared gaze: feed the live viewport to the AI turn context.
        // W-3 — no onViewportClosed: the gaze closes when the Continuum dies,
        // not when the operator looks up from the map.
        onViewportChanged = { snapshot -> container.mapViewport.value = snapshot },
        // MAP-4 — Save current view as offline pack.
        onSaveOfflineRegion = { bounds, styleUrl ->
            // Імʼя з землі, а не з годинника. «регіон · 03.07 12:56» називало
            // мить натискання кнопки і не називало місця: два записи поспіль
            // виглядали однаково, лежачи за сто кілометрів один від одного.
            val name = local.phantom.companion.core.geo.place.PlaceNaming.forBox(
                northLat = bounds.northLat,
                eastLon = bounds.eastLon,
                southLat = bounds.southLat,
                westLon = bounds.westLon,
            )
            coroutineScope.launch {
                runCatching {
                    container.offlineRegions.createRegion(
                        name = name,
                        bounds = bounds,
                        styleUrl = styleUrl,
                    )
                }.onFailure {
                    container.errorBus.emit(
                        source = "OfflineRegions",
                        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
                        message = container.applicationContext
                            .getString(R.string.err_region_save_failed),
                        cause = it,
                    )
                }
            }
        },
        onSaveRouteCorridor = { geometryJson, bounds, styleUrl, routeLabel, estimateBytes ->
            // Слово «коридор» тепер додає UI з поля `kind`, а в метаданих лежить
            // чиста назва землі. Доки тип сутності жив у рядку назви, він і
            // губився разом із рядком: 59 старих коридорів осідали в «КЛАПТІ».
            val name = local.phantom.companion.core.geo.place.PlaceNaming.forBox(
                northLat = bounds.northLat,
                eastLon = bounds.eastLon,
                southLat = bounds.southLat,
                westLon = bounds.westLon,
            )
            coroutineScope.launch {
                runCatching {
                    container.offlineRegions.createCorridor(
                        name = name,
                        geometryJson = geometryJson,
                        bounds = bounds,
                        styleUrl = styleUrl,
                        routeLabel = routeLabel,
                        estimateBytes = estimateBytes,
                    )
                }.onFailure {
                    container.errorBus.emit(
                        source = "OfflineRegions",
                        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
                        message = container.applicationContext
                            .getString(R.string.err_region_save_failed),
                        cause = it,
                    )
                }
            }
        },
        offlinePacks = offlinePacks,
        onRefreshOfflineRegions = {
            coroutineScope.launch { runCatching { container.offlineRegions.refresh() } }
        },
    )
    }
}
