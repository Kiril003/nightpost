package local.phantom.companion.feature.stream.ui.map.cockpit

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.PhantomDivider
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon

@Composable
internal fun CarCockpitSheet(
    open: Boolean,
    onClose: () -> Unit,
    onLaunchMount: () -> Unit,
    onReportRoadHazard: (String) -> Unit,
    onQuickSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val theme = LocalPhantomTheme.current

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = theme.surfaceBase,
        shape = PhantomRadii.SheetTop,
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhantomTitle(
                    text = stringResource(R.string.cockpit_title),
                    color = theme.inkStrong,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(PhantomTouchFloor)
                        .clip(PhantomRadii.Pill)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cockpit_close),
                        tint = theme.inkSecondary,
                    )
                }
            }

            CockpitRow(
                glyph = PhantomGlyph.CAR,
                title = stringResource(R.string.cockpit_mount_title),
                sub = stringResource(R.string.cockpit_mount_sub),
                onClick = {
                    onLaunchMount()
                    onClose()
                },
            )

            PhantomDivider()
            PhantomKicker(text = stringResource(R.string.cockpit_marks_kicker), color = theme.inkSecondary)
            COCKPIT_MARKS.forEach { mark ->
                CockpitRow(
                    glyph = mark.glyph,
                    title = stringResource(mark.label),
                    sub = null,
                    onClick = {
                        onReportRoadHazard(mark.kind)
                        onClose()
                    },
                )
            }

            PhantomDivider()
            PhantomKicker(text = stringResource(R.string.cockpit_find_kicker), color = theme.inkSecondary)
            COCKPIT_SERVICES.forEach { service ->
                CockpitRow(
                    glyph = service.glyph,
                    title = stringResource(service.label),
                    sub = null,
                    onClick = {
                        onQuickSearch(service.query)
                        onClose()
                    },
                )
            }

            Spacer(Modifier.height(PhantomSpacings.Small))
        }
    }
}

/** Позначка на дорозі: [kind] — ключ шару позначок, [label] — підпис. */
internal data class CockpitMark(val kind: String, @StringRes val label: Int, val glyph: PhantomGlyph)

/** Сервіс поруч: [query] іде в пошук мапи як є. */
internal data class CockpitService(val query: String, @StringRes val label: Int, val glyph: PhantomGlyph)

internal val COCKPIT_MARKS = listOf(
    CockpitMark(kind = "ДТП", label = R.string.cockpit_mark_crash, glyph = PhantomGlyph.ACCIDENT),
    CockpitMark(kind = "ремонт", label = R.string.cockpit_mark_repair, glyph = PhantomGlyph.REPAIR),
    CockpitMark(kind = "камера", label = R.string.cockpit_mark_camera, glyph = PhantomGlyph.CAMERA),
)

internal val COCKPIT_SERVICES = listOf(
    CockpitService(query = "АЗС", label = R.string.cockpit_find_fuel, glyph = PhantomGlyph.FUEL),
    CockpitService(query = "Зарядка для електромобілів", label = R.string.cockpit_find_charging, glyph = PhantomGlyph.EV),
    CockpitService(query = "шиномонтаж автосервіс СТО", label = R.string.cockpit_find_tyres, glyph = PhantomGlyph.TYRE),
    CockpitService(query = "автомийка", label = R.string.cockpit_find_wash, glyph = PhantomGlyph.CAR),
    CockpitService(query = "підземний паркінг", label = R.string.cockpit_find_parking, glyph = PhantomGlyph.PARKING),
)

@Composable
private fun CockpitRow(
    glyph: PhantomGlyph,
    title: String,
    sub: String?,
    onClick: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(PhantomRadii.MediumShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
    ) {
        PhantomGlyphIcon(glyph = glyph, tint = theme.inkSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            PhantomBody(text = title, color = theme.inkStrong)
            if (sub != null) PhantomCaption(text = sub, color = theme.inkSecondary)
        }
    }
}
