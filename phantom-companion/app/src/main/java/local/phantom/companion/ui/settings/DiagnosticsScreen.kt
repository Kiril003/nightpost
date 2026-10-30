package local.phantom.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import local.phantom.companion.R
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.errors.PhantomError
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomMono
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.voice.SttRouteKind
import local.phantom.companion.core.voice.VoiceManager
import local.phantom.companion.core.voice.route.RoutedTts
import local.phantom.companion.core.voice.route.TtsRoute
import local.phantom.companion.net.LinkStatus
import local.phantom.companion.data.PulseSignal
import local.phantom.companion.core.telemetry.HardwareTelemetryEngine
import local.phantom.companion.core.constitution.AuditVerdict

/**
 * QH4 — Settings → Діагностика screen.
 *
 * Why this screen is necessary: PhantomError surfaces Error/Critical
 * into Stream as Notice items, but the operator-facing complaint
 * also covers ambient-degradation states ("system TTS instead of
 * StyleTTS2", "GPS denied", "WS reconnecting") that shouldn't
 * crowd the Stream. They live quietly in [PhantomErrorBus.history]
 * and surface here on demand. The status row at the top is the
 * one-glance "what's degraded right now" answer.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPrecision: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PhantomApp).container

    val history by container.errorBus.history.collectAsState()
    val linkStatus by container.link.connected.collectAsState()
    val pulse by container.systemPulse.pulse.collectAsState()
    val ttsRouteFlow = remember(container) {
        (container.ttsEngine as? RoutedTts)?.currentRoute
            ?: kotlinx.coroutines.flow.MutableStateFlow(TtsRoute.None)
    }
    val ttsRoute by ttsRouteFlow.collectAsState()
    val sttRouteFlow = remember(container) {
        (container.voiceManager as? VoiceManager)?.activeEngine
            ?: kotlinx.coroutines.flow.MutableStateFlow(SttRouteKind.None)
    }
    val sttRoute by sttRouteFlow.collectAsState()
    val paired = rememberOnResume(container) { container.store.load() }

    // ── Resources (LUX-0) — moved out of the Stream column. Only the
    // engine's REAL fields surface here; tokensPerSec/activeAgents are
    // simulated constants in HardwareTelemetryEngine (Law 1: no fake
    // theater), so they stay hidden until they carry live data.
    val telemetryEngine = remember(context) {
        HardwareTelemetryEngine(context.applicationContext)
    }
    val telemetry by telemetryEngine.streamTelemetry().collectAsState(
        initial = HardwareTelemetryEngine.TelemetrySnapshot(0, 0, false),
    )

    // ── Constitution rejections — ConstitutionRegistry.rejections had
    // zero readers anywhere in the shipped app before this; a tool that
    // exists, is wired, and is refused at every boot (e.g. mission.arm)
    // was indistinguishable from one that never existed at all.
    val rejections by container.constitutionRegistry.rejections.collectAsStateWithLifecycle()

    DiagnosticsContent(
        onBack = onBack,
        onOpenPrecision = onOpenPrecision,
        onClearLog = { container.errorBus.clearHistory() },
        pairedName = paired?.let { pairedComputerLabel(it, stringResource(R.string.pair_computer_fallback)) },
        linkStatus = linkStatus,
        ttsRoute = ttsRoute,
        sttRoute = sttRoute,
        sensorStatus = pulse.sensor.status,
        telemetry = telemetry,
        rejections = rejections,
        history = history,
        modifier = modifier,
    )
}

/**
 * Кожен `remember`/`collectAsState` лишається В [DiagnosticsScreen], НАД
 * списком: елементи LazyColumn знищуються при прокрутці, тож telemetryEngine
 * перебудовувався б і перепідписувався на кожному русі пальця.
 */
@Composable
internal fun DiagnosticsContent(
    onBack: () -> Unit,
    onOpenPrecision: () -> Unit,
    onClearLog: () -> Unit,
    pairedName: String?,
    linkStatus: LinkStatus,
    ttsRoute: TtsRoute,
    sttRoute: SttRouteKind,
    sensorStatus: PulseSignal.Status,
    telemetry: HardwareTelemetryEngine.TelemetrySnapshot,
    rejections: List<AuditVerdict.Failed>,
    history: List<PhantomError>,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalStateAccent.current

    // A single LazyColumn, not a plain Column wrapping one: the error log
    // below is itself a LazyColumn, and nesting a lazy list inside a
    // Modifier.verticalScroll() Column of the same orientation crashes at
    // runtime ("vertically scrollable component was measured with an
    // infinite height"). The old plain Column had no scroll at all for
    // exactly that reason, so on a short screen the bottom cards — and the
    // whole error log — had nowhere to go.
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(bottom = LocalBottomChromeInset.current)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(PhantomRadii.Pill)
                        .background(theme.glassPanel.copy(alpha = 0.85f))
                        .border(0.5.dp, theme.glassBorder, PhantomRadii.Pill)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    PhantomCaption(
                        text = stringResource(R.string.settings_back),
                        color = theme.inkSecondary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                PhantomKicker(
                    text = stringResource(R.string.diag_kicker),
                    color = accent.color,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }

        item {
            // ── Status row: pair / WS / TTS / STT / GPS ──────────────────
            GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PhantomKicker(
                        text = stringResource(R.string.diag_state),
                        color = accent.color,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusRow(
                        label = stringResource(R.string.diag_pair),
                        value = pairedName ?: stringResource(R.string.diag_pair_none),
                        healthy = pairedName != null,
                        theme = theme,
                        accent = accent.color,
                    )
                    StatusRow("WS", stringResource(linkStatusLabel(linkStatus)), linkStatus == LinkStatus.Connected, theme = theme, accent = accent.color)
                    StatusRow("TTS", stringResource(ttsRouteLabel(ttsRoute)), ttsRoute != TtsRoute.None, theme = theme, accent = accent.color)
                    StatusRow("STT", stringResource(sttRouteLabel(sttRoute)), sttRoute != SttRouteKind.None, theme = theme, accent = accent.color)
                    StatusRow(
                        label = stringResource(R.string.diag_sensors),
                        value = stringResource(sensorStatusLabel(sensorStatus)),
                        healthy = sensorStatus == PulseSignal.Status.LIVE,
                        theme = theme,
                        accent = accent.color,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            GlassCard(
                level = GlassLevel.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrecision),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        PhantomKicker(
                            text = stringResource(R.string.diag_gnss_title),
                            color = accent.color,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        PhantomCaption(
                            text = stringResource(R.string.diag_gnss_body),
                            color = theme.inkSecondary,
                        )
                    }
                    Text(
                        text = "→",
                        color = accent.color,
                        fontSize = 18.sp,
                        fontFamily = PhantomMono,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PhantomKicker(
                        text = stringResource(R.string.diag_resources),
                        color = accent.color,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusRow(
                        label = stringResource(R.string.diag_mem_pss),
                        value = stringResource(R.string.diag_megabytes, telemetry.pssMemoryMb),
                        healthy = !telemetry.isLowMemory,
                        theme = theme,
                        accent = accent.color,
                    )
                    StatusRow(
                        label = stringResource(R.string.diag_ram_free),
                        value = stringResource(R.string.diag_megabytes, telemetry.availableRamMb),
                        healthy = !telemetry.isLowMemory,
                        theme = theme,
                        accent = accent.color,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PhantomKicker(
                        text = stringResource(R.string.diag_constitution_title, rejections.size),
                        color = accent.color,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (rejections.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diag_constitution_empty),
                            color = theme.inkSecondary,
                            fontSize = 12.sp,
                            fontFamily = PhantomMono,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rejections.forEach { verdict -> RejectionRow(verdict) }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        item {
            // ── Error log ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.diag_log, history.size),
                    color = accent.color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = PhantomMono,
                    modifier = Modifier.weight(1f),
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearLog) {
                        PhantomCaption(
                            text = stringResource(R.string.diag_clear),
                            color = theme.inkSecondary,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        if (history.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.diag_empty),
                    color = theme.inkSecondary,
                    fontSize = 12.sp,
                    fontFamily = PhantomMono,
                )
            }
        } else {
            // Newest first.
            items(history.asReversed(), key = { it.id }) { err ->
                ErrorRow(err)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    healthy: Boolean,
    theme: local.phantom.companion.core.design.theme.PhantomThemeSpec,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (healthy) "✓" else "·",
            color = if (healthy) accent else theme.coral,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            // 56dp was sized for «TTS». «сенсори» fills it exactly, so the
            // value it is meant to separate ran straight into the label.
            text = label,
            color = theme.inkSecondary,
            fontSize = 12.sp,
            fontFamily = PhantomMono,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = theme.inkStrong,
            fontSize = 12.sp,
            fontFamily = PhantomMono,
        )
    }
}

@Composable
private fun RejectionRow(verdict: local.phantom.companion.core.constitution.AuditVerdict.Failed) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PhantomRadii.SmallShape)
            .background(theme.glassPanel.copy(alpha = 0.4f))
            .border(0.5.dp, theme.glassBorder, PhantomRadii.SmallShape)
            .padding(12.dp),
    ) {
        PhantomBody(
            text = verdict.displayName,
            color = theme.inkStrong,
            fontWeight = FontWeight.SemiBold,
        )
        verdict.violations.forEach { violation ->
            PhantomCaption(
                text = "· " + stringResource(axiomLabel(violation.axiom)),
                color = theme.inkSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@StringRes
internal fun axiomLabel(axiom: local.phantom.companion.core.constitution.Axiom): Int =
    when (axiom) {
        local.phantom.companion.core.constitution.Axiom.OPERATOR_ADVOCATE -> R.string.diag_axiom_operator_advocate
        local.phantom.companion.core.constitution.Axiom.REFUSAL_OK -> R.string.diag_axiom_refusal_ok
        local.phantom.companion.core.constitution.Axiom.CONFLICT_TRANSPARENCY -> R.string.diag_axiom_conflict_transparency
        local.phantom.companion.core.constitution.Axiom.POWER_ASYMMETRY_GUARD -> R.string.diag_axiom_power_asymmetry_guard
        local.phantom.companion.core.constitution.Axiom.SUNSET_CLAUSE -> R.string.diag_axiom_sunset_clause
        local.phantom.companion.core.constitution.Axiom.SELF_AUDIT_DAILY -> R.string.diag_axiom_self_audit_daily
    }

@Composable
private fun ErrorRow(err: PhantomError) {
    val theme = LocalPhantomTheme.current
    var expanded by remember(err.id) { mutableStateOf(false) }
    val severityTint = when (err.severity) {
        PhantomError.Severity.Info -> theme.inkSecondary
        PhantomError.Severity.Warn -> theme.primary
        PhantomError.Severity.Error -> theme.coral
        PhantomError.Severity.Critical -> theme.coralDeep
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PhantomRadii.SmallShape)
            .background(theme.glassPanel.copy(alpha = 0.4f))
            .border(0.5.dp, theme.glassBorder, PhantomRadii.SmallShape)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(severityLabel(err.severity)),
                    color = severityTint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = PhantomMono,
                    // 60dp fitted «warn»; «попередження» is twice that.
                    modifier = Modifier.widthIn(min = 60.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = err.source,
                    color = theme.inkStrong,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(err.ts),
                    color = theme.inkSecondary,
                    fontSize = 10.sp,
                    fontFamily = PhantomMono,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = err.message,
                color = theme.ink,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            val cause = err.cause
            if (expanded && cause != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stackTraceText(cause),
                    color = theme.inkSecondary,
                    fontSize = 10.sp,
                    fontFamily = PhantomMono,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@StringRes
internal fun linkStatusLabel(status: LinkStatus): Int = when (status) {
    LinkStatus.Connected -> R.string.diag_link_connected
    LinkStatus.Connecting -> R.string.diag_link_connecting
    LinkStatus.Disconnected -> R.string.diag_link_disconnected
}

internal fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale("uk")).format(Date(ts))

internal fun stackTraceText(t: Throwable): String {
    val sw = StringWriter()
    PrintWriter(sw).use { t.printStackTrace(it) }
    return sw.toString().lineSequence().take(20).joinToString("\n")
}

/** Both enums reached the operator as their constant names. */
@StringRes
private fun sensorStatusLabel(status: local.phantom.companion.data.PulseSignal.Status): Int =
    when (status) {
        local.phantom.companion.data.PulseSignal.Status.LIVE -> R.string.diag_sensor_live
        local.phantom.companion.data.PulseSignal.Status.IDLE -> R.string.diag_sensor_idle
        local.phantom.companion.data.PulseSignal.Status.OFF -> R.string.diag_sensor_off
    }

@StringRes
private fun severityLabel(s: local.phantom.companion.core.data.errors.PhantomError.Severity): Int =
    when (s) {
        local.phantom.companion.core.data.errors.PhantomError.Severity.Info -> R.string.diag_sev_info
        local.phantom.companion.core.data.errors.PhantomError.Severity.Warn -> R.string.diag_sev_warn
        local.phantom.companion.core.data.errors.PhantomError.Severity.Error -> R.string.diag_sev_error
        local.phantom.companion.core.data.errors.PhantomError.Severity.Critical -> R.string.diag_sev_critical
    }
