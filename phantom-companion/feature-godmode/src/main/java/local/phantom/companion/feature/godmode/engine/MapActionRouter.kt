package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * MAP-AI-3 — process-singleton bus that translates AI-emitted map verbs
 * ([AppAction.NavigateTo] / [AppAction.SearchPlace] / [AppAction.ShowOnMap] /
 * [AppAction.SaveOfflineRegion]) into typed [MapIntent] events that
 * `feature-stream` consumers subscribe to.
 *
 * Decouples `feature-godmode` from MapLibre, Compose, and Room — the
 * router itself is pure Kotlin coroutines and inert: subscribers
 * implement the side-effect (open MapWidget, run Nominatim search,
 * render polyline, kick off OfflineRegionsManager download).
 *
 * Why a singleton SharedFlow instead of a callback DI:
 *
 *  • The AccessibilityActionDispatcher already exists with a fixed
 *    constructor; we don't want to plumb a new dependency through
 *    AppActionTraceBus / ScreenAwarenessService / etc.
 *  • Multiple surfaces may want to observe (in-chat MapWidget,
 *    Settings → My Places preview, future Symbiote audit). SharedFlow
 *    fan-out is the natural pattern.
 *  • Test isolation: tests collect the events directly; production
 *    StreamViewModel collects them via a launched coroutine.
 *
 * Buffer cap 16 — enough for a burst of map-related verbs in a single
 * dispatch (the AI rarely emits more than 1-2 per turn), and trimmed
 * by oldest-first so a slow consumer doesn't pile up history.
 */
object MapActionRouter {

    private val _events = MutableSharedFlow<MapIntent>(extraBufferCapacity = 16)

    /** Hot stream of typed map intents. Multi-subscriber-safe. */
    val events: SharedFlow<MapIntent> = _events.asSharedFlow()

    /**
     * Emit a typed [MapIntent] for the given [AppAction]. Returns
     * `true` when the action is a map verb and was emitted, `false`
     * when it is not a map verb (caller routes to the accessibility
     * controller instead) or when the buffer was full.
     */
    fun emit(action: AppAction): Boolean = when (action) {
        is AppAction.NavigateTo -> _events.tryEmit(
            MapIntent.Navigate(
                latitude = action.latitude,
                longitude = action.longitude,
                mode = action.mode,
                label = action.label,
            )
        )
        is AppAction.SearchPlace -> _events.tryEmit(MapIntent.Search(query = action.query))
        is AppAction.ShowOnMap -> _events.tryEmit(MapIntent.Show(geoJson = action.geoJson))
        is AppAction.SaveOfflineRegion -> _events.tryEmit(
            MapIntent.SaveRegion(
                north = action.north,
                east = action.east,
                south = action.south,
                west = action.west,
                name = action.name,
            )
        )
        else -> false
    }

    /**
     * Pure predicate: is [action] one of the four MAP-AI-3 verbs? The
     * dispatcher uses this to route past the [AccessibilityActionController]
     * (which knows nothing about map widgets).
     */
    fun isMapAction(action: AppAction): Boolean = when (action) {
        is AppAction.NavigateTo,
        is AppAction.SearchPlace,
        is AppAction.ShowOnMap,
        is AppAction.SaveOfflineRegion -> true
        else -> false
    }
}

/**
 * Typed intent emitted by [MapActionRouter]. The receiving surface
 * decides how to act — render markers, draw polylines, trigger an
 * offline-tile download. Sealed hierarchy keeps the consumer
 * exhaustive.
 */
sealed class MapIntent {

    /** Open a route / recenter the map on (latitude, longitude). */
    data class Navigate(
        val latitude: Double,
        val longitude: Double,
        val mode: TravelMode,
        val label: String?,
    ) : MapIntent()

    /** Run a free-text place search; pin results on the MapWidget. */
    data class Search(val query: String) : MapIntent()

    /** Render arbitrary GeoJSON geometry on the MapWidget. */
    data class Show(val geoJson: String) : MapIntent()

    /**
     * Download tile pack for an axis-aligned bbox. Mutation: receivers
     * are encouraged to confirm with the operator (e.g. via the
     * existing question widget) before committing.
     */
    data class SaveRegion(
        val north: Double,
        val east: Double,
        val south: Double,
        val west: Double,
        val name: String?,
    ) : MapIntent()
}
