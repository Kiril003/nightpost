package local.phantom.companion.core.design.component

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/** Видима частина вікна діалогу в px вікна, без системних смуг і клавіатури; [visibleHeight] — уся видима висота екрана. */
@Immutable
data class DialogRoom(val left: Int, val top: Int, val width: Int, val height: Int, val visibleHeight: Int)

/**
 * Compose 1.7 міряє Dialog на всю ширину за `Configuration.screenHeightDp`, а з
 * Android 15 (targetSdk 35+) це вся висота разом зі смугами. Плаваюче вікно ж
 * система й далі тримає між смугами, тож низ вмісту опиняється поза екраном.
 * Справжню межу дає лише видима рамка вікна.
 */
@Composable
fun rememberDialogRoom(): DialogRoom? {
    val root = LocalView.current.rootView
    var room by remember(root) { mutableStateOf(roomOf(root)) }
    DisposableEffect(root) {
        val l = ViewTreeObserver.OnGlobalLayoutListener { roomOf(root)?.let { if (it != room) room = it } }
        root.viewTreeObserver.addOnGlobalLayoutListener(l)
        onDispose { root.viewTreeObserver.removeOnGlobalLayoutListener(l) }
    }
    return room
}

/** `fillMaxSize` для вмісту Dialog: рівно видима частина вікна; смуги й клавіатуру вже враховано. */
fun Modifier.fillDialogRoom(): Modifier = composed {
    val room = rememberDialogRoom()
    val density = LocalDensity.current
    if (room == null) {
        Modifier.fillMaxSize()
    } else {
        with(density) {
            Modifier
                .absolutePadding(left = room.left.toDp(), top = room.top.toDp())
                .size(room.width.toDp(), room.height.toDp())
        }
    }
}

private fun roomOf(root: View): DialogRoom? {
    if (!root.isAttachedToWindow) return null
    val vis = Rect().also(root::getWindowVisibleDisplayFrame)
    val at = IntArray(2).also(root::getLocationOnScreen)
    val left = maxOf(vis.left - at[0], 0)
    val top = maxOf(vis.top - at[1], 0)
    val w = vis.right - at[0] - left
    val h = vis.bottom - at[1] - top
    return if (w > 0 && h > 0) DialogRoom(left, top, w, h, vis.height()) else null
}
