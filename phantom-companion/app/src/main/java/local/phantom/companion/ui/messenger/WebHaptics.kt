package local.phantom.companion.ui.messenger

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference
import java.util.Locale

internal object WebHaptics {
    @Volatile
    private var ref: WeakReference<View>? = null

    fun attach(view: View) {
        ref = WeakReference(view)
    }

    fun constantFor(kind: String, sdk: Int = Build.VERSION.SDK_INT): Int? =
        when (kind.lowercase(Locale.ROOT)) {
            "threshold" -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
            "release" -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            "tick", "tap" -> HapticFeedbackConstants.CLOCK_TICK
            "long" -> HapticFeedbackConstants.LONG_PRESS
            "confirm" -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "reject" -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> null
        }

    fun perform(kind: String): Boolean {
        val c = constantFor(kind) ?: return false
        val view = ref?.get() ?: return false
        Handler(Looper.getMainLooper()).post { view.performHapticFeedback(c) }
        return true
    }
}
