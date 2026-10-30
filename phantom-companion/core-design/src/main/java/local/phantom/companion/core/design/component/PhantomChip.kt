package local.phantom.companion.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.DISABLED_INK_ALPHA
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomElevation
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.onAccentInk
import local.phantom.companion.core.design.theme.phantomElevation
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors

/**
 * The chip — ONE of them.
 *
 * Counted 2026-08-15: **35 separate `*Chip`/`*Pill` implementations**, 76% of
 * them `private`, so every new screen wrote a thirty-sixth. Reading six of
 * them side by side is the whole argument for this file — they disagreed on
 * every axis, and three of the disagreements were defects:
 *
 *  1. **Selection was carried by hue alone.** `FacetPill`, `ToggleChip`,
 *     `FilterChip` and `NewGroupChip` each said "selected" by changing the
 *     fill and the ink to amber and nothing else. That is invisible under a
 *     red filter, in greyscale, and to a colour-blind operator. Here selection
 *     also grows a leading ✓ (shape), goes SemiBold (weight), doubles its
 *     edge, and sets the `selected` semantic so TalkBack says it.
 *  2. **The accent was used as ink.** Three of them coloured the selected
 *     label `theme.primary` (or, in `ToggleChip`, a hardcoded `0xFFF4AF25`
 *     that ignores the theme outright). The accent is a FILL — it measures
 *     1.9:1 as text on «День», which is unreadable. Accent ink is the
 *     `accentText` token; ink on a filled chip is [onAccentInk], which asks
 *     which ink actually wins.
 *  3. **None of them could be hit.** `FilterChip` stood ~26dp tall,
 *     `ToggleChip` and `FacetPill` ~28dp — all far under the 48dp floor, all
 *     invisible to `TouchTargetFirewallTest` because that scan reads
 *     `.size()`, not padding. A tappable chip here is 48dp, always.
 *
 * [selected] is `Boolean?` on purpose. `null` means "this chip does not take
 * part in a selection" — an action or a status, with no ✓ slot reserved and
 * no `selected` semantic. `true`/`false` reserve the slot in both states, so
 * ticking one chip never reflows the row it sits in.
 */
@Composable
fun PhantomChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
    emphasis: ChipEmphasis = ChipEmphasis.Quiet,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    count: Int? = null,
) {
    val skin = chipSkin(LocalPhantomColors.current, tone, emphasis, selected, enabled)

    val shape = PhantomRadii.Pill
    val interaction = when {
        onClick == null || !enabled -> Modifier
        selected != null -> Modifier.selectable(
            selected = selected,
            role = Role.Tab,
            onClick = onClick,
        )
        else -> Modifier.clickable(role = Role.Button, onClick = onClick)
    }

    Row(
        modifier = modifier
            .phantomElevation(skin.elevation, shape)
            .heightIn(min = if (onClick == null) STATIC_MIN else TAP_MIN)
            .clip(shape)
            .background(skin.fill)
            .border(skin.edgeWidth, skin.edge, shape)
            .then(interaction)
            .padding(horizontal = PhantomSpacings.Base),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        if (selected != null) {
            // Drawn transparent rather than omitted: the slot is held in both
            // states so ticking one chip never reflows the row it sits in.
            Icon(
                painter = painterResource(R.drawable.lc_check),
                contentDescription = null,
                tint = if (skin.mark) skin.ink else Color.Transparent,
                modifier = Modifier.size(GLYPH),
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = skin.ink,
                modifier = Modifier.size(GLYPH),
            )
        }
        Text(
            // Один регістр на всі чипи: «Патруль» поруч із «ДТП», не «патруль».
            text = label.replaceFirstChar { it.uppercase() },
            // §3.2: a chip speaks in `subhead`; the weight is the selection's.
            style = PhantomText.subhead,
            color = skin.ink,
            fontWeight = skin.weight,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (count != null) {
            PhantomDataText(
                text = count.toString(),
                color = skin.ink.copy(alpha = PhantomAlpha.Emphasis),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Which meaning the chip carries. Each maps onto tokens the palettes tune. */
enum class ChipTone { Neutral, Accent, Positive, Danger }

/**
 * How loudly it is drawn.
 *  - [Quiet]   — a wash, no edge. A resting filter, a status.
 *  - [Outline] — raised on the surface tone, no edge. An action offered, not urged.
 *  - [Solid]   — filled with the tone. At most ONE per row.
 */
enum class ChipEmphasis { Quiet, Outline, Solid }

/**
 * Everything the chip draws, resolved with no composition in sight so it can
 * be asserted on the JVM — `PhantomChipSkinTest` is the point of this being a
 * data class rather than eight `if`s inlined in the layout.
 */
data class ChipSkin(
    val fill: Color,
    val edge: Color,
    val edgeWidth: Dp,
    val ink: Color,
    val mark: Boolean,
    val weight: FontWeight,
    val elevation: PhantomElevation,
)

fun chipSkin(
    c: PhantomColors,
    tone: ChipTone,
    emphasis: ChipEmphasis,
    selected: Boolean?,
    enabled: Boolean,
): ChipSkin {
    val fillHue = when (tone) {
        ChipTone.Neutral -> c.raised
        ChipTone.Accent -> c.accent
        ChipTone.Positive -> c.success
        ChipTone.Danger -> c.danger
    }
    // `accent` as a word measures 1.9:1 on «День»; `accentText` is the same
    // signal at ink weight. See AccentAsInkFirewallTest.
    val inkHue = when (tone) {
        ChipTone.Neutral -> c.text
        ChipTone.Accent -> c.accentText
        ChipTone.Positive -> c.success
        ChipTone.Danger -> c.danger
    }

    val on = selected != false
    val restingInk = if (on) inkHue else c.text2

    val skin = when (emphasis) {
        ChipEmphasis.Quiet -> ChipSkin(
            fill = fillHue.copy(alpha = if (on) PhantomAlpha.Wash else PhantomAlpha.Ghost),
            edge = Color.Transparent,
            edgeWidth = 0.dp,
            ink = restingInk,
            mark = selected == true,
            weight = if (selected == true) FontWeight.SemiBold else FontWeight.Medium,
            elevation = PhantomElevation.Flat,
        )
        // 26.09: «обведені пілюлі як єдиний тип кнопки» — ШІшний штамп. Запропонована
        // дія тепер піднята тоном поверхні; кант лишився лише за обраним — форма стану.
        ChipEmphasis.Outline -> ChipSkin(
            fill = if (selected == true) inkHue.copy(alpha = PhantomAlpha.Ghost) else c.raised,
            edge = if (selected == true) inkHue.copy(alpha = PhantomAlpha.Secondary) else Color.Transparent,
            edgeWidth = if (selected == true) 2.dp else 0.dp,
            ink = restingInk,
            mark = selected == true,
            weight = if (selected == true) FontWeight.SemiBold else FontWeight.Medium,
            elevation = if (selected == true) PhantomElevation.Flat else PhantomElevation.Raised,
        )
        ChipEmphasis.Solid -> ChipSkin(
            fill = fillHue,
            edge = Color.Transparent,
            edgeWidth = 0.dp,
            ink = fillHue.onAccentInk(),
            mark = selected == true,
            weight = FontWeight.SemiBold,
            elevation = PhantomElevation.Raised,
        )
    }

    if (enabled) return skin
    return skin.copy(
        fill = skin.fill.copy(alpha = skin.fill.alpha * PhantomAlpha.Recessed),
        edge = skin.edge.copy(alpha = skin.edge.alpha * PhantomAlpha.Recessed),
        ink = c.text.copy(alpha = DISABLED_INK_ALPHA),
        elevation = PhantomElevation.Flat,
    )
}

private val TAP_MIN: Dp = 48.dp

/** Untappable: no floor to meet, and a status chip in a dense row is small. */
private val STATIC_MIN: Dp = 28.dp

private val GLYPH: Dp = 16.dp
