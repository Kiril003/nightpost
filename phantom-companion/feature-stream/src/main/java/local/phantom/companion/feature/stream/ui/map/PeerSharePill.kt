package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomProse
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.net.peer.PeerLiveShare
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.people.rememberLocationGrant

/**
 * «Ділюсь положенням з N, ще 42 хв · зупинити» — на СКЛІ, доки триває.
 *
 * Стоїть у тій самій лівій колонці, що й тривога й довіра до позиції: це
 * речення про стан, і воно належить смузі речень про стан. Дію воно несе в
 * собі, а не окремим орбом поруч — саме орб серед тверджень колись і зробив
 * цю колонку рваною.
 *
 * Другий рядок каже, чи лист ДОХОДИТЬ. Плашка, яка каже лише «ділюсь», обіцяє
 * доставку, якої може не бути: людина, що вирішує, чи покластись на те, що її
 * знайдуть, мусить це знати наперед, а не після.
 *
 * Жодного пульсу й жодного нескінченного переходу: сеанс — не тривога, а
 * анімація, що крутиться годину, коштує кадр і градус.
 */
@Composable
internal fun PeerSharePill(
    banner: PeerLiveShare.Banner,
    nowMs: Long,
    onStop: (String) -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current.color
    val interaction = remember { MutableInteractionSource() }
    val grantInteraction = remember { MutableInteractionSource() }

    val endsAt = banner.endsAtMs
    val left = if (endsAt == null) {
        stringResource(R.string.peer_share_left_stop)
    } else {
        // Вгору: «ще 0 хв» на живому сеансі — неправда про те, що зараз є.
        val minutes = ((endsAt - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L
        stringResource(R.string.peer_share_left_min, minutes.toInt())
    }
    val reachLine = when (banner.reach) {
        PeerLiveShare.Reach.NO_KEY -> stringResource(R.string.peer_share_no_key, banner.name)
        PeerLiveShare.Reach.MESH -> stringResource(R.string.peer_share_reach_nearby)
        PeerLiveShare.Reach.RELAY -> stringResource(R.string.peer_share_reach_relay)
        PeerLiveShare.Reach.QUEUED -> stringResource(R.string.peer_share_reach_queued)
    }
    // Доки з сеансу не поїхало нічого й міряти нема чим, плашка каже про
    // ПОЛОЖЕННЯ, а не про шлях: «телефон поруч — піде одразу» над порожнечею
    // — це обіцянка доставки того, чого немає.
    val promises = PeerLiveShare.promises(banner)
    val askGrant = !promises && banner.position == PeerLiveShare.Position.NO_PERMISSION
    // Вичерпний when по стану положення, а не `else`: пʼятий стан, який колись
    // тут зʼявиться, мусить не зібратись, а не мовчки прикинутись «шукаю».
    val line = if (promises) {
        reachLine
    } else {
        when (banner.position) {
            PeerLiveShare.Position.NO_PERMISSION -> stringResource(R.string.peer_share_pos_denied_tap)
            PeerLiveShare.Position.SEARCHING -> stringResource(R.string.peer_share_pos_searching)
            // Недосяжне: щойно вимір є, сеанс уже обіцяє.
            PeerLiveShare.Position.HELD -> reachLine
        }
    }
    val tint = when {
        !promises || banner.reach == PeerLiveShare.Reach.NO_KEY -> theme.coral
        else -> accent
    }
    val grant = rememberLocationGrant()

    Row(
        modifier = Modifier
            .heightIn(min = if (askGrant) PhantomTouchFloor else MapChromeMetrics.Ribbon)
            .clip(PhantomRadii.Pill)
            .background(theme.glassPanel.copy(alpha = MapChromeMetrics.PANEL_ALPHA))
            .border(MapChromeMetrics.Hairline, theme.glassBorder, PhantomRadii.Pill)
            .padding(horizontal = MapChromeMetrics.PillPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        Box(
            modifier = Modifier
                .size(DOT_SIZE)
                .clip(PhantomRadii.Pill)
                .background(tint),
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                // Сама фраза і є дією: другого слова поруч із «зупинити» на
                // 390 px просто немає куди покласти.
                .then(
                    if (askGrant) {
                        Modifier.clickable(
                            interactionSource = grantInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = grant,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            PhantomProse(
                text = if (promises) {
                    stringResource(R.string.peer_share_banner, banner.name) + " · " + left
                } else {
                    stringResource(R.string.peer_share_wait, banner.name) + " · " + left
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Два рядки, бо жодна з чотирьох фраз про шлях не влазить в один
            // на 390 px: «поруч не видно — потримає сховок…» обривалась на
            // третьому слові, і плашка казала протилежне тому, що мала.
            PhantomCaption(text = line, color = tint, maxLines = 2)
        }
        // Ціль ≥48 стоїть ПЕРШОЮ в ланцюжку: скло лишається вузьким, палець
        // ловить норму.
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(PhantomRadii.Pill)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                ) { onStop(banner.peerId) }
                .padding(horizontal = PhantomSpacings.Small),
            contentAlignment = Alignment.Center,
        ) {
            PhantomProse(text = stringResource(R.string.peer_share_stop), color = theme.coral)
        }
    }
}

private val DOT_SIZE = 6.dp
