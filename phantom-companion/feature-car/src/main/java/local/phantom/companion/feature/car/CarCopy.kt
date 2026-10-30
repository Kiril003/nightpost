package local.phantom.companion.feature.car

import kotlin.math.roundToInt

/**
 * How much PHANTOM knows about the nearest one of a kind.
 *
 * Spelled out as states rather than a nullable number so that the two ignorant
 * cases cannot quietly become the same pixel: «I have no fix» and «I looked and
 * found none» send a driver in opposite directions.
 */
sealed interface Nearest {
    /** The gazetteer has not answered yet. */
    data object Asking : Nearest

    /** No fix PHANTOM trusts, so it can say nothing at all about «near». */
    data object NoFix : Nearest

    /** Looked, within the radius, and knows of none. */
    data object NotFound : Nearest

    data class Found(val metres: Double) : Nearest
}

/**
 * HIGHWAY — what the car screen says, as pure functions.
 *
 * Split out from the templates on purpose. Everything a driver reads at speed
 * is decided here, where it can be unit-tested without a car: the templates
 * below only arrange it.
 */
object CarCopy {

    /**
     * Distance, rounded the way a driver reads it — never the way a float
     * prints.
     *
     * Precision is a lie above walking range: "12.847 км" implies PHANTOM knows
     * the road distance to the metre when this is a straight-line estimate over
     * an OSM point. Under a kilometre he gets metres (rounded to 10, because
     * "347 м" and "350 м" are the same turn); beyond it, one decimal to 10 km
     * and whole kilometres after that.
     */
    fun distance(meters: Double): String = when {
        meters < 1_000 -> "${(meters / 10).roundToInt() * 10} м"
        meters < 10_000 -> String.format("%.1f км", meters / 1_000).replace('.', ',')
        else -> "${(meters / 1_000).roundToInt()} км"
    }

    /**
     * A place PHANTOM has no distance for says so. Sorting it quietly to the
     * bottom of the list under a plausible-looking number is the version of
     * this that a driver cannot see and therefore cannot discount.
     */
    fun distanceOrUnknown(meters: Double): String =
        if (!meters.isFinite() || meters < 0.0) "відстань невідома" else distance(meters)

    /**
     * The line under a place's name.
     *
     * Distance first, always: it is the only field that changes the decision.
     */
    fun subtitle(place: CarPlace): String =
        listOfNotNull(
            distanceOrUnknown(place.distanceMeters),
            place.subtitle?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    /**
     * The line under a category tile.
     *
     * Four states, and no two of them may look alike. The one that matters is
     * [Nearest.NoFix]: a blank tile in an underground car park reads as «поруч
     * заправок немає», and the driver passes the last station before 40 km of
     * nothing — when the truth was that PHANTOM did not know where he was.
     *
     * [Nearest.NotFound] says «не знайшов», not «немає»: PHANTOM can testify
     * about its own gazetteer and not about the world, and the tile has no room
     * to explain the difference. Only [Nearest.Found] is allowed to be a number.
     */
    fun gridSubtitle(nearest: Nearest): String? = when (nearest) {
        Nearest.Asking -> null
        Nearest.NoFix -> "чекаю сигнал"
        Nearest.NotFound -> "не знайшов"
        is Nearest.Found -> distance(nearest.metres)
    }

    /**
     * What the screen says when a search finds nothing.
     *
     * It must distinguish the two cases, because they are opposite instructions
     * to the driver. An empty gazetteer means PHANTOM has never been here and
     * cannot help — keep driving, get signal. A non-empty one means he looked
     * and there is genuinely nothing of that kind within reach — stop looking.
     * "Нічого не знайдено" for both would be a lie half the time.
     */
    fun emptyMessage(gazetteerSize: Int, hadFix: Boolean): String = when {
        !hadFix -> "не знаю, де ти — чекаю на сигнал"
        gazetteerSize == 0 -> "я ще не бачив цих місць — підʼїдь із мережею, і я їх запамʼятаю"
        else -> "поруч такого немає"
    }

    /** Android Auto refuses to draw more than this many rows at once, and it is
     *  right to: a driver cannot read a list. */
    const val MAX_ROWS = 6

    /** How often the grid re-asks while the driver is looking at it. A number
     *  computed once at build time is wrong the moment the car moves. */
    const val GRID_REFRESH_MS = 45_000L

    /** Turn-by-turn on the car display. */
    const val STOP_DRIVE = "Зупинити"
    const val ARRIVED = "Прибули"
    const val THEN = "далі"
    const val SURFACE_WAITING = "чекаю маршрут"

    /**
     * Єдина дія спокійного екрана. Питання, а не обіцянка: PHANTOM у
     * спокої не знає ні куди людина їде, ні куди вона зазвичай їздить,
     * тож «Додому» тут було б словом без джерела.
     */
    const val WHERE_TO = "Куди?"
    const val DESTINATION = "Точка"
}
