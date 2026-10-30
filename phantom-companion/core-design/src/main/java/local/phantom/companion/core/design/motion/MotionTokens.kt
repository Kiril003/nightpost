package local.phantom.companion.core.design.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * W1.5-3 — canonical motion specs from DESIGN_BRIEF §VI.
 *
 * The brief specifies three first-class motion gestures:
 *
 *   • snap         — element finds its target with organic damping;
 *                    spring(stiffness=180, dampingRatio=0.75,
 *                           visibilityThreshold=0.001f), settles in
 *                    ~600ms, used for QR-corner brackets snapping to
 *                    a frame, RouteWidget's snap-to-corner, etc.
 *
 *   • breathing    — slower, more organic breath cycle for the Orb's
 *                    state-mood morph and the breathing radial bloom;
 *                    spring(stiffness=120, dampingRatio=0.85).
 *
 *   • fade         — page transitions and crossfades on Stream item
 *                    enter/exit; easeInOutQuart over 600ms (the
 *                    quart curve is steeper near the edges, gentler
 *                    in the middle — matches the «morph, never slide»
 *                    rule of the brief).
 *
 * Pulling these into a single tokens object means future composables
 * just import the spec instead of inventing fresh stiffness numbers.
 * MotionTokensTest pins each so a future refactor that swings
 * stiffness back to Material's spring(50) is caught.
 */
object MotionTokens {

    /** Stiffness for the canonical snap spring. */
    const val SNAP_STIFFNESS: Float = 180f
    /** Damping ratio for the canonical snap spring. */
    const val SNAP_DAMPING_RATIO: Float = 0.75f

    /** Stiffness for the canonical breathing spring. */
    const val BREATHING_STIFFNESS: Float = 120f
    /** Damping ratio for the canonical breathing spring. */
    const val BREATHING_DAMPING_RATIO: Float = 0.85f

    /** Duration in ms for the canonical fade transition. */
    const val FADE_QUART_DURATION_MS: Int = 600

    /**
     * easeInOutQuart Bézier — `cubic-bezier(0.77, 0, 0.175, 1)` —
     * that's the de-facto «quart» easing the brief calls out (steeper
     * acceleration/deceleration at the edges, gentler in the middle
     * than easeInOut).
     */
    val EaseInOutQuart: CubicBezierEasing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** Snap spring for `Animatable<Float>` (scale, alpha, single-axis position). */
    val snapSpringFloat: SpringSpec<Float> =
        spring(stiffness = SNAP_STIFFNESS, dampingRatio = SNAP_DAMPING_RATIO)

    /** Snap spring for `Animatable<Offset>` (position morphs in 2D). */
    val snapSpringOffset: SpringSpec<Offset> =
        spring(
            stiffness = SNAP_STIFFNESS,
            dampingRatio = SNAP_DAMPING_RATIO,
            visibilityThreshold = Offset(0.001f, 0.001f),
        )

    /** Snap spring for `Animatable<Color>` (state-accent, orb color shifts). */
    val snapSpringColor: SpringSpec<Color> =
        spring(stiffness = SNAP_STIFFNESS, dampingRatio = SNAP_DAMPING_RATIO)

    /** Breathing spring for `Animatable<Float>` (Orb scale, slow morphs). */
    val breathingSpringFloat: SpringSpec<Float> =
        spring(stiffness = BREATHING_STIFFNESS, dampingRatio = BREATHING_DAMPING_RATIO)

    /** EaseInOutQuart fade tween — used for crossfades, page-morph. */
    val fadeQuart: TweenSpec<Float> =
        tween(durationMillis = FADE_QUART_DURATION_MS, easing = EaseInOutQuart)

    /** EaseInOutQuart fade tween for `Color` (state-accent, orb transitions). */
    val fadeQuartColor: TweenSpec<Color> =
        tween(durationMillis = FADE_QUART_DURATION_MS, easing = EaseInOutQuart)

    /** Snap spring for `IntSize` — expand/shrink morphs (the stratum
     *  switcher pill, any AnimatedVisibility size change on the snap
     *  gesture). Same 180/0.75 as every other snap. */
    val snapSpringIntSize: SpringSpec<androidx.compose.ui.unit.IntSize> =
        spring(
            stiffness = SNAP_STIFFNESS,
            dampingRatio = SNAP_DAMPING_RATIO,
            visibilityThreshold = androidx.compose.ui.unit.IntSize(1, 1),
        )

    /** Layer spring (bible 05 §1.3: k 400, ζ 0.86) for `Dp` — the horizon
     *  bar's sunrise stroke sliding between tabs. Sheets and screens use the
     *  same numbers; no overshoot. */
    val layerSpringDp: SpringSpec<androidx.compose.ui.unit.Dp> =
        spring(
            stiffness = local.phantom.companion.core.design.tokens.PhantomMotion.LAYER_STIFFNESS,
            dampingRatio = local.phantom.companion.core.design.tokens.PhantomMotion.LAYER_DAMPING,
            visibilityThreshold = androidx.compose.ui.unit.Dp.VisibilityThreshold,
        )

    /** Generic spec lookup if a caller wants a typed `AnimationSpec<Float>` reference. */
    val snapFloat: AnimationSpec<Float> = snapSpringFloat
    val breathingFloat: AnimationSpec<Float> = breathingSpringFloat

    // ── Μ1 — the Meridian's clocks (PROTEUS §2.2–2.3) ──────────────────

    /** Duration for the Meridian unfold — §2.2 specifies 220ms on the
     *  quart curve: fast enough to feel like the line itself opening,
     *  not a panel arriving. */
    const val UNFOLD_DURATION_MS: Int = 220

    /** The unfold's alpha leg. */
    val unfoldQuart: TweenSpec<Float> =
        tween(durationMillis = UNFOLD_DURATION_MS, easing = EaseInOutQuart)

    /** The unfold's size leg (expandVertically/shrinkVertically). */
    val unfoldQuartIntSize: TweenSpec<androidx.compose.ui.unit.IntSize> =
        tween(durationMillis = UNFOLD_DURATION_MS, easing = EaseInOutQuart)

    /** One slow-pulse period — the THINKING tempo the presence orb already
     *  breathes at (1.4s); the Meridian line pulses on the same clock so
     *  the two organs read as one body working. */
    val pulseSlow: TweenSpec<Float> =
        tween(durationMillis = 1_400, easing = androidx.compose.animation.core.LinearEasing)

    /** One full spark sweep along the Meridian while PHANTOM streams. */
    val sweep: TweenSpec<Float> =
        tween(durationMillis = 1_200, easing = androidx.compose.animation.core.LinearEasing)

    /** One urgent-pulse period — the alarm cadence. Alert dots and siren
     *  pills breathe at 850ms: fast enough to read as «now», slow enough
     *  not to strobe. Faster than [pulseSlow] on purpose — thinking is
     *  calm, an air-raid is not. */
    const val PULSE_URGENT_MS: Int = 850

    /** The urgent pulse's alpha leg (pair with `infiniteRepeatable` +
     *  `RepeatMode.Reverse`). */
    val pulseUrgent: TweenSpec<Float> =
        tween(durationMillis = PULSE_URGENT_MS)

    /** Μ1d / PROTEUS §2.5 — the composer's crease: the canvas folds open
     *  in 180ms (the spec's number) — faster than the 220ms unfold because
     *  the operator's finger is already committed to typing; the surface
     *  must be ready before the first glyph. */
    const val CREASE_DURATION_MS: Int = 180

    /** The crease-open/close alpha+presence leg. */
    val creaseQuart: TweenSpec<Float> =
        tween(durationMillis = CREASE_DURATION_MS, easing = EaseInOutQuart)

    // ── Μ5 — the seven working verbs ──────────────────────────────────
    //
    // Everything above is a *specific* organ's clock (the Meridian, the
    // crease, the alarm). What the tree actually needed was the generic
    // set: something arrives, something leaves, something opens, something
    // is thrown away, something demands the eye, something changes value,
    // and a list lands one row after another.
    //
    // Two laws set the numbers:
    //   • an exit is always faster than the matching enter — leaving must
    //     never make the operator wait for a surface he already dismissed;
    //   • anything the finger drives is a spring, anything the system
    //     decides is a tween. A tween under a finger reads as lag.

    /** Something arrives. Shares the Meridian's 220ms tempo on purpose —
     *  one arrival speed for the whole body. */
    const val ENTER_DURATION_MS: Int = 220

    /** Something leaves. Deliberately below [ENTER_DURATION_MS]. */
    const val EXIT_DURATION_MS: Int = 160

    /** Something opens in place (a capsule, a panel, a disclosure). */
    const val EXPAND_DURATION_MS: Int = 260

    /** …and folds back. */
    const val DISMISS_DURATION_MS: Int = 180

    /** A value changes: a tint, a selection, a number's colour band. */
    const val STATE_DURATION_MS: Int = 200

    /** Stiffness of the emphasis spring — quick, with a hint of overshoot,
     *  for the one element that must catch the eye now. */
    const val EMPHASIS_STIFFNESS: Float = 420f
    const val EMPHASIS_DAMPING_RATIO: Float = 0.55f

    /** The finger's own spring: fast and flat, no bounce. A press, a drag
     *  settling home, a sheet chasing the thumb. [SNAP_STIFFNESS] is the
     *  camera's weight and is far too slow under a fingertip. */
    const val GESTURE_STIFFNESS: Float = 700f
    const val GESTURE_DAMPING_RATIO: Float = 0.9f

    /** Per-row delay for a staggered list, and the ceiling it never passes —
     *  five rows in, the stagger has said everything it can say. */
    const val STAGGER_STEP_MS: Int = 28
    const val STAGGER_CAP_MS: Int = 140

    /** A meter tracking a live quantity (approach bar, route progress).
     *  Linear on purpose: the value is re-targeted every GPS fix, and an
     *  eased curve on a moving target reads as surging. */
    const val METER_DURATION_MS: Int = 500

    val enterQuart: TweenSpec<Float> =
        tween(durationMillis = ENTER_DURATION_MS, easing = EaseInOutQuart)

    val exitQuart: TweenSpec<Float> =
        tween(durationMillis = EXIT_DURATION_MS, easing = EaseInOutQuart)

    val expandQuart: TweenSpec<Float> =
        tween(durationMillis = EXPAND_DURATION_MS, easing = EaseInOutQuart)

    val expandQuartIntSize: TweenSpec<androidx.compose.ui.unit.IntSize> =
        tween(durationMillis = EXPAND_DURATION_MS, easing = EaseInOutQuart)

    val dismissQuart: TweenSpec<Float> =
        tween(durationMillis = DISMISS_DURATION_MS, easing = EaseInOutQuart)

    val dismissQuartIntSize: TweenSpec<androidx.compose.ui.unit.IntSize> =
        tween(durationMillis = DISMISS_DURATION_MS, easing = EaseInOutQuart)

    val stateQuart: TweenSpec<Float> =
        tween(durationMillis = STATE_DURATION_MS, easing = EaseInOutQuart)

    val stateQuartColor: TweenSpec<Color> =
        tween(durationMillis = STATE_DURATION_MS, easing = EaseInOutQuart)

    val emphasisSpring: SpringSpec<Float> =
        spring(stiffness = EMPHASIS_STIFFNESS, dampingRatio = EMPHASIS_DAMPING_RATIO)

    val gestureSpring: SpringSpec<Float> =
        spring(stiffness = GESTURE_STIFFNESS, dampingRatio = GESTURE_DAMPING_RATIO)

    val meterLinear: TweenSpec<Float> =
        tween(
            durationMillis = METER_DURATION_MS,
            easing = androidx.compose.animation.core.LinearEasing,
        )

    /**
     * A clock, not a gesture: something that must run for exactly [durationMs]
     * at constant speed (the first-run language screen's own budget, already
     * scaled by the system animator setting). Linear, because it measures time.
     */
    fun clockLinear(durationMs: Int): TweenSpec<Float> =
        tween(durationMillis = durationMs, easing = androidx.compose.animation.core.LinearEasing)

    // ── Μ6 — the two things that are not breaths ──────────────────────

    /**
     * A text caret. Not a breath: [pulseSlow] is the body's thinking tempo
     * and a cursor blinking at 1.4s reads as a rendering fault rather than
     * as a cursor. Typing convention is its own clock and always has been.
     */
    const val CARET_BLINK_MS: Int = 600

    val caretBlink: TweenSpec<Float> =
        tween(durationMillis = CARET_BLINK_MS, easing = androidx.compose.animation.core.LinearEasing)

    /**
     * The voice depth morph — the one sanctioned asymmetry in the grammar.
     *
     * Everywhere else an exit is faster than its enter, because a surface
     * the operator dismissed must not make him wait. This is not a surface:
     * voice mode does not open a window, it changes the operator's depth in
     * the one he is already in. Arriving fast reads as responsive; leaving
     * fast reads as the stream snapping back at him, so the return is the
     * slower leg on purpose.
     */
    const val VOICE_PRESENCE_IN_MS: Int = 320
    const val VOICE_PRESENCE_OUT_MS: Int = 480

    val voicePresenceIn: TweenSpec<Float> =
        tween(
            durationMillis = VOICE_PRESENCE_IN_MS,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        )

    val voicePresenceOut: TweenSpec<Float> =
        tween(
            durationMillis = VOICE_PRESENCE_OUT_MS,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        )

    /** The launch sequence's one clock. Linear because the sequence's own
     *  phases carry the easing; curving the clock would curve them twice. */
    const val LAUNCH_DURATION_MS: Int = 1750

    val launchClock: TweenSpec<Float> =
        tween(
            durationMillis = LAUNCH_DURATION_MS,
            easing = androidx.compose.animation.core.LinearEasing,
        )

    // ── Μ7 — mount mode's press-and-hold gate ─────────────────────────
    //
    // ux-map-design.md §4.3's blackout/exit grammar, first built for
    // MountExitControl: neither an arrival nor a departure, so none of the
    // Μ5 verbs fit. Linear because the ring reports elapsed hold time, not
    // a feeling — an eased curve would make the ring lie about how much
    // longer the finger has to wait.

    const val HOLD_CONFIRM_DURATION_MS: Int = 1500

    val holdConfirmLinear: TweenSpec<Float> =
        tween(
            durationMillis = HOLD_CONFIRM_DURATION_MS,
            easing = androidx.compose.animation.core.LinearEasing,
        )

    /** The ring relaxing back to zero on an early release. Default easing
     *  (unchanged from the hand-rolled `tween(150)` it replaces). */
    const val HOLD_RELEASE_DURATION_MS: Int = 150

    val holdReleaseTween: TweenSpec<Float> =
        tween(durationMillis = HOLD_RELEASE_DURATION_MS)

    /** The route-register chip's worsening flash (ux-map-design.md §5.2):
     *  the background takes the new severity's tint for one beat before
     *  settling. Default easing (unchanged from the hand-rolled `tween(600)`
     *  it replaces) — [fadeQuart] is also 600ms but on the quart curve,
     *  which was not part of this flash's original spec. */
    const val REGISTER_FLASH_DURATION_MS: Int = 600

    val registerFlashTween: TweenSpec<Float> =
        tween(durationMillis = REGISTER_FLASH_DURATION_MS)

    const val FADE_THROUGH_OUT_MS: Int = 90
    const val FADE_THROUGH_IN_MS: Int = 210
    const val FADE_THROUGH_IN_DELAY_MS: Int = 90

    val fadeThroughIn: TweenSpec<Float> =
        tween(
            durationMillis = FADE_THROUGH_IN_MS,
            delayMillis = FADE_THROUGH_IN_DELAY_MS,
            easing = LinearOutSlowInEasing,
        )

    val fadeThroughOut: TweenSpec<Float> =
        tween(durationMillis = FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing)
}
