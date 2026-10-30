package local.phantom.companion.feature.stream.ui.map.transit

import local.phantom.companion.core.data.transit.TransitPlane
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.data.transit.surface.SurfaceLine
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine

/**
 * Хто має право рухатись по склу, і що картка машини має право сказати.
 *
 * Польовий вердикт 23.08: власник не зустрів ЖОДНОЇ машини з тих, що їхали
 * по його мапі. Модельний флот рахує позиції з розкладу — на вулиці такої
 * машини немає, і кутова тильда цього не рятувала: мітку, до якої не можна
 * підійти, людина читає як обіцянку, а не як припущення. Тож ярус розкладів
 * лишається там, де він чесний (табло, маршрути, хаб), а скло бачить лише
 * спостережений GTFS-RT фікс.
 */

/** Чи цьому ярусу дозволено флот — і на склі, і в циклі опитування. */
internal fun feedsGlassFleet(plane: TransitPlane): Boolean = plane == TransitPlane.LIVE_FEED

/** Друга лінія тієї ж стіни: пігулку отримує лише спостережена позиція. */
internal fun glassFleet(vehicles: List<TransitVehicle>): List<TransitVehicle> =
    vehicles.filter { it.isLiveGps }

/**
 * Те, що картка живої машини має право показати.
 *
 * Порожнє поле — це відсутній рядок, ніколи не здогадка: напрямок і
 * геометрію дає лише модель, яка справді описує місто під ЦІЄЮ машиною.
 * Збіг номера «36» за півтисячі кілометрів — випадковість, не маршрут.
 */
internal data class TransitVehicleFacts(
    val routeLabel: String,
    val direction: String?,
    val line: DossierTransitLine?,
)

internal fun transitVehicleFacts(
    vehicle: TransitVehicle,
    routePrefix: String,
    surface: SurfaceFeed?,
): TransitVehicleFacts {
    val known = surface
        ?.takeIf { it.bounds?.contains(vehicle.lat, vehicle.lon) == true }
        ?.doc?.lines?.firstOrNull { it.matchesRoute(vehicle.routeLabel) }
    return TransitVehicleFacts(
        routeLabel = transitPillRoute(vehicle.routeLabel, routePrefix),
        direction = known?.name?.takeIf { it.isNotBlank() },
        // Дія «лінія на мапі» зʼявляється лише з геометрією в руках: кнопка,
        // що завжди відповідає «геометрії немає», — це не дія.
        line = known?.takeIf { it.shape != null }?.let {
            DossierTransitLine(
                ref = it.ref,
                mode = it.mode,
                direction = it.name.takeIf { name -> name.isNotBlank() },
                headwayMinutes = it.headway.day / 60,
                shape = it.shape,
            )
        },
    )
}

private fun SurfaceLine.matchesRoute(routeId: String?): Boolean {
    val other = routeId?.trim().orEmpty()
    return other.isNotEmpty() && other.equals(ref.trim(), ignoreCase = true)
}

/**
 * Вкладки, на які цей ярус справді має відповідь.
 *
 * [modelCovers] — чи точка оператора всередині мережі, яку описує пак. Поза
 * нею київські маршрути в хабі — така сама вигадка, як київська машина на
 * склі Львова.
 */
internal fun hubTabs(
    plane: TransitPlane,
    modelCovers: Boolean,
    hasMetro: Boolean,
): List<HubTab> = buildList {
    if (modelCovers) {
        add(HubTab.NEARBY)
        add(HubTab.ROUTES)
        if (hasMetro) add(HubTab.METRO)
    }
    if (feedsGlassFleet(plane)) add(HubTab.FLEET)
}
