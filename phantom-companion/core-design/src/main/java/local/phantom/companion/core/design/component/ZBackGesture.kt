package local.phantom.companion.core.design.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Modifier extension to enable Z-axis pinch-in gesture navigation.
 * Triggers onBack when two fingers pinch in (accumulated scale goes below 0.8f).
 */
fun Modifier.zBackGesture(onBack: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        var accumulatedZoom = 1f
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                if (event.changes.size > 1) {
                    val zoom = event.calculateZoom()
                    accumulatedZoom *= zoom
                    if (accumulatedZoom < 0.8f) {
                        event.changes.forEach { it.consume() }
                        onBack()
                        break
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
