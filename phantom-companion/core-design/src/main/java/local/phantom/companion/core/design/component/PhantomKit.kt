package local.phantom.companion.core.design.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/*
 * The v1.2 settings kit — the screen, the group, the row, the switch, the
 * segmented control and the one primary button — drawn ONLY from the v1.2
 * tokens ([LocalPhantomColors], [PhantomText], [PhantomSpace], [PhantomRadius],
 * [PhantomSize]).
 *
 * It was born `internal` in `:app` (`ui/home/ProfileKit.kt`) for the profile
 * rebuild, which left every feature module that wanted the same row to write
 * its own — that is how a design system becomes a zoo again. Promoted here so
 * a screen anywhere can be built from the same parts; the app keeps thin
 * same-named wrappers so its callers compile untouched.
 */

/** A pushed screen: back arrow + `title2` on a 56dp bar over `canvas`, scrolling body. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPhantomColors.current
    Column(modifier = Modifier.fillMaxSize().background(c.canvas).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(PhantomSize.topbarPhone),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(PhantomSpace.touchMinAndroid)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.lc_back),
                    contentDescription = stringResource(R.string.back_pill_action),
                    tint = c.text,
                    modifier = Modifier.size(PhantomSize.iconL),
                )
            }
            Text(text = title, style = PhantomText.title2, color = c.text)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhantomSpace.gutterPhone),
        ) {
            Spacer(Modifier.height(PhantomSpace.s2))
            content()
            Spacer(Modifier.height(PhantomSpace.s8))
        }
    }
}

/** The one card a settings screen may have: a raised group of rows. */
@Composable
fun RowGroup(caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPhantomColors.current
    if (caption != null) {
        Text(
            text = caption,
            style = PhantomText.caption,
            color = c.text3,
            modifier = Modifier.padding(start = PhantomSpace.s4, bottom = PhantomSpace.s2, top = PhantomSpace.s2),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.raised),
        content = content,
    )
    Spacer(Modifier.height(PhantomSpace.s5))
}

/** The hairline between rows of one [RowGroup], inset to start under the label. */
@Composable
fun RowDivider() {
    val c = LocalPhantomColors.current
    Box(Modifier.fillMaxWidth().padding(start = ROW_TEXT_INSET).height(1.dp).background(c.line))
}

/** A row that opens something: optional icon, label (+ body), optional value, chevron. */
@Composable
fun NavRow(
    label: String,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    body: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = c.text2,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Spacer(Modifier.width(PhantomSpace.s5))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PhantomText.body, color = c.text)
            if (body != null) {
                Text(text = body, style = PhantomText.footnote, color = c.text3)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(PhantomSpace.s2))
            Text(
                text = value,
                style = PhantomText.callout,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = VALUE_MAX),
            )
        }
        Spacer(Modifier.width(PhantomSpace.s2))
        Icon(
            painter = painterResource(R.drawable.lc_chevron_right),
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(PhantomSize.iconS),
        )
    }
}

/** Old settings panes keep their own rows; this only gives them the group's padding. */
@Composable
fun PaneSlot(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2)) { content() }
}

/** A segmented control: one radio group, the chosen segment lifted to `surface`. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PhantomSpace.s3)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.overlay)
            .padding(PhantomSpace.s1)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhantomSpace.touchMinAndroid)
                    .clip(RoundedCornerShape(PhantomRadius.control - PhantomSpace.s0_5))
                    .background(if (on) c.surface else c.overlay)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = PhantomText.subhead,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.text else c.text2,
                )
            }
        }
    }
}

/** Повзунок налаштувань: лінія 4 dp і кругла ручка 20 dp — не товста смуга M3 1.3 з паличкою. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhantomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        onValueChangeFinished = onValueChangeFinished,
        steps = steps,
        valueRange = valueRange,
        thumb = { Box(Modifier.size(SLIDER_THUMB).clip(CircleShape).background(c.accent)) },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val at = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(SLIDER_TRACK)) {
                val y = size.height / 2
                drawLine(c.overlay, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(c.accent, Offset(0f, y), Offset(size.width * at, y), size.height, StrokeCap.Round)
            }
        },
    )
}

/** A sentence under a group that says what the group does. */
@Composable
fun Explainer(text: String) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        style = PhantomText.footnote,
        color = c.text3,
        modifier = Modifier.padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
    )
}

/** Рядок-перемикач групи: усе тіло рядка — ціль дотику, сам перемикач лише показує стан. */
@Composable
fun SwitchRow(label: String, checked: Boolean, body: String? = null, onChange: (Boolean) -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PhantomText.body, color = c.text)
            if (body != null) Text(text = body, style = PhantomText.footnote, color = c.text3)
        }
        Spacer(Modifier.width(PhantomSpace.s3))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.accent,
                uncheckedThumbColor = c.text3,
                uncheckedTrackColor = c.overlay,
                uncheckedBorderColor = c.lineStrong,
            ),
        )
    }
}

/**
 * §7.8 Primary: `accent` fill, `headline` in `onAccent`, radius `control`,
 * 48dp tall. One per region. Disabled is the same button at 40%, not a grey one.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalPhantomColors.current
    ActionButton(label, onClick, modifier, enabled, fill = c.accent, ink = c.onAccent)
}

/**
 * §7.8 Destructive confirm: `danger` fill, ink from [dangerInk]. Only where the
 * action cannot be taken back — a dialog's «delete», never a list row.
 */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalPhantomColors.current
    ActionButton(label, onClick, modifier, enabled, fill = c.danger, ink = dangerInk(c))
}

/**
 * The word on a `danger` fill. §7.8 says «label #fff», and by day that holds:
 * white on #B42318 is 6.6:1. By night `danger` is the light coral #FF7D71 — a
 * TEXT colour on «Ніч» — and `onAct` on it measured 2.2:1. So the ink is
 * whichever of `onAct` (light) and `onAccent` (dark) actually reads on this
 * palette's `danger` — the question `onAccentInk()` asks of the amber.
 */
internal fun dangerInk(c: PhantomColors): Color {
    fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
    return if (contrast(c.onAct, c.danger) >= contrast(c.onAccent, c.danger)) c.onAct else c.onAccent
}

/** §7.8 Ghost: no fill, `subhead` in `text2` — «Скасувати», an inline second choice. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PhantomText.subhead, color = c.text2, maxLines = 1)
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    ink: Color,
) {
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s5),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PhantomText.headline, color = ink, maxLines = 1)
    }
}

/** §7.8: «Disabled: 40% opacity». */
private const val DISABLED_ALPHA = 0.4f

/** Icon 20 + its 20dp gap + the 16dp row padding: where a row's text starts. */
private val ROW_TEXT_INSET = 56.dp
private val ROW_MIN = 56.dp
private val SLIDER_TRACK = 4.dp
private val SLIDER_THUMB = 20.dp
private val VALUE_MAX = 160.dp
