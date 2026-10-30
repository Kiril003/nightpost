package local.phantom.companion.core.nav

import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.map.RouteStep

/**
 * Кроки наведення для поїздки транспортом. Без них сесія трималась за лінію
 * без жодного маневру й мовчала всю дорогу.
 *
 * Речення кладеться в [RouteStep.roadName]: тип із префіксом [PREFIX] каже
 * фразникові не будувати «поверніть на …», а сказати його як є.
 */
object TransitSteps {

    const val PREFIX = "transit "
    const val WALK = PREFIX + "walk"
    const val BOARD = PREFIX + "board"
    const val ALIGHT_NEXT = PREFIX + "alight next"
    const val ALIGHT = PREFIX + "alight"

    private const val WALK_UK = "Ідіть до зупинки "
    private const val BOARD_UK = "Сідайте на "
    private const val TOWARD_UK = " у напрямку "
    private const val ALIGHT_NEXT_UK = "Виходьте на наступній — "
    private const val ALIGHT_UK = "Виходьте: "
    private const val TRANSFER_UK = " Далі пересадка: "
    private const val ON_FOOT_UK = " Далі пішки."
    private const val RIDE_UK = "Їхати "
    private val RIDE = Regex("^$RIDE_UK(\\d+) \\S+ до (.+)$")

    fun isTransit(step: RouteStep): Boolean = step.type.startsWith(PREFIX)

    fun of(itin: Itinerary): List<RouteStep> {
        val out = ArrayList<RouteStep>()
        val legs = itin.legs
        legs.forEachIndexed { i, leg ->
            when (leg) {
                is Itinerary.Leg.Access -> if (i == 0 && leg.toStopName.isNotBlank()) {
                    out += step(WALK, WALK_UK + leg.toStopName, leg.fromLat, leg.fromLon, leg.seconds, leg.meters.toDouble())
                }
                is Itinerary.Leg.Ride -> {
                    out += step(BOARD, boardSentence(leg), leg.fromLat, leg.fromLon, leg.seconds, 0.0)
                    val last = leg.stops.lastOrNull()
                    if (last != null) {
                        out += step(
                            ALIGHT_NEXT,
                            ALIGHT_NEXT_UK + leg.toStopName,
                            (last.lat + leg.toLat) / 2,
                            (last.lon + leg.toLon) / 2,
                            0,
                            0.0,
                        )
                    }
                    val nextRide = legs.drop(i + 1).firstOrNull { it is Itinerary.Leg.Ride } as? Itinerary.Leg.Ride
                    val tail = when {
                        nextRide != null -> "$TRANSFER_UK${modeAccusative(nextRide.mode)} ${nextRide.routeLabel}".trimEnd() + "."
                        i == legs.lastIndex -> ""
                        else -> ON_FOOT_UK
                    }
                    out += step(ALIGHT, "$ALIGHT_UK${leg.toStopName}.$tail", leg.toLat, leg.toLon, 0, 0.0)
                }
                is Itinerary.Leg.Transfer -> Unit
            }
        }
        val end = legs.lastOrNull()
        val endAt = when (end) {
            is Itinerary.Leg.Access -> end.toLat to end.toLon
            is Itinerary.Leg.Ride -> end.toLat to end.toLon
            else -> null
        }
        if (endAt != null) {
            out += RouteStep("arrive", null, "", 0.0, 0.0, endAt.first, endAt.second)
        }
        return out
    }

    internal fun boardSentence(ride: Itinerary.Leg.Ride): String = buildString {
        append(BOARD_UK)
        append(modeAccusative(ride.mode))
        if (ride.routeLabel.isNotBlank()) append(' ').append(ride.routeLabel)
        ride.headsign?.takeIf { it.isNotBlank() }?.let { append(TOWARD_UK).append(it) }
        ride.boardPlatform?.takeIf { it.isNotBlank() }?.let { append(", ").append(platformWord(ride.mode)).append(' ').append(it) }
        append(". ")
        val count = ride.intermediateStops + 1
        append(RIDE_UK).append(count).append(' ').append(stopsWord(count))
        append(" до ").append(ride.toStopName).append('.')
    }

    internal fun modeAccusative(mode: TransitMode): String = when (mode) {
        TransitMode.METRO -> "метро"
        TransitMode.TRAM -> "трамвай"
        TransitMode.TROLLEYBUS -> "тролейбус"
        TransitMode.BUS -> "автобус"
        TransitMode.RAIL -> "поїзд"
        TransitMode.FERRY -> "пором"
        TransitMode.FUNICULAR -> "фунікулер"
        TransitMode.OTHER -> "транспорт"
    }

    /**
     * The screen line in English. Guidance still speaks the Ukrainian sentence
     * in [RouteStep.roadName], so the stop and line are read back from it.
     */
    fun shortEn(type: String, sentence: String): String {
        val head = headUk(type, sentence)
        return when (type) {
            WALK -> head.after(WALK_UK)?.let { "Walk to $it" } ?: "Walk to the stop"
            BOARD -> head.after(BOARD_UK)?.let(::boardEn) ?: "Board"
            ALIGHT_NEXT -> head.after(ALIGHT_NEXT_UK)?.let { "Get off at the next stop, $it" } ?: "Get off at the next stop"
            ALIGHT -> head.after(ALIGHT_UK)?.let { "Get off at $it" } ?: "Get off"
            else -> "Continue"
        }
    }

    /** The instruction alone, without the sentence that follows it. */
    fun headUk(type: String, sentence: String): String = split(type, sentence).first

    /** What follows the instruction («Їхати 4 зупинки до …»), in the app language; null when nothing does. */
    fun detail(type: String, sentence: String, english: Boolean): String? {
        val rest = split(type, sentence).second ?: return null
        if (!english) return rest
        return when (type) {
            BOARD -> RIDE.matchEntire(rest)?.destructured?.let { (n, stop) ->
                "Ride $n ${if (n == "1") "stop" else "stops"} to $stop"
            }
            ALIGHT -> rest.after(TRANSFER_UK.trimStart())?.let(::changeEn) ?: "Then walk"
            else -> null
        }
    }

    private fun split(type: String, sentence: String): Pair<String, String?> {
        val cut = when (type) {
            BOARD -> sentence.indexOf(". $RIDE_UK")
            ALIGHT -> maxOf(sentence.lastIndexOf(".$TRANSFER_UK"), sentence.lastIndexOf(".$ON_FOOT_UK"))
            else -> -1
        }
        if (cut < 0) return sentence.removeSuffix(".") to null
        return sentence.take(cut) to sentence.drop(cut + 2).removeSuffix(".").takeIf { it.isNotBlank() }
    }

    private fun String.after(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix).takeIf { it.isNotBlank() } else null

    private fun modeOf(text: String): TransitMode? = TransitMode.entries.firstOrNull { text.startsWith(modeAccusative(it)) }

    private fun boardEn(rest: String): String {
        val mode = modeOf(rest) ?: return "Board"
        val platformMark = ", ${platformWord(mode)} "
        val ride = rest.removePrefix(modeAccusative(mode))
        val platform = ride.substringAfter(platformMark, "").trim().takeIf { it.isNotEmpty() }
        val line = ride.substringBefore(platformMark)
        val headsign = line.substringAfter(TOWARD_UK, "").trim().takeIf { it.isNotEmpty() }
        val label = line.substringBefore(TOWARD_UK).trim().takeIf { it.isNotEmpty() }
        return buildString {
            append(if (label != null) "Take ${modeEn(mode)} $label" else "Take the ${modeEn(mode)}")
            headsign?.let { append(" toward ").append(it) }
            platform?.let { append(", ").append(platformEn(mode)).append(' ').append(it) }
        }
    }

    private fun changeEn(next: String): String {
        val mode = modeOf(next) ?: return "Then change"
        val label = next.removePrefix(modeAccusative(mode)).trim()
        return if (label.isNotEmpty()) "Then change to ${modeEn(mode)} $label" else "Then change to the ${modeEn(mode)}"
    }

    private fun modeEn(mode: TransitMode): String = when (mode) {
        TransitMode.METRO -> "metro"
        TransitMode.TRAM -> "tram"
        TransitMode.TROLLEYBUS -> "trolleybus"
        TransitMode.BUS -> "bus"
        TransitMode.RAIL -> "train"
        TransitMode.FERRY -> "ferry"
        TransitMode.FUNICULAR -> "funicular"
        TransitMode.OTHER -> "line"
    }

    private fun platformWord(mode: TransitMode): String =
        if (mode == TransitMode.RAIL || mode == TransitMode.METRO) "колія" else "платформа"

    private fun platformEn(mode: TransitMode): String =
        if (mode == TransitMode.RAIL || mode == TransitMode.METRO) "track" else "platform"

    internal fun stopsWord(n: Int): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> "зупинок"
            mod10 == 1 -> "зупинку"
            mod10 in 2..4 -> "зупинки"
            else -> "зупинок"
        }
    }

    private fun step(type: String, text: String, lat: Double, lon: Double, seconds: Int, meters: Double) =
        RouteStep(type, null, text, meters, seconds.toDouble(), lat, lon)
}
