package local.phantom.companion.core.design.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * LUX-3 — the app's haptic grammar. Verbs instead of ad-hoc
 * `performHapticFeedback` calls, so touch feedback is as consistent as
 * the type scale:
 *
 *  - [confirm] — a commitment: message sent, key saved
 *  - [press] / [release] — PTT bracket: finger down / finger up
 *  - [tick] — light selection: menu opens, chip picked, toggle flipped
 *  - [arrive] — something appeared for the operator (ambient card)
 *  - [error] — a contained failure notice landed
 *
 * PROTEUS §5.2 adds the four verbs the Continuum speaks in. These are the
 * skeleton of the depth (Law 5): with the screen dark, the strata must be
 * distinguishable by the fingers alone.
 *
 *  - [stratumDetent] — the camera arrived at a stratum boundary
 *  - [petalTick] — a Constellation petal passed under the thumb
 *  - [imprintCommit] — the operator's imprint was pressed into the stream
 *  - [depthThreshold] — the pull-down to the World crossed its commit point
 *
 * All verbs go through [View.performHapticFeedback] so they respect the
 * system «touch feedback» setting — no direct `Vibrator` path, ever, or a
 * silenced phone starts buzzing in a pocket.
 *
 * The platform's waveform palette is smaller than our vocabulary. Where a
 * verb has no distinct constant below API 34 it **collapses onto its
 * nearest neighbour** rather than faking a difference; that is honest
 * degradation, not a bug. Distinctness is guaranteed on API 34+.
 *
 * Rules (§5.2): never buzz on recomposition — guard on an id, the pattern
 * in `StreamScreen.kt` is the norm; silence is the default; and at most
 * ONE haptic voice per gesture.
 */
object PhantomHaptics {

    fun confirm(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun press(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun release(view: View) {
        if (Build.VERSION.SDK_INT >= 27) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun arrive(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun error(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /* — PROTEUS §5.2 ———————————————————————————————————————————————— */

    /**
     * One dense detent as the camera lands on a stratum. The boundary
     * between the World, the Dialogue and Presence is a *felt* edge — it is
     * the only thing that tells the operator the depth changed when the
     * optics alone are ambiguous (a dark map under a dark chat).
     *
     * Fires on arrival, never during the flight.
     */
    fun stratumDetent(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /**
     * The lightest tick in the vocabulary — one per petal crossed, so a
     * Constellation can be steered blind, by counting detents under the
     * thumb. Anything heavier turns a six-petal sweep into a drill.
     *
     * Below API 34 this collapses onto [tick]'s clock tick.
     */
    fun petalTick(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * The operator's turn being pressed into the surface — deeper than a
     * [tick], lighter than a [stratumDetent]. Below API 30 the platform has
     * no distinct press and this collapses onto [confirm]'s waveform.
     */
    fun imprintCommit(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * The pull-down to the World crossing its commit threshold: the point
     * past which letting go means descending. The old exit gesture made
     * this leap invisible — 180px of faith, no signal. Now the threshold
     * announces itself under the finger, before the operator commits.
     */
    fun depthThreshold(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /* — Μ4 — the personality layer ————————————————————————————————— */

    /**
     * One beat of the working heartbeat — the softest tick the platform
     * offers, repeated by the caller on a slow loop while PHANTOM runs a
     * long tool chain (sustained ACTING). The operator *feels* him working
     * with the screen dark or the phone in a pocket; silence returns the
     * instant the work ends. Real signal only: the beat is keyed to the
     * turn phase, never to a timer pretending to be effort.
     */
    fun heartbeat(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
