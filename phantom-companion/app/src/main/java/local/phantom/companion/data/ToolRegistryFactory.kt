package local.phantom.companion.data

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import local.phantom.companion.core.ai.route.RetryingAIRouter
import local.phantom.companion.core.ai.withResumePrefix
import local.phantom.companion.core.resilience.RetryPolicy
import local.phantom.companion.core.data.PhantomDatabase
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.data.lifelog.LifeLogArchive
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.routingBaseFor
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.route.RoutedTts
import local.phantom.companion.feature.godmode.engine.GodModeEventBus
import local.phantom.companion.feature.godmode.engine.TaskerEngine
import local.phantom.companion.feature.stream.ui.map.verdict
import local.phantom.companion.net.PhantomApi
import local.phantom.companion.net.PhantomLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import local.phantom.companion.core.bus.PhantomEvent
import local.phantom.companion.core.bus.PhantomNervousBus
import local.phantom.companion.core.predict.proactive.ProactiveEngine
import local.phantom.companion.core.predict.proactive.ProactiveFrameSource
import local.phantom.companion.core.predict.proactive.ProactiveLlmReasoner
import local.phantom.companion.core.predict.proactive.ProactiveUtteranceSink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import local.phantom.companion.core.tool.ToolRegistry
import androidx.compose.ui.graphics.toArgb


/**
 * Every tool the model can call, and the wiring each one needs.
 *
 * It is a list, not a graph: the only reason it lived inside [AppContainer]
 * was that it reads a dozen of the container's other members. As an extension
 * it still does, without four hundred lines of registration sitting between
 * the database and the sensor stack.
 */
internal fun AppContainer.buildToolRegistry(): ToolRegistry {
    val httpForTools = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
    // R5 — one launcher for the whole phone.* family (tools add NEW_TASK).
    val phoneIntentLauncher: (android.content.Intent) -> Unit =
        { intent -> applicationContext.startActivity(intent) }
    // R1.4 + R4.5 — shared GeoHttp (User-Agent + Nominatim throttle) for
    // both the geo stack and the R4 world-layer tools (transit/cameras/aircraft).
    val geoHttp = local.phantom.companion.core.tool.impl.GeoHttp(httpForTools)
    // Слова зустрічі й рахунку — мовою месенджера (Профіль › Мова), а не системи.
    val meetupWords = MeetupAssistantWords.replyLanguage { applicationContext }
    val tools = devOnlyTools(local.phantom.companion.BuildConfig.DEBUG) { termuxInstalled } + listOf<local.phantom.companion.core.tool.Tool>(
        local.phantom.companion.core.tool.impl.HttpTool(
            httpClient = httpForTools,
            hostAllowlist = toolHostAllowlist,
        ),
        local.phantom.companion.core.tool.impl.FileTool(
            sandboxProvider = {
                java.io.File(applicationContext.filesDir, "phantom/agent").apply { mkdirs() }
            },
        ),
        // Документ ЛЮДИНИ — не пісочниця агента. Читати те, що вона дала
        // з телефона, ФАНТОМ не міг жодним дієсловом.
        local.phantom.companion.core.tool.impl.DocumentTool(),
        // Світлина ЛЮДИНИ. Рушій розпізнавання стояв у дереві весь час,
        // але жив лише на «Лінзі»: сфотографований документ не читало
        // жодне дієслово. Витяг, не опис — моделі, яка бачить, тут немає.
        local.phantom.companion.core.tool.impl.ImageTool(),
        local.phantom.companion.core.tool.impl.SettingsTool(applicationContext),
        // R5.1 — launch resolves human names against the same installed-app
        // catalogue the [APPS:] header uses; apps.list serves the full
        // list on demand (the header caps at 50).
        local.phantom.companion.core.tool.impl.LaunchTool(
            applicationContext,
            listApps = installedAppPairs,
        ),
        local.phantom.companion.core.tool.impl.AppsListTool(
            listApps = installedAppPairs,
        ),
        local.phantom.companion.core.tool.impl.NotificationTool(
            applicationContext,
            smallIconRes = local.phantom.companion.service.NotificationLook.smallIcon,
            accentColor = local.phantom.companion.service.NotificationLook.accent,
        ),
        local.phantom.companion.core.tool.impl.MediaTool(applicationContext),
        // R5.1 — the accessibility tool now runs the WHOLE sequence
        // through ONE awaited dispatch and reports the truth (all done /
        // Accessibility off / denied / operator intervened / step
        // failed) instead of the old per-action fire-and-forget that
        // always claimed success. AppContainer is the adapter between
        // the godmode outcome and the core-symbiote outcome type.
        local.phantom.companion.core.tool.impl.AccessibilityTool(
            parseActions = { jsonArray ->
                local.phantom.companion.feature.godmode.engine.AppActionParser
                    .parseArray(jsonArray)
            },
            dispatchSequence = { actions, onStep ->
                @Suppress("UNCHECKED_CAST")
                val typed = actions
                    .filterIsInstance<local.phantom.companion.feature.godmode.engine.AppAction>()
                val outcome = local.phantom.companion.feature.godmode.service
                    .AccessibilityServiceActionRegistry
                    .dispatchAwait(
                        widgetId = java.util.UUID.randomUUID().toString(),
                        actions = typed,
                        onStep = onStep,
                    )
                val stop = outcome.stop
                local.phantom.companion.core.tool.impl.AccessibilitySequenceOutcome(
                    completedSteps = outcome.completedSteps,
                    totalSteps = outcome.totalSteps,
                    failure = stop?.let {
                        val kind = when (it.reasonKey) {
                            "service-unbound" ->
                                local.phantom.companion.core.tool.impl
                                    .AccessibilitySequenceOutcome.KIND_SERVICE_UNBOUND
                            local.phantom.companion.feature.godmode.engine
                                .AccessibilityActionDispatcher.REASON_OPERATOR_INTERVENED ->
                                local.phantom.companion.core.tool.impl
                                    .AccessibilitySequenceOutcome.KIND_OPERATOR_INTERVENED
                            local.phantom.companion.feature.godmode.engine
                                .AccessibilityActionDispatcher.REASON_STEP_FAILED ->
                                local.phantom.companion.core.tool.impl
                                    .AccessibilitySequenceOutcome.KIND_STEP_FAILED
                            else -> it.reasonKey // vault-locked / sensitive-app → generic denied
                        }
                        local.phantom.companion.core.tool.impl
                            .AccessibilitySequenceOutcome.Failure(kind, it.detail)
                    },
                )
            },
        ),
        // R5.1 — the model's eyes: read the foreground UI between actions.
        local.phantom.companion.core.tool.impl.ScreenReadTool(
            snapshot = {
                local.phantom.companion.feature.godmode.service
                    .AccessibilityServiceActionRegistry
                    .snapshotUi()?.render()
            },
        ),
        // R5.1 — deliberate pacing for long sequential missions.
        local.phantom.companion.core.tool.impl.WaitTool(),
        local.phantom.companion.core.tool.impl.MentalParkTool(
            parkAdd = { text, anchor, category ->
                val tags = setOfNotNull(anchor, category)
                mentalPark.add(text = text, tags = tags).id
            },
        ),
        local.phantom.companion.core.tool.impl.WidgetTool(
            emitWidget = { type, payload ->
                val id = java.util.UUID.randomUUID().toString()
                toolWidgetEmissions.tryEmit(Triple(id, type, payload))
                id
            },
        ),
        // R1.1 — real senses: the AI can finally know where the operator is.
        local.phantom.companion.core.tool.impl.LocationTool(
            snapshotProvider = locationSnapshotProvider,
            reverseGeocode = { lat, lon -> reverseGeocoder.coarseAddress(lat, lon) },
        ),
        // R1.2 — real weather in front of the model, not hallucinated
        // into the widget: weather.now + weather.forecast (Open-Meteo).
        local.phantom.companion.core.tool.impl.WeatherNowTool(
            openMeteo = openMeteoClient,
            locationProvider = locationProvider,
        ),
        local.phantom.companion.core.tool.impl.WeatherForecastTool(
            openMeteo = openMeteoClient,
            locationProvider = locationProvider,
        ),
        // Own web search (DDG Lite, keyless) — replaces Gemini's
        // Google-Search grounding, whose separate free-tier quota
        // being exhausted 429'd EVERY request (2026-07-03). The
        // model now calls this only when it needs fresh web data.
        local.phantom.companion.core.tool.impl.WebSearchTool(
            httpClient = httpForTools,
        ),
        // R6 intelligence — research.deep: decompose → multi-search →
        // READ the top pages → synthesize with real-URL-only sources.
        // The depth web.search's one snippet page can't give.
        local.phantom.companion.core.tool.impl.DeepResearchTool(
            httpClient = httpForTools,
            complete = singleTurnComplete,
        ),
        // R4.1 — the spatial spine: the model pushes NAMED, persistent
        // layers onto the map (grouped "від Фантома"), focuses the
        // camera, and clears them. Thread-scoped via activeThreadRef.
        local.phantom.companion.core.tool.impl.MapShowTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.MapFocusTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.MapClearTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
        ),
        // ATLAS-5 — map.zone: tactical judgement as a first-class layer
        // (exclusion zones, safe corridors, watch areas), TTL'd because a
        // stale verdict is worse than none.
        local.phantom.companion.core.tool.impl.MapZoneTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
        ),
        // LEVIATHAN-5 — map.analyze: PHANTOM reads his own instruments
        // (EW trust, kinematics, live drive, layers, nearest shelter) and
        // speaks one honest tactical brief. Every field is process truth.
        local.phantom.companion.core.tool.impl.MapAnalyzeTool(
            snapshot = { assembleTacticalSnapshot() },
        ),
        // M-I01 — map agency: the operator's Wave-1 gestures (long-press
        // point, OSRM route, "поруч" chips) as tool-verbs, pushed onto the
        // SAME live map via spatialLayerRepository (not a chat widget),
        // and — since M-I01 fix — actually bringing the map on screen via
        // mapOpenRequests instead of writing a layer nobody looks at.
        local.phantom.companion.core.tool.impl.MapPointTool(
            http = geoHttp,
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        local.phantom.companion.core.tool.impl.MapRouteLiveTool(
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            http = geoHttp,
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
            offlineRouteCalculator = { profile, waypoints -> offlineMeshRoute(profile, waypoints) },
        ),
        local.phantom.companion.core.tool.impl.MapNearbyTool(
            poiRepository = poiRepository,
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        local.phantom.companion.core.tool.impl.MapNearestShelterTool(
            poiRepository = poiRepository,
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // F-02 + F-08 (spec-medevac-shelter §1.3, §2) — «найближча лікарня»
        // answered by ROAD, never by great-circle, with the radio handover
        // card (MGRS / Plus Code / settlement+bearing) generated unasked on
        // the same path. The POI lookup is handed in as a lambda so the tool
        // carries no store of its own and stays unit-testable offline.
        local.phantom.companion.core.tool.impl.MedevacNearestTool(
            findPois = { lat, lon, radiusM, selector ->
                poiRepository.getNearby(lat, lon, radiusM, selector).map {
                    local.phantom.companion.core.tool.impl.MedevacPoi(it.name, it.lat, it.lon, it.tags)
                }
            },
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        local.phantom.companion.core.tool.impl.HandoverCardTool(
            findPois = { lat, lon, radiusM, selector ->
                poiRepository.getNearby(lat, lon, radiusM, selector).map {
                    local.phantom.companion.core.tool.impl.MedevacPoi(it.name, it.lat, it.lon, it.tags)
                }
            },
            locationProvider = locationProvider,
        ),
        // NAV-1 — route depth: OSRM alternatives + RouteIntel corridor
        // analysis (roads/lighting/cameras/ascent), drawn as one layer.
        local.phantom.companion.core.tool.impl.RouteCompareTool(
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        local.phantom.companion.core.tool.impl.ThreatBriefTool(
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
            requestThreatBrief = { threatBriefRequests.tryEmit(it) },
        ),
        // NAV-2 — live guidance verbs: the same GuidanceController path
        // the operator's «старт» tap drives (Law 2 — dual control).
        local.phantom.companion.core.tool.impl.NavStartTool(
            requestNavStart = { toLat, toLon, profile, via ->
                navCommands.tryEmit(
                    local.phantom.companion.core.nav.NavCommand.Start(
                        toLat = toLat, toLon = toLon, profile = profile, via = via,
                    ),
                )
            },
            http = geoHttp,
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        local.phantom.companion.core.tool.impl.NavStopTool(
            requestNavStop = {
                navCommands.tryEmit(local.phantom.companion.core.nav.NavCommand.Stop)
            },
        ),
        local.phantom.companion.core.tool.impl.NavStatusTool(
            telemetryProvider = {
                val st = navEngine.state.value
                val isGuiding = st.guiding
                val p = st.progress
                val nextManeuver = p?.let {
                    local.phantom.companion.core.nav.maneuverPhraseUk(
                        type = it.maneuverType ?: "continue",
                        modifier = it.maneuverModifier,
                        roadName = it.roadName,
                        distanceM = it.distanceToManeuverM.toInt(),
                    )
                }
                local.phantom.companion.core.tool.impl.NavTelemetrySnapshot(
                    isGuiding = isGuiding,
                    remainingDistanceM = p?.remainingDistanceM,
                    remainingDurationS = p?.remainingDurationS,
                    currentSpeedKmh = p?.speedKmh?.toDouble(),
                    speedLimitKmh = p?.limitKmh,
                    nextManeuverText = nextManeuver,
                    // Жодного сканування коридору в дереві немає, тож єдине
                    // чесне значення — «невідомо». Тут стояв твердий 0, і
                    // інструмент публікував його як `threats_count: 0` —
                    // модель читала це як «перевірено, чисто». Сторожі на
                    // самому інструменті вже вимагають null; червоніти вони
                    // не могли, бо брехня жила у виклику, а не в інструменті.
                    activeThreatsCount = null,
                    curfewWarning = null,
                    curfewChecked = false,
                )
            },
        ),
        local.phantom.companion.core.tool.impl.NavAddWaypointTool(
            requestAddWaypoint = { lat, lon, _ ->
                val currentDest = navEngine.state.value.destination
                if (currentDest != null) {
                    navCommands.tryEmit(
                        local.phantom.companion.core.nav.NavCommand.Start(
                            toLat = currentDest.first,
                            toLon = currentDest.second,
                            profile = navEngine.state.value.profile?.slug,
                            via = listOf(lat to lon),
                        ),
                    )
                }
            },
            http = geoHttp,
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // NAV-Ω1 — «відлік»: the arrival deadline rides the same
        // nav-command flow, so the map's own affordance and PHANTOM's
        // verb move one state.
        local.phantom.companion.core.tool.impl.NavDeadlineTool(
            requestDeadline = { epochMs ->
                navCommands.tryEmit(
                    local.phantom.companion.core.nav.NavCommand.Deadline(epochMs),
                )
            },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // NAV-Ω2 — «по слідах» / retrace: reverse recorded breadcrumbs
        local.phantom.companion.core.tool.impl.NavRetraceTool(
            buildRetraceRoute = { breadcrumbTracker.buildRetraceRoute() },
            requestNavStart = { toLat, toLon, profile, via ->
                navCommands.tryEmit(
                    local.phantom.companion.core.nav.NavCommand.Start(
                        toLat = toLat, toLon = toLon, profile = profile.slug, via = via,
                    ),
                )
            },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // NAV-Ω2 — «евакуація / exfil»: urgent egress route away from danger
        local.phantom.companion.core.tool.impl.NavExfilTool(
            locationProvider = locationProvider,
            requestExfil = {
                val loc = locationProvider.current() ?: return@NavExfilTool false
                val marks = spatialLayerRepository.list(null)
                    .filter { it.visible && it.spec.kind == local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND }
                    .flatMap { it.spec.markers }
                    .filterNot { local.phantom.companion.core.data.spatial.isRefusedMarkKind(it.label ?: it.glyph.orEmpty()) }
                    .map { Triple(it.lat, it.lon, it.label ?: "позначка") }
                val target = local.phantom.companion.core.nav.ExfilEngine.computeExfilTarget(
                    operatorLat = loc.lat,
                    operatorLon = loc.lon,
                    threats = marks,
                )
                navCommands.tryEmit(
                    local.phantom.companion.core.nav.NavCommand.Start(
                        toLat = target.targetLat,
                        toLon = target.targetLon,
                        profile = local.phantom.companion.core.net.map.RouteProfile.CAR.slug,
                    ),
                )
            },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // NAV-Ω2 — «периметр безпеки / sentinel fence»: sovereign boundary monitoring
        local.phantom.companion.core.tool.impl.FenceSetTool(
            locationProvider = locationProvider,
            setFence = { label, radiusM, kind, lat, lon ->
                val k = when (kind) {
                    "hazard" -> local.phantom.companion.core.nav.SentinelFenceEngine.FenceKind.HAZARD_ZONE
                    "anchor" -> local.phantom.companion.core.nav.SentinelFenceEngine.FenceKind.ANCHOR_PERIMETER
                    else -> local.phantom.companion.core.nav.SentinelFenceEngine.FenceKind.SAFE_HAVEN
                }
                val fence = local.phantom.companion.core.nav.SentinelFenceEngine.Geofence(
                    label = label,
                    centerLat = lat,
                    centerLon = lon,
                    radiusM = radiusM,
                    kind = k,
                )
                sentinelFenceEngine.addFence(fence)
                fence.id
            },
        ),
        local.phantom.companion.core.tool.impl.FenceListTool(
            listFences = {
                sentinelFenceEngine.listFences().map { f ->
                    local.phantom.companion.core.tool.impl.FenceItem(
                        id = f.id,
                        label = f.label,
                        radiusM = f.radiusM,
                        kind = f.kind.name,
                        lat = f.centerLat,
                        lon = f.centerLon,
                    )
                }
            },
        ),
        // M-N08 — «експорт треку / track.export»: GPX / GeoJSON export
        local.phantom.companion.core.tool.impl.TrackExportTool(
            exportCurrentTrack = { format ->
                val points = breadcrumbTracker.getTrack().map { pt ->
                    local.phantom.companion.core.nav.TrackExporter.TrackPoint(
                        lat = pt.lat,
                        lon = pt.lon,
                        timeMs = pt.timeMs,
                    )
                }
                if (points.isEmpty()) null
                else {
                    val data = if (format == "geojson") {
                        local.phantom.companion.core.nav.TrackExporter.exportGeoJson("Nightpost Track", points)
                    } else {
                        local.phantom.companion.core.nav.TrackExporter.exportGpx("Nightpost Track", points)
                    }
                    local.phantom.companion.core.tool.impl.ExportedTrack(
                        format = format,
                        pointCount = points.size,
                        data = data,
                    )
                }
            },
        ),
        // M-N01 — «shelter.vector»: tactical azimuth, distance & ETA to nearest shelter
        local.phantom.companion.core.tool.impl.ShelterVectorTool(
            getVector = { reqLat, reqLon, reqHeading ->
                val originLat = reqLat ?: locationProvider.current()?.lat
                val originLon = reqLon ?: locationProvider.current()?.lon
                if (originLat == null || originLon == null) null
                else {
                    val scan = shelterRepository.findNearest(originLat, originLon, initialRadiusMeters = 3000)
                    val found = scan as? local.phantom.companion.core.data.evac.ShelterScanResult.Found
                    val nearest = found?.tokens?.firstOrNull()
                    if (nearest == null) null
                    else {
                        val guidance = shelterVectorEngine.calculateVector(
                            currentLat = originLat,
                            currentLon = originLon,
                            currentHeadingDeg = reqHeading,
                            target = local.phantom.companion.core.nav.ShelterVectorEngine.ShelterTarget(
                                id = nearest.id.toString(),
                                name = nearest.name,
                                lat = nearest.lat,
                                lon = nearest.lon,
                                type = nearest.assetType.name,
                                address = null,
                            ),
                        )
                        local.phantom.companion.core.tool.impl.ShelterVectorResult(
                            name = guidance.target.name,
                            distanceM = guidance.distanceMeters,
                            bearingDeg = guidance.bearingDeg,
                            compassDir = guidance.compassDirectionUk,
                            clockDir = guidance.clockPositionUk,
                            walkEtaMin = guidance.walkEtaMinutes,
                            runEtaMin = guidance.runEtaMinutes,
                            instruction = guidance.instructionUk,
                        )
                    }
                }
            },
        ),
        // M-F01 / M-F02 — «weather.radar»: atmospheric radar reflectivity & precipitation overlay
        local.phantom.companion.core.tool.impl.AtmosphericRadarTool(
            getRadar = { reqLat, reqLon ->
                val originLat = reqLat ?: locationProvider.current()?.lat
                val originLon = reqLon ?: locationProvider.current()?.lon
                if (originLat == null || originLon == null) null
                else {
                    val snap = openMeteoClient.fetch(originLat, originLon, hourCount = 1)
                    val now = snap?.hours?.firstOrNull()
                    // Мовчання джерела підмінялось на 18°C / 0 мм / 12 км/год,
                    // і рушій друкував із них готовий бриф «небо ясне». Нема
                    // години — нема показника.
                    if (now == null) null
                    else {
                        val eval = atmosphericRadarEngine.evaluateAtmosphere(
                            lat = originLat,
                            lon = originLon,
                            temperatureC = now.tempC.toDouble(),
                            precipitationMmH = now.rainMm.toDouble(),
                            windKmh = (now.windMs * 3.6).toDouble(),
                            // Борг закрито 13.09: клієнт питає wind_direction_10m,
                            // і сюди їде те, що відповіло джерело. null означає
                            // «не сказали» — тоді румб зникає з брифа й зі
                            // структури, замість сталої 270° («Західний» у
                            // будь-яку погоду й на будь-якій широті).
                            windDeg = now.windDirDeg,
                        )
                        local.phantom.companion.core.tool.impl.RadarResult(
                            conditionType = eval.precipitationType.name,
                            reflectivityDbz = eval.reflectivityDbz,
                            temperatureC = eval.temperatureCelsius,
                            windKmh = eval.windSpeedKmh,
                            windDir = eval.windCompassUk,
                            activeLayer = eval.activeMapLayer,
                            brief = eval.briefUk,
                            // Без цього інструмент відмовляє «radar_unsourced»
                            // НА КОЖЕН виклик: він вимагає часу виміру, а сюди
                            // його ніхто не клав. Погода мовчала не тому, що
                            // її нема, а тому, що ніхто не назвав, коли її взяли.
                            observedAtMs = now.tsMs,
                        )
                    }
                }
            },
        ),
        // M-L01 / M-L03 — «place.context»: semantic place mode & home sentry monitor
        local.phantom.companion.core.tool.impl.PlaceContextTool(
            getContext = { reqLat, reqLon, reqSpeed ->
                val originLat = reqLat ?: locationProvider.current()?.lat
                val originLon = reqLon ?: locationProvider.current()?.lon
                if (originLat == null || originLon == null) null
                else {
                    val isAlert = airAlertWatch.sky.value.oblasts.isNotEmpty()
                    val eval = placeContextEngine.evaluateContext(
                        currentLat = originLat,
                        currentLon = originLon,
                        speedMs = reqSpeed ?: 0f,
                        // ATLAS-3 — «База» була Києвом у двох числах: оператор
                        // у Варшаві мав домашню варту за 1200 км і мовчазно
                        // «в дорозі» назавжди. Дім каже пак; нема пака — нема
                        // якоря, і варта чесно вимкнена, а не привʼязана до
                        // чужої столиці.
                        homeAnchor = local.phantom.companion.core.data.country.CountryPackRegistry
                            .current()?.home?.let {
                                local.phantom.companion.core.nav.PlaceContextEngine.GeoAnchor(
                                    name = "База",
                                    lat = it.lat,
                                    lon = it.lon,
                                )
                            },
                        isAlertActiveInArea = isAlert,
                    )
                    local.phantom.companion.core.tool.impl.PlaceContextResult(
                        mode = eval.mode.name,
                        activeAnchor = eval.activeAnchorName,
                        speedKmh = eval.speedKmh,
                        homeSentryActive = eval.homeSentryActive,
                        suggestedMapMode = eval.suggestedMapMode,
                        summary = eval.summaryUk,
                    )
                }
            },
        ),
        // «solar.lighting» — висота, азимут і фаза з ЄДИНОГО альманаху (core-geo).
        local.phantom.companion.core.tool.impl.SolarLightingTool(
            getSolar = { reqLat, reqLon, reqTimeMs ->
                val originLat = reqLat ?: locationProvider.current()?.lat
                val originLon = reqLon ?: locationProvider.current()?.lon
                if (originLat == null || originLon == null) null
                else {
                    val pos = local.phantom.companion.core.geo.sky.SolarLighting.at(
                        latDeg = originLat,
                        lonDeg = originLon,
                        epochMs = reqTimeMs ?: System.currentTimeMillis(),
                    )
                    local.phantom.companion.core.tool.impl.SolarLightingResult(
                        elevationDeg = pos.elevationDeg,
                        azimuthDeg = pos.azimuthDeg,
                        phase = pos.phase.name,
                        intensityLux = pos.luxDirect,
                        colorTempK = pos.colorTempK,
                        description = pos.descriptionUk,
                    )
                }
            },
        ),
        // OSM UNIVERSE — «spatial.search»: unified spatial search across categories, vault & coords
        local.phantom.companion.core.tool.impl.SpatialSearchTool(
            executeSearch = { query, reqLat, reqLon, reqHeading ->
                val originLat = reqLat ?: locationProvider.current()?.lat
                val originLon = reqLon ?: locationProvider.current()?.lon
                val searchRes = spatialSearchEngine.search(
                    query = query,
                    operatorLat = originLat,
                    operatorLon = originLon,
                    operatorHeadingDeg = reqHeading,
                )
                local.phantom.companion.core.tool.impl.SpatialSearchExecutionResult(
                    query = searchRes.query,
                    items = searchRes.items.map { item ->
                        local.phantom.companion.core.tool.impl.SpatialSearchItemOutput(
                            id = item.id,
                            title = item.title,
                            subtitle = item.subtitle,
                            kind = item.kind.name,
                            icon = item.icon,
                            lat = item.lat,
                            lon = item.lon,
                            distanceM = item.distanceMeters,
                            compassDir = item.compassDirectionUk,
                            clockDir = item.clockDirectionUk,
                            isCritical = item.isWartimeCritical,
                        )
                    },
                    summary = searchRes.summaryUk,
                )
            },
        ),
        // NAV-Ω1 — «по дорозі»: category search along the live route.
        local.phantom.companion.core.tool.impl.RouteAlongTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
            activeRoutePoints = { activeRoutePoints.value },
        ),
        // ПРОВИДЕЦЬ — map.prefetch: pre-pull tiles + road mesh along a
        // corridor so navigation survives the connection dying mid-drive.
        local.phantom.companion.core.tool.impl.MapPrefetchTool(
            resolvePlace = { q ->
                // GEO-LAW — no configured geocoder means no resolve; the
                // tool already treats a null result as honest failure.
                val geocodeBase = GeoEndpoints.config.geocodeBaseUrl
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val body = geocodeBase?.let {
                    geoHttp.getNominatim("$it/search?q=$enc&format=json&limit=1")
                }
                if (body == null) null
                else runCatching {
                    val el = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
                    val la = el?.get("lat")?.jsonPrimitive?.content?.toDoubleOrNull()
                    val lo = el?.get("lon")?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (la != null && lo != null) la to lo else null
                }.getOrNull()
            },
            launchCorridor = { dest, label, tiles -> corridorLaunch(dest, label, tiles) },
            progress = { corridorPrefetcher.stateLine() },
        ),
        // R1.4 — geo stack: search / nearby / route over public OSM services.
        local.phantom.companion.core.tool.impl.GeoSearchTool(http = geoHttp),
        local.phantom.companion.core.tool.impl.GeoNearbyTool(
            http = geoHttp,
            locationProvider = locationProvider,
        ),
        local.phantom.companion.core.tool.impl.GeoRouteTool(
            http = geoHttp,
            locationProvider = locationProvider,
            // The tool's own description promises «OSRM / офлайн Фантом-граф»;
            // without this it only ever had the first half, so every route
            // off Wi-Fi died as routing_unavailable.
            offlineRouteCalculator = { fromLat, fromLon, toLat, toLon, isPedestrian ->
                offlineMeshRoute(
                    profile = if (isPedestrian) {
                        local.phantom.companion.core.net.map.RouteProfile.FOOT
                    } else {
                        local.phantom.companion.core.net.map.RouteProfile.CAR
                    },
                    waypoints = listOf(fromLat to fromLon, toLat to toLon),
                )?.firstOrNull()
            },
        ),
        // R4.4/R4.5/R4.6 — the living map: real world layers that push
        // themselves onto the map (WORLD origin, self-expiring TTLs).
        local.phantom.companion.core.tool.impl.TransitNearbyTool(
            http = geoHttp, sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.TransitRoutesTool(
            http = geoHttp, sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.WorldCamerasTool(
            http = geoHttp, sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.WorldAircraftTool(
            http = geoHttp, sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
        ),
        local.phantom.companion.core.tool.impl.HazardReportTool(
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
        ),
        // NAV-4 — «досьє місця» as a verb: the same facts the operator's
        // tap assembles (OSM tags, wiki, building, stop lines).
        local.phantom.companion.core.tool.impl.PlaceInfoTool(
            sink = spatialLayerRepository,
            currentThreadId = { activeThreadRef.get() },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // NAV-6 — «додому громадським»: direct OSM lines operator ∩ home.
        // Home = the map-poi vault entry named «Дім»/«home».
        local.phantom.companion.core.tool.impl.TransitHomeTool(
            sink = spatialLayerRepository,
            locationProvider = locationProvider,
            currentThreadId = { activeThreadRef.get() },
            resolveHome = {
                // Дім живе в `saved_places` — тому дім, збережений на мапі, і
                // дім, названий у чаті, тепер це один і той самий запис.
                val places = savedPlaceStore.list()
                val home = places.firstOrNull {
                    local.phantom.companion.core.data.places.SavedPlaceCategory
                        .canonical(it.category) ==
                        local.phantom.companion.core.data.places.SavedPlaceCategory.HOME
                } ?: places.firstOrNull {
                    it.label.trim().lowercase() in setOf("дім", "дом", "home", "додому")
                }
                home?.let { it.lat to it.lon }
            },
            requestMapOpen = { mapOpenRequests.tryEmit(Unit) },
        ),
        // R5 — Device Hands: phone commands as system intents. Alarm/timer
        // set silently (SET_ALARM manifest permission + EXTRA_SKIP_UI);
        // dial/SMS/calendar open pre-filled system UI so the operator
        // confirms with their own finger; panels never toggle silently.
        // Власна мережа: помічник бачить, кого можна дістати, і ГОТУЄ лист —
        // надсилає оператор, як і в phone.sms.
        local.phantom.companion.core.tool.impl.PeopleListTool(roster = ::reachablePeople),
        local.phantom.companion.core.tool.impl.PeopleDraftTool(
            roster = ::reachablePeople,
            prepareDraft = { peerId, text ->
                // Буфер, а не поле розмови: рідний екран розмови пішов 06.09, а
                // веб-бандл має один маршрут і адреси розмови не приймає. Текст
                // мусить лишитись там, звідки оператор дістане його сам —
                // інакше інструмент каже «готово» над порожнечею.
                val copied = runCatching {
                    val clipboard = applicationContext.getSystemService(ClipboardManager::class.java)
                        ?: return@runCatching false
                    val clip = android.content.ClipData.newPlainText("", text).apply {
                        // Чужий лист не має світитись у системній підказці вставки.
                        // Прапорець існує з API 33, а minSdk тут 29: без цієї
                        // огорожі lint валить збірку (InlinedApi піднято до
                        // помилки в app/build.gradle.kts). На 29-32 захисту немає
                        // взагалі — там усе вирішує клавіатура.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            description.extras = android.os.PersistableBundle().apply {
                                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                    true
                }.getOrDefault(false)
                if (copied) events.peerChatRequest.value = peerId
                copied
            },
        ),
        // Зустріч і рахунок голосом: помічник лише відкриває чернетку в месенджері
        // (`WebMessengerMeetupDraft` → хост → `__phantomOpenMeetupDraft`). Запрошує й
        // платить людина — у цих двох інструментів до надсилання дверей немає.
        local.phantom.companion.core.tool.impl.MeetupCreateTool(
            roster = ::reachablePeople,
            open = { ask -> local.phantom.companion.ui.messenger.WebMessengerMeetupDraft.asked(ask) },
            words = { meetupWords },
        ),
        local.phantom.companion.core.tool.impl.BillSplitTool(
            open = { ask -> local.phantom.companion.ui.messenger.WebMessengerMeetupDraft.asked(ask) },
            words = { meetupWords },
        ),
        local.phantom.companion.core.tool.impl.PhoneAlarmTool(phoneIntentLauncher),
        local.phantom.companion.core.tool.impl.PhoneTimerTool(phoneIntentLauncher),
        local.phantom.companion.core.tool.impl.PhoneDialTool(phoneIntentLauncher),
        local.phantom.companion.core.tool.impl.PhoneSmsTool(phoneIntentLauncher),
        local.phantom.companion.core.tool.impl.PhoneCalendarTool(phoneIntentLauncher),
        local.phantom.companion.core.tool.impl.PhonePanelTool(phoneIntentLauncher),
        // R6.1 — PhantomCanvas: multi-pass native artifact builder +
        // living patch. Both ride one extra model turn per pass via the
        // same router the chat uses (no separate key/quota).
        local.phantom.companion.core.tool.impl.CanvasBuildTool(
            complete = singleTurnComplete,
        ),
        local.phantom.companion.core.tool.impl.CanvasPatchTool(
            complete = singleTurnComplete,
        ),
        // МЕТРО — схема станції з OSM/OpenStationMap (`:core-geo`): входи з
        // номерами на табличках, доступність у візку, шлях усередині станції
        // до платформи. Кладе шар тим самим конвеєром, що й `world.*`, тож
        // маркери, чип у капсулі інструментів і TTL працюють без нового UI.
        local.phantom.companion.core.geo.metro.MetroStationTool(
            sink = spatialLayerRepository,
            currentLocation = { locationProvider.current()?.let { it.lat to it.lon } },
            currentThreadId = { activeThreadRef.get() },
        ),
        // DATA-SYSTEMS — the typed stores behind the chat widgets, as verbs the
        // model can call: an advanced local calendar, tasks/checklists,
        // time-based reminders, and the operator's personal saved places. All
        // offline, all on the SQLCipher DB.
        local.phantom.companion.core.tool.impl.CalendarAddTool(calendarStore),
        local.phantom.companion.core.tool.impl.CalendarListTool(calendarStore),
        local.phantom.companion.core.tool.impl.CalendarRemoveTool(calendarStore),
        local.phantom.companion.core.tool.impl.TaskAddTool(taskStore),
        local.phantom.companion.core.tool.impl.TaskListTool(taskStore),
        local.phantom.companion.core.tool.impl.TaskDoneTool(taskStore),
        local.phantom.companion.core.tool.impl.TaskRemoveTool(taskStore),
        local.phantom.companion.core.tool.impl.ReminderAddTool(reminderStore),
        local.phantom.companion.core.tool.impl.ReminderListTool(reminderStore),
        local.phantom.companion.core.tool.impl.ReminderRemoveTool(reminderStore),
        local.phantom.companion.core.tool.impl.PlaceSaveTool(savedPlaceStore),
        local.phantom.companion.core.tool.impl.PlaceListTool(savedPlaceStore),
        local.phantom.companion.core.tool.impl.PlaceRemoveTool(savedPlaceStore),
        local.phantom.companion.core.tool.impl.MemoryListTool(memoryFactStore),
        local.phantom.companion.core.tool.impl.MemoryForgetTool(memoryFactStore),
        local.phantom.companion.core.tool.impl.MemorySaveTool(memoryFactStore),
    )
    return local.phantom.companion.core.tool.ToolRegistry(
        tools = tools,
        constitutionRegistry = constitutionRegistry,
    )
}

// sh -c runs with the app uid: every private file, the wallet blob included, is one prompt away.
internal fun devOnlyTools(debug: Boolean, termux: () -> Boolean): List<local.phantom.companion.core.tool.Tool> =
    if (debug) listOf(local.phantom.companion.core.tool.impl.ShellTool(termuxAvailable = termux())) else emptyList()

private val MESSENGER_NO_HANDS =
    Regex("""(^|[._-])(tap|click|swipe|long_?press|gesture|app[._-]?action|accessibility|godmode|shell)([._-]|$)|^pc\.""")

internal fun messengerToolset(all: List<local.phantom.companion.core.tool.Tool>): List<local.phantom.companion.core.tool.Tool> =
    all.filterNot { MESSENGER_NO_HANDS.containsMatchIn(it.id.trim().lowercase()) }

/** Реєстр так, як його бачить помічник — без типів мережі в core-symbiote. */
private suspend fun AppContainer.reachablePeople():
    List<local.phantom.companion.core.tool.impl.ReachablePerson> {
    val now = System.currentTimeMillis()
    return peers.peers.value.map { p ->
        local.phantom.companion.core.tool.impl.ReachablePerson(
            id = p.id,
            name = p.name,
            live = p.isLive(now),
            lastSeenMs = p.lastSeenMs,
            verified = p.isVerified,
        )
    }
}

internal fun AppContainer.buildProductToolset(
    registry: List<local.phantom.companion.core.tool.Tool>,
    english: () -> Boolean = { true },
): local.phantom.companion.assistant.ProductToolset {
    val webDay by lazy { local.phantom.companion.ui.messenger.WebMessengerDay.of(this) }
    val sources = local.phantom.companion.assistant.ProductSources(
        day = { webDay.today() },
        circles = {
            local.phantom.companion.core.net.peer.PeerRoster.people(peers.peers.value).map { p ->
                local.phantom.companion.assistant.CircleRow(
                    name = p.name,
                    circle = peers.circleOf(p.id),
                )
            }
        },
        where = {
            val now = System.currentTimeMillis()
            local.phantom.companion.core.net.peer.PeerRoster.people(peers.peers.value).mapNotNull { p ->
                val theirs = peopleWhere.view(p.id).theirs ?: return@mapNotNull null
                val ageMin = ((now - theirs.atMs).coerceAtLeast(0L)) / 60_000L
                local.phantom.companion.assistant.WhereRow(
                    name = p.name,
                    level = theirs.precision.name.lowercase(),
                    ageMin = ageMin,
                )
            }
        },
        wallet = wallet@{
            val st = local.phantom.companion.core.solana.SolanaLayer.state.value
            val addr = st.address ?: return@wallet null
            val balances = st.balances.orEmpty().entries.map { (coin, units) ->
                coin.symbol to local.phantom.companion.core.solana.Amounts.format(units, coin)
            }
            local.phantom.companion.assistant.WalletRow(
                shortAddress = addr.short(),
                balances = balances,
                cluster = st.cluster.wire,
            )
        },
        viewport = { events.mapViewport.value },
        english = english,
    )
    return local.phantom.companion.assistant.ProductToolset(
        registry = registry,
        sources = sources,
    )
}
