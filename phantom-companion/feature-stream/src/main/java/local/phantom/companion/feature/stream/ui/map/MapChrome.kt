package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import local.phantom.companion.feature.stream.ui.map.around.AroundPlaces
import local.phantom.companion.feature.stream.ui.map.around.PeekPlaces
import local.phantom.companion.feature.stream.ui.map.around.peekPlaces
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.feature.stream.ui.map.peers.tapped
import local.phantom.companion.feature.stream.ui.map.dossier.dossierTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.tokens.PhantomSpace
import androidx.compose.ui.platform.LocalContext
import local.phantom.companion.core.geo.GeoMath
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.geo.MapGroundLayers
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.feature.stream.ui.map.country.countryHomePoint
import local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.source.BatchISource

/** W-9 — below this the map is north-up in every way that matters to an eye. */
private const val COMPASS_DEAD_ZONE_DEG = 1.5f

/**
 * Нижня смуга перехідних повідомлень — Notice / motion / TransitHUD / EtaStrip.
 * Вона центрована й на всю ширину, тож рейка мусить стояти НАД нею, а не поруч.
 * (Імʼя «clearance for the command pill» брехало: пігулка команд переїхала в
 * острівець, а 96dp лишились потрібні цій смузі.)
 */
private const val BOTTOM_BAND_DP = 96

/**
 * Скільки чекати на перший кадр радара, перш ніж назвати мовчання мовчанням.
 *
 * Запит до RainViewer — один короткий JSON; шість секунд лишають запас навіть
 * мобільному каналу й не змушують людину гадати довше за це.
 */
private const val RADAR_ANSWER_WAIT_MS = 6_000L

/**
 * NAV-0 — the chrome scaffold: arranges every control around the canvas
 * according to the current [SceneMode].
 *
 * ATLAS-2: сцена сюди більше не ПРИХОДИТЬ параметром, а читається з
 * `ui.sceneMode` — тієї самої, з якої Лінза рахує густоту. До цієї хвилі сцен
 * було дві: густота виводилась із діяльності оператора, а хром — зі стану
 * екрана, і на пів хвилини спокою з відкритою панеллю вони розходились рівно
 * там, де ця хвиля й важить. Одна сцена, один власник.
 *
 * Three corners, three registers, and none of them borrows another's:
 *
 * - **верх-ліво — слова.** Statements about state, and nothing else: alert →
 *   tactical fallback → position trust → guidance. One material (pill), one
 *   step (8dp). A button here reads as a sentence that fell out of a pocket.
 * - **низ-право — дієслова і прилади.** Everything a thumb presses, plus the
 *   reference nobody has to press: mount, deck, locate, one instrument plate.
 * - **низ-центр — перехідне.** Notice / motion / transit HUD / ETA strip.
 *
 * **Верх-право лишається порожнім** — nothing competes with street labels and
 * nothing is cut by the edge.
 *
 * Per scene: CALM is the alert pill and locate alone (W-8: the back arrow is
 * dead — the World is the ground, and there is no «back» from it); EXPLORE
 * adds the deck's basemap/compass/offline and lets the tools panel slide out;
 * GUIDE docks the maneuver banner into the top slot and the ETA strip below.
 */
@Composable
internal fun MapChrome(
    ui: MapUiState,
    viewModel: MapViewModel,
    selfName: String? = null,
    operatorLocation: LocationFix?,
    /** W-11 — MapScreen's one sun clock, reused. The light countdown needs a
     *  minute at most, so it rides the existing 30s tick rather than starting
     *  a second timer of its own. */
    skyClock: Long,
    /** H-02 — the last satellite check of the device clock; null = never checked. */
    clockSkew: local.phantom.companion.core.geo.sky.ClockSkew?,
    phantomLayers: List<SpatialLayer>,
    summoning: String?,
    routeInfo: MapViewModel.RouteInfo?,
    routing: Boolean,
    // NAV-2/NAV-3 — live guidance state + the flyover trigger (owned by MapScreen).
    guidance: local.phantom.companion.core.nav.GuidanceState,
    onFlyover: () -> Unit,
    evacuationState: EvacuationState,
    nearbyActive: String?,
    mapNotice: String?,
    /** ATLAS-3 — the kinematic verdict; null when no locator runs (previews). */
    motion: local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionProfile? = null,
    selectedVehicle: TransitVehicle?,
    ownOblast: String?,
    activeAlerts: List<Pair<String, AlertState>>,
    offlinePackCount: Int = 0,
    /** Вирок про позицію. Живе в тій самій колонці, що й тривога, бо це та сама
     *  смуга екрана, і два автори на неї — це перекриття, а не композиція. */
    trustFeed: kotlinx.coroutines.flow.StateFlow<
        local.phantom.companion.core.sensor.nav.LocationTrust?,
        >? = null,
    /**
     * Чи вміє ЦЕЙ виклик зберігати офлайн-територію взагалі — а не сам
     * обробник: тут його однаково ніхто не кличе (кнопка лише відкриває
     * панель, де живе справжній `onSaveOfflineRegion?.invoke`). Раніше сюди
     * приходила функція, і MapScreen загортав свій (насправді необовʼязковий)
     * параметр у лямбду, яка сама була БЕЗУМОВНО не-null — умова нижче
     * ставала тотожністю, і кнопка не могла сховатись, навіть якби виклик
     * MapScreen колись передав null (аудит map-honesty-05sep).
     */
    canSaveOfflineRegion: Boolean,
    /** Телефон у кріпленні; null прибирає орб разом із режимом (превʼю). */
    onEnterMount: (() -> Unit)? = null,
    alertsHere: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // W-11 — one clock, remembered. This was constructed inside the banner's
    // composition, which during guidance meant a fresh SimpleDateFormat on
    // every GPS fix — a date parser built sixty times a minute, forever.
    val clockFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale("uk", "UA")) }

    // NAV-Ω1 — «відлік»: the deadline rides the ViewModel so the banner
    // (leave-by preview) and the ETA strip (live slack) read one truth.
    val deadlineWatch by viewModel.deadline.collectAsStateWithLifecycle()
    val deadlineStatus by viewModel.deadlineStatus.collectAsStateWithLifecycle()

    // СИРЕНА-3 — той самий вирок, що й у шторці. Пігулка більше не має права
    // сказати «почалась 7 год тому», коли стрічка сім годин мовчить.
    //
    // Вирок знімається ТУТ, на годиннику сонця: 30 с, у композиції, тобто лише
    // поки мапа на склі. Власного тікера СИРЕНА не заводить — другий таймер на
    // те саме питання гріє телефон у кишені й нічого не додає.
    val alertSky by viewModel.sky.collectAsStateWithLifecycle()
    val whoSeesRows by viewModel.whoSeesMeRows.collectAsStateWithLifecycle()
    val skyVerdict = remember(alertSky, skyClock, alertsHere) { alertSky.verdict(skyClock).onlyIf(alertsHere) }

    val chromeContext = LocalContext.current
    var peekCollapsed by remember { mutableStateOf(NearbyPeek.read(chromeContext)) }

    // Радар, який нічого не приніс, мусить сказати це СЛОВОМ.
    //
    // Дотик по «опади» лише ставить прапорець; сам реєстр кадрів тягне
    // MapScreen із RainViewer, і без мережі він повертає порожньо — мовчки.
    // Тобто чип світився синім поверх мапи, на якій не зʼявилось нічого.
    // Один відклад на вмикання, не цикл: кадри приходять одним запитом, а
    // другий таймер на мапі — це тепло в кишені й нічого більше.
    val radarSilentLine = stringResource(R.string.map_layer_radar_silent)
    androidx.compose.runtime.LaunchedEffect(ui.radarVisible) {
        if (!ui.radarVisible) return@LaunchedEffect
        kotlinx.coroutines.delay(RADAR_ANSWER_WAIT_MS)
        if (ui.radarVisible && ui.radarFrames.isEmpty() && ui.radarOverlay == null) {
            viewModel.postNotice(radarSilentLine)
        }
    }

    // Одна причина на кожен із двох читачів нижче: badge деки і список шарів
    // мають казати про транспорт те саме, і колись це були два незалежні вирази.
    val transitActive = phantomLayers.any {
        it.spec.title?.lowercase()?.contains(BatchISource.TRANSIT_TITLE_TOKEN) == true || it.id.contains("transit")
    } || routeInfo?.profile == RouteProfile.TRANSIT
    val toolsContextLive = (ui.alertsVisible && alertsHere) || ui.radarVisible || transitActive ||
        nearbyActive != null || phantomLayers.isNotEmpty() || routeInfo != null

    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        // ── Верх-ліво: ТІЛЬКИ речення про стан. Тривога → відкат → довіра →
        // ведення. Один матеріал (пігулка), один крок (8dp), одна граматика.
        //
        // Звідси пішли двоє: орб шарів (кнопка серед тверджень — 48dp квадрат
        // між пігулками різної ширини) і прилад РЕБ (налагоджувальна
        // телеметрія серед речень, адресованих оператору). Рвана права межа
        // стосу речень — норма, як у субтитрів; рваним його робило змішання
        // жанрів, а не ширина.
        //
        // `systemBarsPadding` тут більше немає: вставки вікна вже спожив
        // `safeDrawingPadding` на корені, і другий виклик був чистим шумом.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = MapChromeMetrics.Edge, end = MapChromeMetrics.Edge, bottom = MapChromeMetrics.Edge, top = if (ui.sceneMode == SceneMode.GUIDE) MapChromeMetrics.Edge else SEARCH_BAND),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            // W-8 — the back arrow is dead. Under the Continuum there is no
            // «back» from the World: it is the ground, the deepest stratum,
            // and the arrow's «назад» pointed UP into the Dialogue — a glass
            // circle telling the operator a lie about where he was. The
            // ascent is the pull-gesture and the system back, both already
            // whole (§1.4). Chrome is guilty until proven needed.
            //
            // R4.x — the living banner: self-silencing when the sky is
            // quiet and no fix exists (honest emptiness in CALM).
            AlertBanner(
                ownOblast = ownOblast,
                ownState = ownOblast?.let { ui.alertStates[it] },
                activeCount = activeAlerts.size,
                verdict = skyVerdict,
                // СИРЕНА — the pill opens the «Небо» alarm command sheet.
                onOpen = { ui.alarmSheetOpen = true },
            )

            // Землі під виглядом немає — і це стан пристрою, а не мапи.
            GroundNotSavedBanner(
                gap = basemapGap(ui.groundNotSavedHere, ui.basemapStyleFailed),
                onSave = { ui.toolsExpanded = false; ui.offlineOpen = true },
            )

            // Той самий урок для внесеного файла mbtiles, не для кешу регіонів.
            OfflineBasemapCoverageBanner(visible = ui.offlineBasemapOutOfBounds)

            // Доки я комусь видимий — я це бачу. На склі, не в шторці: шторку
            // змахують і забувають, а це триває годину.
            val shareBanners by viewModel.shareBanners.collectAsStateWithLifecycle()
            shareBanners.forEach { banner ->
                PeerSharePill(banner = banner, nowMs = skyClock, onStop = viewModel.onStopShare)
            }

            // M-C05/C06 — degraded shelter routing / In-Place Defense always
            // surfaces at the top, independent of scene or capsule state.
            val evacDegraded = (evacuationState as? EvacuationState.ActiveGuidance)?.degraded == true ||
                evacuationState is EvacuationState.InPlaceDefense
            if (evacDegraded) {
                FallbackPlanBanner()
            }

            // Довіра до позиції — одразу під тривогою і ніколи над нею.
            // Коустинг говорить тут навіть без движка РЕБ: чесність щодо того,
            // ЩО намальовано, не має права залежати від чужого приладу.
            //
            // Три речі, з яких Лінза рахує ГУЧНІСТЬ чипа: куди дивиться сцена,
            // як іде оператор, і чи не зайнятий розгорнутий слот сиреною.
            val trustScene = ui.sceneMode
            val trustMotion = motion?.state.toMotionClass()
            val sirenLoud = ownOblast?.let { ui.alertStates[it]?.active } == true
            // Вирок про позицію піднімається сюди, бо його читає не лише чип:
            // ведення нижче не має права казати «поза маршрутом», коли ми самі
            // кажемо, що позиції не віримо. Один стан руху на екрані.
            var trustNow: local.phantom.companion.core.sensor.nav.LocationTrust? = null
            if (trustFeed != null) {
                val trust by trustFeed.collectAsStateWithLifecycle()
                trustNow = trust
                TrustChip(
                    verdict = trust,
                    coast = ui.coastBadge,
                    scene = trustScene,
                    motion = trustMotion,
                    alarmLoud = sirenLoud,
                )
            } else {
                TrustChip(
                    verdict = null,
                    coast = ui.coastBadge,
                    scene = trustScene,
                    motion = trustMotion,
                    alarmLoud = sirenLoud,
                )
            }

            // GUIDE slot — під час ведення тут лише банер маневру та кнопка повернення.
            if (ui.sceneMode == SceneMode.GUIDE) {
                val guidingNow = guidance as? local.phantom.companion.core.nav.GuidanceState.Guiding
                if (guidingNow != null) {
                    local.phantom.companion.feature.stream.ui.map.nav.ManeuverBanner(
                        progress = guidingNow.progress,
                        // Глушіння й підміна — це «я не знаю, де ти». З такого
                        // стану «поза маршрутом» не виводиться: ми не знаємо,
                        // що людина зʼїхала, ми знаємо, що не знаємо, де вона.
                        positionTrusted = trustNow !=
                            local.phantom.companion.core.sensor.nav.LocationTrust.JAMMED &&
                            trustNow !=
                            local.phantom.companion.core.sensor.nav.LocationTrust.SPOOFED,
                        onClick = { ui.maneuverListOpen = true },
                    )
                    if (!ui.followMode) {
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = PhantomSpace.s2))
                        // Та сама мова, що й чипи дій унизу: слово, lc-значок,
                        // бурштин — бо це дія, а не стан.
                        MapActionChip(
                            label = stringResource(R.string.map_recenter_driving),
                            icon = local.phantom.companion.core.design.R.drawable.lc_locate,
                            active = true,
                            onClick = { ui.followMode = true },
                        )
                    }
                }
            }
        }

        val indoorPlan = ui.indoorPlan
        if (indoorPlan != null) {
            local.phantom.companion.feature.stream.ui.map.indoor.IndoorLevelSwitcher(
                visible = true,
                availableLevels = indoorPlan.availableLevels,
                currentLevel = ui.selectedIndoorLevel,
                onLevelSelected = { lvl -> ui.selectedIndoorLevel = lvl },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = MapChromeMetrics.Edge),
            )
        }

        // Прилад РЕБ прибрано з мапи разом із параметром, що його годував:
        // моноширинний рядок «EW ±3m age=0.1s» серед речень, адресованих
        // оператору. Телеметрія лишається в локаторі — коли для неї знайдеться
        // власне місце (окремий екран приладів, а не поверх землі), це буде
        // новий виклик, а не мертвий параметр, що чекав роками.

        // ── Низ-право: дієслова і прилади, одна колона завширшки 48dp.
        //
        // Було чотири групи різної ширини: орб кріплення, ряд «рейка основи +
        // дека», орб «де я», і три окремі скла довідки. Праві межі збігалися,
        // ліві — ні, а три скла на одну роль читались як три дрібки. Тепер
        // смуга одна, і причин у ній дві: спершу те, що налаштовує погляд,
        // нижче — те, що тисне палець частіше, і аж наприкінці довідка, яка
        // нічого не вимагає.
        //
        // `navigationBarsPadding` знято: вставку вже спожив `safeDrawingPadding`
        // на корені, і другий виклик нічого не додавав.

        // ATLAS-2 — спокій стишує ДІЄСЛОВА, і тільки їх. Рейка не розчиняється
        // (вердикт QA нижче лишається в силі): вона на місці, ловить палець і
        // повертається на повний тон від першого дотику до землі. Речення вгорі
        // ліворуч не стишуються ніколи — чесність про стан не має гучності.
        val routeFailureNow by viewModel.routeFailure.collectAsStateWithLifecycle()
        val guidingActive = guidance is local.phantom.companion.core.nav.GuidanceState.Guiding ||
            guidance is local.phantom.companion.core.nav.GuidanceState.Arrived
        val hasRouteNow = routeInfo != null || routing || routeFailureNow != null
        val bottomOwner = resolveBottomOwner(ui, guidingActive, hasRouteNow)

        var lastSearchGestureMs by remember { mutableStateOf(ui.cameraGestureAtMs) }
        var lastSearchSelector by remember { mutableStateOf(HOME_CATEGORY_CHIPS.first().selector) }
        val pannedSinceSearch = nearbyActive != null && ui.cameraGestureAtMs != lastSearchGestureMs

        val aroundNow = System.currentTimeMillis()
        val peerPointsNow by viewModel.peerPoints.collectAsStateWithLifecycle()
        val me = operatorLocation?.let { it.lat to it.lon }
        val friends = remember(peerPointsNow, me) {
            local.phantom.companion.feature.stream.ui.map.around.aroundFriends(peerPointsNow, me, aroundNow)
        }
        val trayPeople = remember(friends, aroundNow, chromeContext) {
            formatTrayPeople(
                friends = friends,
                nowMs = aroundNow,
                minLabel = { chromeContext.getString(R.string.map_around_min_ago, it) },
                nowLabel = chromeContext.getString(R.string.map_around_now),
                hourLabel = { chromeContext.getString(R.string.map_around_h_ago, it) },
            )
        }
        val homeChrome = resolveHomeChrome(
            ui = ui,
            guiding = guidingActive,
            hasRoute = hasRouteNow,
            nearbyActive = nearbyActive,
            pannedSinceSearch = pannedSinceSearch,
            trayPeople = trayPeople,
        )

        val mapRotated by remember(ui) {
            derivedStateOf {
                abs(((ui.mapBearing + 180f).mod(360f)) - 180f) > COMPASS_DEAD_ZONE_DEG
            }
        }
        if (ui.sceneMode != SceneMode.GUIDE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = SEARCH_BAND, end = MapChromeMetrics.Edge),
            ) {
                MapControlOrb(
                    icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(
                        local.phantom.companion.core.design.R.drawable.lc_layers,
                    ),
                    contentDescription = stringResource(R.string.map_layers),
                    onClick = { MapBottomSurface.open(ui, MapBottomOwner.LAYERS) },
                    modifier = Modifier.testTag(TAG_MAP_LAYERS_ORB),
                )
                if (toolsContextLive) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(LocalPhantomColors.current.accent),
                    )
                }
            }
        }
        if (mapRotated && ui.sceneMode != SceneMode.GUIDE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = SEARCH_BAND + MapChromeMetrics.Control + PhantomSpace.s2, end = MapChromeMetrics.Edge),
            ) {
                MapControlOrb(
                    icon = PhantomGlyph.COMPASS.asImageVector(),
                    contentDescription = stringResource(R.string.map_compass_north),
                    onClick = {
                        ui.camera?.submit(
                            CameraIntent.BearingReset(issuedAtMs = android.os.SystemClock.uptimeMillis()),
                        )
                    },
                )
            }
        }

        if (homeChrome.searchBarVisible) {
            MapHomeTopBand(
                showSearch = !ui.searchOpen,
                onSearch = {
                    ui.mapRef?.let { viewModel.rememberTilePlaces(TilePlaceHarvest.read(it)) }
                    MapBottomSurface.open(ui, MapBottomOwner.SEARCH)
                },
                initials = selfInitialOf(selfName),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                MapHomeCategoryBar(
                    chips = homeChrome.categoryChips,
                    activeLabel = nearbyActive,
                    searchAreaVisible = homeChrome.searchAreaVisible,
                    onSelectChip = { chip, label ->
                        val here = ui.viewportCenter?.let { it.first to it.second }
                            ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                            ?: operatorLocation?.let { it.lat to it.lon }
                            ?: countryHomePoint()
                        lastSearchSelector = chip.selector
                        lastSearchGestureMs = ui.cameraGestureAtMs
                        ui.mapRef?.let { viewModel.rememberTilePlaces(TilePlaceHarvest.read(it)) }
                        viewModel.showNearby(chip.selector, label, here.first, here.second)
                        MapBottomSurface.open(ui, MapBottomOwner.HOME_PEEK)
                        peekCollapsed = false
                    },
                    onSearchArea = {
                        val label = nearbyActive ?: return@MapHomeCategoryBar
                        val here = ui.viewportCenter?.let { it.first to it.second }
                            ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                            ?: operatorLocation?.let { it.lat to it.lon }
                            ?: countryHomePoint()
                        lastSearchGestureMs = ui.cameraGestureAtMs
                        ui.mapRef?.let { viewModel.rememberTilePlaces(TilePlaceHarvest.read(it)) }
                        viewModel.showNearby(lastSearchSelector, label, here.first, here.second)
                    },
                )
            }
        }

        if (bottomOwner == MapBottomOwner.LAYERS) {
            MapLayersSheet(
                onDismiss = { ui.toolsExpanded = false },
                styles = {
                    BasemapTiles(
                        selected = ui.basemapOverride,
                        onSelect = { ui.basemapOverride = it },
                    )
                },
                tools = {
            MapToolsPanel(
                alertsOffered = alertsHere,
                whoSeesCount = whoSeesRows.count { it.precision != null },
                onOpenWhoSees = {
                    ui.toolsExpanded = false
                    ui.whoSeesOpen = true
                },
                alertsVisible = ui.alertsVisible,
                // Увімкнути шар, за яким нічого нема, — і промовчати: рівно
                // та кнопка, через яку власник написав «нічого не робить».
                // `updateAlertOverlay` ховає ВСІ пʼять шарів, коли активних
                // областей нема, тож на тихому небі дотик не міняв жодного
                // пікселя. Порожньо — це стан СТРІЧКИ, а не факт про небо,
                // і три причини мовчання звучать по-різному.
                onToggleAlerts = {
                    val turningOn = !ui.alertsVisible
                    ui.alertsVisible = turningOn
                    if (turningOn && ui.alertStates.none { it.value.active }) {
                        viewModel.postNotice(
                            chromeContext.getString(
                                when {
                                    skyVerdict == SkyVerdict.NO_COUNTRY_FEED ->
                                        R.string.map_layer_alerts_no_feed
                                    skyVerdict.feedSilent -> R.string.map_layer_alerts_silent
                                    else -> R.string.map_layer_alerts_none
                                },
                            ),
                        )
                    }
                },
                radarVisible = ui.radarVisible,
                onToggleRadar = { ui.radarVisible = !ui.radarVisible },
                transitBusy = summoning == "transit" ||
                    (routing && routeInfo?.profile == RouteProfile.TRANSIT),
                transitActive = transitActive,
                anyBusy = summoning != null || routing,
                onToggleTransit = {
                    ui.transitHubOpen = true
                },
                // Overpass world layers: same direct world-summon path as
                // транспорт (the tap is the permission grant, no LLM turn) —
                // cameras plus the sphere-sweep ground layers, one list
                // instead of a hand-maintained toggle per layer.
                worldLayers = run {
                    fun groundCenter(): Pair<Double, Double> =
                        ui.viewportCenter?.let { it.first to it.second }
                            ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                            ?: operatorLocation?.let { it.lat to it.lon }
                            ?: countryHomePoint()
                    // Справжній перемикач, три стани замість одного.
                    //
                    // Доти ОБИДВА дотики звали `summonWorld`, а
                    // `repo.push()` кладе НОВИЙ рядок із новим UUID: чип
                    // із `Role.Switch` не вмів вимкнутись жодним способом,
                    // і кожен наступний натиск клав другу копію того
                    // самого шару поверх першої. Саме це людина з апарата
                    // і бачить як «тисну — нічого не міняється».
                    //
                    // Гасимо `setVisible`, не `clear`: перемикач ховає й
                    // показує, а не знищує — інакше повернути шар коштує
                    // ще одного запиту в мережу по даних, які вже в руках.
                    // Прибирання лишається за × у рядку «активне зараз»,
                    // де воно вже й живе.
                    fun toggle(kind: String, live: List<SpatialLayer>) {
                        val shown = live.filter { it.visible }
                        when {
                            shown.isNotEmpty() -> shown.forEach { viewModel.toggleLayer(it.id, false) }
                            live.isNotEmpty() -> live.forEach { viewModel.toggleLayer(it.id, true) }
                            else -> {
                                val c = groundCenter()
                                viewModel.summonWorld(kind, c.first, c.second)
                            }
                        }
                    }
                    // `visible`, не сама наявність: шар, схований рукою в
                    // рядку нижче, світив би чип бурштином над мапою, на
                    // якій його не видно.
                    val cameraLayers = phantomLayers.filter {
                        it.spec.kind == "cameras" || it.spec.title?.lowercase()?.contains(BatchISource.CAMERAS_TITLE_TOKEN) == true
                    }
                    val towerLayers = phantomLayers.filter { it.spec.kind == MapGroundLayers.POWER_TOWERS }
                    val drainLayers = phantomLayers.filter { it.spec.kind == MapGroundLayers.DRAINAGE_CROSSINGS }
                    val fordLayers = phantomLayers.filter { it.spec.kind == MapGroundLayers.FORDS }
                    listOfNotNull(
                        if (viewModel.worldMenuEnabled) {
                            WorldLayerToggle(
                                id = "cameras",
                                label = stringResource(R.string.map_layer_cameras),
                                busy = summoning == "cameras",
                                active = cameraLayers.any { it.visible },
                                onClick = { toggle("cameras", cameraLayers) },
                            )
                        } else null,
                        WorldLayerToggle(
                            id = MapGroundLayers.POWER_TOWERS,
                            label = stringResource(R.string.map_layer_power_towers),
                            busy = summoning == MapGroundLayers.POWER_TOWERS,
                            active = towerLayers.any { it.visible },
                            onClick = { toggle(MapGroundLayers.POWER_TOWERS, towerLayers) },
                        ),
                        WorldLayerToggle(
                            id = MapGroundLayers.DRAINAGE_CROSSINGS,
                            label = stringResource(R.string.map_layer_drainage),
                            busy = summoning == MapGroundLayers.DRAINAGE_CROSSINGS,
                            active = drainLayers.any { it.visible },
                            onClick = { toggle(MapGroundLayers.DRAINAGE_CROSSINGS, drainLayers) },
                        ),
                        WorldLayerToggle(
                            id = MapGroundLayers.FORDS,
                            label = stringResource(R.string.map_layer_fords),
                            busy = summoning == MapGroundLayers.FORDS,
                            active = fordLayers.any { it.visible },
                            onClick = { toggle(MapGroundLayers.FORDS, fordLayers) },
                        ),
                    )
                },
                nearbyActive = nearbyActive,
                // NAV-Ω1 — with a live route the same chips search «по
                // дорозі» (along the corridor, first-reachable first);
                // without one they stay the radial «поруч».
                alongRoute = routeInfo != null,
                onPickNearby = { selector, label ->
                    if (routeInfo != null) {
                        viewModel.showNearbyAlongRoute(selector, label)
                    } else {
                        val here = ui.viewportCenter?.let { it.first to it.second }
                            ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                            ?: operatorLocation?.let { it.lat to it.lon }
                            ?: countryHomePoint()
                        viewModel.showNearby(selector, label, here.first, here.second)
                    }
                },
                onClearNearby = { viewModel.clearNearby() },
                phantomLayers = phantomLayers,
                routeInfo = routeInfo,
                onClearRoute = { viewModel.clearEvacuation() },
                evacuationState = evacuationState,
                operatorLocation = operatorLocation?.let { it.lat to it.lon },
                onToggleLayer = { id, visible -> viewModel.toggleLayer(id, visible) },
                onClearLayer = { id -> viewModel.clearLayer(id) },
                onFocusLayer = { layer ->
                    val c = layerCentroid(layer)
                    if (c != null) {
                        ui.camera?.submit(
                            CameraIntent.Focus(
                                target = GeoPoint(c.first, c.second),
                                zoom = 14.0,
                                durationMs = 600,
                                issuedAtMs = android.os.SystemClock.uptimeMillis(),
                            ),
                        )
                    }
                },
            )
                },
                onOffline = if (canSaveOfflineRegion) {
                    { ui.toolsExpanded = false; ui.offlineOpen = true }
                } else {
                    null
                },
                offlineCount = offlinePackCount,
                onCar = onEnterMount?.let { enter -> { ui.toolsExpanded = false; enter() } },
            )
        }

        // ── Bottom cluster: notice, transit HUD, command pill.
        // Нижній край мапи ділиться з навігаційним острівцем, який малюється
        // ПОВЕРХ усього NavHost і тому невидимий для цього кластера. Острівець
        // публікує свою висоту через LocalBottomChromeInset — читаємо її, інакше
        // «ЧАТ / МАПА / VAULT» лягають просто на рядок пошуку (звіт із пристрою).
        val islandReserve = LocalBottomChromeInset.current
        val peekShown = bottomOwner == MapBottomOwner.HOME_PEEK && ui.drawPick == null
        var peekBand by remember { mutableStateOf(PEEK_BAND) }
        val peekDensity = LocalDensity.current
        val chipHits by viewModel.pois.collectAsStateWithLifecycle()
        val chipLoading by viewModel.loading.collectAsStateWithLifecycle()
        val aroundState by viewModel.aroundPlaces.collectAsStateWithLifecycle()
        val peekAnchor = me ?: ui.viewportCenter?.let { it.first to it.second }
        if (peekShown && peekAnchor != null) {
            androidx.compose.runtime.LaunchedEffect(peekAnchor) { viewModel.loadAround(peekAnchor.first, peekAnchor.second) }
        }
        val peekState = remember(nearbyActive, chipHits, chipLoading, aroundState, peekAnchor) {
            peekPlaces(
                chipActive = nearbyActive != null,
                chipHits = chipHits,
                chipLoading = chipLoading,
                around = aroundState,
                anchor = peekAnchor,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(
                    start = MapChromeMetrics.Edge,
                    end = MapChromeMetrics.Edge,
                    bottom = PhantomSpace.s4 + islandReserve + if (peekShown) peekBand else 0.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // «Звідси маршрут» — початок обрано, кінця ще нема: стан, не подія,
            // тож він стоїть, доки палець не назве кінець або не скасує.
            ui.routeOriginPick?.let { pick ->
                RouteOriginPill(
                    label = pick.label ?: "%.5f, %.5f".format(pick.lat, pick.lon),
                    onCancel = { ui.routeOriginPick = null },
                )
            }

            // Той самий клас стану: мапа чекає дотику й мусить це сказати.
            ui.anchorPick?.takeIf { it.placing }?.let { pick ->
                AnchorPickPill(
                    label = pick.label,
                    onCancel = { ui.anchorPick = null },
                )
            }

            // Малювання — теж режим: скільки вже намальовано і як вийти.
            ui.drawPick?.let { draw ->
                val drawContext = LocalContext.current
                val held = draw.selectedIndex
                DrawPill(
                    vertexCount = draw.points.size,
                    // Довжина рахується з вершин на кожен кадр, а не зберігається:
                    // після зсуву вершини кешоване число брехало б.
                    lengthLabel = draw.points
                        .takeIf { it.size >= 2 }
                        ?.let { fmtDistance(drawContext.resources, GeoMath.pathLengthM(it)) },
                    heldVertex = held != null,
                    onUndo = {
                        ui.drawPick = draw.copy(points = draw.points.dropLast(1), selected = null)
                    },
                    onRemoveHeld = {
                        if (held != null) {
                            ui.drawPick = draw.copy(
                                points = draw.points.filterIndexed { i, _ -> i != held },
                                selected = null,
                            )
                        }
                    },
                    onDone = {
                        if (draw.points.size < 2) {
                            // Відмова словом: мовчазне «нічого не сталось» —
                            // саме те, від чого людина перестає вірити кнопці.
                            viewModel.postNotice(
                                drawContext.getString(R.string.map_draw_too_few),
                            )
                        } else if (draw.editingId != null) {
                            // Правлять уже названу лінію — імені не питаємо вдруге.
                            viewModel.updateDrawnLine(draw.editingId, draw.points)
                            ui.drawPick = null
                        } else {
                            ui.drawNaming = draw.points
                            ui.drawPick = null
                        }
                    },
                    onCancel = { ui.drawPick = null },
                )
            }

            NoticePill(mapNotice)

            // ATLAS-3 — the kinematic pill: only when the classifier is sure
            // enough to be worth a pixel, and never for standing still (the
            // absence of a pill IS the STILL state). TRAIN says «ймовірно»
            // while its confidence sits under the ambiguity ceiling — the
            // classifier's honesty is the operator's, verbatim.
            val motionLabel = motion?.let { m ->
                if (m.confidence < 0.55f) null else when (m.state) {
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.WALK ->
                        R.string.map_motion_walk
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.RUN ->
                        R.string.map_motion_run
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.CYCLE ->
                        R.string.map_motion_cycle
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.DRIVE ->
                        R.string.map_motion_drive
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.TRAIN ->
                        if (m.confidence < 0.7f) R.string.map_motion_train_maybe else R.string.map_motion_train
                    local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.FLIGHT ->
                        R.string.map_motion_flight
                    else -> null
                }
            }
            // ATLAS-2 — вхід у режим, що ЗАБИРАЄ з екрана, говорить у ТОМУ
            // САМОМУ слоті й на чотири секунди ЗАМІСТЬ пілюлі ходи, а не поруч
            // із нею: «авто» і «режим: за кермом» одне під одним — це два
            // написи про одну новину, тобто рівно той хром, який ця хвиля
            // прибирає. Далі слот повертається пілюлі, бо хода — стан, а режим
            // сказано один раз.
            val modeNotice = rememberModeAnnouncement(ui.sceneMode)
            NoticePill(modeNotice ?: motionLabel?.let { stringResource(it) })

            val vehicle = selectedVehicle
            if (vehicle != null) {
                TransitVehicleCard(
                    vehicle = vehicle,
                    surfaceFeed = { viewModel.surfaceFeed },
                    onShowLine = { viewModel.showLocalTransitLine(it) },
                    onDismiss = { viewModel.selectVehicle(null) },
                )
            }

            // NAV-2 — while guiding, the bottom slot belongs to the ETA
            // strip: the command pill steps aside for the journey.
            val guiding = guidance as? local.phantom.companion.core.nav.GuidanceState.Guiding
            val routeFailure by viewModel.routeFailure.collectAsStateWithLifecycle()
            when {
                guiding != null ->
                    local.phantom.companion.feature.stream.ui.map.nav.EtaStrip(
                        progress = guiding.progress,
                        // NAV-Ω1 — live deadline slack rides the strip.
                        deadline = deadlineStatus,
                        onStop = { viewModel.guidance.stop() },
                        // ux-map-design.md §2.3/§5.2 — the route-summary chip.
                        worstRegister = ui.routeWorstRegister,
                    )

                guidance is local.phantom.companion.core.nav.GuidanceState.Arrived ->
                    local.phantom.companion.feature.stream.ui.map.nav.ArrivedBanner(
                        onDone = { viewModel.clearRoute() },
                    )

                // Смуга маршруту стоїть рівно там, де потім стане стрічка ETA:
                // після «старту» відповідь не телепортується з верху екрана вниз.
                routeInfo != null || routing || routeFailure != null -> {
                    // Під час евакуації маршрутом уже керує EvacuationCard
                    // (MapToolsCapsule) — вона знає стан рушія евакуації, якого
                    // тут нема. Без цієї умови «СТАРТ» малювався поверх
                    // опублікованого евакуаційного маршруту й тиснув на
                    // guidance.start(), який мовчки нічого не робив: маршрут
                    // прийшов в обхід планувальника (LEVIATHAN-1
                    // publishExternalRoute), а guidance.start() читає САМЕ його.
                    if (evacuationState != EvacuationState.Idle) return@Column
                    val cmdOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
                    val cmdSelected by viewModel.routeSelectedIndex.collectAsStateWithLifecycle()
                    val transitDepart by viewModel.transitDepartSec.collectAsStateWithLifecycle()
                    val routeTargets by viewModel.shareTargets.collectAsStateWithLifecycle()
                    val routeHandoff by viewModel.routeHandoff.collectAsStateWithLifecycle()
                    MapCommandSheet(
                        info = routeInfo,
                        busy = routing,
                        failure = routeFailure,
                        options = cmdOptions,
                        selectedIndex = cmdSelected,
                        waypoints = viewModel.waypoints,
                        onRemoveVia = { viewModel.removeViaPoint(it) },
                        onSetDeadline = { viewModel.setDeadlineTo(it) },
                        onClearDeadline = { viewModel.clearDeadline() },
                        transitDepartSec = transitDepart,
                        onTransitDepart = { viewModel.setTransitDeparture(it) },
                        // NAV-Ω1 — the preview face of the deadline: when
                        // to leave to make it (honest null when missed).
                        deadlineLine = deadlineWatch?.let { w ->
                            val leaveBy = routeInfo?.let { w.leaveByEpochMs(it.durationSeconds) }
                            if (leaveBy != null && leaveBy > System.currentTimeMillis()) {
                                stringResource(
                                    R.string.map_deadline_leave_by,
                                    clockFmt.format(java.util.Date(leaveBy)),
                                )
                            } else {
                                stringResource(
                                    R.string.map_deadline_missed,
                                    clockFmt.format(java.util.Date(w.deadlineEpochMs)),
                                )
                            }
                        },
                        worstRegister = ui.routeWorstRegister,
                        cameraGestureAtMs = ui.cameraGestureAtMs,
                        onOpenManeuverList = { ui.maneuverListOpen = true },
                        shareTargets = routeTargets,
                        onShareRoute = { peerId -> viewModel.shareRoute(peerId) },
                        // Результат дотику доходить до скла тим самим кадром,
                        // що й дія: інакше «надіслати» — це тиша.
                        shareHandoff = routeHandoff,
                        onShareHandoffSeen = { viewModel.consumeRouteHandoff() },
                        onSelectVariant = { viewModel.selectRouteAlternative(it) },
                        onProfile = { p ->
                            val from = operatorLocation?.let { it.lat to it.lon }
                                ?: ui.mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
                            if (from != null) {
                                viewModel.reRouteProfile(from.first, from.second, p)
                            } else {
                                // Без початку перерахунку не буде — і доти це
                                // була четверта мовчазна кнопка: сегмент режиму
                                // підсвічувався пальцем, а маршрут лишався той
                                // самий, без жодного слова про причину.
                                viewModel.postNotice(
                                    chromeContext.getString(R.string.map_route_no_origin),
                                )
                            }
                        },
                        onClear = { viewModel.clearRoute() },
                        onStartGuidance = { viewModel.guidance.start() },
                        onFlyover = onFlyover,
                    )
                }

                else -> {
                    val locationGate = local.phantom.companion.feature.stream.ui.people.rememberMapLocationGate()
                    MapLocateFab(
                        permissionGranted = locationGate.granted(),
                        followActive = ui.followMode,
                        onClick = {
                            if (!locationGate.granted()) {
                                locationGate.requestThen { ui.followMode = true }
                            } else if (!locationGate.servicesOn()) {
                                locationGate.promptServices()
                            } else if (operatorLocation == null) {
                                viewModel.postNotice(chromeContext.getString(R.string.map_locate_no_fix))
                            } else {
                                ui.followMode = !ui.followMode
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        if (peekShown) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { peekBand = with(peekDensity) { it.height.toDp() } },
            ) {
                MapHomePeekBar(
                    sharing = friends.size,
                    activeCategory = nearbyActive,
                    people = homeChrome.trayPeople,
                    onOpenAround = { MapBottomSurface.open(ui, MapBottomOwner.PEOPLE) },
                    onPersonClick = { person ->
                        val match = friends.firstOrNull { it.point.nodeId == person.id }
                        if (match != null) {
                            ui.followMode = false
                            ui.selectedPeer = match.point.tapped(aroundNow)
                            ui.camera?.submit(peopleFit(listOf(match.point.lat to match.point.lon)))
                        } else {
                            MapBottomSurface.open(ui, MapBottomOwner.PEOPLE)
                        }
                    },
                    onPlansClick = { MapBottomSurface.open(ui, MapBottomOwner.PEOPLE) },
                    onMeetClick = {
                        MapBottomSurface.open(ui, MapBottomOwner.PEOPLE)
                        ui.aroundOpen = false
                        ui.meetPickOpen = true
                    },
                    places = peekState,
                    onPlace = { p -> ui.dossierTarget = p.dossierTarget() },
                    collapsed = peekCollapsed,
                    onCollapsedChange = { peekCollapsed = it; NearbyPeek.write(chromeContext, it) },
                )
            }
        }
        if (bottomOwner == MapBottomOwner.PEOPLE && ui.aroundOpen) {
            val places by viewModel.aroundPlaces.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(me) {
                me?.let { viewModel.loadAround(it.first, it.second) }
            }
            val listed = (peekState as? PeekPlaces.Shown)
                ?.takeIf { nearbyActive != null }
                ?.let { shown -> AroundPlaces.Shown(shown.rows.map { it.place }, venues = false) }
            MapSheet(
                onDismissRequest = { ui.aroundOpen = false },
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
            ) {
                local.phantom.companion.feature.stream.ui.map.around.AroundYouContent(
                    friends = friends,
                    places = listed ?: places,
                    me = me,
                    nowMs = aroundNow,
                    onFriend = { f ->
                        ui.aroundOpen = false
                        ui.followMode = false
                        ui.selectedPeer = f.point.tapped(aroundNow)
                        ui.camera?.submit(peopleFit(listOf(f.point.lat to f.point.lon)))
                    },
                    onPlace = { p ->
                        ui.aroundOpen = false
                        ui.dossierTarget = p.dossierTarget()
                    },
                )
            }
        }
        if (homeChrome.instrumentPlateVisible) {
            MapInstrumentPlate(
                metersPerDp = ui.viewportCenter?.let { metersPerDp(it.first, it.third) },
                composition = ui.registerComposition,
                operatorLocation = operatorLocation,
                nowMs = skyClock,
                clockSkew = clockSkew,
                mapRotated = mapRotated,
                compact = !ui.toolsExpanded,
            )
        }
    }
}

internal const val TAG_MAP_HOME_CHIPS = "map-home-chips"
internal const val TAG_MAP_LOCATE_FAB = "map-locate-fab"
internal const val TAG_MAP_PEOPLE_TRAY = "map-people-tray"
internal const val TAG_MAP_SEARCH_AREA_CHIP = "map-search-area-chip"
internal const val TAG_MAP_LAYERS_ORB = "map-layers-orb"

internal enum class MapBottomOwner {
    HOME_PEEK,
    SEARCH,
    PLACE,
    ROUTE,
    GUIDANCE,
    PEOPLE,
    LAYERS,
}

internal fun resolveBottomOwner(
    ui: MapUiState,
    guiding: Boolean,
    hasRoute: Boolean,
): MapBottomOwner = when {
    guiding -> MapBottomOwner.GUIDANCE
    ui.searchOpen -> MapBottomOwner.SEARCH
    ui.toolsExpanded -> MapBottomOwner.LAYERS
    ui.dossierTarget != null ||
        ui.selectedSavedPlace != null ||
        ui.droppedPoint != null ||
        ui.selectedLayerMarker != null ||
        ui.selectedLine != null -> MapBottomOwner.PLACE
    ui.aroundOpen ||
        ui.whoSeesOpen ||
        ui.selectedPeer != null ||
        ui.peerGroup != null ||
        ui.meetOpen ||
        ui.meetPickOpen ||
        ui.myWorldOpen -> MapBottomOwner.PEOPLE
    hasRoute -> MapBottomOwner.ROUTE
    else -> MapBottomOwner.HOME_PEEK
}

internal data class MapHomeCategoryChip(
    val id: String,
    val labelRes: Int,
    val selector: String,
)

internal val HOME_CATEGORY_CHIPS: List<MapHomeCategoryChip> = listOf(
    MapHomeCategoryChip("coffee", R.string.map_chip_coffee, "amenity=cafe"),
    MapHomeCategoryChip("food", R.string.map_chip_food, "amenity=restaurant"),
    MapHomeCategoryChip("tonight", R.string.map_chip_tonight, "amenity=bar"),
    MapHomeCategoryChip("groceries", R.string.map_chip_groceries, "shop=supermarket"),
    MapHomeCategoryChip("pharmacy", R.string.map_chip_pharmacy, "amenity=pharmacy"),
    MapHomeCategoryChip("transit", R.string.map_chip_transit, "public_transport=platform"),
)

internal data class MapTrayPerson(
    val id: String,
    val name: String,
    val status: String,
    val fresh: Boolean,
    val face: String? = null,
)

internal fun formatTrayPerson(
    id: String,
    name: String,
    meters: Double?,
    atMs: Long,
    fresh: Boolean,
    nowMs: Long,
    minLabel: (Int) -> String = { "${it}m" },
    nowLabel: String = "now",
    hourLabel: (Int) -> String = { "${it}h" },
    face: String? = null,
): MapTrayPerson {
    val status = if (fresh && meters != null) {
        val walkMinutes = maxOf(1, Math.round(meters / 80.0).toInt())
        minLabel(walkMinutes)
    } else {
        val ageMinutes = ((nowMs - atMs).coerceAtLeast(0L)) / 60_000L
        when {
            ageMinutes < 1 -> nowLabel
            ageMinutes < 60 -> minLabel(ageMinutes.toInt())
            else -> hourLabel((ageMinutes / 60).toInt())
        }
    }
    return MapTrayPerson(
        id = id,
        name = name,
        status = status,
        fresh = fresh,
        face = face,
    )
}

internal fun formatTrayPeople(
    friends: List<local.phantom.companion.feature.stream.ui.map.around.AroundFriend>,
    nowMs: Long,
    minLabel: (Int) -> String,
    nowLabel: String,
    hourLabel: (Int) -> String,
): List<MapTrayPerson> = friends.map { f ->
    formatTrayPerson(
        id = f.point.nodeId,
        name = f.point.name,
        meters = f.meters,
        atMs = f.point.atMs,
        fresh = f.fresh,
        nowMs = nowMs,
        minLabel = minLabel,
        nowLabel = nowLabel,
        hourLabel = hourLabel,
        face = f.point.face,
    )
}

internal data class MapHomeChromeModel(
    val bottomOwner: MapBottomOwner,
    val searchBarVisible: Boolean,
    val instrumentPlateVisible: Boolean,
    val categoryChips: List<MapHomeCategoryChip>,
    val searchAreaVisible: Boolean,
    val trayPeople: List<MapTrayPerson>,
    val mountedTags: Set<String>,
)

internal fun resolveHomeChrome(
    ui: MapUiState,
    guiding: Boolean,
    hasRoute: Boolean,
    nearbyActive: String?,
    pannedSinceSearch: Boolean,
    trayPeople: List<MapTrayPerson>,
): MapHomeChromeModel {
    val owner = resolveBottomOwner(ui, guiding, hasRoute)
    val searchVisible = ui.sceneMode != SceneMode.GUIDE && owner != MapBottomOwner.SEARCH
    val searchArea = searchVisible && nearbyActive != null && pannedSinceSearch
    val tags = buildSet {
        if (ui.sceneMode != SceneMode.GUIDE) add(TAG_MAP_LAYERS_ORB)
        if (searchVisible) add(TAG_MAP_HOME_CHIPS)
        if (searchArea) add(TAG_MAP_SEARCH_AREA_CHIP)
        if (owner == MapBottomOwner.HOME_PEEK && ui.drawPick == null) {
            add(TAG_MAP_LOCATE_FAB)
            add(TAG_MAP_PEOPLE_TRAY)
        }
    }
    return MapHomeChromeModel(
        bottomOwner = owner,
        searchBarVisible = searchVisible,
        instrumentPlateVisible = false,
        categoryChips = HOME_CATEGORY_CHIPS,
        searchAreaVisible = searchArea,
        trayPeople = trayPeople,
        mountedTags = tags,
    )
}

private val SEARCH_BAND = 112.dp
private val PEEK_BAND = 104.dp

/** Everyone sharing a place with me, plus me, in one frame. */
private fun peopleFit(points: List<Pair<Double, Double>>): CameraIntent {
    if (points.size == 1) {
        return CameraIntent.Focus(
            target = GeoPoint(points[0].first, points[0].second),
            zoom = 15.0,
            issuedAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }
    val pad = 0.002
    return CameraIntent.FitBounds(
        bounds = local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds(
            southLat = points.minOf { it.first } - pad,
            westLon = points.minOf { it.second } - pad,
            northLat = points.maxOf { it.first } + pad,
            eastLon = points.maxOf { it.second } + pad,
        ),
        padding = local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets(120, 260, 120, 420),
        priority = local.phantom.companion.feature.stream.ui.map.atlas.CameraPriority.OPERATOR,
        issuedAtMs = android.os.SystemClock.uptimeMillis(),
    )
}
