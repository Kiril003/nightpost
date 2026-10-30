package local.phantom.companion.feature.stream.ui.widget

import kotlinx.serialization.Serializable

/**
 * Що PHANTOM просить показати на мапі: центр, мітки, кола, лінії.
 *
 * Жив у MapWidget.kt — картці мапи в старій стрічці. Картка пішла разом зі
 * стрічкою (04.10), а формат лишився: ним говорять інструменти мапи, маршрут,
 * евакуація і шари поверхні мапи (ui/map). Пакет не змінено, щоб не рухати
 * десять імпортів мапи в чужому пакеті робіт.
 */
@Serializable
data class MapPayload(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double = 12.0,
    val markers: List<MarkerData> = emptyList(),
    val circles: List<CircleData> = emptyList(),
    val polylines: List<PolylineData> = emptyList()
) {
    @Serializable data class MarkerData(val lat: Double, val lon: Double, val label: String? = null)
    @Serializable data class CircleData(val lat: Double, val lon: Double, val radiusM: Double, val color: String? = null)
    @Serializable data class PolylineData(
        val points: List<PointData>,
        val color: String? = null,
        val width: Float = 4f,
        val profile: String? = null,
        val isDashed: Boolean = false,
        val opacity: Float = 1.0f,
        val selected: Boolean = true,
    )
    @Serializable data class PointData(val lat: Double, val lon: Double)
}
