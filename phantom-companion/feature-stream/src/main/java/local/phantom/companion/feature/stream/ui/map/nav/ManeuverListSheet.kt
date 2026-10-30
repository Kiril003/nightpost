package local.phantom.companion.feature.stream.ui.map.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import local.phantom.companion.core.design.layout.bottomChromeInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.ChipEmphasis
import local.phantom.companion.core.design.component.ChipTone
import local.phantom.companion.core.design.component.PhantomChip
import local.phantom.companion.core.design.component.PhantomDivider
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import local.phantom.companion.core.nav.maneuverLines
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.fmtDistance
import local.phantom.companion.feature.stream.ui.map.fmtDuration
import local.phantom.companion.feature.stream.ui.map.profileLabelRes

/**
 * NAV-2 — ManeuverListSheet: full turn-by-turn itinerary with distance, street names,
 * and guidance actions in the app language.
 */
@Composable
internal fun ManeuverListSheet(
    variant: RouteVariant,
    currentStepIndex: Int = 0,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current.color
    val res = LocalContext.current.resources

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceDeep)
            .padding(horizontal = PhantomSpacings.Base)
            .windowInsetsPadding(local.phantom.companion.core.design.layout.bottomChromeInsets()),
    ) {
        // ── Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PhantomSpacings.Base),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                PhantomKicker(text = stringResource(R.string.nav_steps_kicker), color = accent)
                PhantomTitle(
                    text = "${fmtDistance(res, variant.distanceMeters)} · ${fmtDuration(res, variant.durationSeconds)}",
                    color = theme.inkStrong,
                )
            }
            Box(
                modifier = Modifier
                    .size(PhantomTouchFloor)
                    .clip(PhantomRadii.Pill)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.nav_steps_close),
                    tint = theme.inkSecondary,
                )
            }
        }

        // ── Summary badges (turns, profile)
        Row(
            horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
            modifier = Modifier.padding(bottom = PhantomSpacings.Small),
        ) {
            PhantomChip(
                label = androidx.compose.ui.res.pluralStringResource(
                    local.phantom.companion.feature.stream.R.plurals.route_maneuver_count,
                    variant.steps.size,
                    variant.steps.size,
                ),
                tone = ChipTone.Accent,
                emphasis = ChipEmphasis.Outline,
            )
            PhantomChip(
                label = stringResource(profileLabelRes(variant.profile)),
                tone = ChipTone.Neutral,
                emphasis = ChipEmphasis.Quiet,
            )
        }

        // ЗВІДКИ ХВИЛИНИ. Сюди переїхав рядок, що стояв під КОЖНИМ офлайновим
        // маршрутом на смузі («офлайн · без класу доріг — перевіряй очима»):
        // вирок власника 18.09 — «нафіга мені ця інформація… воно заважає».
        //
        // Факт не зник і не помʼякшав, він лише став на своє місце: деталі —
        // це те, куди йдуть по ЦЮ відповідь, а не те, що лізе на очі при
        // кожному маршруті. Слова беруться з RouteBasis — одного місця, яке
        // вже читають і голос, і інструмент моделі; окремого формулювання тут
        // нема навмисно, бо два формулювання одного твердження — це два місця,
        // де воно може посилитись до впевненого, і лише одне з них перевірять.
        //
        // Третій чип із сирим `speedBasis` («ASSUMED») тут і зник: назва
        // константи рушія — не мова для людини.
        local.phantom.companion.core.net.map.RouteBasis.summary(variant)?.let { basis ->
            PhantomKicker(
                text = stringResource(local.phantom.companion.feature.stream.R.string.route_basis_kicker),
                color = theme.inkSecondary,
            )
            PhantomCaption(
                text = basis,
                color = theme.inkSecondary,
                modifier = Modifier.padding(bottom = PhantomSpacings.Base),
            )
        }

        val paw = variant.parkAndWalk
        if (paw != null) {
            local.phantom.companion.feature.stream.ui.map.ParkAndWalkLine(paw)
            Spacer(Modifier.height(PhantomSpacings.Small))
        }

        PhantomDivider()

        // ── Steps list
        val locale = LocalConfiguration.current.locales[0]
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = PhantomSpacings.Base),
        ) {
            itemsIndexed(variant.steps) { idx, step ->
                val isCurrent = idx == currentStepIndex
                val lines = maneuverLines(step.type, step.modifier, step.roadName, step.exitNumber, locale)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PhantomRadii.MediumShape)
                        .background(if (isCurrent) accent.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isCurrent) accent.copy(alpha = 0.4f) else theme.glassBorder.copy(alpha = 0.3f),
                            shape = PhantomRadii.MediumShape,
                        )
                        .padding(PhantomSpacings.Base),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
                ) {
                    // Turn Glyph
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(PhantomRadii.MediumShape)
                            .background(theme.glassPanel.copy(alpha = 0.7f))
                            .border(1.dp, if (isCurrent) accent else theme.glassBorder, PhantomRadii.MediumShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ManeuverGlyph(
                            type = step.type,
                            modifier = step.modifier,
                            color = if (isCurrent) accent else theme.inkStrong,
                            dimColor = theme.inkSecondary,
                            glyphModifier = Modifier.size(28.dp),
                            turnAngleDeg = step.turnAngleDeg,
                            exitNumber = step.exitNumber,
                        )
                    }

                    // Description & Street name
                    Column(modifier = Modifier.weight(1f)) {
                        PhantomBody(
                            text = lines.action,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrent) accent else theme.inkStrong,
                        )
                        lines.detail?.let {
                            PhantomCaption(
                                text = it,
                                color = theme.inkSecondary,
                            )
                        }
                    }

                    // Distance & Duration
                    Column(horizontalAlignment = Alignment.End) {
                        PhantomDataText(
                            text = fmtDistance(res, step.distanceMeters),
                            color = if (isCurrent) accent else theme.inkStrong,
                        )
                        if (step.durationSeconds > 0) {
                            PhantomCaption(
                                text = fmtDuration(res, step.durationSeconds),
                                color = theme.inkSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
