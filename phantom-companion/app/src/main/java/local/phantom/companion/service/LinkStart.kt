package local.phantom.companion.service

object LinkStart {
    fun fromBoot(sdkInt: Int): Boolean = sdkInt < 35
    fun fromForeground(alive: Boolean): Boolean = !alive
}
