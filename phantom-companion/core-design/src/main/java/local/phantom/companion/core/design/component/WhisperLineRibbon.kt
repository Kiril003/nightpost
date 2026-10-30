package local.phantom.companion.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomMono

/**
 * Phase 1.5-C / Phase 3-E2 — pure marquee ribbon for the Whisper Line.
 *
 * Reference: `/tmp/phantom-design-4/phantom-app2/project/phase2.jsx#WhisperLineScene`
 * (lines 390-471). The prototype uses a CSS keyframe `phantom-whisper-drift`
 * that translates the text from 0% to -50% across `38s linear infinite` —
 * the text is concatenated 3× so a -50% shift visually moves exactly one
 * full message cycle off-screen. Compose port: rebuild the same continuous
 * leftward drift via [rememberInfiniteTransition] keyed on cycle-duration,
 * driving an x-offset proportional to [textWidthDp].
 *
 * Math is extracted into pure helpers ([fullRibbonText], [ribbonProgress])
 * so a JVM unit test can pin the timing without instantiating Compose.
 *
 * Phase 3-E2 — promoted from `:feature-stream/ui/whisper` to `:core-design`
 * so both the preview surface (`feature-stream`) and the always-on overlay
 * service (`feature-ambient.WhisperLineService`) can share one composable.
 * Helpers were `internal` to feature-stream; now `public` so tests in any
 * module that depends on `:core-design` can keep their assertions.
 */
@Composable
fun WhisperLineRibbon(
    messages: List<String>,
    cycleDurationMs: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
    stripeHeight: Dp = 2.dp,
    fontSizeSp: Int = 8,
    textWidthDp: Dp = 1200.dp,
    /**
     * Чи є що нести. Стрічка живе в overlay-сервісі, який не вмирає ніколи:
     * доки перехід стоїть у композиції, він замовляє кадр за кадром поверх
     * КОЖНОГО чужого застосунку. У спокої нести нема чого, тож переходу тут
     * не існує — не «є і не малює».
     */
    animate: Boolean = true,
) {
    val text = remember(messages) { fullRibbonText(messages) }
    val progress = ribbonDrift(cycleDurationMs, animate)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(stripeHeight)
            .background(Color.Black.copy(alpha = PhantomAlpha.Ghost)),
    ) {
        // Render the text 3× for seamless wrap. Offset moves leftward by
        // -50% of total width across the cycle, mirroring the CSS keyframe.
        val offsetDp = (-progress * (textWidthDp.value)).dp
        Text(
            text = AnnotatedString("$text$text$text"),
            color = accentColor,
            fontSize = fontSizeSp.sp,
            fontFamily = PhantomMono,
            fontWeight = FontWeight.Light,
            letterSpacing = 1.4.sp,
            modifier = Modifier.offset(x = offsetDp),
            maxLines = 1,
        )
    }
}

/**
 * Дрейф стрічки — і єдине місце, де вона взагалі просить кадри.
 *
 * Гілка `!animate` не має жодного `remember`-у анімації, тож при перемиканні
 * група відкидається, `InfiniteTransition` забувається, а його `LaunchedEffect`
 * скасовується. Це перевіряється не прапорцем, а лічильником кадрів:
 * `WhisperRibbonDriftTest`.
 */
@Composable
internal fun ribbonDrift(cycleDurationMs: Int, animate: Boolean): Float {
    if (!animate) return 0f
    val transition = rememberInfiniteTransition(label = "whisper-ribbon")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "whisper-drift",
    )
    return progress
}

/* ---- pure helpers (covered by WhisperLineRibbonTest) ---- */

/**
 * Joins [messages] with the prototype's exact separator and trailing
 * separator, matching `phase2.jsx#WhisperLineScene` line 397:
 * `messages.join('   ···   ') + '   ···   '`. The trailing copy keeps the
 * marquee's last-message → first-message handoff continuous.
 *
 * Public (was `internal` in feature-stream pre-Phase-3-E2) so tests in
 * any consuming module can pin the prototype-verbatim shape.
 */
fun fullRibbonText(
    messages: List<String>,
    separator: String = "   ···   ",
): String = if (messages.isEmpty()) "" else messages.joinToString(separator) + separator

/**
 * Maps absolute elapsed time to the [0f, 1f) drift progress within the
 * current cycle. A monotonic clock with positive [cycleDurationMs] is the
 * only valid input — nonpositive cycles short-circuit to 0f so a misuse
 * doesn't crash the ribbon.
 */
fun ribbonProgress(elapsedMs: Long, cycleDurationMs: Long): Float {
    if (cycleDurationMs <= 0L) return 0f
    val safe = elapsedMs.coerceAtLeast(0L)
    val mod = safe % cycleDurationMs
    return mod.toFloat() / cycleDurationMs.toFloat()
}

/**
 * Number of full cycles that have wrapped at [elapsedMs]. Useful to count
 * how many times the message list has fully cycled past — the Whisper
 * preview displays a soft "повторено · {n}" debug overlay in tests.
 */
fun cyclesElapsed(elapsedMs: Long, cycleDurationMs: Long): Long {
    if (cycleDurationMs <= 0L) return 0L
    val safe = elapsedMs.coerceAtLeast(0L)
    return safe / cycleDurationMs
}

/**
 * Phase 3-E2 — drift-speed enum shared between the preview surface and
 * the always-on overlay. Cycle durations match `phase2.jsx#WhisperLineScene`
 * cadence picker exactly. Persisted to disk by ordinal in
 * `VoicePreferences.whisperFreq` so a future enum reorder breaks tests
 * loudly rather than silently rotating saved values.
 */
enum class WhisperFreq(val cycleMs: Int) {
    Slow(60_000),
    Medium(38_000),
    Fast(22_000),
}

/** Default fallback messages used by the overlay when no agentEvents have populated it yet. */
val WhisperLineDefaultMessages: List<String> = listOf(
    "Nightpost тут · присутність активна",
    "тиха стрічка · нічого не вимагає твоєї уваги",
)
