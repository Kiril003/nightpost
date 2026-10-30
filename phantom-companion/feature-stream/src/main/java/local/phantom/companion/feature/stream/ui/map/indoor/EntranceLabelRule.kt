package local.phantom.companion.feature.stream.ui.map.indoor

import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.EntranceKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * СКІЛЬКИ ВХОДІВ ПІДПИСУВАТИ.
 *
 * Кадр тестувальника 11.09: перехрестя, і поверх усього — десятки плашок
 * «Вхід / Головний вхід / Аварійний вихід». Причина не в шарі, а в засіві:
 * план будується з `queryRenderedFeatures` по квадрату 900×900 px, а
 * `entrance=yes` в OSM стоїть на дверях КОЖНОГО підʼїзду. На міському зумі в
 * той квадрат лягає квартал.
 *
 * Той самий клас, що корінь «parking» ×6 у [[phantom-map-look-front-03sep]]:
 * дані правильні, межа показу — ні.
 *
 * Правило:
 * - вхід підписується з [MIN_ZOOM] — там, де видно самі двері;
 * - аварійний вихід з [MIN_ZOOM_EMERGENCY]: його шукають, коли треба вийти,
 *   а не коли роздивляються будівлю;
 * - лише біля обраної (найближчої) будівлі — [FOCUS_RADIUS_M] від центру;
 * - не більше [MAX_LABELS] плашок: у торговому центрі дверей більше, ніж
 *   екрана.
 */
internal object EntranceLabelRule {

    const val MIN_ZOOM = 18.0
    const val MIN_ZOOM_EMERGENCY = 17.0
    const val FOCUS_RADIUS_M = 60.0
    const val MAX_LABELS = 8

    private const val METRES_PER_DEGREE = 111_320.0

    fun minZoom(kind: EntranceKind): Double =
        if (kind == EntranceKind.EMERGENCY) MIN_ZOOM_EMERGENCY else MIN_ZOOM

    fun visible(
        entrances: List<BuildingEntrance>,
        zoom: Double,
        focusLat: Double,
        focusLon: Double,
    ): List<BuildingEntrance> = entrances
        .asSequence()
        .filter { zoom >= minZoom(it.kind) }
        .map { it to metresFrom(it, focusLat, focusLon) }
        .filter { (_, d) -> d <= FOCUS_RADIUS_M }
        .sortedBy { (_, d) -> d }
        .map { (entrance, _) -> entrance }
        .take(MAX_LABELS)
        .toList()

    private fun metresFrom(entrance: BuildingEntrance, lat: Double, lon: Double): Double {
        val dLat = abs(entrance.lat - lat) * METRES_PER_DEGREE
        val dLon = abs(entrance.lon - lon) * METRES_PER_DEGREE * cos(Math.toRadians(lat))
        return hypot(dLat, dLon)
    }
}
