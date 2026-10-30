package local.phantom.companion.feature.stream.ui.map

import android.content.Context

internal object NearbyPeek {
    const val DRAG_DP = 24f
    private const val PREFS = "map_nearby_peek"
    private const val KEY = "collapsed"

    fun afterDrag(collapsed: Boolean, dragDp: Float): Boolean = when {
        dragDp > DRAG_DP -> true
        dragDp < -DRAG_DP -> false
        else -> collapsed
    }

    fun read(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun write(context: Context, collapsed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, collapsed).apply()
    }
}
