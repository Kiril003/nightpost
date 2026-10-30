package local.phantom.companion.feature.stream.ui.map.mount

import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.RoutePoint
import local.phantom.companion.core.nav.RouteRegister

/**
 * design-critic.md §S7 — the phone clamped to a windscreen has no host to
 * confine what it shows. This is the reduction: heading, distance-or-time to
 * the next decision point, worst-register chip, each already resolved to its
 * honest-empty variant. Pure derivation from [GuidanceState] + the
 * register-tagged route line + the device compass — nothing here reads a
 * theme or draws a pixel, so it is testable without Compose.
 */
internal sealed interface MountHeading {
    data class Maneuver(val progress: GuidanceProgress) : MountHeading
    data class Compass(val bearingDeg: Float) : MountHeading
    data object NoFix : MountHeading
}

internal sealed interface MountDistance {
    data class ToManeuver(val meters: Double) : MountDistance
    data object OffRoute : MountDistance
    data object Arrived : MountDistance

    /** Guiding started but the first fix hasn't landed yet — mirrors
     *  ManeuverBanner's own "чекаю GPS" branch for `Guiding(progress = null)`. */
    data object Waiting : MountDistance

    /** No active guidance at all — the normal state for most of a drive on
     *  a device-symbiosis product; never a blank countdown. */
    data object NoRoute : MountDistance
}

internal data class MountGlance(
    val heading: MountHeading,
    val distance: MountDistance,
    val worstRegister: RouteRegister?,
)

internal fun deriveMountGlance(
    guidance: GuidanceState,
    routeLine: List<RoutePoint>,
    bearingDeg: Float,
    hasFix: Boolean,
): MountGlance {
    val progress = (guidance as? GuidanceState.Guiding)?.progress
    val heading = when {
        progress != null -> MountHeading.Maneuver(progress)
        hasFix -> MountHeading.Compass(bearingDeg)
        else -> MountHeading.NoFix
    }
    val distance = when {
        guidance is GuidanceState.Arrived -> MountDistance.Arrived
        progress != null && progress.offRoute -> MountDistance.OffRoute
        progress != null -> MountDistance.ToManeuver(progress.distanceToManeuverM)
        guidance is GuidanceState.Guiding -> MountDistance.Waiting
        else -> MountDistance.NoRoute
    }
    return MountGlance(heading, distance, worstRouteRegister(routeLine))
}

/**
 * GUESSED and REMEMBERED both mean "trust this less" ([RouteRegister]'s own
 * `isUncertain()`); map-register-schema.md's downgrade rule never ranks the
 * two against each other, so GUESSED-wins-ties is this screen's own
 * tie-break, not an inherited one.
 */
internal fun worstRouteRegister(routeLine: List<RoutePoint>): RouteRegister? {
    if (routeLine.isEmpty()) return null
    val present = routeLine.mapTo(HashSet()) { it.register }
    return when {
        RouteRegister.GUESSED in present -> RouteRegister.GUESSED
        RouteRegister.REMEMBERED in present -> RouteRegister.REMEMBERED
        RouteRegister.DERIVED in present -> RouteRegister.DERIVED
        else -> RouteRegister.MEASURED
    }
}
