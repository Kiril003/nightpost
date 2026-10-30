package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteOrigin
import local.phantom.companion.core.net.map.RouteVariant

/**
 * How a route point's road was earned — map-register-schema.md §1's
 * measured/derived/guessed/remembered grammar, extended from map tiles to
 * the line PHANTOM is actually driving.
 *
 * MEASURED and REMEMBERED are not produced anywhere in this module today:
 * MEASURED needs a survey-grade source no client here has, and REMEMBERED
 * needs the occupied/front `status_flags` authority list
 * map-register-schema.md §5 describes. Both stay in the enum as the seam a
 * future pack-fed classifier plugs into — not a claim that either fires yet.
 */
enum class RouteRegister { MEASURED, DERIVED, GUESSED, REMEMBERED }

/** True for the two tiers a driver should trust less — the only distinction
 *  the drawn line or a spoken cue ever acts on (map-register-schema.md §5's
 *  `downgrade()`, reduced here to what a glanceable Canvas can carry). */
fun RouteRegister.isUncertain(): Boolean = this == RouteRegister.GUESSED || this == RouteRegister.REMEMBERED

/** One point of a route line, carrying the register its road was drawn with. */
data class RoutePoint(val lat: Double, val lon: Double, val register: RouteRegister)

/**
 * ATLAS-2.5 §11 — the CEILING this variant's register may reach, decided by
 * what its router could see of the road law.
 *
 * An offline mesh route is GUESSED however beautifully its roads are named:
 * the way mesh carries geometry and tags, never the turn-restriction
 * relations, so «поверніть ліворуч» over it is a guess about legality wearing
 * the clothes of an instruction — the owner's штраф on the Житомирська траса.
 * An online profile did read those relations, so DERIVED is the honest
 * ceiling. Nothing here ever claims MEASURED: no router in this app surveys
 * anything.
 *
 * This states ignorance only; it invents no legal fact of its own.
 */
fun RouteVariant.legalityCeiling(): RouteRegister = when (origin) {
    RouteOrigin.OFFLINE_MESH -> RouteRegister.GUESSED
    RouteOrigin.ONLINE_ROUTER -> RouteRegister.DERIVED
}

/** True while this line's turn instructions are unbacked by a restriction model. */
fun RouteVariant.hasGuessedTurnLaw(): Boolean = legalityCeiling() == RouteRegister.GUESSED

/** Мішанина ніг: незнання перемагає — одна офлайнова нога робить увесь маршрут офлайновим. */
internal fun worstOrigin(a: RouteOrigin, b: RouteOrigin): RouteOrigin =
    if (a == RouteOrigin.OFFLINE_MESH || b == RouteOrigin.OFFLINE_MESH) {
        RouteOrigin.OFFLINE_MESH
    } else {
        RouteOrigin.ONLINE_ROUTER
    }
