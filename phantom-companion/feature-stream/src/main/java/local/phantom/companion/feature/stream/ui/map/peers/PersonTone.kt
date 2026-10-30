package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.theme.PhantomPalette

/**
 * Тон людини — той самий, що в месенджері (`formTokens.ts toneIndex`, `form.css PEOPLE`):
 * Оля на мапі того ж кольору, що й її аватар у розмові. FNV-1a над кодовими
 * одиницями UTF-16, як `charCodeAt` у вебі.
 */
internal object PersonTone {

    /** Шість тонів людей дизайн-системи (docs/design/system/tokens.json › person). */
    const val COUNT = 6

    /** Плоска заливка й літера на ній — без градієнта й сяйва (tokens.json › person). */
    data class Tone(val fill: Int, val ink: Int)

    /** Той самий FNV-1a, що `toneIndex` у вебі (styles/formTokens.ts): одна людина — один тон. */
    fun index(key: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in key) {
            h = h xor c.code
            h *= 0x01000193
        }
        return ((h.toLong() and 0xFFFFFFFFL) % COUNT).toInt()
    }

    fun of(key: String, night: Boolean): Tone = of(index(key), night)

    fun of(index: Int, night: Boolean): Tone = (if (night) NIGHT else DAY)[index.mod(COUNT)]

    private fun tone(p: Pair<Color, Color>) = Tone(p.first.toArgb(), p.second.toArgb())

    // Дзеркало docs/design/system/tokens.json › person: рівні тони, свої для Ночі й Дня.
    private val NIGHT = PhantomPalette.PersonNight.map(::tone)

    private val DAY = PhantomPalette.PersonDay.map(::tone)
}
