package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.atlas.CameraService

internal object PlaceFocus {
    const val TOP_DP = 128f
    const val EDGE_DP = 24f
    const val SHEET_TOP_SHARE = 0.5f
    const val MIN_ZOOM = 13.0

    fun flyMs(reduced: Boolean): Int = if (reduced) 0 else CameraService.FOCUS_MS

    fun shouldFly(x: Float, y: Float, width: Float, height: Float, zoom: Double, density: Float): Boolean {
        val edge = EDGE_DP * density
        return zoom < MIN_ZOOM ||
            x < edge || x > width - edge ||
            y < TOP_DP * density || y > height * SHEET_TOP_SHARE
    }
}

internal fun streetOf(address: String?, title: String?): String? {
    val parts = address?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != title } ?: return null
    val first = parts.firstOrNull() ?: return null
    return if (first.first().isDigit() && parts.size > 1) "${parts[1]} $first" else first
}
