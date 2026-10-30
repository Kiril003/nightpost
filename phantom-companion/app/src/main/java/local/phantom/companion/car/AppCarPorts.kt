package local.phantom.companion.car

import local.phantom.companion.data.AppContainer
import local.phantom.companion.feature.car.CarFix
import local.phantom.companion.feature.car.CarPlace
import local.phantom.companion.feature.car.CarPorts

/**
 * HIGHWAY — the car head's only contact with PHANTOM.
 *
 * Everything here comes off [AppContainer], which is application-scoped, and
 * nothing off a ViewModel or a composition: the Android Auto host starts the
 * car process on its own schedule, and the phone's Activity is usually not
 * running when it does. A port backed by a screen would be a port backed by
 * nothing.
 */
class AppCarPorts(private val container: AppContainer) : CarPorts {

    override suspend fun currentLocation(): CarFix? =
        container.locationProvider.current()?.let { CarFix(it.lat, it.lon) }

    override suspend fun search(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        subcategories: List<String>,
        limit: Int,
    ): List<CarPlace> = container.poiRepository.searchOffline(
        query = query,
        lat = lat,
        lon = lon,
        radiusMeters = radiusMeters,
        subcategories = subcategories,
        limit = limit,
    ).map { hit ->
        CarPlace(
            id = hit.id,
            name = hit.name,
            lat = hit.lat,
            lon = hit.lon,
            distanceMeters = hit.distanceMeters,
            subtitle = hit.subcategory,
        )
    }

    /**
     * State the destination; do not drive.
     *
     * `navCommands` has `replay = 1`, which is exactly the semantics the car
     * needs and for a reason worth stating: the operator taps «АЗС» on the car
     * screen while the phone is asleep in the cupholder, so there may be no
     * collector at all at this instant. The command waits.
     *
     * The collector is [local.phantom.companion.core.nav.NavigationEngine] on
     * the container's scope, so a destination chosen on the car head starts a
     * real route with no Activity of ours alive. The map still collects the
     * same flow for the arrival deadline, which is a map-surface concern.
     */
    override suspend fun navigateTo(place: CarPlace) {
        container.navCommands.emit(
            local.phantom.companion.core.nav.NavCommand.Start(
                toLat = place.lat,
                toLon = place.lon,
                profile = "авто",
            ),
        )
    }

    override suspend fun placesKnown(): Int = container.poiRepository.corpusSize()

    override suspend fun stopDrive() {
        container.navCommands.emit(local.phantom.companion.core.nav.NavCommand.Stop)
    }
}
