package local.phantom.companion.feature.stream.ui.map.peers

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerNear
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.core.design.layout.bottomChromeInsets
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapPrimaryAction
import local.phantom.companion.feature.stream.ui.map.MapSecondaryAction
import local.phantom.companion.feature.stream.ui.map.SheetSegment
import local.phantom.companion.feature.stream.ui.map.SheetSegments
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import local.phantom.companion.feature.stream.ui.map.mapRaised

/** Хто з моїх поруч: живе місце, свіже, у тому ж кварталі чи поруч. Незнайомих тут немає й бути не може. */
internal fun peersNear(points: List<PeerPoint>, nowMs: Long): List<TappedPeer> = points
    .filter { it.isWhere() && !it.expiredAt(nowMs) && it.freshAt(nowMs) && (it.near == PeerNear.SAME || it.near == PeerNear.NEAR) }
    .sortedWith(compareBy({ it.near != PeerNear.SAME }, { it.name.lowercase() }))
    .map { it.tapped(nowMs) }

/** Рядок людини: обличчя, імʼя, стан одним словом. Увесь рядок — дотик до її картки. */
@Composable
internal fun PeerRow(peer: TappedPeer, nowMs: Long, onOpen: (TappedPeer) -> Unit) {
    val theme = LocalPhantomTheme.current
    val words = peerStateWords(peer, nowMs).map { stringResource(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button) { onOpen(peer) }
            .padding(vertical = PhantomSpacings.Micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(peer.name, 40.dp, toneKey = peer.threadId.ifBlank { peer.name }, face = peer.face)
        Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
        Column {
            PhantomBody(text = peer.name, color = theme.inkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (words.isNotEmpty()) PhantomCaption(text = words.joinToString(" · ").replaceFirstChar { it.uppercase() })
        }
    }
}

@Composable
internal fun PeerGroupSheet(members: List<TappedPeer>, nowMs: Long, onOpen: (TappedPeer) -> Unit) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomTitle(text = stringResource(R.string.peer_group_title, members.size), color = theme.inkStrong, fontWeight = FontWeight.Bold)
        members.forEach { PeerRow(it, nowMs, onOpen) }
    }
}

@Composable
internal fun MyWorldSheet(
    world: MyWorldSummary,
    onMap: Boolean,
    onShowOnMap: () -> Unit,
    onForget: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val quarters = pluralStringResource(R.plurals.my_world_quarters, world.quarters, world.quarters)
    val cities = pluralStringResource(R.plurals.my_world_cities, world.cities, world.cities)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets())
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro)) {
            PhantomTitle(text = stringResource(R.string.my_world_title), color = theme.inkStrong, fontWeight = FontWeight.Bold)
            PhantomBody(text = stringResource(R.string.my_world_lede), color = theme.inkSecondary)
        }
        if (world.quarters == 0) {
            PhantomBody(text = stringResource(R.string.my_world_empty), color = theme.inkSecondary)
            return@Column
        }
        // Два числа — головне на аркуші, тож великі: їх видно з відстані руки.
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Large)) {
            PhantomTitle(text = quarters, color = theme.primary, fontWeight = FontWeight.Bold)
            if (world.cities > 0) PhantomTitle(text = cities, color = theme.inkStrong, fontWeight = FontWeight.Bold)
        }
        if (world.frequent.isNotEmpty()) {
            PhantomCaption(text = stringResource(R.string.my_world_frequent))
            world.frequent.forEach { f ->
                val minutes = f.dwellMs / 60_000L
                Column(Modifier.fillMaxWidth().mapRaised().padding(PhantomSpacings.Small + PhantomSpacings.Micro)) {
                    PhantomBody(text = f.label ?: stringResource(R.string.my_world_place), color = theme.inkStrong, fontWeight = FontWeight.SemiBold)
                    PhantomCaption(
                        text = if (minutes >= 60) stringResource(R.string.my_world_hours, minutes / 60) else stringResource(R.string.my_world_minutes, minutes),
                    )
                }
            }
        }
        MapPrimaryAction(
            PhantomGlyph.LAYERS.asImageVector(),
            stringResource(if (onMap) R.string.my_world_hide else R.string.my_world_show),
            onShowOnMap,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small)) {
            // Поділитися — лише числами, словами; жодної клітинки й жодного місця.
            val shareText = stringResource(R.string.my_world_share_text, quarters, cities)
            MapSecondaryAction(PhantomGlyph.HANDOUT.asImageVector(), stringResource(R.string.my_world_share), {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.weight(1f))
            MapSecondaryAction(PhantomGlyph.DISCARD.asImageVector(), stringResource(R.string.my_world_forget), { confirm = true }, Modifier.weight(1f))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.my_world_forget_title)) },
            text = { Text(stringResource(R.string.my_world_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onForget()
                }) { Text(stringResource(R.string.my_world_forget)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.peer_where_cancel)) } },
        )
    }
}

internal enum class LiveShareDuration(val minutes: Int?) {
    MIN_15(15),
    HOUR_1(60),
    UNTIL_ARRIVE(null),
}

internal data class ShareRecipient(
    val peerId: String,
    val name: String,
    val eligible: Boolean,
    val inClose: Boolean,
    val hiddenUntilMs: Long = 0L,
)

internal fun resolveShareTargets(
    recipients: List<ShareRecipient>,
    audienceId: String?,
): List<ShareRecipient> =
    if (audienceId != null) {
        recipients.filter { it.eligible && it.peerId == audienceId }
    } else {
        recipients.filter { it.eligible && it.inClose }
    }

internal data class ActiveLiveShare(
    val targets: List<ShareRecipient>,
    val audienceId: String?,
    val duration: LiveShareDuration,
    val startedAtMs: Long,
    val expiresAtMs: Long?,
)

internal class LiveShareController(
    private val port: MapPeoplePort,
    private val shareWords: String,
    private val arrivedWords: String,
) {
    var activeShare by mutableStateOf<ActiveLiveShare?>(null)
        private set

    var stopped by mutableStateOf(false)
        private set

    private var lastSentLocation: Pair<Double, Double>? = null
    private var lastSentAtMs: Long = Long.MIN_VALUE

    fun start(
        recipients: List<ShareRecipient>,
        audienceId: String?,
        duration: LiveShareDuration,
        me: Pair<Double, Double>?,
        nowMs: Long,
    ): ActiveLiveShare? {
        val targets = resolveShareTargets(recipients, audienceId)
        if (targets.isEmpty()) {
            activeShare = null
            return null
        }
        stopped = false
        targets.forEach { port.hide(it.peerId, 0L) }
        val expiresAtMs = duration.minutes?.let { nowMs + it * 60_000L }
        val session = ActiveLiveShare(
            targets = targets,
            audienceId = audienceId,
            duration = duration,
            startedAtMs = nowMs,
            expiresAtMs = expiresAtMs,
        )
        activeShare = session
        if (me != null) {
            if (duration == LiveShareDuration.UNTIL_ARRIVE) {
                port.tellOnArrival(targets.map { it.peerId }, me.first, me.second, arrivedWords)
            }
            targets.forEach { port.sendPlace(it.peerId, me.first, me.second, shareWords) }
            lastSentLocation = me
            lastSentAtMs = nowMs
        }
        return session
    }

    fun onLocationOrTick(
        me: Pair<Double, Double>?,
        nowMs: Long,
    ): Boolean {
        val current = activeShare ?: return false
        val exp = current.expiresAtMs
        if (exp != null && nowMs >= exp) {
            expireCurrent()
            return false
        }
        if (stopped || me == null) return false
        if (me == lastSentLocation && nowMs <= lastSentAtMs) return false
        current.targets.forEach { port.sendPlace(it.peerId, me.first, me.second, shareWords) }
        lastSentLocation = me
        lastSentAtMs = nowMs
        return true
    }

    fun stop(recipients: List<ShareRecipient> = emptyList()) {
        val ids = (activeShare?.targets?.map { it.peerId }.orEmpty() +
            recipients.filter { it.eligible && it.inClose }.map { it.peerId }).distinct()
        port.hide(null, Long.MAX_VALUE)
        ids.forEach { port.hide(it, Long.MAX_VALUE) }
        activeShare = null
        stopped = true
        lastSentLocation = null
        lastSentAtMs = Long.MIN_VALUE
    }

    fun isSharingAt(
        nowMs: Long,
        recipients: List<ShareRecipient> = emptyList(),
        hidden: Map<String, Long> = emptyMap(),
    ): Boolean {
        val current = activeShare
        if (current != null) {
            val exp = current.expiresAtMs
            if (exp != null && nowMs >= exp) {
                expireCurrent()
                return false
            }
            return true
        }
        if (stopped) return false
        val globalHide = hidden[""] ?: 0L
        if (globalHide > nowMs) return false
        return recipients.any { r ->
            val until = maxOf(hidden[r.peerId] ?: 0L, r.hiddenUntilMs)
            r.eligible && r.inClose && until <= nowMs
        }
    }

    fun sharingLabel(
        nowMs: Long,
        recipients: List<ShareRecipient>,
        hidden: Map<String, Long> = emptyMap(),
    ): String? {
        if (!isSharingAt(nowMs, recipients, hidden)) return null
        val current = activeShare
        if (current != null) {
            return current.targets.joinToString(", ") { it.name }.ifEmpty { null }
        }
        return recipients
            .filter { r ->
                val until = maxOf(hidden[r.peerId] ?: 0L, r.hiddenUntilMs)
                r.eligible && r.inClose && until <= nowMs
            }
            .joinToString(", ") { it.name }
            .ifEmpty { null }
    }

    private fun expireCurrent() {
        val current = activeShare ?: return
        current.targets.forEach { port.hide(it.peerId, Long.MAX_VALUE) }
        activeShare = null
        stopped = true
        lastSentLocation = null
        lastSentAtMs = Long.MIN_VALUE
    }
}

internal fun peerWalkAwayTag(
    origin: Pair<Double, Double>?,
    peerLat: Double,
    peerLon: Double,
): PeerMinuteTag? {
    if (origin == null) return null
    val dist = haversineMeters(origin.first, origin.second, peerLat, peerLon)
    val mins = maxOf(1, (dist / 80.0).roundToInt())
    return PeerMinuteTag(R.plurals.map_minutes_away, mins)
}

internal fun fairMeetEtaTags(
    candidate: FairMeetCandidate,
): List<Pair<String, PeerMinuteTag>> =
    candidate.etasMinutes.map { (person, mins) ->
        person to PeerMinuteTag(R.plurals.map_minutes_away, mins)
    }

@Composable
internal fun LiveSharePicker(
    audiences: List<Pair<String, String>>,
    selectedAudienceId: String?,
    onSelectAudience: (String?) -> Unit,
    onSelectDuration: (LiveShareDuration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small)) {
        SheetSegments {
            SheetSegment(
                label = stringResource(R.string.map_share_close_audience),
                selected = selectedAudienceId == null,
                onClick = { onSelectAudience(null) },
            )
            audiences.take(3).forEach { (id, name) ->
                SheetSegment(
                    label = name,
                    selected = selectedAudienceId == id,
                    onClick = { onSelectAudience(id) },
                )
            }
        }
        SheetSegments {
            SheetSegment(
                label = stringResource(R.string.map_share_15m),
                selected = false,
                onClick = { onSelectDuration(LiveShareDuration.MIN_15) },
            )
            SheetSegment(
                label = stringResource(R.string.map_share_1h),
                selected = false,
                onClick = { onSelectDuration(LiveShareDuration.HOUR_1) },
            )
            SheetSegment(
                label = stringResource(R.string.map_share_until_arrive),
                selected = false,
                onClick = { onSelectDuration(LiveShareDuration.UNTIL_ARRIVE) },
            )
        }
    }
}

@Composable
internal fun PeoplePeekContent(
    people: List<TappedPeer>,
    origin: Pair<Double, Double>?,
    nowMs: Long,
    sharingUntilLabel: String? = null,
    audiences: List<Pair<String, String>> = emptyList(),
    onStartShare: (audienceId: String?, duration: LiveShareDuration) -> Unit = { _, _ -> },
    onStopShare: () -> Unit = {},
    onOpenPeer: (TappedPeer) -> Unit = {},
    onMeet: () -> Unit = {},
) {
    val theme = LocalPhantomTheme.current
    var pickerOpen by remember { mutableStateOf(false) }
    var selectedAudienceId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Small),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomTitle(
            text = stringResource(R.string.map_people_title),
            color = theme.inkStrong,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mapRaised()
                .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Micro),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (sharingUntilLabel != null) {
                PhantomBody(
                    text = stringResource(R.string.map_share_with_close, sharingUntilLabel),
                    color = theme.inkStrong,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStopShare) {
                    Text(stringResource(R.string.live_share_stop))
                }
            } else {
                PhantomBody(
                    text = stringResource(R.string.map_people_none_sharing),
                    color = theme.inkSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pickerOpen = !pickerOpen }) {
                    Text(stringResource(R.string.map_share_my_location))
                }
            }
        }
        if (pickerOpen && sharingUntilLabel == null) {
            LiveSharePicker(
                audiences = audiences,
                selectedAudienceId = selectedAudienceId,
                onSelectAudience = { selectedAudienceId = it },
                onSelectDuration = { duration ->
                    pickerOpen = false
                    onStartShare(selectedAudienceId, duration)
                },
            )
        }
        people.forEach { peer ->
            val walkTag = peerWalkAwayTag(origin, peer.lat, peer.lon)?.let {
                pluralStringResource(it.pluralsRes, it.minutes, it.minutes)
            }
            val stateWords = peerStateWords(peer, nowMs).map { stringResource(it) }
            val subtitle = listOfNotNull(walkTag, stateWords.takeIf { it.isNotEmpty() }?.joinToString(" · "))
                .joinToString(" · ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomTouchFloor)
                    .clickable(role = Role.Button) { onOpenPeer(peer) }
                    .padding(vertical = PhantomSpacings.Micro),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(peer.name, 40.dp, toneKey = peer.threadId.ifBlank { peer.name }, face = peer.face)
                Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
                Column(Modifier.weight(1f)) {
                    PhantomBody(text = peer.name, color = theme.inkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (subtitle.isNotEmpty()) PhantomCaption(text = subtitle)
                }
            }
        }
        MapPrimaryAction(
            PhantomGlyph.PERSON.asImageVector(),
            stringResource(R.string.map_people_meet),
            onMeet,
        )
    }
}

@Composable
internal fun FairMeetPeekContent(
    candidates: List<FairMeetCandidate>,
    selectedTimeChip: String,
    onSelectTime: (String) -> Unit,
    onSendToChat: (FairMeetCandidate, String) -> Unit,
) {
    val theme = LocalPhantomTheme.current
    if (candidates.isEmpty()) {
        PhantomCaption(text = stringResource(R.string.meet_nobody))
        return
    }
    var selectedIndex by remember(candidates) { mutableIntStateOf(0) }
    val current = candidates[selectedIndex.coerceIn(candidates.indices)]
    val nowLabel = stringResource(R.string.map_meet_now)
    val timeChips = listOf(nowLabel, "18:45", "19:00", "19:30")
    val activeTime = selectedTimeChip.ifBlank { nowLabel }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(vertical = PhantomSpacings.Small),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomTitle(
            text = stringResource(R.string.map_meet_at, current.name),
            color = theme.inkStrong,
            fontWeight = FontWeight.Bold,
        )
        val etaLine = fairMeetEtaTags(current)
            .map { (person, tag) ->
                "$person ${pluralStringResource(tag.pluralsRes, tag.minutes, tag.minutes)}"
            }
            .joinToString(" · ")
        if (etaLine.isNotEmpty()) {
            PhantomCaption(text = etaLine)
        }
        if (candidates.size > 1) {
            SheetSegments {
                candidates.forEachIndexed { index, candidate ->
                    SheetSegment(
                        label = candidate.name,
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                    )
                }
            }
        }
        SheetSegments {
            timeChips.forEach { chip ->
                SheetSegment(
                    label = chip,
                    selected = activeTime == chip,
                    onClick = { onSelectTime(chip) },
                )
            }
        }
        MapPrimaryAction(
            PhantomGlyph.HANDOUT.asImageVector(),
            stringResource(R.string.map_meet_send_chat),
            { onSendToChat(current, activeTime) },
        )
    }
}

internal fun shareRecipients(
    rows: List<local.phantom.companion.core.data.peers.PeerSeeRow>,
    hidden: Map<String, Long>,
): List<ShareRecipient> = rows.map { r ->
    ShareRecipient(r.peerId, r.name, r.eligible, r.inClose, hidden[r.peerId] ?: 0L)
}
