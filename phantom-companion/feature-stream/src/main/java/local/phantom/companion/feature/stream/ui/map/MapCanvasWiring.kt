package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.BuildingIndoorEngine
import local.phantom.companion.core.geo.indoor.BuildingIndoorPlan
import local.phantom.companion.core.geo.indoor.EntranceKind
import local.phantom.companion.core.geo.indoor.IndoorPassage
import local.phantom.companion.core.geo.indoor.IndoorRoom
import local.phantom.companion.core.geo.indoor.IndoorVenueCategory
import local.phantom.companion.core.geo.indoor.PassageKind
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.source.BatchFSource
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature

/**
 * ATLAS-0b — what the canvas does with a resolved tap, one handler per
 * contribution id. The chain of five early-returns that used to decide this
 * lives in [MapTapDispatcher] now; these only say what the winner means.
 */
internal fun mapTapHandlers(
    ui: MapUiState,
    viewModel: MapViewModel,
    zoomToCluster: (LatLng) -> Unit,
): Map<String, (Feature, LatLng) -> Unit> = mapOf(
    "routeAlternatives" to { f: Feature, _: LatLng ->
        // The feature carries WHICH option it is, so a tap picks that one
        // rather than advancing blindly through the list.
        val idx = runCatching { f.getNumberProperty("idx")?.toInt() }.getOrNull()
        if (idx != null) viewModel.selectRouteAlternative(idx) else viewModel.selectNextAlternative()
    },
    // Чужий маршрут під пальцем. Ідентифікатор із самої лінії, а список —
    // від скриньки: картка мусить показувати те, що зараз на землі, а не те,
    // що лежало в розмові в мить малювання.
    "peerRoutes" to { f: Feature, _: LatLng ->
        val who = f.getStringOr("peerId")
        ui.selectedPeerRoute = viewModel.peerRoutes.value.firstOrNull { it.peerId == who }
    },
    "transitLayers" to { f: Feature, point: LatLng ->
        ui.selectedLayerMarker = TappedLayerMarker(
            lat = point.latitude,
            lon = point.longitude,
            label = f.getStringOr("label").ifBlank { MapText.s(R.string.mapl_f_stop) },
            detail = f.getStringOr("detail").takeIf { it.isNotBlank() },
            glyph = null,
            layerTitle = MapText.s(R.string.mapl_f_transit),
        )
    },
    "packTransit" to { f: Feature, point: LatLng ->
        val at = f.geometry() as? org.maplibre.geojson.Point
        val name = f.getStringOr("name")
        ui.dossierTarget = DossierTarget(
            lat = at?.latitude() ?: point.latitude,
            lon = at?.longitude() ?: point.longitude,
            nameHint = name.takeIf { it.isNotBlank() }
                ?: packTransitDetail(f.getStringOr("routes")),
            categoryHint = BatchFSource.DOSSIER_STOP_CATEGORY_HINT,
            subcategoryHint = packStopModeWord(f.getStringOr("mode")),
        )
    },
    "phantomLayers" to { f: Feature, point: LatLng ->
        ui.selectedLayerMarker = TappedLayerMarker(
            lat = point.latitude,
            lon = point.longitude,
            label = f.getStringOr("label").ifBlank { MapText.s(R.string.mapl_f_point) },
            detail = f.getStringOr("detail").takeIf { it.isNotBlank() },
            glyph = f.getStringOr("glyph").takeIf { it.isNotBlank() },
            layerTitle = f.getStringOr("layer_title").takeIf { it.isNotBlank() },
        )
    },
    // Знак події відкриває те саме досьє, що й раніше відкривала пігулка:
    // категорія «barrier» лишається, бо саме за нею досьє добирає вигляд.
    // Виняток — мітка шару «позначки на мапі»: у неї немає OSM-досьє, її
    // правда — це її власна картка (вид, фактаж, чия вона).
    "eventMarks" to { f: Feature, point: LatLng ->
        if (f.getStringOr("mark") == "true") {
            ui.selectedLayerMarker = TappedLayerMarker(
                lat = point.latitude,
                lon = point.longitude,
                label = f.getStringOr("label").ifBlank { f.getStringOr("kind") },
                detail = f.getStringOr("detail").takeIf { it.isNotBlank() },
                glyph = null,
                layerTitle = local.phantom.companion.core.data.spatial.HAZARD_LAYER_TITLE,
            )
        } else {
            // Місце світу (аптека, АЗС) несе справжні теги; подія — «barrier».
            val placeCat = f.getStringOr("cat").takeIf { it.isNotBlank() }
            ui.dossierTarget = DossierTarget(
                lat = point.latitude,
                lon = point.longitude,
                nameHint = f.getStringOr("label").takeIf { it.isNotBlank() },
                categoryHint = placeCat ?: "barrier",
                subcategoryHint = (if (placeCat != null) f.getStringOr("sub") else f.getStringOr("kind")).takeIf { it.isNotBlank() },
            )
        }
    },
    // СИМБІОЗ — людина. Координати беруться з ГЕОМЕТРІЇ фічі, не з точки
    // пальця: картка каже відстань, і рахувати її від того місця, куди влучив
    // палець, означало б помилятись рівно на промах.
    "peerFigures" to { f: Feature, point: LatLng ->
        val at = f.geometry() as? org.maplibre.geojson.Point
        ui.selectedPeer = local.phantom.companion.feature.stream.ui.map.peers.TappedPeer(
            nodeId = f.getStringOr("nodeId"),
            threadId = f.getStringOr("threadId"),
            name = f.getStringOr("name"),
            lat = at?.latitude() ?: point.latitude,
            lon = at?.longitude() ?: point.longitude,
            atMs = runCatching { f.getNumberProperty("atMs")?.toLong() }.getOrNull() ?: 0L,
            verified = f.getStringOr("verified") == "true",
            fresh = f.getStringOr("fresh") == "true",
        )
    },
    // Координати — з властивостей: тапнути можна й пляму, а в неї центр — не людина.
    // Досі тут стояло 0.0/0.0, і «Маршрут» до точного місця вів у Гвінейську затоку.
    "peerWhere" to { f: Feature, _: LatLng ->
        val members = f.getStringOr("members").split('|').filter { it.isNotBlank() }
        if (f.getStringOr("nodeId") == "meet") {
            ui.meetOpen = true
        } else if (members.size > 1) {
            ui.peerGroup = members
        } else {
            ui.selectedPeer = local.phantom.companion.feature.stream.ui.map.peers.TappedPeer(
                nodeId = f.getStringOr("nodeId"),
                threadId = f.getStringOr("threadId"),
                name = f.getStringOr("name"),
                lat = runCatching { f.getNumberProperty("lat")?.toDouble() }.getOrNull() ?: 0.0,
                lon = runCatching { f.getNumberProperty("lon")?.toDouble() }.getOrNull() ?: 0.0,
                atMs = runCatching { f.getNumberProperty("atMs")?.toLong() }.getOrNull() ?: 0L,
                verified = f.getStringOr("verified") == "true",
                fresh = f.getStringOr("fresh") == "true",
                precision = f.getStringOr("precision").takeIf { it.isNotBlank() },
                near = f.getStringOr("near").takeIf { it.isNotBlank() },
                motion = f.getStringOr("motion").takeIf { it.isNotBlank() },
                spot = f.getStringOr("spot").takeIf { it.isNotBlank() },
                lowBattery = f.getStringOr("low") == "true",
                face = f.getStringOr("face").takeIf { it.isNotBlank() },
            )
        }
    },
    // A tapped star is the operator's OWN place — its light action card, not
    // the heavy dossier a stranger POI gets.
    "savedPlacesOverlay" to { f: Feature, point: LatLng ->
        val at = f.geometry() as? org.maplibre.geojson.Point
        ui.selectedSavedPlace = local.phantom.companion.core.data.poi.PoiEntity(
            id = f.getLongOr("id", point.hashCode().toLong()),
            lat = at?.latitude() ?: point.latitude,
            lon = at?.longitude() ?: point.longitude,
            name = f.getStringOr("name").ifBlank { MapText.s(R.string.mapl_f_place) },
            category = f.getStringOr("category").ifBlank { "other" },
            subcategory = f.getStringOr("subcategory").takeIf { it.isNotBlank() },
            tags = emptyMap(),
        )
    },
    // Місце Nightpost — картка місця з його сторінкою (PlaceVenueSection за venueId).
    "venuePins" to { f: Feature, point: LatLng ->
        val at = f.geometry() as? org.maplibre.geojson.Point
        ui.dossierTarget = DossierTarget(
            lat = at?.latitude() ?: point.latitude,
            lon = at?.longitude() ?: point.longitude,
            nameHint = f.getStringOr("name").ifBlank { null },
            venueId = f.getStringOr("venueId").ifBlank { null },
        )
    },
    "poiOverlay" to { f: Feature, point: LatLng ->
        if (f.hasProperty("point_count")) {
            zoomToCluster(point)
        } else {
            ui.dossierTarget = DossierTarget(
                lat = point.latitude,
                lon = point.longitude,
                nameHint = f.getProperty("name").asString,
                categoryHint = f.getProperty("category").asString,
                subcategoryHint = if (f.hasProperty("subcategory")) {
                    f.getProperty("subcategory").asString
                } else {
                    null
                },
            )
        }
    },
    // The base style draws its OWN rich set of icons — shops, banks, stops —
    // entirely separate from our overlay. Open the same sheet with whatever
    // tags the tile carries (Law 1 — no invented data).
    "groundFeature" to { f: Feature, point: LatLng ->
        val explicitName = listOf("name:uk", "name", "name:en")
            .firstNotNullOfOrNull { f.getStringOr(it).takeIf { s -> s.isNotBlank() } }
        val houseNum = f.getStringOr("addr:housenumber")
            .ifBlank { f.getStringOr("housenumber") }.takeIf { it.isNotBlank() }
        val street = f.getStringOr("addr:street")
            .ifBlank { f.getStringOr("street") }.takeIf { it.isNotBlank() }
        val brand = f.getStringOr("brand")
            .ifBlank { f.getStringOr("operator") }.takeIf { it.isNotBlank() }
        val name = when {
            explicitName != null -> explicitName
            street != null && houseNum != null -> "$street, $houseNum"
            houseNum != null -> "№ $houseNum"
            else -> brand
        }
        val klass = listOf("class", "kind", "amenity", "shop", "building", "office", "tourism", "craft")
            .firstNotNullOfOrNull { f.getStringOr(it).takeIf { s -> s.isNotBlank() } }
        ui.dossierTarget = DossierTarget(
            lat = point.latitude,
            lon = point.longitude,
            nameHint = name,
            categoryHint = klass,
            subcategoryHint = f.getStringOr("subclass").takeIf { it.isNotBlank() },
        )
    },
)

/**
 * Where the camera must sit so a just-dropped point clears the action sheet.
 *
 * Deliberately NOT in a `@Composable` file: reading the projection is legal,
 * drawing with it is what delaminates, and `MapGeoOverlayFirewallTest` draws
 * that line at file level. Keeping the arithmetic here keeps the listener file
 * off the geo-overlay allow-list.
 */
internal fun liftedDropTarget(map: MapLibreMap, point: LatLng, fraction: Float): LatLng {
    val screen = map.projection.toScreenLocation(point)
    return map.projection.fromScreenLocation(
        android.graphics.PointF(screen.x, screen.y + map.height * fraction),
    )
}

internal fun placeOutOfView(map: MapLibreMap, lat: Double, lon: Double, density: Float): Boolean {
    val p = map.projection.toScreenLocation(LatLng(lat, lon))
    return PlaceFocus.shouldFly(p.x, p.y, map.width, map.height, map.cameraPosition.zoom, density)
}

/**
 * design-critic §S9 — the «не завантажено» hatch for whatever the map is
 * showing right now. Empty while online: live tiles answer for themselves, and
 * hatching ground the base style is actively fetching would be the opposite
 * lie ("not downloaded" over data that is right there).
 */
internal fun notDownloadedGeoJsonFor(
    map: MapLibreMap,
    context: Context,
    offlinePacks: List<RegionInfo>,
    offlineStyleUrl: String,
): String {
    val online = runCatching {
        OfflineConditions.check(context, allowMetered = true) != OfflineConditions.Verdict.OFFLINE
    }.getOrDefault(true)
    if (online) return EMPTY_FC
    val b = map.projection.visibleRegion.latLngBounds
    val viewportArea = local.phantom.companion.feature.stream.domain.OfflineCoverage.Area(
        northLat = b.latitudeNorth,
        eastLon = b.longitudeEast,
        southLat = b.latitudeSouth,
        westLon = b.longitudeWest,
    )
    return notDownloadedHatchGeoJson(
        viewportArea,
        offlinePacks
            .filter { it.isComplete && (it.styleUrl == null || it.styleUrl == offlineStyleUrl) }
            .mapNotNull { it.area },
    )
}

/**
 * INDOOR — the building plan for what the operator is looking at, network
 * first and the already-rendered vector tiles as the local fallback.
 *
 * Still a Compose overlay this wave (allow-listed); its tier is reserved and
 * the migration into the engine is ATLAS-1.
 */
internal suspend fun fetchIndoorPlan(
    engine: BuildingIndoorEngine,
    map: MapLibreMap?,
    lat: Double,
    lon: Double,
): BuildingIndoorPlan? {
    engine.fetchBuildingPlan(lat, lon, radiusM = 350)?.let { return it }
    val m = map ?: return null
    val screenCenter = m.projection.toScreenLocation(LatLng(lat, lon))
    val box = android.graphics.RectF(
        screenCenter.x - 450f, screenCenter.y - 450f,
        screenCenter.x + 450f, screenCenter.y + 450f,
    )
    val rendered = runCatching { m.queryRenderedFeatures(box) }.getOrNull().orEmpty()
    val passages = mutableListOf<IndoorPassage>()
    val rooms = mutableListOf<IndoorRoom>()
    val entrances = mutableListOf<BuildingEntrance>()

    for (f in rendered) {
        val geom = f.geometry() ?: continue
        val isBuildingPart = f.getStringOr("indoor") == "room" ||
            f.getStringOr("indoor") == "area" || f.hasProperty("building:part")
        val isIndoorCorridor = f.getStringOr("indoor") == "corridor" ||
            f.getStringOr("indoor") == "yes" ||
            f.getStringOr("tunnel") == "building_passage" || f.getStringOr("covered") == "yes"
        // Вуличні сходи з тайла не мають ні `indoor`, ні `level` — і не є
        // частиною будівлі: без цієї умови кожні сходи двору ставали «проходом».
        val insideAHint = f.hasProperty("indoor") || f.hasProperty("level")
        val isVerticalTransit = (f.getStringOr("highway") == "steps" && insideAHint) ||
            f.getStringOr("highway") == "elevator" ||
            f.getStringOr("conveying") == "escalator" ||
            f.getStringOr("conveying") == "moving_walkway"
        val isEntrance = f.hasProperty("entrance") || f.getStringOr("railway") == "subway_entrance"

        if (isEntrance && geom is org.maplibre.geojson.Point) {
            entrances += BuildingEntrance(
                id = "local_ent_${entrances.size}",
                lat = geom.latitude(),
                lon = geom.longitude(),
                kind = if (f.getStringOr("railway") == "subway_entrance") {
                    EntranceKind.SUBWAY
                } else {
                    EntranceKind.MAIN
                },
                name = f.getStringOr("name").ifBlank { MapText.s(R.string.mapl_f_entrance) },
            )
        } else if ((isIndoorCorridor || isVerticalTransit) && geom is org.maplibre.geojson.LineString) {
            val pts = geom.coordinates().map { it.latitude() to it.longitude() }
            if (pts.size >= 2) {
                passages += IndoorPassage(
                    id = "local_pass_${passages.size}",
                    points = pts,
                    level = 0,
                    kind = when {
                        f.getStringOr("conveying") == "escalator" -> PassageKind.ESCALATOR
                        f.getStringOr("conveying") == "moving_walkway" -> PassageKind.MOVING_WALKWAY
                        f.getStringOr("tunnel") == "building_passage" ||
                            f.getStringOr("covered") == "yes" -> PassageKind.BUILDING_PASSAGE
                        f.getStringOr("highway") == "steps" -> PassageKind.STEPS
                        f.getStringOr("highway") == "elevator" -> PassageKind.ELEVATOR
                        else -> PassageKind.CORRIDOR
                    },
                    name = f.getStringOr("name").ifBlank { null },
                )
            }
        } else if (isBuildingPart && geom is org.maplibre.geojson.Polygon) {
            val ring = geom.coordinates().firstOrNull()?.map { it.latitude() to it.longitude() }
            if (ring != null && ring.size >= 3) {
                rooms += IndoorRoom(
                    id = "local_room_${rooms.size}",
                    polygon = ring,
                    level = 0,
                    name = f.getStringOr("name").ifBlank { null },
                    category = f.getStringOr("shop")
                        .ifBlank { f.getStringOr("amenity").ifBlank { "indoor" } },
                    venueCategory = IndoorVenueCategory.OTHER,
                )
            }
        }
    }
    // Самі входи планом не є: `entrance=yes` стоїть на дверях кожного підʼїзду,
    // і план «із самих дверей» давав панель поверхів посеред двору.
    if (passages.isEmpty() && rooms.isEmpty()) return null
    return BuildingIndoorPlan(
        buildingId = "local_vector_$lat-$lon",
        availableLevels = listOf(0),
        entrances = entrances,
        passages = passages,
        rooms = rooms,
        venues = emptyList(),
    )
}

/**
 * Яка вершина ламаної під пальцем. `null` — жодної ближче за [tolerancePx].
 *
 * Порівняння в ЕКРАННИХ пікселях, а не в метрах: на віддаленій мапі два сусідні
 * метри — це один піксель, і поріг у метрах або хапав би все, або нічого.
 */
internal fun nearestDrawVertex(
    map: MapLibreMap,
    points: List<local.phantom.companion.core.data.spatial.GeoPoint>,
    screenX: Float,
    screenY: Float,
    tolerancePx: Float,
): Int? {
    var best = -1
    var bestSq = tolerancePx * tolerancePx
    points.forEachIndexed { i, p ->
        val pt = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
        val dx = pt.x - screenX
        val dy = pt.y - screenY
        val sq = dx * dx + dy * dy
        if (sq <= bestSq) {
            bestSq = sq
            best = i
        }
    }
    return best.takeIf { it >= 0 }
}

/**
 * Перетягування вершини — з ВЛАСНОГО дотику, а не з жесту MapLibre.
 *
 * Причина виміряна в байткоді SDK 11.5.1: `MapGestureDetector.MoveGestureListener`
 * спершу кличе `Transform.moveBy`, і лише ПОТІМ сповіщає сторонніх слухачів, а
 * при вимкнених `isScrollGesturesEnabled` не сповіщає взагалі. Тобто через
 * `addOnMoveListener` вершину можна тягти тільки разом з усією землею.
 *
 * Тому тут `OnTouchListener` на самому [org.maplibre.android.maps.MapView]:
 * жест забирається ЦІЛКОМ на ACTION_DOWN або не забирається зовсім. Слухач
 * живе лише поки триває правка (див. виклик у `MapSurfaceListeners`), тож поза
 * правкою дотик до мапи проходить рівно той самий шлях, що й до цієї хвилі.
 */
internal class DrawVertexDrag(
    private val map: MapLibreMap,
    private val pick: () -> DrawPick?,
    private val tolerancePx: Float,
    private val onHold: (Int) -> Unit,
    private val onDrag: (Int, local.phantom.companion.core.data.spatial.GeoPoint) -> Unit,
) : android.view.View.OnTouchListener {

    private var held: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouch(view: android.view.View, event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val points = pick()?.takeIf { it.editingId != null }?.points ?: return false
            val hit = nearestDrawVertex(map, points, event.x, event.y, tolerancePx) ?: return false
            held = hit
            lastX = event.x
            lastY = event.y
            onHold(hit)
            // Батько не має права перехопити цей жест собі на прокрутку:
            // інакше вершина зривається з пальця на першому ж пікселі.
            view.parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        // Жест забирається ЦІЛКОМ або не забирається зовсім: віддати мапі
        // середину послідовності означало б лишити її детектор жестів без
        // ACTION_DOWN, тобто в стані, з якого він уже не вийде.
        val index = held ?: return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                // Тремтіння пальця нижче пікселя — це не рух, а перебудова
                // джерела мапи на кожен кадр за те саме місце.
                if (kotlin.math.abs(event.x - lastX) >= 1f || kotlin.math.abs(event.y - lastY) >= 1f) {
                    lastX = event.x
                    lastY = event.y
                    val at = map.projection.fromScreenLocation(
                        android.graphics.PointF(event.x, event.y),
                    )
                    onDrag(
                        index,
                        local.phantom.companion.core.data.spatial.GeoPoint(at.latitude, at.longitude),
                    )
                }
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> {
                held = null
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

/**
 * Найближча назва з того, що мапа вже намалювала: спершу вулиця під пальцем,
 * далі будь-яке назване місце поруч (район, місто). Номери будинків — не назва.
 */
internal fun nearestMapName(map: MapLibreMap, lat: Double, lon: Double): String? {
    val at = runCatching { map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon)) }.getOrNull() ?: return null
    for (radius in RADII_PX) {
        val box = android.graphics.RectF(at.x - radius, at.y - radius, at.x + radius, at.y + radius)
        val named = runCatching { map.queryRenderedFeatures(box) }.getOrNull().orEmpty()
            .mapNotNull { f -> f.getStringOr("name").trim().takeIf { it.isNotEmpty() && it.any(Char::isLetter) }?.let { f to it } }
        if (named.isEmpty()) continue
        val road = named.firstOrNull { (f, _) -> f.hasProperty("highway") || f.getStringOr("class") in ROAD_CLASSES }
        return (road ?: named.first()).second
    }
    return null
}

private val RADII_PX = listOf(48f, 160f, 420f)
private val ROAD_CLASSES = setOf(
    "motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service",
    "street", "residential", "path", "pedestrian", "track",
)
