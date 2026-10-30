package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapPrimaryAction
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector

internal enum class CircleSky { ALERT, QUIET, SILENT }

internal data class CircleRow(val peer: TappedPeer, val sky: CircleSky, val oblast: String?)

/**
 * Коло безпеки: мої люди, які самі показують мені, де вони, — і чи над ними зараз тривога.
 * Небо рахується на цьому телефоні з тієї ж стрічки тривог; нікуди не питаємо.
 * Хто давно мовчить — окремо: «тиша» над ним була б вигадкою.
 */
internal fun safetyCircle(
    points: List<PeerPoint>,
    nowMs: Long,
    oblastAt: (Double, Double) -> String?,
    alertOn: (String) -> Boolean,
): List<CircleRow> = points
    .filter { it.isWhere() && !it.expiredAt(nowMs) }
    .distinctBy { it.threadId.ifBlank { it.nodeId } }
    .map { p ->
        val oblast = runCatching { oblastAt(p.lat, p.lon) }.getOrNull()
        val sky = when {
            peerAgeMinutes(p.atMs, nowMs) >= PEER_STALE_MIN -> CircleSky.SILENT
            oblast != null && alertOn(oblast) -> CircleSky.ALERT
            else -> CircleSky.QUIET
        }
        CircleRow(p.tapped(nowMs), sky, oblast)
    }
    .sortedWith(compareBy({ it.sky.ordinal }, { it.peer.name.lowercase() }))

/** Блок у «Небі»: хто з моїх під тривогою, і одним дотиком — «я в безпеці» всім, хто бачить мене. */
@Composable
internal fun SafetyCircleBlock(
    rows: List<CircleRow>,
    listeners: Int,
    onOpen: (TappedPeer) -> Unit,
    onSafe: () -> Unit,
) {
    if (rows.isEmpty() && listeners == 0) return
    val theme = LocalPhantomTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small)) {
        PhantomTitle(text = stringResource(R.string.circle_title), color = theme.inkStrong, fontWeight = FontWeight.Bold)
        rows.forEach { r ->
            val line = when (r.sky) {
                CircleSky.ALERT -> stringResource(R.string.circle_alert, r.oblast.orEmpty())
                CircleSky.QUIET -> r.oblast?.let { stringResource(R.string.circle_quiet, it) } ?: stringResource(R.string.circle_quiet_bare)
                CircleSky.SILENT -> stringResource(R.string.peer_state_stale)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomTouchFloor)
                    .clickable(role = Role.Button) { onOpen(r.peer) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(r.peer.name, 36.dp, toneKey = r.peer.threadId.ifBlank { r.peer.name }, face = r.peer.face)
                Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
                Column(Modifier.weight(1f)) {
                    PhantomBody(text = r.peer.name, color = theme.inkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    PhantomCaption(
                        text = line.replaceFirstChar { it.uppercase() },
                        color = if (r.sky == CircleSky.ALERT) theme.coral else theme.inkSecondary,
                    )
                }
            }
        }
        if (listeners > 0) {
            MapPrimaryAction(PhantomGlyph.HANDOUT.asImageVector(), stringResource(R.string.circle_safe), onSafe)
        } else {
            PhantomCaption(text = stringResource(R.string.circle_no_listeners))
        }
    }
}
