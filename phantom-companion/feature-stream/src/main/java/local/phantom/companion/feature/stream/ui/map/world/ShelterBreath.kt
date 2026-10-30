package local.phantom.companion.feature.stream.ui.map.world

import kotlin.math.PI
import kotlin.math.sin

/**
 * Дихання укриття під тривогою — єдиний рух у світі мапи, бо він показує стан:
 * «тут сховатися, зараз». Рух скінченний: [CYCLES] вдихів після того, як
 * тривога почалась чи камера зупинилась, далі — нерухоме бурштинове коло.
 * Нескінченна анімація на мапі — це батарея й тепло, а не інформація.
 */
internal object ShelterBreath {
    const val PERIOD_MS = 2_400L
    const val CYCLES = 4
    const val FRAME_MS = 80L
    const val BASE_RADIUS_DP = 20f
    const val SWELL = 0.35f

    /** Кадр руху в мить [tMs] від початку; null — рух скінчився. */
    fun frame(tMs: Long): Frame? {
        if (tMs < 0 || tMs >= PERIOD_MS * CYCLES) return null
        val phase = (tMs % PERIOD_MS).toFloat() / PERIOD_MS
        val s = sin(PI * phase).toFloat()
        val swell = s * s
        return Frame(radiusDp = BASE_RADIUS_DP * (1f + SWELL * swell), opacity = REST_OPACITY + 0.35f * (1f - swell))
    }

    /** Коли рух скінчився — коло стоїть, не зникає: стан лишається правдою. */
    val rest = Frame(BASE_RADIUS_DP, REST_OPACITY)

    data class Frame(val radiusDp: Float, val opacity: Float)

    private const val REST_OPACITY = 0.22f
}
