package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.TransitMode

/** How the operator gets to the first stop, or away from the last one. */
enum class AccessMode { WALK, DRIVE }

/**
 * MULTIMODAL CONTINUUM — one complete door-to-door answer.
 *
 * Deliberately a flat list of legs rather than a nest: the UI reads it as a
 * ribbon, PHANTOM reads it as a sentence, and neither should have to
 * understand a tree to do so.
 */
data class Itinerary(
    val legs: List<Leg>,
    /** Departure from the origin door. Seconds since local midnight. */
    val departSec: Int,
    /** Arrival at the destination door. May exceed 86_400 — see Timetable. */
    val arriveSec: Int,
    val transfers: Int,
    /**
     * True when any leg's times are modelled rather than scheduled — today
     * that is every ride, metro and surface alike: both networks are
     * synthesized from OSM headway models. The UI must degrade its confidence
     * out loud rather than present a modelled minute as a promise.
     */
    val hasModelledTimes: Boolean,
) {
    val durationSec: Int get() = arriveSec - departSec

    /** Does the operator leave a car somewhere in the middle of this? */
    val isParkAndRide: Boolean
        get() = legs.any { it is Leg.Access && it.mode == AccessMode.DRIVE } &&
            legs.any { it is Leg.Ride }

    /** The modes actually ridden, in order — the spine of the UI's ribbon. */
    val ridden: List<TransitMode> get() = legs.filterIsInstance<Leg.Ride>().map { it.mode }

    sealed interface Leg {
        val seconds: Int

        /** Origin → a stop, or the last stop → destination, on foot or by car. */
        data class Access(
            val mode: AccessMode,
            val fromLat: Double, val fromLon: Double,
            val toLat: Double, val toLon: Double,
            /** Blank when this leg ends at the destination door rather than a stop. */
            val toStopName: String,
            override val seconds: Int,
            val meters: Int,
            /** Road geometry when a real router produced it; empty when estimated. */
            val points: List<Pair<Double, Double>> = emptyList(),
            /**
             * True when [seconds] is a crow-fly estimate because no road
             * router was reachable — the honest degraded state, not a lie.
             */
            val estimated: Boolean = false,
            /** Seconds budgeted for parking, folded into [seconds]. 0 when walking. */
            val parkingSeconds: Int = 0,
            /** A city-declared lot with capacity, rather than "find a space". */
            val knownLot: Boolean = false,
        ) : Leg

        /** A vehicle, boarded and ridden. */
        data class Ride(
            val routeLabel: String,
            val mode: TransitMode,
            /** 0xRRGGBB, or -1 when the feed declares none. */
            val colour: Int,
            val fromStopName: String,
            val toStopName: String,
            val fromLat: Double, val fromLon: Double,
            val toLat: Double, val toLon: Double,
            val departSec: Int,
            val arriveSec: Int,
            /** Stops passed through, excluding both ends. */
            val intermediateStops: Int,
            /**
             * The ride's path THROUGH its stop chain, board→alight inclusive.
             * Chained stop positions, not road/track geometry — honest enough
             * to draw a route that bends where the line actually goes, instead
             * of a straight slash across the city.
             */
            val points: List<Pair<Double, Double>> = emptyList(),
            /**
             * True when this vehicle's times come from a model, not a
             * schedule. Decided by where the minutes came from (headway
             * expansion vs published stop_times), never by vehicle kind.
             */
            val timesAreModelled: Boolean,
            /** Куди написано на табличці машини; null — джерело не сказало. */
            val headsign: String? = null,
            val boardPlatform: String? = null,
            val alightPlatform: String? = null,
            /** Проміжні зупинки з координатами — по них наведення знає, коли «наступна ваша». */
            val stops: List<RideStop> = emptyList(),
            /** Ручка рейсу для розгортання; людині не показується. */
            val tripRef: String? = null,
            val operatorName: String? = null,
            /** Затримка за живими даними, секунди; null — живих даних нема. */
            val liveDelaySec: Int? = null,
        ) : Leg {
            override val seconds: Int get() = arriveSec - departSec
        }

        data class RideStop(val name: String, val lat: Double, val lon: Double, val arriveSec: Int? = null)

        /** Changing vehicle on foot — including a metro interchange tunnel. */
        data class Transfer(
            val fromStopName: String,
            val toStopName: String,
            override val seconds: Int,
        ) : Leg
    }
}
