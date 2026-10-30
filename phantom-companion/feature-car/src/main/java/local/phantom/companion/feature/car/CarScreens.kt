package local.phantom.companion.feature.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HIGHWAY — the car screens.
 *
 * Three, and no more, because a driver cannot be given a menu: the categories
 * he actually wants at 90 km/h ([CarHomeScreen]), the results ([CarPlacesScreen])
 * and a keyboard for the rare case a chip is not enough ([CarSearchScreen]).
 *
 * Every screen is asynchronous over [CarPorts] and re-invalidates when the
 * answer lands. That is the required shape: `onGetTemplate` is called by the
 * host on ITS thread and must return instantly — blocking it to run a database
 * query is how a car app gets killed by the host's ANR watchdog.
 */
internal class CarHomeScreen(
    carContext: CarContext,
    private val ports: CarPorts,
) : Screen(carContext), DefaultLifecycleObserver {

    private var nearest: Map<CarCategory, Nearest> =
        CarCategory.entries.associateWith { Nearest.Asking }
    private var probe: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    /**
     * Kept asking while the grid is on screen, rather than answered once.
     * A distance measured when the car was plugged in is wrong a minute later,
     * and a head unit opened before the first fix would otherwise show four
     * tiles that never learn anything.
     */
    override fun onStart(owner: LifecycleOwner) {
        probe?.cancel()
        probe = owner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(CarCopy.GRID_REFRESH_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        probe?.cancel()
        probe = null
    }

    /**
     * How far the nearest one of each kind is. Off the template thread and
     * after the grid is already drawn — the host kills an app that blocks
     * `onGetTemplate`, and four 40 km sweeps of the gazetteer are not free.
     */
    private suspend fun refresh() {
        val fix = ports.currentLocation()
        val next = CarCategory.entries.associateWith { category ->
            if (fix == null) Nearest.NoFix else nearestOf(fix, category)
        }
        // Compared before invalidating: driving changes the metres continuously
        // but «3,4 км» only now and then, and the host redraws on every call.
        if (next.mapValues { CarCopy.gridSubtitle(it.value) } ==
            nearest.mapValues { CarCopy.gridSubtitle(it.value) }
        ) {
            return
        }
        nearest = next
        invalidate()
    }

    private suspend fun nearestOf(fix: CarFix, category: CarCategory): Nearest {
        val found = runCatching {
            ports.search(
                query = "",
                lat = fix.lat,
                lon = fix.lon,
                radiusMeters = CarCategory.RADIUS_METERS,
                subcategories = category.subcategories,
                limit = CarCopy.MAX_ROWS,
            )
        }.getOrNull() ?: return Nearest.Asking
        val metres = found
            .map { it.distanceMeters }
            .filter { it.isFinite() && it >= 0.0 }
            .minOrNull()
        return if (metres == null) Nearest.NotFound else Nearest.Found(metres)
    }

    override fun onGetTemplate(): Template {
        val grid = ItemList.Builder().apply {
            CarCategory.entries.forEach { category ->
                val item = GridItem.Builder()
                    .setTitle(category.label)
                    .setImage(category.icon(), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        screenManager.push(CarPlacesScreen(carContext, ports, category, null))
                    }
                CarCopy.gridSubtitle(nearest[category] ?: Nearest.Asking)
                    ?.let { item.setText(it) }
                addItem(item.build())
            }
        }.build()

        return GridTemplate.Builder()
            .setTitle("Nightpost")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(grid)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Пошук")
                            .setOnClickListener {
                                screenManager.push(CarSearchScreen(carContext, ports))
                            }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    /** Tinted, so the host recolours it for its own day and night palettes. */
    private fun CarCategory.icon(): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, glyph.drawable()))
            .setTint(CarColor.DEFAULT)
            .build()
}

internal fun CarGlyph.drawable(): Int = when (this) {
    CarGlyph.FUEL -> R.drawable.ic_car_fuel
    CarGlyph.FOOD -> R.drawable.ic_car_food
    CarGlyph.PARKING -> R.drawable.ic_car_parking
    CarGlyph.PHARMACY -> R.drawable.ic_car_pharmacy
}

/**
 * Orders the rows by straight-line distance, and claims nothing more than that.
 *
 * Not «найближчі»: a station 3 km away across a river with no bridge for 20 km
 * sorts above one 5 km down the road being driven, and the driver reads the top
 * row. The order beats whatever the gazetteer happened to return, which is the
 * whole of its justification — the module's own copy is what refuses to dress
 * an OSM-point straight line up as a road distance.
 *
 * A place with no usable distance goes last and is marked as such on its own
 * row, rather than being sorted to an end by a sentinel nobody can see.
 */
internal fun nearbyFirst(places: List<CarPlace>): List<CarPlace> {
    val (measured, unknown) =
        places.partition { it.distanceMeters.isFinite() && it.distanceMeters >= 0.0 }
    return measured.sortedBy { it.distanceMeters } + unknown
}

/**
 * Results — a list beside a map, which is the one template Auto allows a
 * non-navigation app to show places on.
 *
 * The list is capped at [CarCopy.MAX_ROWS] by the host, and would be capped
 * here anyway: the answer to "where is fuel" is the nearest one, and the next
 * five are only there in case it is closed.
 */
internal class CarPlacesScreen(
    carContext: CarContext,
    private val ports: CarPorts,
    private val category: CarCategory?,
    private val query: String?,
) : Screen(carContext) {

    private var places: List<CarPlace> = emptyList()
    private var loading = true
    private var message: String? = null

    init {
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val fix = ports.currentLocation()
        if (fix == null) {
            // No trusted fix — see FixGrade. A car head that guesses the
            // operator's position guesses his fuel stop too.
            loading = false
            message = CarCopy.emptyMessage(
                gazetteerSize = runCatching { ports.placesKnown() }.getOrDefault(0),
                hadFix = false,
            )
            invalidate()
            return
        }

        places = nearbyFirst(
            runCatching {
                ports.search(
                    query = query.orEmpty(),
                    lat = fix.lat,
                    lon = fix.lon,
                    radiusMeters = CarCategory.RADIUS_METERS,
                    subcategories = category?.subcategories ?: emptyList(),
                    limit = CarCopy.MAX_ROWS,
                )
            }.getOrDefault(emptyList()),
        )

        loading = false
        message = if (places.isEmpty()) {
            // Розмір довідника ПИТАЄМО, а не вигадуємо. Донедавна тут стояв
            // літерал `1`, і гілка «я ще не бачив цих місць» була недосяжна за
            // побудовою: водій із порожнім довідником читав «поруч такого
            // немає» — тобто продукт звинувачував місцевість у тому, чого сам
            // ніколи не завантажив. Запит іде лише на порожній видачі, тож
            // ціни на щасливому шляху немає.
            CarCopy.emptyMessage(
                gazetteerSize = runCatching { ports.placesKnown() }.getOrDefault(0),
                hadFix = true,
            )
        } else {
            null
        }
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val title = category?.label ?: query.orEmpty().ifBlank { "Пошук" }

        if (loading || places.isEmpty()) {
            return androidx.car.app.model.MessageTemplate.Builder(
                message ?: "шукаю…",
            )
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val list = ItemList.Builder().apply {
            places.forEach { place ->
                addItem(
                    Row.Builder()
                        .setTitle(place.name)
                        .addText(CarCopy.subtitle(place))
                        .setPlaceMetadata(place)
                        .setOnClickListener {
                            lifecycleScope.launch {
                                ports.navigateTo(place)
                                // The drive is not ours to run — hand it over
                                // and get out of the driver's way.
                                screenManager.popToRoot()
                            }
                        }
                        .setBrowsable(false)
                        .build(),
                )
            }
        }.build()

        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setItemList(list)
            .build()
    }

    /** Pins the row on the template's map. */
    private fun Row.Builder.setPlaceMetadata(place: CarPlace): Row.Builder = setMetadata(
        Metadata.Builder()
            .setPlace(
                Place.Builder(CarLocation.create(place.lat, place.lon))
                    .setMarker(PlaceMarker.Builder().build())
                    .build(),
            )
            .build(),
    )
}

/** The keyboard, for when a chip is not what he meant. */
internal class CarSearchScreen(
    carContext: CarContext,
    private val ports: CarPorts,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchSubmitted(searchText: String) {
                    screenManager.push(CarPlacesScreen(carContext, ports, null, searchText))
                }
            },
        )
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false)
            .setSearchHint("АЗС, кава, аптека…")
            .build()
}
