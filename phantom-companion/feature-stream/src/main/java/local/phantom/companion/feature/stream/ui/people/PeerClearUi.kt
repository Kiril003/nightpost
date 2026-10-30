package local.phantom.companion.feature.stream.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerPurge
import local.phantom.companion.feature.stream.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import local.phantom.companion.core.design.R as DesignR

/**
 * Несиметричний спротив, той самий, що на «скільки тримати історію»: стерти —
 * два свідомі дотики, передумати — будь-який жест. Друга форма для того самого
 * наслідку вчила б читати кнопки, а не слова.
 */
@Composable
internal fun ArmedAction(
    label: String,
    armedLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    var armed by remember(label) { mutableStateOf(false) }
    val ink = if (armed) c.canvas else c.danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(ArmedShape)
            .then(if (armed) Modifier.background(c.danger) else Modifier.border(1.dp, c.danger, ArmedShape))
            .clickable(role = Role.Button) {
                if (armed) {
                    onConfirm()
                    armed = false
                } else {
                    armed = true
                }
            }
            .padding(horizontal = PhantomSpace.s4),
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_trash),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(PhantomSize.iconM),
        )
        Spacer(Modifier.width(PhantomSpace.s2))
        Text(text = (if (armed) armedLabel else label).sentence(), style = PhantomText.headline, color = ink)
    }
}

/**
 * Скільки саме зникне і за який час — перед дотиком, не після нього.
 *
 * [note] — те, що правда лише іноді: остання репліка групи забирає й саму
 * групу, а прибрана людина лишає рядки в спільних чатах.
 */
@Composable
internal fun ClearBlock(
    scope: PeerPurge.Scope,
    preview: String,
    note: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = preview.sentence(), style = PhantomText.body, color = c.text)
        if (note != null) {
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(text = note, style = PhantomText.footnote, color = c.text3)
        }
        Spacer(Modifier.height(PhantomSpace.s4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArmedAction(
                label = pluralStringResource(R.plurals.peer_clear_wipe, scope.count, scope.count),
                armedLabel = stringResource(R.string.peer_clear_armed),
                onConfirm = onConfirm,
            )
            Spacer(Modifier.width(PhantomSpace.s2))
            QuietButton(
                label = stringResource(R.string.peer_card_remove_no),
                color = c.text,
                onClick = onCancel,
                outlined = true,
            )
        }
    }
}

/** «3 травня — 11 серпня», або один день, коли розмова прожила один день. */
@Composable
internal fun purgeSpan(scope: PeerPurge.Scope): String = remember(scope.fromMs, scope.toMs) {
    val day = SimpleDateFormat("d MMMM", Locale.getDefault())
    val from = day.format(Date(scope.fromMs))
    val to = day.format(Date(scope.toMs))
    if (from == to) to else "$from — $to"
}

private val ArmedShape = RoundedCornerShape(PhantomRadius.control)
