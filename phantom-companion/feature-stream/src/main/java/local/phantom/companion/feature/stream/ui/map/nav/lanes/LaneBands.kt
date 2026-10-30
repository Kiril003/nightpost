package local.phantom.companion.feature.stream.ui.map.nav.lanes

import local.phantom.companion.core.net.map.LaneInfo
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import java.util.Locale

/**
 * PRESENCE wave 1 — the lane band, baked at plan time.
 *
 * One band per maneuver that actually HAS lane data, each already serialised:
 * during the drive the map does nothing but hand a finished string to a source
 * when the step changes. No per-frame geometry, no per-fix serialisation —
 * this file's whole shape is the answer to the map's own thermal scar.
 */
internal data class LaneBand(
    /** Index into [RouteVariant.steps] — the same number the banner counts by. */
    val stepIndex: Int,
    val laneCount: Int,
    val geoJson: String,
)

internal object LaneBands {

    /** How much road the band covers, matching the widest approach window. */
    const val BAND_LENGTH_M = 250.0

    /** Shorter than this the band is a smudge, not a lane. */
    private const val MIN_BAND_M = 30.0

    /** Beyond this the data is a tagging error, not a junction. */
    private const val MAX_LANES = 8

    fun of(variant: RouteVariant): List<LaneBand> {
        // Lanes are a driving claim. The foot and bike profiles never emit
        // them, and a pedestrian shown four lanes of asphalt learns nothing.
        if (variant.profile != RouteProfile.CAR || variant.points.size < 2) return emptyList()
        return variant.steps.mapIndexedNotNull { index, step ->
            if (index == 0) return@mapIndexedNotNull null // depart has no approach behind it
            val lanes = step.lanes
            if (lanes.isEmpty() || lanes.size > MAX_LANES) return@mapIndexedNotNull null
            val room = variant.steps[index - 1].distanceMeters
            val length = minOf(BAND_LENGTH_M, room)
            if (length < MIN_BAND_M) return@mapIndexedNotNull null
            val spine = approachPath(variant.points, step.lat to step.lon, length)
            if (spine.size < 2) return@mapIndexedNotNull null
            bandGeoJson(spine, lanes)?.let { LaneBand(index, lanes.size, it) }
        }
    }

    /** Lane centres as `valid`-tagged lines plus the separators between them. */
    internal fun bandGeoJson(
        spine: List<Pair<Double, Double>>,
        lanes: List<LaneInfo>,
    ): String? {
        val features = mutableListOf<String>()
        laneCenterOffsets(lanes.size).forEachIndexed { i, offset ->
            val line = offsetPolyline(spine, offset)
            if (line.size < 2) return@forEachIndexed
            features += feature(line, """"kind":"lane","valid":${lanes[i].validForManeuver}""")
        }
        if (features.isEmpty()) return null
        laneSeparatorOffsets(lanes.size).forEach { offset ->
            val line = offsetPolyline(spine, offset)
            if (line.size < 2) return@forEach
            features += feature(line, """"kind":"sep"""")
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun feature(line: List<Pair<Double, Double>>, props: String): String {
        val coords = line.joinToString(",") { (lat, lon) ->
            "[${fmt(lon)},${fmt(lat)}]"
        }
        return """{"type":"Feature","properties":{$props},""" +
            """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }

    /** Six decimals ≈ 0.1 m — finer than the paint, coarse enough to stay small. */
    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.6f", v)
}

/**
 * Which arrow one lane shows in the chrome strip.
 *
 * A lane tagged `through;right` carries two arrows in the real world, and the
 * strip has room for one. The one that matters is the one the operator is
 * about to use: on a valid lane the indication that MATCHES the maneuver wins,
 * everywhere else the first painted arrow stands. Never invent one — a lane
 * with no indications draws nothing and stays an empty slot in the row.
 */
internal fun laneGlyphModifier(lane: LaneInfo, maneuverModifier: String?): String? {
    if (lane.indications.isEmpty()) return null
    if (lane.validForManeuver && maneuverModifier != null) {
        lane.indications.firstOrNull { it == maneuverModifier }?.let { return glyphModifierOf(it) }
    }
    return glyphModifierOf(lane.indications.first())
}

/** OSRM lane indications the glyph family has no word for, said in its words. */
private fun glyphModifierOf(indication: String): String = when (indication) {
    "merge to left" -> "slight left"
    "merge to right" -> "slight right"
    else -> indication
}
