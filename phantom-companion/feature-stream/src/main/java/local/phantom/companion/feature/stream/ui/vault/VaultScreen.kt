package local.phantom.companion.feature.stream.ui.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.component.zBackGesture
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.vault.UnlockAttempt
import local.phantom.companion.feature.stream.vault.VaultTrouble
import local.phantom.companion.feature.stream.vault.VaultUiState
import local.phantom.companion.feature.stream.vault.classify
import local.phantom.companion.core.design.R as DesignR

private const val IDLE_LOCK_MS = 30_000L
private const val ARM_SECONDS = 6
private const val COUNTDOWN_VISIBLE_AT = 10

/** Знак замка на гладкому обличчі; на порожньому й службових — трохи менший. */
private val MARK = 120.dp
private val MARK_SMALL = 104.dp

/** Ширина, на якій абзац під заголовком ще читається одним поглядом. */
private val READING = 340.dp

@Composable
fun VaultScreen(
    state: VaultUiState,
    onBack: () -> Unit,
    onUnlockRequested: () -> Unit,
    onBiometricSuccess: () -> Unit,
    onUnlockRefused: (UnlockAttempt) -> Unit,
    onLockSession: () -> Unit,
    onErase: (String) -> Unit,
    onDismissTrouble: () -> Unit,
    onReread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val locked = state is VaultUiState.Locked

    var shownAttempt by remember { mutableStateOf(UnlockAttempt.Idle) }
    LaunchedEffect(state) {
        (state as? VaultUiState.Locked)?.let { shownAttempt = it.attempt }
    }

    // Полотно стоїть: фіолетова заграва над замком пішла разом зі старим склом.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.canvas)
            .zBackGesture(onBack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(bottom = LocalBottomChromeInset.current),
        ) {
            VaultChrome(locked = locked, onBack = onBack)

            VaultFaces(
                state = state,
                shownAttempt = shownAttempt,
                onUnlockRequested = onUnlockRequested,
                onBiometricSuccess = onBiometricSuccess,
                onUnlockRefused = onUnlockRefused,
                onLockSession = onLockSession,
                onErase = onErase,
                onDismissTrouble = onDismissTrouble,
                onReread = onReread,
            )
        }
    }
}

// Its own composable, not a Box inside the Column above: with a ColumnScope in
// scope, `AnimatedVisibility` resolves to the ColumnScope overload.
@Composable
private fun VaultFaces(
    state: VaultUiState,
    shownAttempt: UnlockAttempt,
    onUnlockRequested: () -> Unit,
    onBiometricSuccess: () -> Unit,
    onUnlockRefused: (UnlockAttempt) -> Unit,
    onLockSession: () -> Unit,
    onErase: (String) -> Unit,
    onDismissTrouble: () -> Unit,
    onReread: () -> Unit,
) {
    val locked = state is VaultUiState.Locked
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = locked,
            enter = PhantomMotion.enter,
            exit = PhantomMotion.exit,
        ) {
            VaultLockedFace(
                attempt = shownAttempt,
                onUnlockRequested = onUnlockRequested,
                onBiometricSuccess = onBiometricSuccess,
                onUnlockRefused = onUnlockRefused,
            )
        }
        // No exit transition: the open face holds decrypted values, and a lock
        // must take them off screen in the same frame it lands.
        AnimatedVisibility(
            visible = !locked,
            enter = PhantomMotion.expand,
            exit = ExitTransition.None,
        ) {
            when (state) {
                is VaultUiState.Reading -> VaultReadingFace()
                is VaultUiState.Broken -> VaultBrokenFace(onReread = onReread)
                is VaultUiState.Open -> if (state.isEmpty) {
                    VaultEmptyFace(onLockSession = onLockSession)
                } else {
                    VaultOpenFace(
                        state = state,
                        onLockSession = onLockSession,
                        onErase = onErase,
                        onDismissTrouble = onDismissTrouble,
                    )
                }
                is VaultUiState.Locked -> Unit
            }
        }
    }
}

/**
 * Верхня смуга тієї ж мови, що підекрани «Профілю»: стрілка назад, назва
 * роллю title2 («Сховище», реченням — не «СХОВИЩЕ · ПРИМАРА» моно-капсом),
 * праворуч — стан замка словом.
 */
@Composable
private fun VaultChrome(locked: Boolean, onBack: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PhantomSize.topbarPhone)
            .padding(end = PhantomSpace.gutterPhone),
        verticalAlignment = Alignment.CenterVertically,
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
                contentDescription = stringResource(DesignR.string.back_pill_action),
                tint = c.text,
                modifier = Modifier.size(PhantomSize.iconL),
            )
        }
        Text(
            text = stringResource(R.string.vault_look_title),
            style = PhantomText.title2,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        VaultStatusChip(locked = locked)
    }
}

/* ---- locked ---------------------------------------------------------- */

@Composable
private fun VaultLockedFace(
    attempt: UnlockAttempt,
    onUnlockRequested: () -> Unit,
    onBiometricSuccess: () -> Unit,
    onUnlockRefused: (UnlockAttempt) -> Unit,
) {
    val c = LocalPhantomColors.current
    val authenticate = rememberBiometricLaunch(
        onRequested = onUnlockRequested,
        onSuccess = onBiometricSuccess,
        onRefused = onUnlockRefused,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PhantomSpace.gutterPhone),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(PhantomSpace.s6))
        VaultMark(
            open = false,
            busy = attempt == UnlockAttempt.Waiting,
            tone = c.text,
            modifier = Modifier.size(MARK),
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s5))
        FaceHeadline(text = stringResource(R.string.vault_locked_headline))
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        FaceLead(text = stringResource(R.string.vault_locked_lead))
        Spacer(modifier = Modifier.height(PhantomSpace.s6))
        VaultUnlockButton(
            waiting = attempt == UnlockAttempt.Waiting,
            onClick = authenticate,
        )
        AnimatedVisibility(
            visible = refusalMessage(attempt) != null,
            enter = PhantomMotion.expand,
            exit = PhantomMotion.dismiss,
        ) {
            VaultRefusal(attempt = attempt, modifier = Modifier.padding(top = PhantomSpace.s4))
        }
        Spacer(modifier = Modifier.height(PhantomSpace.s8))
        VaultShieldLedger()
        Spacer(modifier = Modifier.height(PhantomSpace.s6))
    }
}

/* ---- open ------------------------------------------------------------ */

@Composable
private fun VaultOpenFace(
    state: VaultUiState.Open,
    onLockSession: () -> Unit,
    onErase: (String) -> Unit,
    onDismissTrouble: () -> Unit,
) {
    val context = LocalContext.current
    val timers = remember { mutableStateMapOf<String, Int>() }
    var copied by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf<String?>(null) }
    var armedLeft by remember { mutableStateOf(0) }
    var lastTouchMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var idleLeftSec by remember { mutableStateOf((IDLE_LOCK_MS / 1000).toInt()) }

    fun touch() {
        lastTouchMs = System.currentTimeMillis()
    }

    fun hide(id: String) {
        timers.remove(id)
        if (timers.isEmpty()) {
            copied?.let { revokeClipboard(context, it) }
            copied = null
        }
        touch()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            val idle = System.currentTimeMillis() - lastTouchMs
            idleLeftSec = (((IDLE_LOCK_MS - idle) + 999L) / 1000L).toInt().coerceAtLeast(0)
            if (idle >= IDLE_LOCK_MS) {
                onLockSession()
                break
            }
            timers.keys.toList().forEach { id ->
                val next = (timers[id] ?: 0) - 1
                if (next <= 0) timers.remove(id) else timers[id] = next
            }
            if (timers.isEmpty() && copied != null) {
                copied?.let { revokeClipboard(context, it) }
                copied = null
            }
            if (armed != null) {
                armedLeft -= 1
                if (armedLeft <= 0) armed = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { copied?.let { revokeClipboard(context, it) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Observes without consuming, so the list still scrolls. Reading a
            // long note is presence too — the clock used to reset on the copy
            // pill only, and threw the operator out mid-read.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                lastTouchMs = System.currentTimeMillis()
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = PhantomSpace.gutterPhone),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            item(key = "hero") { VaultOpenHero(state) }

            state.trouble?.let { trouble ->
                item(key = "trouble") {
                    VaultTroubleBanner(
                        trouble = trouble,
                        onDismiss = onDismissTrouble,
                        modifier = Modifier.animateItem(fadeInSpec = MotionTokens.fadeQuart),
                    )
                }
            }

            state.groups.forEach { group ->
                item(key = "group-${group.kind}") {
                    VaultGroupHeader(
                        group = group,
                        modifier = Modifier.animateItem(fadeInSpec = MotionTokens.fadeQuart),
                    )
                }
                items(group.entries, key = { it.id }) { entity ->
                    VaultRow(
                        entity = entity,
                        kind = classify(entity),
                        actions = VaultRowActions(
                            armed = armed == entity.id,
                            revealSeconds = timers[entity.id],
                            onReveal = {
                                copyAsSecret(context, entity.value)
                                copied = entity.value
                                timers[entity.id] = REVEAL_SECONDS
                                touch()
                            },
                            onHide = { hide(entity.id) },
                            onArmErase = {
                                armed = entity.id
                                armedLeft = ARM_SECONDS
                                touch()
                            },
                            onDisarmErase = {
                                armed = null
                                touch()
                            },
                            onConfirmErase = {
                                armed = null
                                hide(entity.id)
                                onErase(entity.id)
                            },
                        ),
                        modifier = Modifier.animateItem(fadeInSpec = MotionTokens.fadeQuart),
                    )
                }
            }

            item(key = "shield") {
                VaultShieldLedger(modifier = Modifier.padding(top = PhantomSpace.s6, bottom = PhantomSpace.s3))
            }
        }

        VaultFooter(idleLeftSec = idleLeftSec, onLockSession = onLockSession)
    }
}

@Composable
private fun VaultOpenHero(state: VaultUiState.Open) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s4, top = PhantomSpace.s2),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.total.toString(),
                style = PhantomText.display.copy(fontFeatureSettings = "tnum"),
                color = c.text,
            )
            Spacer(modifier = Modifier.width(PhantomSpace.s2))
            Text(
                text = stringResource(R.string.vault_open_total).sentence(),
                style = PhantomText.callout,
                color = c.text2,
                modifier = Modifier.padding(bottom = PhantomSpace.s1),
            )
        }
        Text(
            text = if (state.unopenable == 0) {
                stringResource(R.string.vault_open_all_good)
            } else {
                stringResource(R.string.vault_open_broken, state.unopenable)
            }.sentence(),
            style = PhantomText.footnote,
            color = if (state.unopenable == 0) c.success else c.danger,
        )
    }
}

@Composable
private fun VaultFooter(idleLeftSec: Int?, onLockSession: () -> Unit) {
    val c = LocalPhantomColors.current
    val urgent = idleLeftSec != null && idleLeftSec <= COUNTDOWN_VISIBLE_AT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.gutterPhone, vertical = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (idleLeftSec != null) {
            Text(
                text = if (urgent) {
                    stringResource(R.string.vault_idle_countdown, idleLeftSec)
                } else {
                    // Ресурс обрамлений крапками-оздобами («· … ·»): мова форми їх не носить.
                    stringResource(R.string.vault_auto_exit).trim('·', ' ')
                }.sentence(),
                style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
                color = if (urgent) c.accentText else c.text3,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.width(PhantomSpace.s3))
        VaultPill(
            label = stringResource(R.string.vault_lock),
            ink = c.text,
            onClick = onLockSession,
        )
    }
}

@Composable
private fun VaultTroubleBanner(
    trouble: VaultTrouble,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.danger, shape)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (trouble) {
                    VaultTrouble.NoRoom -> R.string.vault_trouble_no_room
                    VaultTrouble.EraseFailed -> R.string.vault_trouble_erase
                },
            ).sentence(),
            style = PhantomText.footnote,
            color = c.danger,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(PhantomSpace.s3))
        VaultPill(
            label = stringResource(R.string.vault_trouble_dismiss),
            ink = c.text,
            onClick = onDismiss,
        )
    }
}

/* ---- empty / reading / broken ---------------------------------------- */

@Composable
private fun VaultEmptyFace(onLockSession: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhantomSpace.gutterPhone),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(PhantomSpace.s6))
            VaultMark(
                open = true,
                busy = false,
                tone = c.text,
                modifier = Modifier.size(MARK_SMALL),
            )
            Spacer(modifier = Modifier.height(PhantomSpace.s5))
            FaceHeadline(text = stringResource(R.string.vault_empty))
            Spacer(modifier = Modifier.height(PhantomSpace.s2))
            FaceLead(text = stringResource(R.string.vault_empty_why))
            Spacer(modifier = Modifier.height(PhantomSpace.s6))

            VaultCaption(
                text = stringResource(R.string.vault_empty_how_title),
                modifier = Modifier.fillMaxWidth(),
            )
            VaultCard {
                Column(modifier = Modifier.padding(PhantomSpace.s4)) {
                    RouteLine(text = stringResource(R.string.vault_empty_how_chat))
                    Spacer(modifier = Modifier.height(PhantomSpace.s3))
                    Text(
                        text = stringResource(R.string.vault_empty_how_seal).sentence(),
                        style = PhantomText.footnote,
                        color = c.success,
                    )
                }
            }
            Spacer(modifier = Modifier.height(PhantomSpace.s5))
            VaultShieldLedger()
            Spacer(modifier = Modifier.height(PhantomSpace.s6))
        }
        VaultFooter(idleLeftSec = null, onLockSession = onLockSession)
    }
}

@Composable
private fun RouteLine(text: String) {
    val c = LocalPhantomColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(c.text3),
        )
        Spacer(modifier = Modifier.width(PhantomSpace.s3))
        Text(text = text.sentence(), style = PhantomText.callout, color = c.text)
    }
}

@Composable
private fun VaultReadingFace() {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PhantomSpace.gutterPhone),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaultMark(
            open = true,
            busy = true,
            tone = c.text,
            modifier = Modifier.size(MARK_SMALL),
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s4))
        Text(
            text = stringResource(R.string.vault_reading).sentence(),
            style = PhantomText.callout,
            color = c.text2,
        )
    }
}

@Composable
private fun VaultBrokenFace(onReread: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PhantomSpace.gutterPhone),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaultMark(
            open = false,
            busy = false,
            tone = c.danger,
            modifier = Modifier.size(MARK_SMALL),
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s4))
        FaceHeadline(text = stringResource(R.string.vault_broken_title))
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        FaceLead(text = stringResource(R.string.vault_broken_why))
        Spacer(modifier = Modifier.height(PhantomSpace.s5))
        VaultPill(
            label = stringResource(R.string.vault_broken_retry),
            ink = c.text,
            onClick = onReread,
        )
    }
}

@Composable
private fun FaceHeadline(text: String) {
    val c = LocalPhantomColors.current
    Text(
        text = text.sentence(),
        style = PhantomText.title1,
        color = c.text,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FaceLead(text: String) {
    val c = LocalPhantomColors.current
    Text(
        text = text.sentence(),
        style = PhantomText.callout,
        color = c.text2,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = READING),
    )
}
