package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.component.ChipEmphasis
import local.phantom.companion.core.design.component.ChipTone
import local.phantom.companion.core.design.component.PhantomChip
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomDisplay
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.theme.onAccentInk
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R

/**
 * M-C01 / СИРЕНА — one oblast's threat brief: status, the typed threat (from
 * [ThreatMeta] — one table for every alarm surface), how long it has lasted
 * (live ticker), the raion-level alarms the detailed feed knows about, the
 * feed's own notes, and the shelter verb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreatBriefSheet(
    oblast: String,
    state: AlertState?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onRouteToShelter: (() -> Unit)? = null
) {
    val theme = LocalPhantomTheme.current
    val isActive = state?.active == true

    // Live «триває …» — a 30-second heartbeat, not a per-frame clock.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.surfaceBase,
        shape = PhantomRadii.SheetTop,
        scrimColor = theme.surfaceDeep.copy(alpha = PhantomAlpha.Secondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = readingGutter(PhantomSpacings.Base),
                    vertical = PhantomSpacings.Base,
                ),
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Base)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhantomDisplay(
                    text = oblast,
                    color = theme.inkStrong,
                    modifier = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.width(PhantomSpacings.Small))

                // A missing feed entry is NOT an all-clear. Silence gets its
                // own word; only a state we actually hold may say «ВІДБІЙ».
                PhantomChip(
                    label = when {
                        state == null -> stringResource(R.string.kt_threat_no_data)
                        isActive -> stringResource(R.string.kt_threat_alert)
                        else -> stringResource(R.string.kt_threat_clear)
                    },
                    tone = when {
                        state == null -> ChipTone.Neutral
                        isActive -> ChipTone.Danger
                        else -> ChipTone.Positive
                    },
                    emphasis = ChipEmphasis.Quiet,
                )
            }

            if (isActive && state != null) {
                run {
                    val meta = state.threatType.meta
                    val hue = Color(meta.colorArgb)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PhantomRadii.MediumShape)
                            .background(hue.copy(alpha = PhantomAlpha.Ghost))
                            .border(
                                MapChromeMetrics.Hairline,
                                hue.copy(alpha = PhantomAlpha.Recessed),
                                PhantomRadii.MediumShape,
                            )
                            .padding(PhantomSpacings.Base),
                        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PhantomGlyphIcon(glyph = meta.glyph, tint = hue, modifier = Modifier.size(THREAT_GLYPH))
                        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro)) {
                            PhantomBody(
                                text = meta.label,
                                color = hue,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.changedEpochMs?.let {
                                PhantomDataText(
                                    text = stringResource(R.string.kt_threat_lasting, durationShortUk(it, nowMs)),
                                    color = theme.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // Raion-level truth: shown whether or not the oblast-wide siren
            // is on («тривога в районах» matters most when it is NOT).
            val districts = state?.districts.orEmpty()
            if (districts.isNotEmpty()) {
                PhantomKicker(text = stringResource(R.string.kt_threat_local))
                PhantomBody(
                    text = buildString {
                        districts.take(12).forEachIndexed { i, d ->
                            if (i > 0) append('\n')
                            append("• ").append(d.name)
                        }
                        if (districts.size > 12) append(MapText.s(R.string.kt_threat_more, districts.size - 12))
                    },
                    color = theme.ink,
                )
            }

            if (isActive && state != null) {
                if (!state.notes.isNullOrBlank()) {
                    PhantomKicker(text = stringResource(R.string.kt_threat_details))
                    PhantomBody(text = state.notes, color = theme.ink)
                }

                if (onRouteToShelter != null) {
                    val shelterName = stringResource(R.string.kt_threat_shelter)
                    Button(
                        onClick = onRouteToShelter,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.coral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = PhantomTouchFloor)
                            .semantics(mergeDescendants = true) {
                                contentDescription = shelterName
                            },
                        shape = PhantomRadii.MediumShape,
                    ) {
                        PhantomBody(
                            text = shelterName,
                            color = theme.coral.onAccentInk(),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else if (districts.isEmpty()) {
                PhantomBody(
                    text = if (state == null) {
                        stringResource(R.string.kt_threat_unknown)
                    } else {
                        stringResource(R.string.kt_threat_none)
                    },
                    color = theme.inkSecondary,
                )
            }

            Spacer(modifier = Modifier.height(PhantomSpacings.Large))
        }
    }
}
