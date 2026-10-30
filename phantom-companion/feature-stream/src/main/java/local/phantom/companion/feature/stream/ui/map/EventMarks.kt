package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND
import local.phantom.companion.core.data.spatial.HAZARD_SOURCE_COMMUNITY
import local.phantom.companion.core.data.spatial.MARK_CLASS_CAMERA
import local.phantom.companion.core.data.spatial.MARK_CLASS_CHECKPOINT
import local.phantom.companion.core.data.spatial.MARK_CLASS_CRASH
import local.phantom.companion.core.data.spatial.MARK_CLASS_GATE
import local.phantom.companion.core.data.spatial.MARK_CLASS_SHELTER
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.barrierMarkClass
import local.phantom.companion.core.data.spatial.hazardMarkClass
import local.phantom.companion.core.data.spatial.isRefusedMarkKind
import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.core.geo.cameras.SafetyHazard
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import local.phantom.companion.feature.stream.ui.map.source.BatchHSource
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import local.phantom.companion.feature.stream.ui.map.world.WorldKind
import local.phantom.companion.feature.stream.ui.map.world.WorldObject
import local.phantom.companion.feature.stream.ui.map.world.WorldState
import local.phantom.companion.feature.stream.ui.map.world.placeWorld
import local.phantom.companion.feature.stream.ui.map.world.worldState

/**
 * Одна подія на мапі, готова до шару: клас несе силует, курс — брус воріт,
 * провенанс — крапку живого тону. Підпис тут не малюється: він відповідає на
 * тап, і саме тому мусить бути перекладеним, а не зліпленим у сесії.
 */
internal data class EventMark(
    val id: String,
    val lat: Double,
    val lon: Double,
    /** Клас події; null — місце світу з POI (аптека, АЗС), не подія. */
    val kind: MarkKind?,
    val label: String,
    val community: Boolean,
    val bearingDeg: Double?,
    /** Один рядок фактажу для картки мітки; несуть лише мітки шару. */
    val detail: String? = null,
    /** Мітка шару «позначки на мапі»: тап відкриває картку мітки, не досьє. */
    val mark: Boolean = false,
    /** Вид у світі мапи — тіло, ранг, стани. */
    val world: WorldKind = WorldKind.of(kind?.id) ?: WorldKind.DANGER,
    val state: WorldState? = null,
    /** Справжні теги місця для досьє за тапом (лише місця з POI). */
    val poiCategory: String? = null,
    val poiSubcategory: String? = null,
    // Рішення розподілу — заповнює [placeWorld].
    val rank: Int = 0,
    val opacity: Float = 1f,
    val accent: Boolean = false,
    val breathe: Boolean = false,
)

/**
 * Мітка з шару «позначки на мапі», як її бачить жива сесія. Раніше це був
 * `Triple(lat, lon, label)` — і провенанс гинув саме тут: чия це мітка, знав
 * лише шар, а до знака доїжджали три числа.
 */
internal data class LayerMarkSighting(
    val lat: Double,
    val lon: Double,
    val kind: String,
    val source: String?,
)

/** Барʼєр коридору з інтелу маршруту; [bearingDeg] — курс шляху крізь вузол. */
internal data class CorridorBarrier(
    val lat: Double,
    val lon: Double,
    val kind: String,
    val bearingDeg: Double? = null,
)

internal fun hazardTypeOfClass(markClass: String): HazardType = when (markClass) {
    MARK_CLASS_CHECKPOINT -> HazardType.CHECKPOINT
    MARK_CLASS_GATE -> HazardType.GATE
    MARK_CLASS_CRASH -> HazardType.ACCIDENT_HOTSPOT
    MARK_CLASS_CAMERA -> HazardType.SPEED_CAMERA
    MARK_CLASS_SHELTER -> HazardType.SHELTER
    else -> HazardType.DANGER_ZONE
}

/** Вид мітки оператора («ДТП», «укриття») → клас знака. */
internal fun markHazardType(kind: String): HazardType = hazardTypeOfClass(hazardMarkClass(kind))

/** Тег `barrier=*` → клас знака. Ворота проріджуються, пункт пропуску — ніколи. */
internal fun barrierHazardType(osmKind: String): HazardType =
    hazardTypeOfClass(barrierMarkClass(osmKind))

private fun HazardType.markKind(): MarkKind = when (this) {
    HazardType.SPEED_CAMERA -> MarkKind.CAMERA
    HazardType.ACCIDENT_HOTSPOT -> MarkKind.CRASH
    HazardType.CHECKPOINT -> MarkKind.CHECKPOINT
    HazardType.GATE -> MarkKind.GATE
    HazardType.SHELTER -> MarkKind.SHELTER
    HazardType.DANGER_ZONE -> MarkKind.DANGER
    // Мертва арма: ніде не конструюється. Загальний клас, а не власний знак —
    // малювати восьме тіло заради того, чого немає, немає сенсу.
    HazardType.SPEED_BUMP -> MarkKind.DANGER
}

internal fun SafetyHazard.toEventMark(names: Map<MarkKind, String>): EventMark {
    val kind = type.markKind()
    return EventMark(
        id = id,
        lat = lat,
        lon = lon,
        kind = kind,
        // Порожній заголовок — це не втрата: клас і Є назвою («Шлагбаум»), а
        // власне імʼя приходить лише там, де його дала OSM або оператор.
        label = title.ifBlank { names[kind].orEmpty() },
        community = source == HAZARD_SOURCE_COMMUNITY,
        bearingDeg = bearingDeg,
    )
}

/**
 * Точки з POI-конвеєра, які насправді є подіями: барʼєри й камери. Категорія
 * `barrier` тепер приходить з живого Overpass (PoiRepository), а відбір за
 * підрядком назви лишається другим поясом — синтетичні імена дворових воріт
 * старших кешів категорії не мають.
 */
internal fun poiEventHazards(pois: List<PoiEntity>): List<SafetyHazard> = pois.mapNotNull { p ->
    val isCamera = p.subcategory == "speed_camera" || p.name.contains(BatchHSource.POI_NAME_CAMERA)
    val isBarrier = p.category == "barrier" ||
        p.name.contains(BatchHSource.POI_NAME_BARRIER) || p.name.contains(BatchHSource.POI_NAME_GATE)
    if (!isCamera && !isBarrier) return@mapNotNull null
    SafetyHazard(
        id = "poi_haz_${p.id}",
        lat = p.lat,
        lon = p.lon,
        title = p.name,
        type = when {
            isCamera -> HazardType.SPEED_CAMERA
            p.category == "barrier" -> barrierHazardType(p.subcategory.orEmpty())
            else -> HazardType.GATE
        },
    )
}

/** Клітинка ~11 м — «одна точка» для всіх злиттів сцени знаків. */
private fun eventCell(lat: Double, lon: Double): String =
    "${"%.4f".format(java.util.Locale.US, lat)}_${"%.4f".format(java.util.Locale.US, lon)}"

/** Одна точка — одна подія: живий барʼєр і той самий барʼєр із POI-кешу. */
internal fun mergeEventHazards(
    hazards: List<SafetyHazard>,
    pois: List<PoiEntity>,
): List<SafetyHazard> = (hazards + poiEventHazards(pois)).distinctBy { eventCell(it.lat, it.lon) }

/**
 * Мітки шару «позначки на мапі» → знаки, ПРЯМО зі сховища шарів. Це той самий
 * шар, який пишуть палець, world.report і жива площина; сесійний радар лише
 * перечитує його у власний зошит, і мапа, що чекала на цей зошит, показувала
 * порожнє місце там, де мітка вже лежала. Джерело правди для рендера — шар.
 */
internal fun hazardLayerEventMarks(
    layers: List<SpatialLayer>,
    names: Map<MarkKind, String>,
): List<EventMark> = layers
    .filter { it.visible && it.spec.kind == HAZARD_LAYER_KIND }
    .flatMap { it.spec.markers }
    // Старі мітки шару й чужі пристрої ще можуть нести «патруль» чи «блокпост».
    .filterNot { isRefusedMarkKind(it.label ?: it.glyph.orEmpty()) }
    .map { m ->
        val kind = MarkKind.of(hazardMarkClass(m.label ?: m.glyph.orEmpty()))
        EventMark(
            id = "mark_${m.lat}_${m.lon}",
            lat = m.lat,
            lon = m.lon,
            kind = kind,
            label = m.label ?: names[kind].orEmpty(),
            community = m.source == HAZARD_SOURCE_COMMUNITY,
            bearingDeg = m.bearing,
            detail = m.detail,
            mark = true,
        )
    }

/**
 * Сцена знаків цілком. Мітки шару йдуть повз воротаря доречності свідомо
 * (HazardGate: «ручні мітки тактичні, їх одиниці») і виграють клітинку у
 * власного відлуння з радара (haz_*) — лише мітка несе картку й провенанс.
 */
internal fun eventMarksScene(
    layers: List<SpatialLayer>,
    events: List<SafetyHazard>,
    zoom: Double,
    corridor: List<Pair<Double, Double>>,
    focus: Pair<Double, Double>?,
    names: Map<MarkKind, String>,
    scene: SceneMode = SceneMode.EXPLORE,
    /** Місця з POI, що мають тіло у світі (аптека, АЗС, укриття…). */
    places: List<PoiEntity> = emptyList(),
    /** Сирена над областю людини — укриття стають бурштиновими й дихають. */
    alertOverhead: Boolean = false,
    now: java.util.Calendar = java.util.Calendar.getInstance(),
): List<EventMark> {
    val marks = hazardLayerEventMarks(layers, names)
    val taken = marks.mapTo(HashSet()) { eventCell(it.lat, it.lon) }
    val gated = gateHazards(events, zoom, corridor, focus)
        .filterNot { eventCell(it.lat, it.lon) in taken }
    // ATLAS-2: режим вирішує доречність КЛАСУ (за кермом дворові ворота — не
    // факт про дорогу), а ранг лише переставляє джерело.
    val happenings = (marks + gated.map { it.toEventMark(names) })
        .filter { m -> m.kind?.let { EventLens.admits(scene, it) } ?: true }
        .map { it.copy(state = if (it.world == WorldKind.SHELTER && alertOverhead) WorldState.ALERT else it.state) }
    val takenAll = happenings.mapTo(HashSet()) { eventCell(it.lat, it.lon) }
    val world = places.mapNotNull { p -> placeMark(p, alertOverhead, now) }
        .filter { EventLens.admitsWorld(scene, it.world) && eventCell(it.lat, it.lon) !in takenAll }
    val all = (happenings + world).sortedBy { m -> m.kind?.let { EventLens.rank(scene, it) } ?: 1 }

    val target = corridor.lastOrNull()
    val byId = all.associateBy { it.id }
    val placed = placeWorld(
        objects = all.map { m ->
            WorldObject(
                id = m.id,
                kind = m.world,
                lat = m.lat,
                lon = m.lon,
                state = m.state,
                target = target != null && haversineMeters(target, m.lat to m.lon) <= TARGET_M,
                onRoute = corridor.isNotEmpty() && nearCorridor(m.lat, m.lon, corridor),
            )
        },
        zoom = zoom,
        focus = focus,
        routeActive = corridor.isNotEmpty(),
    )
    return placed.map { p ->
        byId.getValue(p.obj.id).copy(rank = p.rank, opacity = p.opacity, accent = p.accent, breathe = p.breathe)
    }
}

/**
 * Що лишається піном. Місце з тілом у світі стоїть лише там — на будь-якому
 * масштабі: поки відсів вмикався з z16, нижче те саме місце жило круглим піном,
 * а тіло його не заступало (A52, Poruba).
 */
internal fun pinnedPlaces(pois: List<PoiEntity>): List<PoiEntity> = pois.filter { WorldKind.ofPoi(it) == null }

/** Місце з POI як тіло світу, або null — у нього тіла немає. */
private fun placeMark(p: PoiEntity, alertOverhead: Boolean, now: java.util.Calendar): EventMark? {
    val kind = WorldKind.ofPoi(p) ?: return null
    return EventMark(
        id = "poi_${p.id}",
        lat = p.lat,
        lon = p.lon,
        kind = null,
        label = p.name,
        community = false,
        bearingDeg = null,
        world = kind,
        state = worldState(kind, p.tags, alertOverhead, now),
        poiCategory = p.category,
        poiSubcategory = p.subcategory,
    )
}

/** Мета маршруту — місце в межах цього від кінця лінії. */
private const val TARGET_M = 40.0

private fun nearCorridor(lat: Double, lon: Double, corridor: List<Pair<Double, Double>>): Boolean {
    val maxDeg = HazardGate.ROUTE_CORRIDOR_M / 111_320.0 * 1.5
    return corridor.any {
        kotlin.math.abs(it.first - lat) <= maxDeg && haversineMeters(it, lat to lon) <= HazardGate.ROUTE_CORRIDOR_M
    }
}

/**
 * Сцена знаків для шару движка.
 *
 * Просівання POI лишається на своєму старому ключі: воно перебирає тисячі
 * місць рядковим contains, і чіпляти його до камери означало б повний прохід
 * на кожну зупинку жесту. Від вьюпорта залежить лише дешевий відбір нижче, а
 * сам вьюпорт пишеться на OnCameraIdle, не на кожен кадр руху.
 */
@Composable
internal fun rememberEventMarksGeoJson(
    viewModel: MapViewModel,
    pois: List<PoiEntity>,
    routes: List<MapPayload.PolylineData>,
    viewport: Triple<Double, Double, Double>?,
    scene: SceneMode,
    alertOverhead: Boolean = false,
): String {
    val activeHazards by viewModel.activeHazards.collectAsStateWithLifecycle()
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val names = markClassNames()
    val corridor = remember(routes) {
        corridorSpine(routes.flatMap { r -> r.points.map { it.lat to it.lon } })
    }
    val merged = remember(activeHazards, pois) { mergeEventHazards(activeHazards, pois) }
    // Лише місця з тілом — щоб не перебирати тисячі POI на кожну зупинку камери.
    val places = remember(pois) { pois.filter { WorldKind.ofPoi(it) != null } }
    return remember(merged, layers, corridor, viewport, names, scene, places, alertOverhead) {
        eventMarksGeoJson(
            eventMarksScene(
                layers = layers,
                events = merged,
                zoom = viewport?.third ?: 0.0,
                corridor = corridor,
                focus = viewport?.let { it.first to it.second },
                names = names,
                scene = scene,
                places = places,
                alertOverhead = alertOverhead,
            ),
        )
    }
}

@Composable
private fun markClassNames(): Map<MarkKind, String> = mapOf(
    MarkKind.GATE to stringResource(R.string.mark_gate),
    MarkKind.CHECKPOINT to stringResource(R.string.mark_checkpoint),
    MarkKind.CRASH to stringResource(R.string.mark_crash),
    MarkKind.CAMERA to stringResource(R.string.mark_camera),
    MarkKind.SHELTER to stringResource(R.string.mark_shelter),
    MarkKind.DANGER to stringResource(R.string.mark_danger),
)
