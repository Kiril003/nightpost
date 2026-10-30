package local.phantom.companion.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.voice.rememberRecordAudioPermissionGate
import local.phantom.companion.self.CallUi
import local.phantom.companion.core.design.R as DesignR

/**
 * Дзвінок PHANTOM — накладка над домівкою. Двигун — [local.phantom.companion.self.PhantomCall]:
 * живий голос через Gemini Live, інакше покроково. Тут лише двері: аватар із
 * тонким кільцем, що відгукується на рівень голосу (без сяйва), слова стану,
 * субтитри і чотири кнопки — мікрофон, динамік, завершити, субтитри.
 */
@Composable
internal fun PhantomCallScreen(onEnd: () -> Unit) {
    val container = remember { PhantomApp.container() }
    val call = container.phantomCall
    val c = LocalPhantomColors.current
    val gate = rememberRecordAudioPermissionGate()
    var refused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        refused = true
        gate.requestThen {
            refused = false
            call.begin()
        }
    }
    DisposableEffect(Unit) {
        onDispose { call.end() }
    }
    BackHandler(onBack = onEnd)

    val ui by call.ui.collectAsStateWithLifecycle()
    // Рівень приходить кадрами по 20 мс — кільце йде за ним без власної анімації.
    val rawLevel by call.level.collectAsStateWithLifecycle()
    val level = rawLevel.coerceIn(0f, 1f)

    val word = when {
        refused -> stringResource(R.string.call_mic_needed)
        else -> when (ui.word) {
            CallUi.Word.CONNECTING -> stringResource(R.string.call_connecting)
            CallUi.Word.LISTENING -> stringResource(R.string.call_listening)
            CallUi.Word.THINKING -> stringResource(R.string.call_thinking)
            CallUi.Word.SPEAKING -> stringResource(R.string.call_speaking)
            CallUi.Word.HOLD_TO_TALK -> stringResource(R.string.call_hold_to_talk)
        }
    }
    val ringOn = ui.word == CallUi.Word.LISTENING || ui.word == CallUi.Word.SPEAKING
    val ring = c.accentLine

    Column(
        modifier = Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(PhantomSpace.s4))
        Text(
            text = when (ui.mode) {
                CallUi.Mode.LIVE -> stringResource(R.string.call_mode_live)
                CallUi.Mode.TURN -> stringResource(R.string.call_mode_turn)
            },
            style = PhantomText.caption,
            color = c.text3,
        )
        Spacer(Modifier.weight(0.45f))
        Box(
            modifier = Modifier
                .size(172.dp)
                .drawBehind {
                    if (ringOn) {
                        val base = 66.dp.toPx()
                        val reach = 18.dp.toPx() * level
                        drawCircle(
                            ring.copy(alpha = 0.25f + 0.45f * level),
                            radius = base + reach,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Знак Nightpost, а не літера «P» від старого імені (FR-6).
                Icon(
                    painter = painterResource(R.drawable.lc_phantom_mark),
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(PhantomSpace.s5))
        Text(stringResource(R.string.call_phantom_title), style = PhantomText.title1, color = c.text)
        Spacer(Modifier.height(PhantomSpace.s1))
        Text(
            word,
            style = PhantomText.callout,
            color = c.text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = PhantomSpace.s8),
        )
        if (ui.note.isNotBlank()) {
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(
                ui.note,
                style = PhantomText.footnote,
                color = c.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = PhantomSpace.s8),
            )
        }
        Spacer(Modifier.height(PhantomSpace.s6))
        if (ui.captions) Captions(ui)
        ui.ask?.let { ask ->
            Spacer(Modifier.height(PhantomSpace.s4))
            Confirm(ask, onYes = { call.confirm(true) }, onNo = { call.confirm(false) })
        }
        Spacer(Modifier.weight(1f))
        if (refused) {
            // FR-3 (ніч 09.10): після відмови екран казав «дозвольте мікрофон» і
            // не давав чим. Кнопка питає знову; після остаточної відмови брама
            // сама покаже «Відкрити налаштування».
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable(role = Role.Button) {
                        gate.requestThen {
                            refused = false
                            call.begin()
                        }
                    }
                    .padding(horizontal = PhantomSpace.s6),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.call_mic_allow), style = PhantomText.headline, color = c.onAccent)
            }
            Spacer(Modifier.height(PhantomSpace.s8))
        }
        if (ui.mode == CallUi.Mode.TURN && !refused) {
            val holdLabel = stringResource(R.string.call_hold_to_talk)
            val held = ui.word == CallUi.Word.LISTENING
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(if (held) c.accent else c.raised)
                    // Читач екрана: подвійний дотик = почати/закінчити репліку.
                    .semantics {
                        contentDescription = holdLabel
                        role = Role.Button
                        onClick {
                            if (held) call.release() else call.press()
                            true
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            call.press()
                            tryAwaitRelease()
                            call.release()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_mic),
                    contentDescription = null,
                    tint = if (held) c.onAccent else c.text,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(PhantomSpace.s8))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.s6),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            Control(
                icon = if (ui.muted) DesignR.drawable.lc_mic_off else DesignR.drawable.lc_mic,
                label = stringResource(R.string.call_mute),
                on = !ui.muted,
                onClick = call::toggleMute,
            )
            Control(
                icon = DesignR.drawable.lc_volume,
                label = stringResource(R.string.call_speaker),
                on = ui.speaker,
                onClick = call::toggleSpeaker,
            )
            Control(
                icon = DesignR.drawable.lc_phone_off,
                label = stringResource(R.string.call_end),
                on = true,
                danger = true,
                onClick = onEnd,
            )
            Control(
                icon = DesignR.drawable.lc_captions,
                label = stringResource(R.string.call_captions),
                on = ui.captions,
                onClick = call::toggleCaptions,
            )
        }
        Spacer(Modifier.height(PhantomSpace.s8))
    }
}

@Composable
private fun Captions(ui: CallUi) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.s8).heightIn(min = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ui.owner.isNotBlank()) {
            Text(
                stringResource(R.string.call_you) + ": " + ui.owner.takeLast(CAPTION_OWNER),
                style = PhantomText.footnote,
                color = c.text3,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
        }
        if (ui.phantom.isNotBlank()) {
            Text(
                ui.phantom.takeLast(CAPTION_PHANTOM),
                style = PhantomText.body,
                color = c.text,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Confirm(ask: String, onYes: () -> Unit, onNo: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = PhantomSpace.s6)
            .fillMaxWidth()
            .clip(PhantomRadii.LargeShape)
            .background(c.surface)
            .padding(PhantomSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ask, style = PhantomText.callout, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(PhantomSpace.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3)) {
            Pill(stringResource(R.string.call_confirm_no), c.raised, c.text, onNo)
            Pill(stringResource(R.string.call_confirm_yes), c.accent, c.onAccent, onYes)
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(44.dp)
            .clip(PhantomRadii.Pill)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PhantomText.headline, color = fg)
    }
}

@Composable
private fun Control(icon: Int, label: String, on: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val c = LocalPhantomColors.current
    val bg = when {
        danger -> c.danger
        on -> c.raised
        else -> c.surface
    }
    val tint = when {
        danger -> c.canvas
        on -> c.text
        else -> c.text3
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(if (danger) 64.dp else 56.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        Text(label, style = PhantomText.caption, color = c.text2)
    }
}

private const val CAPTION_OWNER = 140
private const val CAPTION_PHANTOM = 260
