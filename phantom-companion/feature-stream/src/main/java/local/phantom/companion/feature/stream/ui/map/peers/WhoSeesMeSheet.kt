package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.theme.onAccentInk
import local.phantom.companion.feature.stream.ui.map.MapSecondaryAction
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import local.phantom.companion.core.design.theme.PhantomRadii
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerSeeRow
import local.phantom.companion.core.data.peers.PeerSelfShow
import local.phantom.companion.core.design.component.PhantomDivider
import local.phantom.companion.core.design.layout.bottomChromeInsets
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.feature.stream.R

private val CHOICES: List<Pair<PeerPrecision?, Int>> = listOf(
    null to R.string.peer_where_choice_none,
    PeerPrecision.DISTRICT to R.string.peer_where_choice_district,
    PeerPrecision.QUARTER to R.string.peer_where_choice_quarter,
    PeerPrecision.EXACT to R.string.peer_where_choice_exact,
)

/** За замовчуванням у кожного «Нічого»: побачити можна лише того, хто сам дозволив саме тобі. */
@Composable
internal fun WhoSeesMeSheet(
    rows: List<PeerSeeRow>,
    accentColor: Color,
    onSet: (String, PeerPrecision?) -> Unit,
    onVanish: () -> Unit,
    self: PeerSelfShow = PeerSelfShow(),
    onHide: (String?, Long) -> Unit = { _, _ -> },
    onLowBattery: (Boolean) -> Unit = {},
    near: List<TappedPeer> = emptyList(),
    onOpenPeer: (TappedPeer) -> Unit = {},
    world: MyWorldSummary = MyWorldSummary(),
    onOpenWorld: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    onMeet: (() -> Unit)? = null,
    onAddPerson: (() -> Unit)? = null,
) {
    val theme = LocalPhantomTheme.current
    var confirm by remember { mutableStateOf(false) }
    val seen = rows.count { it.precision != null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro)) {
            PhantomTitle(
                text = stringResource(R.string.peer_where_seen_title),
                color = theme.inkStrong,
                fontWeight = FontWeight.Bold,
            )
            PhantomBody(
                text = if (seen == 0) stringResource(R.string.peer_where_seen_nobody) else stringResource(R.string.peer_where_seen_lede),
                color = theme.inkSecondary,
            )
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
        ) {
            if (near.isNotEmpty()) {
                PhantomCaption(text = stringResource(R.string.peer_near_title))
                near.forEach { PeerRow(it, nowMs, onOpenPeer) }
                Spacer(Modifier.height(PhantomSpacings.Small))
            }
            if (seen > 0) GhostAll(self.hiddenAllUntilMs, nowMs, onHide)
            if (rows.isEmpty()) PhantomBody(text = stringResource(R.string.peer_where_empty), color = theme.inkSecondary)
            val (can, cannot) = rows.partition { it.eligible }
            if (can.isEmpty() && onAddPerson != null) {
                MapSecondaryAction(
                    PhantomGlyph.PERSON.asImageVector(),
                    stringResource(R.string.peer_where_add_person),
                    onAddPerson,
                )
            }
            can.forEach { row -> SeeRow(row, onSet, onHide, nowMs, ghostAll = self.hiddenAllUntilMs > nowMs) }
            if (seen > 0) BatteryRow(self.lowBattery, onLowBattery)
            if (onMeet != null && can.size >= 2) {
                MapSecondaryAction(PhantomGlyph.VIA.asImageVector(), stringResource(R.string.meet_open), onMeet)
            }
            WorldRow(world, onOpenWorld)
            if (cannot.isNotEmpty()) {
                Spacer(Modifier.height(PhantomSpacings.Small))
                PhantomCaption(text = stringResource(R.string.peer_where_not_proven))
                cannot.forEach { person ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = PhantomTouchFloor),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(PhantomAlpha.Recessed)) { PeerAvatar(person.name, SMALL_AVATAR, toneKey = person.peerId) }
                        Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
                        PhantomBody(text = person.name, color = theme.inkSecondary, maxLines = 1)
                    }
                }
            }
        }
        // Коли тебе не бачить ніхто, зникати нема звідки — кнопки просто немає.
        if (seen > 0) {
            PhantomDivider()
            MapSecondaryAction(
                PhantomGlyph.DISMISS.asImageVector(),
                stringResource(R.string.peer_where_vanish),
                { confirm = true },
                danger = true,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.peer_where_vanish_confirm_title)) },
            text = { Text(stringResource(R.string.peer_where_vanish_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onVanish()
                }) { Text(stringResource(R.string.peer_where_vanish), color = LocalPhantomColors.current.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.peer_where_cancel)) }
            },
        )
    }
}

/** Мить строку привида словом: «18:40». */
internal fun ghostClock(untilMs: Long): String = peerClockOf(untilMs)

/** Наступний ранок, 08:00 місцевого часу: «до ранку» — це до ранку, а не на вісім годин. */
internal fun nextMorningMs(nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
    val c = java.util.Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        set(java.util.Calendar.HOUR_OF_DAY, 8)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= nowMs) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
    return c.timeInMillis
}

/** Привид від усіх: один рядок, три строки. Увімкнений — одне речення і «Повернутися». */
@Composable
private fun GhostAll(untilMs: Long, nowMs: Long, onHide: (String?, Long) -> Unit) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier.fillMaxWidth().mapRaised().padding(PhantomSpacings.Small + PhantomSpacings.Micro),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        if (untilMs > nowMs) {
            PhantomBody(text = stringResource(R.string.peer_ghost_on_all, ghostClock(untilMs)), color = theme.inkStrong, fontWeight = FontWeight.SemiBold)
            MapSecondaryAction(PhantomGlyph.LOCATE_LOCKED.asImageVector(), stringResource(R.string.peer_ghost_back), { onHide(null, 0L) })
        } else {
            PhantomBody(text = stringResource(R.string.peer_ghost_from_all), color = theme.inkStrong, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro)) {
                listOf(
                    R.string.peer_ghost_1h to nowMs + 3_600_000L,
                    R.string.peer_ghost_3h to nowMs + 3 * 3_600_000L,
                    R.string.peer_ghost_morning to nextMorningMs(nowMs),
                ).forEach { (label, until) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = PhantomTouchFloor)
                            .clip(PhantomRadii.SmallShape)
                            .background(theme.surfaceBase)
                            .clickable(role = Role.Button) { onHide(null, until) },
                        contentAlignment = Alignment.Center,
                    ) { PhantomBody(text = stringResource(label), color = theme.inkStrong, maxLines = 1) }
                }
            }
        }
        PhantomCaption(text = stringResource(R.string.peer_ghost_explain))
    }
}

@Composable
private fun BatteryRow(on: Boolean, onLowBattery: (Boolean) -> Unit) {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Switch) { onLowBattery(!on) }
            .padding(vertical = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PhantomBody(text = stringResource(R.string.peer_battery_title), color = theme.inkStrong, fontWeight = FontWeight.SemiBold)
            PhantomCaption(text = stringResource(R.string.peer_battery_body))
        }
        Spacer(Modifier.width(PhantomSpacings.Small))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun WorldRow(world: MyWorldSummary, onOpen: () -> Unit) {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = PhantomTouchFloor)
            .padding(PhantomSpacings.Small + PhantomSpacings.Micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PhantomBody(text = stringResource(R.string.my_world_title), color = theme.inkStrong, fontWeight = FontWeight.SemiBold)
            PhantomCaption(
                text = if (world.quarters == 0) stringResource(R.string.my_world_lede)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.my_world_quarters, world.quarters, world.quarters),
            )
        }
        androidx.compose.material3.Icon(PhantomGlyph.LAYERS.asImageVector(), contentDescription = null, tint = theme.inkSecondary)
    }
}

/** Людина — піднята картка: обличчя, імʼя і чотири рівні ступені того, що вона бачить. */
@Composable
private fun SeeRow(
    person: PeerSeeRow,
    onSet: (String, PeerPrecision?) -> Unit,
    onHide: (String?, Long) -> Unit = { _, _ -> },
    nowMs: Long = System.currentTimeMillis(),
    ghostAll: Boolean = false,
) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mapRaised()
            .padding(PhantomSpacings.Small + PhantomSpacings.Micro),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small + PhantomSpacings.Micro),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeerAvatar(person.name, SMALL_AVATAR, toneKey = person.peerId)
            Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
            val hidden = person.hiddenUntilMs > nowMs
            Column(Modifier.weight(1f)) {
                PhantomBody(text = person.name, color = theme.inkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (hidden && !ghostAll) PhantomCaption(text = stringResource(R.string.peer_ghost_on_one, ghostClock(person.hiddenUntilMs)), maxLines = 1)
            }
            // Від однієї людини — тихим словом праворуч: вона не дізнається, а ви бачите, що сховані.
            // Не TextButton: його стиль підписів розтягує літери.
            if (person.precision != null && !ghostAll) {
                Box(
                    modifier = Modifier
                        .heightIn(min = PhantomTouchFloor)
                        .clip(PhantomRadii.SmallShape)
                        .clickable(role = Role.Button) { onHide(person.peerId, if (hidden) 0L else nowMs + 3 * 3_600_000L) }
                        .padding(horizontal = PhantomSpacings.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    PhantomCaption(
                        text = stringResource(if (hidden) R.string.peer_ghost_back else R.string.peer_ghost_row),
                        color = theme.inkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PhantomRadii.MediumShape)
                .background(theme.surfaceBase)
                .padding(PhantomSpacings.Micro),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro),
        ) {
            CHOICES.forEach { (p, label) ->
                val on = person.precision == p
                // Бурштин лише коли людину справді видно; «Нічого» обране — тихо.
                val fill = when {
                    !on -> Color.Transparent
                    p == null -> theme.glassPanel
                    else -> theme.primary
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = PhantomTouchFloor - PhantomSpacings.Micro * 2)
                        .clip(PhantomRadii.SmallShape)
                        .background(fill)
                        .semantics { selected = on }
                        .clickable(role = Role.RadioButton) { if (!on) onSet(person.peerId, p) },
                    contentAlignment = Alignment.Center,
                ) {
                    PhantomBody(
                        text = stringResource(label),
                        color = when {
                            on && p != null -> theme.primary.onAccentInk()
                            on -> theme.inkStrong
                            else -> theme.inkSecondary
                        },
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val SMALL_AVATAR = 36.dp
