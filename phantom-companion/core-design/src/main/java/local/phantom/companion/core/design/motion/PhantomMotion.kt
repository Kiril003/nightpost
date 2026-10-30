package local.phantom.companion.core.design.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color

/**
 * Μ0 — the Metamorphosis motion grammar (`docs/METAMORPHOSIS_MASTERPLAN.md`).
 *
 * [MotionTokens] is the mint — raw stiffness/duration/easing values live
 * there and nowhere else. This object is the *vocabulary*: named,
 * ready-made transitions that navigation and chrome compose from, so a
 * route never improvises its own `tween(600)` again. `MotionDriftFirewallTest`
 * enforces exactly that.
 *
 * Two verbs exist, and both are morphs — the «morph, never slide» law
 * (DESIGN_BRIEF §VI) holds at the nav graph the same as everywhere else:
 *
 *   • chamber dolly — the shared-axis-Z depth step used by every chamber
 *     route (and by the Continuum receding beneath them). The incoming
 *     surface rises from depth (0.96 → 1), the outgoing surface passes
 *     the camera (1 → 1.04); popping runs the same dolly backwards, so
 *     Back always *returns*, never re-opens. Scale rides the same quart
 *     curve as the fade — one clock, they land together; a spring here
 *     would let the scale settle after the fade and smear the arrival.
 *
 *   • bloom — the Constellation's radial materialisation: it condenses
 *     out of the space *around* the operator (1.3 → 1) and evaporates
 *     back into it. Formalised from the values ConstellationDestination
 *     shipped with; only the easing changed (raw linear-ish tween(600) →
 *     the canonical quart).
 *
 * The camera rack ([rackSpring]) is the continuum's stratum dolly. A note
 * on velocity, so nobody «improves» this later: the descent gesture
 * commits mid-drag at the 180px crossing (no fling velocity exists at
 * that moment), and `Animatable.animateTo` already inherits the in-flight
 * velocity when one rack interrupts another. The physics is correct as
 * is; a VelocityTracker bolted onto the nested-scroll seam would be
 * plumbing without a signal (see W-12 double-exposure scar before
 * touching that gesture).
 */
object PhantomMotion {

    /** Where an incoming chamber starts its rise from depth. */
    const val CHAMBER_FAR_SCALE: Float = 0.96f

    /** Where an outgoing surface ends, passing the camera. */
    const val CHAMBER_NEAR_SCALE: Float = 1.04f

    /** The Constellation's bloom origin — it condenses from around the operator. */
    const val BLOOM_SCALE: Float = 1.3f

    // ── The chamber dolly ─────────────────────────────────────────────

    /** Forward navigation, incoming surface: rises from depth. */
    val chamberEnter: EnterTransition =
        fadeIn(MotionTokens.fadeQuart) +
            scaleIn(MotionTokens.fadeQuart, initialScale = CHAMBER_FAR_SCALE)

    /** Forward navigation, outgoing surface: recedes past the camera. */
    val chamberExit: ExitTransition =
        fadeOut(MotionTokens.fadeQuart) +
            scaleOut(MotionTokens.fadeQuart, targetScale = CHAMBER_NEAR_SCALE)

    /** Back navigation, returning surface: settles back from past-camera. */
    val chamberReturnEnter: EnterTransition =
        fadeIn(MotionTokens.fadeQuart) +
            scaleIn(MotionTokens.fadeQuart, initialScale = CHAMBER_NEAR_SCALE)

    /** Back navigation, dismissed chamber: sinks back into depth. */
    val chamberReturnExit: ExitTransition =
        fadeOut(MotionTokens.fadeQuart) +
            scaleOut(MotionTokens.fadeQuart, targetScale = CHAMBER_FAR_SCALE)

    // ── The bloom ─────────────────────────────────────────────────────

    /** The Constellation condenses out of the operator's space. */
    val bloomEnter: EnterTransition =
        fadeIn(MotionTokens.fadeQuart) +
            scaleIn(MotionTokens.fadeQuart, initialScale = BLOOM_SCALE)

    /** …and evaporates back into it. */
    val bloomExit: ExitTransition =
        fadeOut(MotionTokens.fadeQuart) +
            scaleOut(MotionTokens.fadeQuart, targetScale = BLOOM_SCALE)

    // ── The camera rack ───────────────────────────────────────────────

    /**
     * The stratum dolly's spring — the one sanctioned way to move the
     * continuum camera between strata. Same physics as the canonical
     * snap; the name exists so call-sites say what they mean and the
     * firewall has something to point at.
     */
    val rackSpring: SpringSpec<Float> = MotionTokens.snapSpringFloat

    // ── Μ5 — the working verbs ────────────────────────────────────────
    //
    // The dolly and the bloom move whole ROUTES. Everything below moves
    // the things inside a route — the chrome the operator touches every
    // session. Seven verbs, no more:
    //
    //   enter / exit        something arrives, something leaves
    //   expand / dismiss    something opens in place, something folds away
    //   swap                one face replaces another in the same slot
    //   emphasis            the one element that must catch the eye
    //   stateChange         a tint, a selection, a value's colour band
    //   gesture             anything the finger is currently driving
    //
    // Every accessor is a `@Composable get()` and reads [LocalMotionScale]:
    // at scale 0 the verb hands back an instant spec. Call sites therefore
    // cannot forget the accessibility gate — there is no ungated spelling
    // of «enter» to reach for.

    val enter: EnterTransition
        @Composable get() = if (reducedMotion()) EnterTransition.None else rawEnter

    val exit: ExitTransition
        @Composable get() = if (reducedMotion()) ExitTransition.None else rawExit

    val expand: EnterTransition
        @Composable get() = if (reducedMotion()) EnterTransition.None else rawExpand

    val dismiss: ExitTransition
        @Composable get() = if (reducedMotion()) ExitTransition.None else rawDismiss

    /** One face replaces another in the same slot: the incoming fades up
     *  while the outgoing fades away, and the size follows. */
    val swap: ContentTransform
        @Composable get() = if (reducedMotion()) instantSwap else rawSwap

    val emphasis: FiniteAnimationSpec<Float>
        @Composable get() = if (reducedMotion()) snap() else MotionTokens.emphasisSpring

    /**
     * Where an emphasis beat starts. The element is snapped here and springs
     * back to 1 — a beat the eye catches without the element ever vanishing
     * and re-arriving, which would read as new information rather than the
     * same element saying «look again».
     */
    const val EMPHASIS_OVERSHOOT: Float = 1.09f

    val stateChange: FiniteAnimationSpec<Float>
        @Composable get() = if (reducedMotion()) snap() else MotionTokens.stateQuart

    val stateChangeColor: FiniteAnimationSpec<Color>
        @Composable get() = if (reducedMotion()) snap() else MotionTokens.stateQuartColor

    /** The finger's spring. Fast, flat, interruptible — an `Animatable`
     *  driven by this always inherits the in-flight velocity, so a new
     *  touch takes the animation over instead of queuing behind it. */
    val gesture: FiniteAnimationSpec<Float>
        @Composable get() = if (reducedMotion()) snap() else MotionTokens.gestureSpring

    /** A live quantity catching up to its latest reading. */
    val meter: FiniteAnimationSpec<Float>
        @Composable get() = if (reducedMotion()) snap() else MotionTokens.meterLinear

    /** Delay before row [index] of a staggered list lands. */
    @Composable
    fun staggerDelayMs(index: Int): Int =
        MotionAccess.staggerDelayMs(index, LocalMotionScale.current)

    @Composable
    fun reducedMotion(): Boolean = MotionAccess.reduced(LocalMotionScale.current)

    /**
     * A monotone meter only ever fills. A target BELOW where the meter
     * stands is not progress running backwards — it is a new step (the next
     * maneuver) or a new route, and unwinding it in front of the operator
     * says something false about the drive. Land instantly instead.
     */
    const val METER_REWIND_EPSILON: Float = 0.02f

    fun meterRewinds(current: Float, target: Float): Boolean =
        target < current - METER_REWIND_EPSILON

    // ── the ungated forms ─────────────────────────────────────────────
    // Internal on purpose: outside this file a verb is only reachable
    // through its gated accessor.

    private val rawEnter: EnterTransition = fadeIn(MotionTokens.enterQuart)

    private val rawExit: ExitTransition = fadeOut(MotionTokens.exitQuart)

    private val rawExpand: EnterTransition =
        expandVertically(MotionTokens.expandQuartIntSize, expandFrom = Alignment.Top) +
            fadeIn(MotionTokens.enterQuart)

    private val rawDismiss: ExitTransition =
        shrinkVertically(MotionTokens.dismissQuartIntSize, shrinkTowards = Alignment.Top) +
            fadeOut(MotionTokens.exitQuart)

    private val rawSwap: ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(MotionTokens.enterQuart),
        initialContentExit = fadeOut(MotionTokens.exitQuart),
        sizeTransform = SizeTransform(clip = false) { _, _ -> MotionTokens.expandQuartIntSize },
    )

    private val instantSwap: ContentTransform = ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        sizeTransform = null,
    )
}
