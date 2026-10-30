package local.phantom.companion.feature.stream.ui.map.indoor

import androidx.compose.ui.graphics.Color
import local.phantom.companion.core.geo.indoor.IndoorVenueCategory
import local.phantom.companion.core.geo.indoor.PassageKind

/**
 * Палітра МАЛЮВАННЯ внутрішнього плану — кольори, якими кімнати й проходи
 * лежать на полотні мапи, той самий рід, що й [MapSymbolPalette]. Хром
 * (плашки, підписи, перемикач поверхів) звідси не бере нічого: він на токенах.
 *
 * Перенесено з IndoorFloorOverlay як є. Одна зміна: «інше» більше не бурштин
 * стану — бурштин на мапі означає дію, а безіменна кімната нічого не просить.
 */
internal object IndoorPalette {

    fun room(category: IndoorVenueCategory): Color = when (category) {
        IndoorVenueCategory.FOOD -> Color(0xFFF59E0B)
        IndoorVenueCategory.FASHION -> Color(0xFFEC4899)
        IndoorVenueCategory.TECH -> Color(0xFF3B82F6)
        IndoorVenueCategory.SUPERMARKET -> Color(0xFF10B981)
        IndoorVenueCategory.HEALTH -> Color(0xFF06B6D4)
        IndoorVenueCategory.ENTERTAINMENT -> Color(0xFF8B5CF6)
        IndoorVenueCategory.SERVICES -> Color(0xFF64748B)
        // Нейтральний сірий, що читається і на нічній, і на денній землі.
        IndoorVenueCategory.OTHER -> Color(0xFF94A3B8)
    }

    /** Колір лінії проходу; товщину й пунктир лишає накладка — це геометрія, не фарба. */
    fun passage(kind: PassageKind): Color = when (kind) {
        PassageKind.ESCALATOR -> Color(0xFFFFB300)
        PassageKind.ELEVATOR -> Color(0xFF10B981)
        PassageKind.STEPS -> Color(0xFF38BDF8)
        PassageKind.BUILDING_PASSAGE -> Color(0xFF818CF8)
        PassageKind.MOVING_WALKWAY -> Color(0xFFA855F7)
        else -> CORRIDOR
    }

    /** Звичайний коридор — та сама блакить сходів, але приглушена. */
    private val CORRIDOR: Color = Color(0xFF38BDF8).copy(alpha = 0.70f)
}
