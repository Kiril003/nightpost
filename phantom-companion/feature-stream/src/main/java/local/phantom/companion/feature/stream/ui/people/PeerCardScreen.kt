package local.phantom.companion.feature.stream.ui.people

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.layout.isLandscape
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerLiveShare
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerPayload
import local.phantom.companion.core.net.peer.PeerPurge
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.R as DesignR

/**
 * Хто це, чим доведено, як зветься, ключ, дії. Більше на цьому екрані
 * нічого немає — і не мусить.
 *
 * Шість цифр — центр, а не примітка. Це єдиний захист від ключа, підміненого
 * посередині: контрольна сума ловить одруківку, вона не ловить людину. Тож
 * позначку не дає застосунок — лише той, хто почув ті самі цифри вголос, — і
 * забрати її можна одним дотиком.
 */
// BringIntoViewRequester у foundation 1.7 ще під ExperimentalFoundationApi.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PeerCardScreen(
    peer: PeerIdentity,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSetVerified: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onOpenChat: () -> Unit,
    nowMs: Long,
    /**
     * Reading this person's code off their screen, with the live camera. Null
     * hides the offer; it is never the way to add somebody new here, only the
     * way an already-known key earns the proof a pasted one cannot.
     */
    onScanInvite: ((String) -> PeerInvite.Decoded)? = null,
    /** The operator's own keys — half of the number on this card. Null until the Keystore answers. */
    myInvite: PeerInvite.Invite? = null,
    /**
     * A safety number read off this person's screen by the live camera. On a
     * match the caller marks the row verified AND names the camera, which is
     * why only PeerCardDestination may supply it (PeerProvenanceFirewallTest).
     */
    onScanVerify: ((String) -> PeerSafety.Outcome)? = null,
    /**
     * Що саме лежить у цій розмові на цьому телефоні. [PeerPurge.NOTHING] ховає
     * і «стерти розмову», і згадку про листи в питанні «прибрати?»: пропонувати
     * стерти порожнечу — це спротив заради спротиву.
     */
    history: PeerPurge.Scope = PeerPurge.NOTHING,
    /** Порожній лишає картку без групи сховку, а не «0» на все, що не порахували. */
    vaultCounts: SharedVaultCounts = SharedVaultCounts.EMPTY,
    /** Стерти всю розмову з цього телефона. Null лишає картку без цієї дії. */
    onClearHistory: (() -> Unit)? = null,
    /** Правда лише іноді: прибрана людина лишає рядки в спільних групах. */
    sharesGroup: Boolean = false,
    /** Null у [onShareStart] прибирає «поділитися положенням» цілком. */
    shareReach: PeerLiveShare.Reach = PeerLiveShare.Reach.QUEUED,
    shareSession: PeerLiveShare.Session? = null,
    shareRemainingMs: Long? = null,
    /** Чи є ЩО надсилати — окремо від того, чи воно доїде. */
    sharePosition: PeerLiveShare.Position = PeerLiveShare.Position.HELD,
    onShareStart: ((PeerLiveShare.Span) -> Unit)? = null,
    onShareStop: (() -> Unit)? = null,
    /** Коли ця людина востаннє показала СЕБЕ нам. Null — не ділиться. */
    theirPositionAtMs: Long? = null,
    /** Звірений довідником @нік; null — невідомий. */
    handle: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val nameless = PeerCardTrust.nameless(peer)
    var name by remember(peer.id) { mutableStateOf(if (nameless) "" else peer.name) }
    var confirmRemove by remember(peer.id) { mutableStateOf(false) }
    var confirmClear by remember(peer.id) { mutableStateOf(false) }
    var mismatch by remember(peer.id) { mutableStateOf<PeerSafety.Outcome?>(null) }
    var verifyOpen by remember(peer.id) { mutableStateOf(false) }
    val title = if (nameless) handle?.let { "@$it" } ?: stringResource(R.string.peer_card_untitled) else peer.name
    val live = peer.isLive(nowMs)
    // «Тут» лише коли [nearby] — інакше «тут» означало б будь-яку розмову через сховок.
    val nearby = peer.isNearby(nowMs)
    val trust = PeerChatFormat.trust(peer)
    val legacyKey = peer.dhPubKey.isBlank()
    val number = remember(myInvite, peer.pubKey, peer.dhPubKey) {
        myInvite?.takeIf { !legacyKey }?.let {
            PeerSafety.number(it.pubKey, it.dhPubKey, peer.pubKey, peer.dhPubKey)
        }
    }

    // Питання «прибрати?» народжується в самому низу, нижче межі видимості:
    // людина бачила б питання без відповідей. Тож картка сама доїжджає до них.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val verification = remember { BringIntoViewRequester() }
    LaunchedEffect(confirmRemove, confirmClear) {
        if (confirmRemove || confirmClear) scroll.animateScrollTo(scroll.maxValue)
    }
    val gutter = readingGutter(PhantomSpace.gutterPhone)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.canvas)
            .systemBarsPadding()
            .padding(bottom = LocalBottomChromeInset.current)
            .imePadding()
            .verticalScroll(scroll),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(PhantomSize.topbarPhone)
                .padding(horizontal = PhantomSpace.s1),
        ) {
            Box(
                modifier = Modifier
                    .size(PhantomSpace.touchMinAndroid)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_back),
                    contentDescription = stringResource(R.string.peer_chat_back),
                    tint = c.text,
                    modifier = Modifier.size(PhantomSize.iconL),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        ) {
            // У горизонталі герой зʼїдав пів висоти, і шість цифр звірки
            // падали за межу видимості.
            val tight = isLandscape()
            PeerFaceDisc(
                peerId = peer.id,
                name = title,
                trust = trust,
                size = if (tight) PhantomSize.avatarL else PhantomSize.avatarXl,
            )
            Spacer(Modifier.height(PhantomSpace.s3))
            Text(
                text = title,
                style = PhantomText.title1,
                color = c.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (handle != null && !nameless) {
                Text(text = "@$handle", style = PhantomText.subhead, color = c.text2)
            }
            Spacer(Modifier.height(PhantomSpace.s1))
            val presence = stringResource(
                when {
                    nearby -> R.string.peer_card_here
                    live -> R.string.peer_card_in_touch
                    else -> R.string.peer_card_quiet
                },
            )
            Text(
                text = (stringResource(PeerCardTrust.label(peer, handle)) + " · " + presence).sentence(),
                style = PhantomText.footnote,
                // Зелене тут читалося б як «перевірено»; колір лише для зміни ключа.
                color = if (peer.keyAlarm) c.warning else c.text3,
            )

            val view = LocalView.current
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PhantomSpace.s5),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QuickAction(DesignR.drawable.lc_chats, stringResource(R.string.peer_card_quick_chat), onOpenChat)
                QuickAction(DesignR.drawable.lc_privacy, stringResource(R.string.peer_card_quick_verify)) {
                    verifyOpen = true
                    scope.launch {
                        withFrameNanos {}
                        verification.bringIntoView()
                    }
                }
                QuickAction(R.drawable.lc_share, stringResource(R.string.peer_card_quick_share)) {
                    myInvite?.let { invite ->
                        PhantomHaptics.confirm(view)
                        InviteShare.send(context, invite)
                    }
                }
            }
            Spacer(Modifier.height(if (tight) PhantomSpace.s4 else PhantomSpace.s6))
        }

        Column(
            modifier = Modifier.padding(horizontal = gutter),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s6),
        ) {
            Box(Modifier.bringIntoViewRequester(verification)) {
                // Без ключа шифрування звіряти нічого: шість цифр покривають
                // обидва ключі, і рядок без другого — рядок, у який не написати.
                if (legacyKey) {
                    CardGroup {
                        Text(
                            text = stringResource(R.string.peer_card_legacy_key),
                            style = PhantomText.body,
                            color = c.danger,
                            modifier = Modifier.padding(PhantomSpace.s4),
                        )
                    }
                } else if (mismatch != null) {
                    MismatchGroup(
                        name = title,
                        outcome = mismatch ?: PeerSafety.Outcome.BOTH_DIFFER,
                        onRemove = onRemove,
                        onKeep = { mismatch = null },
                    )
                } else if (verifyOpen || peer.keyAlarm) {
                    // Звірка — необовʼязкова деталь; сама розкривається лише на зміні ключа.
                    VerificationGroup(
                        trust = trust,
                        why = PeerChatFormat.why(peer),
                        number = number,
                        onSetVerified = onSetVerified,
                        onMismatch = { mismatch = it },
                        onScanInvite = onScanInvite,
                        onScanVerify = onScanVerify,
                    )
                } else {
                    CardGroup {
                        CardRow(
                            label = stringResource(R.string.peer_card_verify_live),
                            icon = DesignR.drawable.lc_privacy,
                            tone = RowTone.Plain,
                            onClick = { verifyOpen = true },
                            body = if (trust.verified) null else stringResource(R.string.peer_card_optional),
                        )
                    }
                }
            }

            // Імʼя, яке прийшло дротом, — пропозиція; це поле єдине, чим тут
            // володіє оператор.
            CardGroup(caption = stringResource(R.string.peer_card_name)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PhantomSize.input)
                        .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = PeerName.cap(it) },
                        singleLine = true,
                        textStyle = PhantomText.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(PhantomSpace.s2))
                    Icon(
                        painter = painterResource(DesignR.drawable.lc_pencil),
                        contentDescription = null,
                        tint = c.text3,
                        modifier = Modifier.size(PhantomSize.iconS),
                    )
                }
                // Рядок з «…» на кінці сам по собі не відрізняється від імені,
                // у якому «…» набрали, а саме за іменем людина звіряє, з ким говорить.
                if (peer.nameShortened) {
                    CardNote(stringResource(R.string.peer_card_name_shortened), c.text3)
                }
                // Без цього речення людина бачить ключ замість імені й не знає чому.
                if (peer.nameUnreadable) {
                    CardNote(stringResource(R.string.peer_card_name_unreadable), c.warning)
                }
                if (name.trim() != peer.name && name.isNotBlank()) {
                    GroupDivider()
                    CardRow(
                        label = stringResource(R.string.peer_card_rename_save),
                        icon = DesignR.drawable.lc_check,
                        tone = RowTone.Action,
                        onClick = { onRename(name.trim()) },
                    )
                }
            }

            // Група зʼявляється, коли є що показати: коробка над порожньою
            // множиною каже «тут щось є» там, де нічого немає.
            if (vaultCounts != SharedVaultCounts.EMPTY) SharedVaultGroup(counts = vaultCounts)

            CardGroup {
                CardRow(
                    label = stringResource(R.string.peer_card_open_chat),
                    icon = DesignR.drawable.lc_chats,
                    tone = RowTone.Action,
                    onClick = onOpenChat,
                )
                if (onShareStart != null) {
                    GroupDivider()
                    PeerShareLocationCard(
                        peerName = title,
                        reach = shareReach,
                        session = shareSession,
                        remainingMs = shareRemainingMs,
                        position = sharePosition,
                        onStart = onShareStart,
                        onStop = { onShareStop?.invoke() },
                        theirPositionAtMs = theirPositionAtMs,
                        nowMs = nowMs,
                    )
                }
            }

            CardGroup {
                // Стерти розмову — мʼякша дія, тож стоїть ВИЩЕ за «прибрати» і
                // має ту саму двоступеневу форму, що й строк зберігання.
                if (onClearHistory != null && !history.isEmpty) {
                    if (!confirmClear) {
                        CardRow(
                            label = stringResource(R.string.peer_clear_open),
                            icon = DesignR.drawable.lc_trash,
                            tone = RowTone.Danger,
                            onClick = { confirmClear = true },
                        )
                    } else {
                        ClearBlock(
                            scope = history,
                            preview = pluralStringResource(
                                R.plurals.peer_clear_preview,
                                history.count,
                                history.count,
                                purgeSpan(history),
                            ),
                            note = null,
                            onConfirm = { onClearHistory(); confirmClear = false },
                            onCancel = { confirmClear = false },
                            modifier = Modifier.padding(PhantomSpace.s4),
                        )
                    }
                    GroupDivider()
                }
                // Removing somebody is not undoable — the key goes with them, and
                // adding them back means another invite. So it asks once, in place,
                // rather than behind a dialog nobody reads.
                if (!confirmRemove) {
                    CardRow(
                        label = stringResource(R.string.peer_card_remove),
                        icon = DesignR.drawable.lc_trash,
                        tone = RowTone.Danger,
                        onClick = { confirmRemove = true },
                    )
                } else {
                    Column(modifier = Modifier.padding(PhantomSpace.s4)) {
                        // Прибрати людину забирає й листування — сказати це
                        // ПЕРЕД дотиком, разом із числом.
                        Text(
                            text = if (history.isEmpty) {
                                stringResource(R.string.peer_card_remove_ask, title)
                            } else {
                                pluralStringResource(
                                    R.plurals.peer_card_remove_ask_messages,
                                    history.count,
                                    title,
                                    history.count,
                                )
                            },
                            style = PhantomText.body,
                            color = c.text,
                        )
                        if (sharesGroup) {
                            Spacer(Modifier.height(PhantomSpace.s2))
                            Text(
                                text = stringResource(R.string.peer_card_remove_groups_note),
                                style = PhantomText.footnote,
                                color = c.text3,
                            )
                        }
                        Spacer(Modifier.height(PhantomSpace.s4))
                        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                            FilledButton(
                                label = stringResource(R.string.peer_card_remove_yes),
                                fill = c.danger,
                                ink = c.canvas,
                                onClick = onRemove,
                                icon = DesignR.drawable.lc_trash,
                            )
                            QuietButton(
                                label = stringResource(R.string.peer_card_remove_no),
                                color = c.text,
                                onClick = { confirmRemove = false },
                                outlined = true,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(PhantomSpace.s4))
        }
    }
}

@Composable
private fun QuickAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val view = LocalView.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = QUICK_W)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button) {
                PhantomHaptics.tick(view)
                onClick()
            }
            .padding(vertical = PhantomSpace.s1),
    ) {
        Box(
            modifier = Modifier
                .size(PhantomSize.buttonL)
                .clip(CircleShape)
                .background(c.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(PhantomSize.iconM),
            )
        }
        Spacer(Modifier.height(PhantomSpace.s1))
        Text(text = label, style = PhantomText.caption, color = c.text2)
    }
}

@Composable
private fun CardNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text.sentence(),
        style = PhantomText.footnote,
        color = color,
        modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s3),
    )
}

/**
 * The number of this conversation, and both answers the ceremony can have.
 *
 * A verification you can only pass is not a verification, so «не збіглось»
 * stands next to «збіглось». The sentence under the number is chosen by [why],
 * not by the tick — a key the camera took and a key a messenger carried cannot
 * honestly share one line.
 */
@Composable
private fun VerificationGroup(
    trust: PeerChatFormat.Trust,
    why: PeerChatFormat.Why,
    number: String?,
    onSetVerified: (Boolean) -> Unit,
    onMismatch: (PeerSafety.Outcome) -> Unit,
    onScanInvite: ((String) -> PeerInvite.Decoded)?,
    onScanVerify: ((String) -> PeerSafety.Outcome)?,
) {
    val c = LocalPhantomColors.current
    var scanning by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val verified = trust.verified
    val proven = trust == PeerChatFormat.Trust.IN_PERSON
    val changed = trust == PeerChatFormat.Trust.CHANGED
    CardGroup(caption = stringResource(R.string.peer_card_safety_code)) {
        Column(modifier = Modifier.padding(PhantomSpace.s4)) {
            // Незвірений стан не кричить заголовком: число тут і є головним.
            if (changed || verified) {
                val tone = when {
                    changed -> c.danger
                    proven -> c.success
                    else -> c.text2
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(DesignR.drawable.lc_privacy),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(PhantomSize.iconM),
                    )
                    Spacer(Modifier.width(PhantomSpace.s2))
                    Text(
                        text = stringResource(
                            when {
                                changed -> R.string.peer_card_key_changed
                                proven -> R.string.peer_card_proven
                                else -> R.string.peer_card_matched
                            },
                        ).sentence(),
                        style = PhantomText.headline,
                        color = tone,
                    )
                }
                Spacer(Modifier.height(PhantomSpace.s3))
            }
            SafetyCodeGrid(number)
            // Пояснення лишається лише для зміни ключа: там людина мусить знати, що сталось.
            if (why == PeerChatFormat.Why.CHANGED) {
                Spacer(Modifier.height(PhantomSpace.s3))
                Text(text = stringResource(R.string.peer_card_key_changed_why), style = PhantomText.callout, color = c.text2)
            }
            if (!verified) {
                Spacer(Modifier.height(PhantomSpace.s4))
                if (!proven && onScanInvite != null) {
                    FilledButton(
                        label = stringResource(R.string.peer_card_scan_code),
                        fill = c.accent,
                        ink = c.onAccent,
                        onClick = { scanning = true },
                        icon = DesignR.drawable.lc_camera,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PhantomSpace.s2))
                }
                QuietButton(
                    label = stringResource(R.string.peer_card_digits_match),
                    color = c.text,
                    onClick = { onSetVerified(true) },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true,
                )
                QuietButton(
                    label = stringResource(R.string.peer_card_mismatch),
                    color = c.danger,
                    // Said aloud, nobody knows which phone holds the wrong key.
                    onClick = { onMismatch(PeerSafety.Outcome.BOTH_DIFFER) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (verified) {
            GroupDivider()
            CardRow(
                label = stringResource(R.string.peer_card_unverify),
                icon = DesignR.drawable.lc_close,
                tone = RowTone.Plain,
                onClick = { onSetVerified(false) },
            )
        }
        // Показати своє число QR-кодом або взяти їхнє камерою — дві половини
        // одного акту; збіг, прочитаний з їхнього екрана, доводить ключ.
        if (number != null) {
            GroupDivider()
            CardRow(
                label = stringResource(
                    if (showQr) R.string.peer_card_safety_qr_hide else R.string.peer_card_safety_qr_show,
                ),
                icon = R.drawable.lc_qr,
                tone = RowTone.Action,
                onClick = { showQr = !showQr },
            )
            if (showQr) {
                Column(modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s4)) {
                    InviteQr(code = PeerSafety.qr(number), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(PhantomSpace.s2))
                    Text(
                        text = stringResource(R.string.peer_card_safety_qr_hint),
                        style = PhantomText.footnote,
                        color = c.text3,
                    )
                }
            }
        }
    }
    if (scanning && onScanInvite != null) {
        PeerScanSheet(
            onCode = { text ->
                onScanInvite(text).also {
                    if (it is PeerInvite.Decoded.Ph2) scanning = false
                }
            },
            onVerifyCode = onScanVerify?.let { verify ->
                { text ->
                    verify(text).also { outcome ->
                        if (outcome != PeerSafety.Outcome.NOT_A_NUMBER) scanning = false
                        if (outcome.mismatch) onMismatch(outcome)
                    }
                }
            },
            onClose = { scanning = false },
        )
    }
}

/**
 * A number that differs means a key under the wrong name — and the camera can
 * even say on WHICH phone, so the sentence sends the two people to the right
 * one instead of both to «прибрати».
 */
@Composable
private fun MismatchGroup(
    name: String,
    outcome: PeerSafety.Outcome,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val theirPhone = outcome == PeerSafety.Outcome.MINE_DIFFERS
    CardGroup(caption = stringResource(R.string.peer_card_number_label)) {
        Column(modifier = Modifier.padding(PhantomSpace.s4)) {
            Text(
                text = stringResource(R.string.peer_card_mismatch_title),
                style = PhantomText.headline,
                color = c.danger,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(
                text = stringResource(
                    if (theirPhone) R.string.peer_card_mismatch_mine_why else R.string.peer_card_mismatch_why,
                    name,
                ),
                style = PhantomText.callout,
                color = c.text2,
            )
        }
        // My copy of their key is real when only THEIR phone is wrong;
        // removing it here would fix nothing and lose the chat.
        if (!theirPhone) {
            GroupDivider()
            CardRow(
                label = stringResource(R.string.peer_card_remove_yes),
                icon = DesignR.drawable.lc_trash,
                tone = RowTone.Danger,
                onClick = onRemove,
            )
        }
        GroupDivider()
        CardRow(
            label = stringResource(R.string.peer_card_mismatch_keep),
            icon = DesignR.drawable.lc_check,
            tone = RowTone.Plain,
            onClick = onKeep,
        )
    }
}

/** Перше речення цілком — або весь текст, якщо крапки в ньому немає. */
internal fun firstSentence(text: String): String {
    val cut = text.indexOf(". ")
    return if (cut < 0) text else text.substring(0, cut + 1)
}

/** Скільки медіа, файлів і місць лежить в одній розмові — не вигадка, лічба. */
data class SharedVaultCounts(val media: Int, val files: Int, val places: Int) {
    companion object {
        val EMPTY = SharedVaultCounts(0, 0, 0)

        /**
         * Рахує з тієї самої черги, яку [PeerPurge.conversation] уже читає для
         * «скільки зникне». Копії вкладень (`PeerFile.isChunk`) вона свідомо не
         * рахує — вони не рядок розмови, а вантаж під одним із цих рядків.
         */
        fun of(outbox: List<PeerMessage>, peerId: String): SharedVaultCounts {
            var media = 0
            var files = 0
            var places = 0
            for (message in PeerOutbox.conversation(outbox, peerId)) {
                val said = PeerGroup.decode(message.body)?.text ?: message.body
                when {
                    PeerPayload.decodePlace(said) != null -> places++
                    said.startsWith("PH-IMAGE:") -> media++
                    said.startsWith("PH-FILE:") -> files++
                }
            }
            return SharedVaultCounts(media, files, places)
        }
    }
}

@Composable
private fun SharedVaultGroup(counts: SharedVaultCounts) {
    val c = LocalPhantomColors.current
    val rows = listOf(
        R.string.peer_card_vault_media to counts.media,
        R.string.peer_card_vault_files to counts.files,
        R.string.peer_card_vault_places to counts.places,
    ).filter { it.second > 0 }
    Column {
        CardGroup(caption = stringResource(R.string.peer_card_vault_title)) {
            rows.forEachIndexed { i, (label, count) ->
                if (i > 0) GroupDivider()
                ValueRow(label = stringResource(label), value = count.toString())
            }
        }
        Text(
            text = stringResource(R.string.peer_card_vault_hint),
            style = PhantomText.footnote,
            color = c.text3,
            modifier = Modifier.padding(start = PhantomSpace.s4, top = PhantomSpace.s2),
        )
    }
}

private val QUICK_W = 72.dp

/** Код безпеки — 12 груп по чотири цифри, сіткою 3 × 4, як у рендері. */
@Composable
private fun SafetyCodeGrid(number: String?) {
    if (number == null) {
        SafetyNumberLines(null)
        return
    }
    val c = LocalPhantomColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
        number.chunked(4).chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { group ->
                    Text(
                        text = group,
                        style = PhantomText.title2.copy(fontFeatureSettings = "tnum"),
                        color = c.text,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
