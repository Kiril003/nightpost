package local.phantom.companion.feature.ambient.service

import android.view.Gravity
import android.view.WindowManager

/**
 * Phase 3-E1 — pure layout/flag helpers for the [WhisperLineService]
 * overlay. Extracted so the WindowManager.LayoutParams shape is
 * pinned in unit tests instead of requiring an Android runtime.
 *
 * Per docs/PRESENCE_AND_CHAT.md §I.2 the ribbon is:
 *  - 1.5dp tall (the prototype's `2px` translates to 1-2dp depending
 *    on density; we anchor on 1.5dp so the Compose stripe matches the
 *    ribbon spec exactly)
 *  - full width
 *  - top-anchored
 *  - never focusable, never touchable, ignores keyboard insets, can
 *    extend past system-bar limits so the strip lives flush at the
 *    very top edge of the screen on devices with no notch
 *
 * FLAG_LAYOUT_INSET_DECOR is intentional — it lets the system reserve
 * space for the strip so it doesn't get covered by status-bar art.
 * (FLAG_LAYOUT_NO_LIMITS does the opposite for size-clamping; both
 * flags can co-exist on TYPE_APPLICATION_OVERLAY.)
 */
object WhisperLineLayoutSpec {
    /** Height of the ribbon in dp. */
    const val STRIPE_HEIGHT_DP: Float = 1.5f

    /** Combined flag bitmask for the overlay window. */
    const val OVERLAY_FLAGS: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

    /** Top-anchored, full-width gravity. */
    const val OVERLAY_GRAVITY: Int = Gravity.TOP or Gravity.START

    /** Pixel height for a given device density. */
    fun stripeHeightPx(density: Float): Int =
        (STRIPE_HEIGHT_DP * density).toInt().coerceAtLeast(1)

    /**
     * Build the canonical LayoutParams for the ribbon overlay. Width
     * is MATCH_PARENT; height is computed from device density.
     */
    fun layoutParams(density: Float): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripeHeightPx(density),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVERLAY_FLAGS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = OVERLAY_GRAVITY
            x = 0
            y = 0
        }
}
