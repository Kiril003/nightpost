package local.phantom.companion.feature.precision

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import androidx.compose.runtime.Composable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Небесна карта супутників — полярна проєкція неба над головою.
 *
 * Це не прикраса, а доказ. У момент підміни на цій карті видно те, чого не
 * покаже жоден звичайний навігатор: скільки супутників реально прийнято, де
 * вони на небі, і які з них перекреслено як брехливі. Зеніт — у центрі,
 * горизонт — на колі; азимут відкладається від півночі за годинниковою
 * стрілкою, як на компасі.
 *
 * Малюється на Canvas, а не з тисячі View: точок буває багато, вони рухаються
 * щосекунди, і кожна — це кілька примітивів, а не вузол дерева розмітки.
 */
@Composable
fun SkyPlot(
    satellites: List<SatelliteRow>,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val theme = LocalPhantomTheme.current
    val measurer = rememberTextMeasurer()
    val northLabel = stringResource(R.string.precision_sky_north)

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = min(cx, cy) - 10.dp.toPx()

            drawSkyGrid(cx, cy, radius, theme.glassBorder, theme.inkSecondary, measurer, northLabel)

            for (s in satellites) {
                val el = s.elevationDeg ?: continue
                val az = s.azimuthDeg ?: continue
                // Кут місця 90° (зеніт) → центр; 0° (горизонт) → зовнішнє коло.
                val r = radius * (1f - (el.coerceIn(0.0, 90.0) / 90.0)).toFloat()
                val rad = Math.toRadians(az).toFloat()
                // Північ угору, схід праворуч: x = sin(az), y = −cos(az).
                val px = cx + r * sin(rad)
                val py = cy - r * cos(rad)

                drawSatellite(Offset(px, py), s, theme.primary, theme.coral, theme.inkSecondary)
            }
        }
    }
}

/** Концентричні кола кутів місця та осі сторін світу. */
private fun DrawScope.drawSkyGrid(
    cx: Float,
    cy: Float,
    radius: Float,
    gridColor: Color,
    labelColor: Color,
    measurer: TextMeasurer,
    northLabel: String,
) {
    // Кільця 0° / 30° / 60° кута місця.
    for (ring in 1..3) {
        drawCircle(
            color = gridColor,
            radius = radius * ring / 3f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    // Хрест сторін світу.
    drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), 1.dp.toPx())
    drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), 1.dp.toPx())

    // Підпис півночі — єдиний орієнтир, якого достатньо.
    val n = measurer.measure(northLabel)
    drawText(n, labelColor, Offset(cx - n.size.width / 2f, cy - radius - n.size.height))
}

/**
 * Один супутник: заповнене коло, розмір — за силою сигналу, колір — за станом.
 * Виключений перекреслюється: людина має бачити не лише «скільки», а «кого і
 * за що відкинуто».
 */
private fun DrawScope.drawSatellite(
    at: Offset,
    s: SatelliteRow,
    used: Color,
    excluded: Color,
    dim: Color,
) {
    val dotR = (3.dp.toPx() + 4.dp.toPx() * s.strength)
    val color = when {
        s.exclusionReason != null -> excluded
        s.used -> used
        else -> dim
    }
    drawCircle(color = color.copy(alpha = 0.9f), radius = dotR, center = at)

    if (s.exclusionReason != null) {
        // Перекреслення — знак «цьому супутнику не віримо».
        val d = dotR + 2.dp.toPx()
        drawLine(excluded, Offset(at.x - d, at.y - d), Offset(at.x + d, at.y + d), 1.5.dp.toPx())
        drawLine(excluded, Offset(at.x - d, at.y + d), Offset(at.x + d, at.y - d), 1.5.dp.toPx())
    }
}
