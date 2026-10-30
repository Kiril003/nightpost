package local.phantom.companion.core.embodiment

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tier 14-A — wraps the platform Vibrator. The implementation is
 * deliberately defensive: on devices that report no amplitude
 * control (older budget Androids) the waveform falls back to plain
 * on/off vibration so a low-intensity tick does not turn into a
 * full-power buzz.
 */
class AndroidHapticEmitter(context: Context) : HapticEmitter {

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun emit(pattern: HapticPattern) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(pattern.timings, -1)
        }
        v.vibrate(effect)
    }

    override fun cancel() {
        vibrator?.cancel()
    }
}
