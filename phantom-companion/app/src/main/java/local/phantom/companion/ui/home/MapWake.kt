package local.phantom.companion.ui.home

/** Map ViewModel + [MapSession] are born on the first Map visit, or when a drive or a geofence needs them off-screen; then they stay. */
internal object MapWake {
    fun wanted(woken: Boolean, tab: HomeTab, guiding: Boolean, fences: Int): Boolean =
        woken || tab == HomeTab.MAP || guiding || fences > 0
}
