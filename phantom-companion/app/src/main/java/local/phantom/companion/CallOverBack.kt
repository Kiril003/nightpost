package local.phantom.companion

internal class CallOverBack(
    private val leave: () -> Boolean,
    private val passThrough: () -> Unit,
) : androidx.activity.OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
        if (leave()) return
        isEnabled = false
        passThrough()
        isEnabled = true
    }
}
