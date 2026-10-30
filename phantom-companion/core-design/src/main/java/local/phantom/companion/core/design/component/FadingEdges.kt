package local.phantom.companion.core.design.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mandate 3 — content dissolves into the edges instead of being cut by them.
 *
 * An alpha mask, not a painted gradient: the fade is drawn with
 * [BlendMode.DstIn] over the content's own layer, so it erases the content's
 * alpha rather than laying a coloured veil on top of it. A painted veil only
 * works over a known, opaque background — over the map it would be a grey
 * smear, and over the backdrop's gradient it would be a visible seam. This
 * works over anything, because it makes the pixels themselves transparent.
 *
 * [CompositingStrategy.Offscreen] is load-bearing: without its own layer there
 * is no destination alpha for DstIn to erase, and the mask would blacken the
 * content instead of fading it.
 *
 * Horizontal fades matter for the same reason vertical ones do. A scrollable
 * chip row with a hard edge shows its last chip sliced mid-word, which reads
 * as a clipping bug rather than as an invitation to scroll.
 */
fun Modifier.fadingEdges(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val t = top.toPx()
        val b = bottom.toPx()
        val s = start.toPx()
        val e = end.toPx()
        if (t > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = t,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (b > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - b,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (s > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = s,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (e > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - e,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
