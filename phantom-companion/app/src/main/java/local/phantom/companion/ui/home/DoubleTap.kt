package local.phantom.companion.ui.home

internal class DoubleTap(private val windowMs: Long = 350L) {
    private var last: Long? = null

    fun tap(nowMs: Long): Boolean {
        val prev = last
        val hit = prev != null && nowMs - prev in 0..windowMs
        last = if (hit) null else nowMs
        return hit
    }
}
