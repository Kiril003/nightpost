package local.phantom.companion.feature.stream.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerLiveShare
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.feature.stream.permission.LocationAccess
import local.phantom.companion.feature.stream.permission.rememberLocationAccess
import local.phantom.companion.core.design.R as DesignR

/**
 * «Поділитися положенням» — з картки ОДНОГО контакта, руками, на визначений час.
 * Малює рядки всередині групи картки; рамку дає той, хто кличе.
 *
 * Строк вибирається ДО того, як щось поїде: сеанс без межі — це шар присутності
 * на одну людину, і саме він одного дня стає шаром на всіх. «До зупинки» котить
 * строк кожного листа, тож замовклий телефон гасне сам.
 *
 * Вирок «чи дійде» стоїть ПІД вибором, а не після натиску. Без ключа дія не
 * мовчить і не вдає, що поклала лист у чергу: вона каже, чого бракує.
 */
@Composable
fun PeerShareLocationCard(
    peerName: String,
    reach: PeerLiveShare.Reach,
    session: PeerLiveShare.Session?,
    remainingMs: Long?,
    onStart: (PeerLiveShare.Span) -> Unit,
    onStop: () -> Unit,
    /** Чи є ЩО надсилати. Без виміру сеанс не сміє казати «ділюсь». */
    position: PeerLiveShare.Position = PeerLiveShare.Position.HELD,
    /** Коли ця людина востаннє показала НАМ себе. Null — не ділиться. */
    theirPositionAtMs: Long? = null,
    nowMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    var picking by remember(peerName) { mutableStateOf(false) }

    // «Нічого не намальовано» і «людина не ділиться» — одне на вигляд і різне по суті.
    val theirs = if (theirPositionAtMs == null) {
        stringResource(R.string.peer_share_none, peerName)
    } else {
        ageLabel(theirPositionAtMs, nowMs)
    }

    if (session != null) {
        // Сеанс, з якого ще нічого не поїхало й покласти в лист нема чого, не
        // сміє казати «ділюсь»: людина покладається на те, що її знайдуть.
        val promises = session.lastSentAtMs > 0L || position == PeerLiveShare.Position.HELD
        Column(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PhantomSpace.s4)) {
                Text(
                    text = if (promises) {
                        stringResource(R.string.peer_share_banner, peerName)
                    } else {
                        stringResource(R.string.peer_share_wait, peerName)
                    }.sentence(),
                    style = PhantomText.body,
                    color = c.text,
                )
                ShareNote(leftLabel(session, remainingMs).sentence(), c.text2)
                ShareNote(reachLabel(reach, peerName).sentence(), reachTint(reach, c.text2, c.danger))
                if (position != PeerLiveShare.Position.HELD) {
                    ShareNote(positionLabel(position).sentence(), c.danger)
                }
                ShareNote(theirs.sentence(), c.text3)
            }
            if (position == PeerLiveShare.Position.NO_PERMISSION) {
                val grant = rememberLocationGrant()
                GroupDivider()
                CardRow(
                    label = stringResource(R.string.peer_share_grant).sentence(),
                    icon = DesignR.drawable.lc_locate,
                    tone = RowTone.Action,
                    onClick = grant,
                )
            }
            GroupDivider()
            CardRow(
                label = stringResource(R.string.peer_share_stop).sentence(),
                icon = DesignR.drawable.lc_close,
                tone = RowTone.Danger,
                onClick = { picking = false; onStop() },
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        CardRow(
            label = stringResource(R.string.peer_share_open).sentence(),
            icon = DesignR.drawable.lc_locate,
            tone = if (reach == PeerLiveShare.Reach.NO_KEY) RowTone.Plain else RowTone.Action,
            onClick = { picking = !picking },
            body = theirs.sentence(),
        )
        if (!picking) return@Column
        Column(
            modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s4),
        ) {
            Text(
                text = reachLabel(reach, peerName).sentence(),
                style = PhantomText.footnote,
                color = reachTint(reach, c.text2, c.danger),
            )
            if (reach != PeerLiveShare.Reach.NO_KEY) {
                Spacer(Modifier.height(PhantomSpace.s3))
                Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                    SpanChoice(R.string.peer_share_span_quarter, Modifier.weight(1f)) {
                        picking = false
                        onStart(PeerLiveShare.Span.QUARTER_HOUR)
                    }
                    SpanChoice(R.string.peer_share_span_hour, Modifier.weight(1f)) {
                        picking = false
                        onStart(PeerLiveShare.Span.HOUR)
                    }
                    SpanChoice(R.string.peer_share_span_stop, Modifier.weight(1f)) {
                        picking = false
                        onStart(PeerLiveShare.Span.UNTIL_STOP)
                    }
                }
            }
            Spacer(Modifier.height(PhantomSpace.s2))
            QuietButton(
                label = stringResource(R.string.peer_share_cancel).sentence(),
                color = c.text2,
                onClick = { picking = false },
            )
        }
    }
}

@Composable
private fun SpanChoice(labelRes: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    FilledButton(
        label = stringResource(labelRes).sentence(),
        fill = c.overlay,
        ink = c.accentText,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ShareNote(text: String, color: Color) {
    Spacer(Modifier.height(PhantomSpace.s1))
    Text(text = text, style = PhantomText.footnote, color = color)
}

@Composable
private fun leftLabel(session: PeerLiveShare.Session, remainingMs: Long?): String =
    if (session.span == PeerLiveShare.Span.UNTIL_STOP || remainingMs == null) {
        stringResource(R.string.peer_share_left_stop)
    } else {
        // Округлення ВГОРУ: «ще 0 хв» на живому сеансі — неправда про те, що зараз відбувається.
        stringResource(R.string.peer_share_left_min, ((remainingMs + 59_999L) / 60_000L).toInt())
    }

@Composable
private fun reachLabel(reach: PeerLiveShare.Reach, peerName: String): String = when (reach) {
    PeerLiveShare.Reach.NO_KEY -> stringResource(R.string.peer_share_no_key, peerName)
    PeerLiveShare.Reach.MESH -> stringResource(R.string.peer_share_reach_nearby)
    PeerLiveShare.Reach.RELAY -> stringResource(R.string.peer_share_reach_relay)
    PeerLiveShare.Reach.QUEUED -> stringResource(R.string.peer_share_reach_queued)
}

/** Вік чужої точки словами. Число тут важливіше за слово «онлайн». */
@Composable
private fun ageLabel(atMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - atMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> stringResource(R.string.peer_share_age_now)
        minutes < 60L -> stringResource(R.string.peer_share_age_min, minutes.toInt())
        else -> stringResource(R.string.peer_share_age_hour, (minutes / 60L).toInt())
    }
}

private fun reachTint(reach: PeerLiveShare.Reach, calm: Color, danger: Color): Color =
    if (reach == PeerLiveShare.Reach.NO_KEY) danger else calm

/**
 * Друга половина «чи дійде»: чи є що везти. Каже не лише «немає», а ЧОМУ —
 * два «немає» лікуються різним: одне дотиком, друге виходом надвір.
 */
@Composable
internal fun positionLabel(position: PeerLiveShare.Position): String = when (position) {
    PeerLiveShare.Position.HELD -> ""
    PeerLiveShare.Position.SEARCHING -> stringResource(R.string.peer_share_pos_searching)
    PeerLiveShare.Position.NO_PERMISSION -> stringResource(R.string.peer_share_pos_denied)
}

/**
 * Дозвіл на локацію — з того самого місця, де сказано про його брак.
 * Живе тут, бо плашка на склі мапи просить рівно те саме й тим самим жестом.
 */
@Composable
internal fun rememberLocationGrant(): () -> Unit {
    val access = rememberLocationAccess(
        PermissionReason(
            DesignR.drawable.lc_locate,
            R.string.perm_location_share_title,
            R.string.perm_location_share_body,
        ),
    )
    return remember(access) { { access.request { } } }
}

/** Мапа просить місце в мить, коли людина тисне «Я». */
@Composable
internal fun rememberMapLocationGate(): LocationAccess = rememberLocationAccess(
    PermissionReason(
        DesignR.drawable.lc_locate,
        R.string.perm_location_map_title,
        R.string.perm_location_map_body,
    ),
)
