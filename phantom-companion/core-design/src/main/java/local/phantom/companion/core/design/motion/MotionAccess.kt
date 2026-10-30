package local.phantom.companion.core.design.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Μ5 — the accessibility gate. Every verb in [PhantomMotion] passes through
 * here before it reaches a composable.
 *
 * Android already carries the answer: **Settings → Developer options →
 * animator duration scale**, which accessibility services and the «remove
 * animations» toggle drive to 0. An operator who has asked the phone to stop
 * moving has asked THIS app to stop moving; a motion system that ignores it
 * is not a system, it is a preference of ours imposed on him.
 *
 * At scale 0 every verb collapses to `snap()` / `EnterTransition.None` — the
 * information still arrives, instantly. Nothing is ever *hidden* because
 * motion is off, and no code path waits on an animation that will not run.
 *
 * The maths lives here as pure functions so it can be tested on the JVM
 * without a device (`MotionSystemTest`).
 */
object MotionAccess {

    /** At or below this the operator has asked for no motion. */
    const val REDUCED_CEILING: Float = 0.01f

    /** The platform's dial tops out at 10×; past 4× a transition is a hang.
     *  We honour «slower», we do not honour «forever». */
    const val MAX_SCALE: Float = 4f

    /** A hostile or missing value reads as «normal», never as «frozen». */
    fun sane(raw: Float): Float =
        if (!raw.isFinite() || raw < 0f) 1f else raw.coerceAtMost(MAX_SCALE)

    fun reduced(scale: Float): Boolean = sane(scale) <= REDUCED_CEILING

    /** [baseMs] under the operator's clock; 0 means «land now». */
    fun durationFor(baseMs: Int, scale: Float): Int {
        if (baseMs <= 0) return 0
        val s = sane(scale)
        if (s <= REDUCED_CEILING) return 0
        return (baseMs * s).roundToInt().coerceAtLeast(1)
    }

    /**
     * Delay before row [index] of a staggered list lands. Row 0 never waits —
     * the first thing the operator looks at is never the thing that is late.
     */
    fun staggerDelayMs(index: Int, scale: Float): Int {
        if (index <= 0) return 0
        val raw = (index * MotionTokens.STAGGER_STEP_MS).coerceAtMost(MotionTokens.STAGGER_CAP_MS)
        return durationFor(raw, scale)
    }
}

/**
 * The operator's animator-duration scale, 1f when nothing is provided (unit
 * tests, previews). [PhantomTheme][local.phantom.companion.core.design.theme.PhantomTheme]
 * fills it from the system setting.
 */
val LocalMotionScale = compositionLocalOf { 1f }

/**
 * Reads the system animator scale and keeps watching it — the setting can be
 * flipped from the notification shade by an accessibility shortcut while the
 * app is in the foreground, and the next transition must already know.
 */
@Composable
fun rememberSystemMotionScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readAnimatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(resolver)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return scale
}

private fun readAnimatorScale(resolver: ContentResolver): Float = MotionAccess.sane(
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f),
)
