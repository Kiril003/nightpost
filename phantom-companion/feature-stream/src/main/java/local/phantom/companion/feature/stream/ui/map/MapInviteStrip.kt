package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.peers.PeerAvatar

internal fun inviteNames(people: List<MapTrayPerson>): String? = when {
    people.isEmpty() -> null
    people.size <= 2 -> people.joinToString(", ") { it.name }
    else -> people.take(2).joinToString(", ") { it.name } + " +${people.size - 2}"
}

@Composable
internal fun MapInviteStrip(
    people: List<MapTrayPerson>,
    onPersonClick: (MapTrayPerson) -> Unit,
    onPlansClick: () -> Unit,
    onMeetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.gutterPhone)
            .clip(shape)
            .border(1.dp, c.line, shape)
            .testTag(TAG_MAP_PEOPLE_TRAY)
            .padding(start = PhantomSpace.s3, end = PhantomSpace.s2, top = PhantomSpace.s2, bottom = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = people.firstOrNull()
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(enabled = first != null, role = Role.Button) { first?.let(onPersonClick) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (people.isNotEmpty()) {
                Box {
                    people.take(3).forEachIndexed { i, person ->
                        Box(Modifier.offset(x = (i * 24).dp)) {
                            PeerAvatar(name = person.name, size = 36.dp, toneKey = person.id, face = person.face)
                        }
                    }
                    Spacer(Modifier.width((36 + (people.take(3).size - 1) * 24).dp))
                }
                Spacer(Modifier.width(PhantomSpace.s3))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = inviteNames(people) ?: stringResource(R.string.map_invite_empty),
                    style = PhantomText.subhead,
                    color = if (people.isEmpty()) c.text2 else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (first != null) {
                    Text(
                        text = first.status,
                        style = PhantomText.caption,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(PhantomRadius.control))
                .clickable(role = Role.Button, onClick = onPlansClick)
                .padding(horizontal = PhantomSpace.s3),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.map_tray_plans), style = PhantomText.subhead, color = c.text, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(PhantomRadius.control))
                .background(c.accent)
                .clickable(role = Role.Button, onClick = onMeetClick)
                .padding(horizontal = PhantomSpace.s4),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.map_tray_meet), style = PhantomText.subhead, color = c.onAccent, maxLines = 1)
        }
    }
}
