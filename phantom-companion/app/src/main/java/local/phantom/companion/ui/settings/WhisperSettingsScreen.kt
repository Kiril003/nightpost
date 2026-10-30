package local.phantom.companion.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.component.PhantomBackPill
import local.phantom.companion.core.design.component.WhisperFreq
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomMono
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.feature.ambient.service.WhisperLineService
import androidx.annotation.StringRes

/**
 * Phase 3-E5 — Settings → Whisper section.
 *
 * SYSTEM_ALERT_WINDOW gating, service start/stop toggle, drift-
 * frequency picker (Slow / Medium / Fast — same enum as the preview
 * surface), sound on/off. The classifier/queue logic lives elsewhere;
 * this screen is the operator-facing affordance that wires the
 * preferences flow + the start/stop service intent.
 */
@Composable
fun WhisperSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current
    val context = LocalContext.current
    val container = (context.applicationContext as PhantomApp).container

    val overlayWanted by container.voicePrefs.whisperOverlayEnabled.collectAsState()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val overlayRunning = overlayWanted && overlayGranted

    // Granting happens in a system screen, so the service can only be started
    // on the way back.
    LaunchedEffect(overlayRunning) {
        if (overlayRunning) {
            runCatching { context.startService(Intent(context, WhisperLineService::class.java)) }
        }
    }
    val freqOrdinal by container.voicePrefs.whisperFreqOrdinal.collectAsState()
    val soundEnabled by container.voicePrefs.whisperSoundEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(bottom = LocalBottomChromeInset.current)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Was its own literal «← повернутись» — the one screen of eight
            // PhantomBackPill's own KDoc names as having drifted from the
            // shared label. Reusing settings_back closes that gap instead of
            // adding a second resource that says the same thing.
            PhantomBackPill(
                label = stringResource(R.string.settings_back),
                onBack = onBack,
            )
            Spacer(modifier = Modifier.weight(1f))
            PhantomKicker(
                text = stringResource(R.string.whisper_line_kicker),
                color = accent.color,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Service start/stop toggle (gated on SYSTEM_ALERT_WINDOW).
        GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.whisper_overlay_title),
                            color = theme.inkStrong,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                overlayRunning -> stringResource(R.string.whisper_overlay_body_running)
                                overlayWanted -> stringResource(R.string.whisper_grant_overlay)
                                else -> stringResource(R.string.whisper_overlay_body_off)
                            },
                            color = theme.inkSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = overlayRunning,
                        onCheckedChange = { newValue ->
                            handleOverlayToggle(
                                context = context,
                                newValue = newValue,
                                prefsSetter = container.voicePrefs::setWhisperOverlayEnabled,
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = accent.color,
                        ),
                    )
                }
                if (overlayWanted && !overlayGranted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionRationale(
                        accent = accent.color,
                        onGrant = {
                            launchOverlayPermissionScreen(context)
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Drift frequency picker.
        GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.whisper_freq_title),
                    color = theme.inkStrong,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                PhantomCaption(
                    text = stringResource(freqDescriptionRes(freqOrdinal)),
                    color = theme.inkSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperFreq.values().forEachIndexed { idx, freq ->
                        FreqChip(
                            label = freqLabelRes(freq)?.let { stringResource(it) } ?: "·",
                            selected = idx == freqOrdinal,
                            accent = accent.color,
                            theme = theme,
                            onClick = { container.voicePrefs.setWhisperFreqOrdinal(idx) },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sound toggle.
        GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.whisper_sound_title),
                        color = theme.inkStrong,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    PhantomCaption(
                        text = stringResource(R.string.whisper_sound_body),
                        color = theme.inkSecondary,
                    )
                }
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = { container.voicePrefs.setWhisperSoundEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent.color),
                )
            }
        }
    }
}

@Composable
private fun PermissionRationale(accent: Color, onGrant: () -> Unit) {
    val theme = LocalPhantomTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.whisper_overlay_rationale_title),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.whisper_overlay_rationale_body),
                color = theme.inkStrong,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = PhantomTouchFloor, minHeight = PhantomTouchFloor)
                    .clip(PhantomRadii.Pill)
                    .background(accent.copy(alpha = 0.20f))
                    .border(0.5.dp, accent, PhantomRadii.Pill)
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.whisper_overlay_rationale_action),
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FreqChip(
    label: String,
    selected: Boolean,
    accent: Color,
    theme: local.phantom.companion.core.design.theme.PhantomThemeSpec,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent.copy(alpha = 0.18f) else theme.glassPanel.copy(alpha = 0.4f)
    val border = if (selected) accent else theme.inkSecondary.copy(alpha = 0.3f)
    val ink = if (selected) accent else theme.inkStrong
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = PhantomTouchFloor, minHeight = PhantomTouchFloor)
            .clip(PhantomRadii.Pill)
            .background(bg)
            .border(0.5.dp, border, PhantomRadii.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = ink, fontSize = 11.sp, letterSpacing = 0.4.sp)
    }
}

/* ---- pure helpers — testable ---- */

/** null = the middle chip, drawn as a bare dot. */
@StringRes
internal fun freqLabelRes(freq: WhisperFreq): Int? = when (freq) {
    WhisperFreq.Slow -> R.string.kt_whisper_slow
    WhisperFreq.Medium -> null
    WhisperFreq.Fast -> R.string.kt_whisper_fast
}

@StringRes
internal fun freqDescriptionRes(ordinal: Int): Int = when (ordinal.coerceIn(0, 2)) {
    0 -> R.string.kt_whisper_desc_slow
    1 -> R.string.kt_whisper_desc_mid
    else -> R.string.kt_whisper_desc_fast
}

/* ---- side-effects ---- */

internal fun handleOverlayToggle(
    context: Context,
    newValue: Boolean,
    prefsSetter: (Boolean) -> Unit,
) {
    prefsSetter(newValue)
    val intent = Intent(context, WhisperLineService::class.java)
    if (newValue) {
        if (Settings.canDrawOverlays(context)) {
            runCatching { context.startService(intent) }
        }
        // If permission missing, the rationale card surfaces; the
        // operator taps "відкрити налаштування" to grant.
    } else {
        runCatching { context.stopService(intent) }
    }
}

private fun launchOverlayPermissionScreen(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
