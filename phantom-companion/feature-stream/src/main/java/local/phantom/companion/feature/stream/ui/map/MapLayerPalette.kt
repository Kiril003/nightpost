package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.graphics.Color

/**
 * Кольори, що належать ШАРАМ, а не інтерфейсу.
 *
 * Радар — погодний прилад, а не стан застосунку, тож його крапка не токен
 * теми і не бурштин дії: блакить лише розрізняє, ЯКИЙ шар перемикається.
 * Хром (пігулки, картки, аркуші) кольорів звідси не бере. Як і
 * [MapSymbolPalette], це палітра малювання, і `Color(0x…)` тут законний.
 */
internal object MapLayerPalette {
    /** Крапка перемикача «Опади» — та сама блакить, що стояла в RADAR_TINT. */
    val Radar: Color = Color(0xFF0EA5E9)
}
