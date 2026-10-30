package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.geo.indoor.BuildingIndoorEngine
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget

/**
 * THE ALLOW-LIST — the only two geo-anchored Compose overlays left on the map.
 *
 * D1's law says everything geo-anchored draws INSIDE the engine, in the same
 * frame as the ground; these two are the delamination the verdict named, and
 * they are here on borrowed time:
 *
 *  1. `IndoorFloorOverlay`  — tier `INDOOR_PLAN` reserved, migration ATLAS-1
 *  2. `TransitVehicleMarkerOverlay` — live vehicle badges
 *
 * `HazardMarkerOverlay` пішов: знаки подій малює тир `EVENT_MARKS` у движку
 * ([installEventMarks]), а разом із оверлеєм зник його `OnCameraMoveListener`,
 * що ганяв рекомпозицію на кожен кадр камери.
 *
 * Each keeps its own tap handler this wave (the single dispatcher owns the
 * engine's layers, not Compose hit-testing). `MapGeoOverlayFirewallTest` fixes
 * the count at two: a third fails the build, and each migration lowers it.
 */
@Composable
internal fun MapComposeOverlays(
    ui: MapUiState,
    viewModel: MapViewModel,
    transitRepository: TransitRepository?,
    active: Boolean,
) {
    val indoorEngine = remember { BuildingIndoorEngine() }
    LaunchedEffect(ui.viewportCenter, ui.dossierTarget, active) {
        if (!active) {
            ui.indoorPlan = null
            return@LaunchedEffect
        }
        delay(250L) // Debounce so pan gestures don't abort in-flight queries.
        val target = ui.dossierTarget
        val center = ui.viewportCenter
        val lat = target?.lat ?: center?.first ?: return@LaunchedEffect
        val lon = target?.lon ?: center?.second ?: return@LaunchedEffect
        val zoom = center?.third ?: 16.0
        // Zoomed into buildings, or a place is selected — the same two gates the
        // Lens states for the INDOOR_PLAN tier.
        if (target == null && zoom < INDOOR_AUTO_ZOOM) {
            ui.indoorPlan = null
            return@LaunchedEffect
        }
        // ПРИСВОЮЄМО ЗАВЖДИ, зокрема null. Тут стояло `?.let { … }`, і через це
        // «плану тут немає» мовчки викидалось, а на екрані лишався план ПОПЕРЕДНЬОГО
        // будинку. Кадр власника 01.09, 02:18: він удома, внутрішнього плану його
        // будинку не існує — а панель пропонує «ПОВЕРХ +2 / +1 / 1 / −1».
        //
        // Панель поверхів існує лише там, де є внутрішній план. Немає плану —
        // немає панелі: не сіра, не порожня, а відсутня. Порожня відповідь — це
        // теж відповідь, і вона мусить доходити.
        ui.indoorPlan = fetchIndoorPlan(indoorEngine, ui.mapRef, lat, lon)
    }

    local.phantom.companion.feature.stream.ui.map.indoor.IndoorFloorOverlay(
        map = ui.mapRef,
        plan = ui.indoorPlan,
        currentLevel = ui.selectedIndoorLevel,
        enabled = active,
        onEntranceClick = { ent ->
            ui.dossierTarget = DossierTarget(
                lat = ent.lat,
                lon = ent.lon,
                nameHint = ent.name ?: ent.kind.labelUk,
                categoryHint = "entrance",
            )
        },
        onRoomClick = { room ->
            ui.dossierTarget = DossierTarget(
                lat = room.polygon.takeIf { it.isNotEmpty() }?.map { it.first }?.average() ?: 0.0,
                lon = room.polygon.takeIf { it.isNotEmpty() }?.map { it.second }?.average() ?: 0.0,
                nameHint = room.name ?: room.category ?: MapText.s(R.string.mapl_i_room),
                categoryHint = room.category ?: "shop",
            )
        },
        onPassageClick = { passage ->
            val pt = passage.points.firstOrNull() ?: return@IndoorFloorOverlay
            ui.dossierTarget = DossierTarget(
                lat = pt.first,
                lon = pt.second,
                nameHint = passage.name ?: passage.kind.labelUk,
                categoryHint = "transit",
            )
        },
    )

    if (transitRepository != null && active) {
        local.phantom.companion.feature.stream.ui.map.transit.TransitVehicleMarkerOverlay(
            map = ui.mapRef,
            repository = transitRepository,
            enabled = active,
            onVehicleClick = { viewModel.selectVehicle(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Indoor is a building-scale fact; below this there is no building. */
private const val INDOOR_AUTO_ZOOM = 16.5
