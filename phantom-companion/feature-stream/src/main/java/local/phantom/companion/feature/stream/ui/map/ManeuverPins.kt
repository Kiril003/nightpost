package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.RouteStep

/**
 * The turns, on the map, where they happen.
 *
 * The route was a ribbon with travel chevrons on it: it said which way it ran
 * and nothing about what the operator would have to DO. Every decision on the
 * drive — the turn, the exit, the fork — was legible only in the banner, one
 * at a time, and only while guiding. A glance at the line could not answer
 * «is this route full of turns or is it a straight run».
 */
internal object ManeuverPins {

    /** Steps that are not decisions: starting, arriving, a road changing name. */
    private val SILENT = setOf("depart", "arrive", "new name", "notification")

    /** A turn too gentle to be a decision — the line itself already shows it. */
    const val MIN_ANGLE_DEG = 18f

    data class Pin(
        val lat: Double,
        val lon: Double,
        /** The RELATIVE turn: 0 = straight on, +90 = right. Chooses the glyph
         *  and rotates it in the banner's screen frame — never on the map. */
        val rotationDeg: Float,
        /** Where the road runs AFTER the turn, compass degrees, map-north
         *  frame: the only datum a map-aligned symbol may be rotated by.
         *  null when the router sent no bearings — then nothing points. */
        val bearingDeg: Float? = null,
        val roundabout: Boolean,
        val exitNumber: Int?,
        /**
         * Черга на місце під сонцем, 0 = найважливіший. Коли маркери почали
         * розріджуватись, знадобилось правило, ХТО лишається: різкий поворот і
         * кільце — це рішення, яке не можна проґавити, а плавний зʼїзд лінія
         * маршруту й так показує. Менше число кладеться першим.
         */
        val sortKey: Float = 0f,
        /**
         * Позиція кроку у ПОВНОМУ плані маршруту, не в цьому списку: частина
         * кроків сюди не потрапляє (старт, прибуття, зміна назви дороги), тож
         * власний номер мітки нічого не сказав би сесії супроводу, яка рахує
         * кроки всі підряд.
         */
        val stepIndex: Int = -1,
    )

    fun of(steps: List<RouteStep>): List<Pin> = steps.mapIndexedNotNull { index, step ->
        val type = step.type.lowercase()
        if (type in SILENT) return@mapIndexedNotNull null
        val roundabout = type.contains("roundabout") || type == "rotary"
        val isIndoor = type == "entrance" || type == "indoor_level"
        val angle = step.turnAngleDeg ?: bucketAngle(step.modifier)
        if (!roundabout && !isIndoor && kotlin.math.abs(angle) < MIN_ANGLE_DEG) return@mapIndexedNotNull null
        if (step.lat == 0.0 && step.lon == 0.0) return@mapIndexedNotNull null
        Pin(
            lat = step.lat,
            lon = step.lon,
            rotationDeg = angle.coerceIn(-180f, 180f),
            bearingDeg = step.bearingAfterDeg,
            roundabout = roundabout,
            exitNumber = step.exitNumber,
            sortKey = if (roundabout) 0f else 1f - kotlin.math.abs(angle).coerceIn(0f, 180f) / 180f,
            stepIndex = index,
        )
    }

    /** The modifier's coarse angle, for routes with no bearings. */
    private fun bucketAngle(modifier: String?): Float = when (modifier) {
        "left" -> -90f
        "right" -> 90f
        "slight left" -> -35f
        "slight right" -> 35f
        "sharp left" -> -135f
        "sharp right" -> 135f
        "uturn" -> 180f
        else -> 0f
    }
}
