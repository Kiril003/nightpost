package local.phantom.companion.core.design.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * State accent — one slot for each cell of DESIGN_BRIEF §IV's per-state
 * matrix. Layered ON TOP of [PhantomThemeSpec]: components ask
 * `LocalStateAccent` for the highlight colour, motion scale, glass alpha,
 * breathing period, and sound trigger; the theme still owns surface /
 * ink / glass tokens. SystemState mirrors the WS `state` channel.
 *
 * W1.5-5 — fields beyond `state` / `color` / `motionScale` / `uiOpacity`
 * carry defaults so existing call sites keep compiling without churn;
 * the resolver functions ([stateAccentForSunrise]) populate them per
 * the canonical matrix.
 */
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.context.ContextFrame
import local.phantom.companion.core.design.sound.PhantomSoundEvent
import local.phantom.companion.core.design.tokens.NightColors

val LocalContextFrame = compositionLocalOf<ContextFrame?> { null }

data class StateAccent(
    val state: SystemState,
    val color: Color,
    val motionScale: Float,
    val uiOpacity: Float,
    /**
     * Multiplier on the theme's glassPanel alpha. 1.0 = normal,
     * <1 = «tighter» (SENTINEL), >1 = «softer/brighter» (DREAM /
     * DIALOGUE). Renderers must clamp the result to [0..1] so a
     * 1.2× scale on a 0.85 base doesn't overshoot.
     */
    val glassAlpha: Float = 1.0f,
    /**
     * Breathing animation period in milliseconds. `0` means «freeze»
     * (GHOST / SHADOW — no animation). Higher = slower, organic.
     */
    val animationPeriodMs: Int = 4000,
    /**
     * Sound trigger associated with this state's *acknowledgement* —
     * fired by composables when the state is entered or a notice is
     * surfaced. `null` for silent states (GHOST, SHADOW).
     */
    val soundEvent: PhantomSoundEvent? = null,
)

/**
 * The default is the matrix's own FOCUS row, not a second opinion about it.
 * It used to be a hand-written StateAccent that said FOCUS and painted
 * #B07A10 — deep bronze where the matrix says amber — and that dropped the
 * sound with it. Anything composed without a provider (previews, the
 * wallpaper and orb services, a screen mounted outside the shell) rendered a
 * state that does not exist.
 */
val LocalStateAccent = compositionLocalOf { stateAccentForSunrise(SystemState.FOCUS) }

/**
 * Per-state matrix, after the native theme bridge (03.10).
 *
 * DESIGN_BRIEF §IV painted each state its own hue — cyan dialogue, coral
 * sentinel, grey ghost, violet dream — and dimmed and re-glassed the UI per
 * state. Forty `.color` reads turned that into a product whose amber changed
 * colour with a mood nobody could name. v1.2 has ONE accent: amber marks an
 * action, never a state. So every row draws [NightColors.accent] (it is the
 * same hex in «День»), the UI is never dimmed or re-glassed by a state
 * (`uiOpacity = 1`, `glassAlpha = 1`), and nothing breathes on a period
 * (`animationPeriodMs = 0`). What a state still legitimately owns is how fast
 * things move and what it sounds like:
 *
 * | State    | Motion | Sound          |
 * | FOCUS    | 1.0×   | CrystalNotice  |
 * | DIALOGUE | 1.1×   | CrystalNotice  |
 * | SENTINEL | 1.3×   | SpikeAlert     |
 * | GHOST    | 0.8×   | (silent)       |
 * | SHADOW   | 0.6×   | (silent)       |
 * | DREAM    | 0.4×   | ChimeBell      |
 *
 * The API is unchanged so the 40 readers compile. Nothing re-tints the
 * accent on top of this any more: the emotion shift (`EmotionTone`) left
 * `PhantomAppShell` with the system-and-subtraction package.
 */
fun stateAccentForSunrise(state: SystemState): StateAccent = when (state) {
    SystemState.FOCUS -> flat(state, motionScale = 1.0f, sound = PhantomSoundEvent.CrystalNotice)
    SystemState.DIALOGUE -> flat(state, motionScale = 1.1f, sound = PhantomSoundEvent.CrystalNotice)
    SystemState.SENTINEL -> flat(state, motionScale = 1.3f, sound = PhantomSoundEvent.SpikeAlert)
    SystemState.GHOST -> flat(state, motionScale = 0.8f, sound = null)
    SystemState.SHADOW -> flat(state, motionScale = 0.6f, sound = null)
    SystemState.DREAM -> flat(state, motionScale = 0.4f, sound = PhantomSoundEvent.ChimeBell)
}

private fun flat(state: SystemState, motionScale: Float, sound: PhantomSoundEvent?) = StateAccent(
    state = state,
    color = NightColors.accent,
    motionScale = motionScale,
    uiOpacity = 1.0f,
    glassAlpha = 1.0f,
    animationPeriodMs = 0,
    soundEvent = sound,
)
