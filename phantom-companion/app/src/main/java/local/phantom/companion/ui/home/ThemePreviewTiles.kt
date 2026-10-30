package local.phantom.companion.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.design.R as DesignR

/** Same contract as the segmented control it replaced: one mode per option, [onSelect] untouched. */
@Composable
internal fun ThemeTiles(
    options: List<Pair<ThemeMode, String>>,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(PhantomSpace.s3).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        options.forEach { (mode, label) ->
            ThemeTile(mode, label, mode == selected, Modifier.weight(1f)) { onSelect(mode) }
        }
    }
}

@Composable
private fun ThemeTile(mode: ThemeMode, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    val sun = c.accentLine
    Column(
        modifier = modifier
            .clip(shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(bottom = PhantomSpace.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(shape)
                .border(if (on) 2.dp else 1.dp, if (on) c.accentLine else c.line, shape),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                when (mode) {
                    ThemeMode.Light -> miniMessenger(DayColors)
                    ThemeMode.Dark -> miniMessenger(NightColors)
                    else -> {
                        miniMessenger(DayColors)
                        clipRect(left = size.width / 2) { miniMessenger(NightColors) }
                        sunArc(sun)
                    }
                }
            }
            if (on) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(PhantomSpace.s2)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(c.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(DesignR.drawable.lc_check),
                        contentDescription = null,
                        tint = c.onAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        Text(
            text = label,
            style = PhantomText.footnote,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            color = if (on) c.text else c.text2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.sunArc(color: Color) {
    val r = size.width * 0.09f
    val centre = Offset(size.width / 2, size.height * 0.33f)
    val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
    drawArc(color, 180f, 180f, false, Offset(centre.x - r, centre.y - r), Size(r * 2, r * 2), style = stroke)
    drawLine(color, Offset(centre.x - r * 1.6f, centre.y), Offset(centre.x + r * 1.6f, centre.y), stroke.width, StrokeCap.Round)
}

private fun DrawScope.miniMessenger(p: PhantomColors) {
    val w = size.width
    val h = size.height
    val line = h * 0.034f
    val pill = CornerRadius(line / 2)
    drawRect(p.canvas)

    drawCircle(p.person.first().first, radius = w * 0.09f, center = Offset(w * 0.17f, h * 0.14f))
    drawRoundRect(p.text, Offset(w * 0.32f, h * 0.105f), Size(w * 0.42f, line), pill)
    drawRoundRect(p.text3, Offset(w * 0.32f, h * 0.16f), Size(w * 0.28f, line), pill)
    drawRect(p.line, Offset(w * 0.32f, h * 0.25f), Size(w * 0.68f, 1.dp.toPx()))

    val bubble = CornerRadius(w * 0.07f)
    val inOrigin = Offset(w * 0.08f, h * 0.38f)
    val inSize = Size(w * 0.58f, h * 0.14f)
    drawRoundRect(p.surface, inOrigin, inSize, bubble)
    drawRoundRect(p.line, inOrigin, inSize, bubble, style = Stroke(1.dp.toPx()))
    drawRoundRect(p.text2, Offset(w * 0.14f, h * 0.435f), Size(w * 0.38f, line), pill)

    drawRoundRect(p.self, Offset(w * 0.34f, h * 0.58f), Size(w * 0.58f, h * 0.14f), bubble)
    drawRoundRect(p.onSelf, Offset(w * 0.40f, h * 0.635f), Size(w * 0.38f, line), pill)

    drawRoundRect(p.surface, Offset(w * 0.08f, h * 0.83f), Size(w * 0.84f, h * 0.09f), CornerRadius(h * 0.045f))
}
