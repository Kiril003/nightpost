package local.phantom.companion.core.design.sound

/**
 * W1.5-4 — canonical sound catalog from DESIGN_BRIEF §V / §XV.
 *
 * Each entry maps to a named slot in the brief; production builds
 * eventually back these with a custom-recorded sample library
 * (`chime.bell.wav`, `crystal.notice.wav`, `spike.alert.wav`).
 * Until the assets ship, the [AndroidToneGeneratorSoundEngine] uses
 * ToneGenerator constants tuned to approximate each event's
 * character — a soft beep for ChimeBell, a brighter notification
 * tone for CrystalNotice, a sharper alert for SpikeAlert.
 *
 * GHOST mode: the brief mandates total silence. The
 * [SoundEngine.gated] wrapper enforces this — every event is
 * dropped on the floor when the GHOST predicate is true. There is
 * no per-event override; «silence > sound» is non-negotiable.
 */
enum class PhantomSoundEvent {
    /** Timer finish, AI completion of a long task. */
    ChimeBell,

    /** Insight card appears, proactive nudge, FOCUS state acknowledgement. */
    CrystalNotice,

    /** Error / Critical surface, lock-vault clamp, sensitive-app whisper auto-hide. */
    SpikeAlert,

    /** Pair-success ceremony — distinct one-shot bell. */
    PairSuccess,

    /** Action confirmation — light tap, shorter than ChimeBell. */
    RippleTap,
}
