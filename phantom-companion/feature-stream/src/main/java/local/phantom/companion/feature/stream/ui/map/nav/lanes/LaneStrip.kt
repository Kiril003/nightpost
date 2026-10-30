package local.phantom.companion.feature.stream.ui.map.nav.lanes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.net.map.LaneInfo
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.nav.ManeuverGlyph

/**
 * PRESENCE wave 1 — the band's echo in the chrome, for when the eyes are up.
 *
 * The same [LaneInfo] that widens the road on the map, as a row of arrows:
 * the lanes that lead where the route leads are lit, the rest recede. No
 * count, no words — a driver at 90 km/h reads a shape, and the shape is
 * «hold right». A lane the data does not describe draws no arrow at all,
 * keeping its slot so the row still matches the asphalt.
 */
private val CELL = 26.dp
private val GLYPH = 18.dp
private val STRIP = 30.dp

@Composable
internal fun LaneStrip(
    lanes: List<LaneInfo>,
    maneuverModifier: String?,
    accent: Color,
    dim: Color,
) {
    if (lanes.isEmpty()) return
    val spoken = stringResource(R.string.nav_lanes_desc, lanes.count { it.validForManeuver })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(STRIP)
            .padding(horizontal = PhantomSpacings.Base)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (lane in lanes) {
            LaneCell(lane, maneuverModifier, accent, dim)
        }
    }
}

@Composable
private fun LaneCell(lane: LaneInfo, maneuverModifier: String?, accent: Color, dim: Color) {
    val lit = lane.validForManeuver
    val ink = if (lit) accent else dim
    Box(
        modifier = Modifier
            .size(CELL)
            .clip(PhantomRadii.SmallShape)
            .background(
                if (lit) accent.copy(alpha = PhantomAlpha.Wash) else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val indication = laneGlyphModifier(lane, maneuverModifier)
        if (indication == null) {
            // Смуга без розмітки — порожня клітинка, а не вигадана стрілка.
            Box(Modifier.width(GLYPH).height(1.dp).background(dim.copy(alpha = PhantomAlpha.Recessed)))
        } else {
            ManeuverGlyph(
                type = if (indication == "uturn") "uturn" else "turn",
                modifier = indication,
                color = ink,
                dimColor = ink.copy(alpha = PhantomAlpha.Recessed),
                glyphModifier = Modifier.size(GLYPH),
            )
        }
    }
}
