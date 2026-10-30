package local.phantom.companion.core.design.sound

import androidx.compose.runtime.compositionLocalOf

/**
 * W1.5-4 — sound-firing abstraction. One method, one event; no
 * decisions about volume / channel / vibration leak into call sites.
 *
 * Implementations:
 *  - [NoopSoundEngine]              — default, drops everything.
 *  - [AndroidToneGeneratorSoundEngine] — production stand-in until
 *    the bundled audio library ships in Phase 3.
 *  - In tests:  `SoundEngine { fired += it }` — fun-interface SAM.
 *
 * TODO(Phase 3): replace ToneGenerator with bundled audio assets
 * (chimeBell.ogg / crystalNotice.ogg / spikeAlert.ogg) loaded via
 * SoundPool. Today every event maps to a synthesized DTMF-adjacent
 * tone — operator-recognisable but tonally generic, not the
 * gold-bell / crystal / spike timbres in DESIGN_BRIEF §V.
 *
 * GHOST mode: wrap the live engine in [gated] passing a `() -> Boolean`
 * that returns true when GHOST is active. Every fire is then a no-op
 * — see DESIGN_BRIEF §III «GHOST mode: SILENCED».
 */
fun interface SoundEngine {
    fun fire(event: PhantomSoundEvent)

    companion object {
        /** Drop-everything default. Used when no engine has been provided. */
        val Noop: SoundEngine = NoopSoundEngine

        /**
         * Wrap [inner] so every fire is short-circuited when [isGhost]
         * returns true. The wrapper is non-stateful so the GHOST flag
         * can flip during a session and the next event respects the
         * new state (no caching).
         */
        fun gated(inner: SoundEngine, isGhost: () -> Boolean): SoundEngine =
            SoundEngine { event ->
                if (!isGhost()) inner.fire(event)
            }
    }
}

private object NoopSoundEngine : SoundEngine {
    override fun fire(event: PhantomSoundEvent) {
        // Intentional no-op — keeps tests + previews silent.
    }
}

/**
 * Composition local for the SoundEngine. Composables read this to
 * fire UX cues without threading the engine through every call.
 * Default is [SoundEngine.Noop] so previews / unit tests don't
 * accidentally start emitting tones.
 */
val LocalSoundEngine = compositionLocalOf { SoundEngine.Noop }
