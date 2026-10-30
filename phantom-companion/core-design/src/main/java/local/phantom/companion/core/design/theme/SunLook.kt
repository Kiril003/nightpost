package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale
import local.phantom.companion.core.design.tokens.DawnColors
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.DuskColors
import local.phantom.companion.core.design.tokens.GoldenColors
import local.phantom.companion.core.design.tokens.MorningColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.SunWindows

/** Точка сонця → її палітра з tokens.json. */
fun DayPhase.colors(): PhantomColors = when (this) {
    DayPhase.Night -> NightColors
    DayPhase.Dawn -> DawnColors
    DayPhase.Morning -> MorningColors
    DayPhase.Day -> DayColors
    DayPhase.Golden -> GoldenColors
    DayPhase.Dusk -> DuskColors
}

/**
 * Кадр сонця: від точки [from] до [to] на частку [t]. Ті самі вікна й та сама
 * арифметика, що `rampAt` у phantom-os/src/frontend/src/services/themeRamp.ts;
 * міст везе кадр рядком [web] (`PhantomBridge.getSunLook`), тож веб і оболонка
 * малюють одну точку.
 */
data class SunLook(val from: DayPhase, val to: DayPhase, val t: Float) {

    val phase: DayPhase get() = if (t < 0.5f) from else to
    val dark: Boolean get() = from.dark
    val isPole: Boolean get() = from == to && (from == DayPhase.Night || from == DayPhase.Day)
    val web: String get() = "${from.web}:${to.web}:${String.format(Locale.ROOT, "%.3f", t)}"

    val colors: PhantomColors get() = if (from == to) from.colors() else mix(from.colors(), to.colors(), t)

    companion object {
        fun flat(phase: DayPhase) = SunLook(phase, phase, 0f)

        fun of(from: DayPhase, to: DayPhase, t: Double): SunLook {
            val k = Math.round(t.coerceIn(0.0, 1.0) * 1000) / 1000.0
            return when {
                k <= 0.0 || from == to -> flat(from)
                k >= 1.0 -> flat(to)
                else -> SunLook(from, to, k.toFloat())
            }
        }

        /** Висота сонця, напрям і найвища точка дня → кадр. NaN — ніч. */
        fun at(elevationDeg: Double, rising: Boolean, peakDeg: Double = 90.0): SunLook {
            val a = anchors(rising, peakDeg)
            if (!(elevationDeg >= a.first().first)) return flat(a.first().second)
            for (i in a.size - 2 downTo 0) {
                val (d0, s0) = a[i]
                val (d1, s1) = a[i + 1]
                if (elevationDeg >= d0 && d1 > d0) {
                    return of(s0, s1, if (elevationDeg >= d1) 1.0 else (elevationDeg - d0) / (d1 - d0))
                }
            }
            return flat(a.last().second)
        }

        private val byWeb = DayPhase.entries.associateBy { it.web }

        private fun side(rising: Boolean) =
            (if (rising) SunWindows.rising else SunWindows.setting).map { (d, s) -> d to byWeb.getValue(s) }

        /** Зима: точки після перемикання стискаються, щоб полудень настав за [SunWindows.WINTER_MARGIN] до піку. */
        private fun anchors(rising: Boolean, peakDeg: Double): List<Pair<Double, DayPhase>> {
            val side = side(rising)
            val top = side.last().first
            val lightAt = side.indexOfFirst { !it.second.dark }
            val low = side[lightAt].first
            val hi = maxOf(low + 1.0, peakDeg - SunWindows.WINTER_MARGIN)
            if (hi >= top) return side
            return side.mapIndexed { i, (d, s) -> (if (i > lightAt) low + (d - low) * (hi - low) / (top - low) else d) to s }
        }

        private fun lerp(a: Color, b: Color, t: Float): Color {
            fun ch(x: Float, y: Float): Float {
                val (p, q) = Math.round(x * 255f) to Math.round(y * 255f)
                return Math.round(p + (q - p) * t.toDouble()) / 255f
            }
            return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue), a.alpha)
        }

        private fun mix(a: PhantomColors, b: PhantomColors, t: Float) = a.copy(
            canvas = lerp(a.canvas, b.canvas, t),
            surface = lerp(a.surface, b.surface, t),
            raised = lerp(a.raised, b.raised, t),
            overlay = lerp(a.overlay, b.overlay, t),
            line = lerp(a.line, b.line, t),
            lineStrong = lerp(a.lineStrong, b.lineStrong, t),
            text = lerp(a.text, b.text, t),
            text2 = lerp(a.text2, b.text2, t),
            text3 = lerp(a.text3, b.text3, t),
            accent = lerp(a.accent, b.accent, t),
            accentHover = lerp(a.accentHover, b.accentHover, t),
            accentPress = lerp(a.accentPress, b.accentPress, t),
            accentText = lerp(a.accentText, b.accentText, t),
            accentLine = lerp(a.accentLine, b.accentLine, t),
            accentSoft = lerp(a.accentSoft, b.accentSoft, t),
            onAccent = lerp(a.onAccent, b.onAccent, t),
            self = lerp(a.self, b.self, t),
            onSelf = lerp(a.onSelf, b.onSelf, t),
            onSelf2 = lerp(a.onSelf2, b.onSelf2, t),
            success = lerp(a.success, b.success, t),
            danger = lerp(a.danger, b.danger, t),
            warning = lerp(a.warning, b.warning, t),
            actPin = lerp(a.actPin, b.actPin, t),
            actMute = lerp(a.actMute, b.actMute, t),
            actArchive = lerp(a.actArchive, b.actArchive, t),
            onAct = lerp(a.onAct, b.onAct, t),
            scrim = lerp(a.scrim, b.scrim, t),
            wall = lerp(a.wall, b.wall, t),
            wallDot = lerp(a.wallDot, b.wallDot, t),
            cover = lerp(a.cover, b.cover, t),
            coverLine = lerp(a.coverLine, b.coverLine, t),
        )
    }
}
