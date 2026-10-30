package local.phantom.companion.feature.stream.ui.map

internal enum class BasemapGap { NONE, AREA, MAP }

internal fun basemapGap(groundNotSavedHere: Boolean, styleFailed: Boolean): BasemapGap = when {
    styleFailed -> BasemapGap.MAP
    groundNotSavedHere -> BasemapGap.AREA
    else -> BasemapGap.NONE
}
