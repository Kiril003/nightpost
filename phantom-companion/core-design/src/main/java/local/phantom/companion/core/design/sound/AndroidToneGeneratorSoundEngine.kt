package local.phantom.companion.core.design.sound

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * W1.5-4 — production stand-in for the eventual bundled-asset
 * [SoundEngine]. Maps each [PhantomSoundEvent] to a (toneType,
 * durationMs, vibrationMs) triple tuned to approximate the brief's
 * sound character without shipping audio files. ToneGenerator is
 * stable across API 24+ and respects the operator's notification
 * volume — the brief explicitly forbids overriding that.
 *
 * Failure modes: ToneGenerator init can throw RuntimeException on
 * contended AudioFlinger sessions (Samsung One UI 7), and Vibrator
 * access can throw SecurityException on paranoid OEMs. Both paths
 * are wrapped in runCatching so a missing capability doesn't crash
 * the UX cue — silence is a degraded but acceptable fallback.
 *
 * Phase 3 will replace this with a SoundPool-backed implementation
 * that loads `chime.bell.ogg`, `crystal.notice.ogg`, etc. from the
 * APK's `res/raw/`.
 */
class AndroidToneGeneratorSoundEngine(
    private val context: Context,
    streamType: Int = AudioManager.STREAM_NOTIFICATION,
    volume: Int = 60,
) : SoundEngine {

    private val toneGen: ToneGenerator? =
        runCatching { ToneGenerator(streamType, volume) }.getOrNull()

    override fun fire(event: PhantomSoundEvent) {
        val spec = SPEC[event] ?: return
        runCatching { toneGen?.startTone(spec.toneType, spec.durationMs) }
        if (spec.vibrationMs > 0) {
            runCatching { vibrate(spec.vibrationMs.toLong()) }
        }
    }

    /** Caller releases when the engine is being torn down. */
    fun close() {
        runCatching { toneGen?.release() }
    }

    private fun vibrate(durationMs: Long) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        }
    }

    /** Public for the regression test that asserts the catalog is complete. */
    data class Spec(val toneType: Int, val durationMs: Int, val vibrationMs: Int)

    companion object {
        /**
         * Per-event tone + vibration mapping. Tones picked to match the
         * brief's character — soft for ChimeBell/RippleTap, urgent for
         * SpikeAlert. Vibration is paired only where the brief asks for
         * tactile feedback (Timer finish, error). RippleTap stays silent
         * on the haptic axis so it can chain rapidly without buzzing.
         */
        val SPEC: Map<PhantomSoundEvent, Spec> = mapOf(
            PhantomSoundEvent.ChimeBell    to Spec(ToneGenerator.TONE_PROP_BEEP, 220, 220),
            PhantomSoundEvent.CrystalNotice to Spec(ToneGenerator.TONE_PROP_PROMPT, 180, 0),
            PhantomSoundEvent.SpikeAlert   to Spec(ToneGenerator.TONE_PROP_BEEP2, 320, 180),
            PhantomSoundEvent.PairSuccess  to Spec(ToneGenerator.TONE_PROP_ACK, 280, 0),
            PhantomSoundEvent.RippleTap    to Spec(ToneGenerator.TONE_PROP_BEEP, 80, 0),
        )
    }
}
