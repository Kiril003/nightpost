package local.phantom.companion.data

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.net.peer.PeerWhere

/** Стан для кадру «де я»: власні дім і робота тримаються в памʼяті, батарея — липкий намір без приймача. */
internal fun AppContainer.peerWhereDoing(): (LocationFix, Boolean) -> PeerWhere.State {
    val anchors = MutableStateFlow<List<PeerWhereDoing.Anchor>>(emptyList())
    scope.launch {
        savedPlaceStore.liveFlow().collect { rows ->
            anchors.value = rows.mapNotNull { r ->
                when (SavedPlaceCategory.canonical(r.category)) {
                    SavedPlaceCategory.HOME -> PeerWhereDoing.Anchor(r.lat, r.lon, PeerWhere.Spot.HOME)
                    SavedPlaceCategory.WORK -> PeerWhereDoing.Anchor(r.lat, r.lon, PeerWhere.Spot.WORK)
                    else -> null
                }
            }
        }
    }
    return { fix, shareLow ->
        val battery = if (!shareLow) null else runCatching {
            val i: Intent? = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level < 0 || scale <= 0) null else PeerWhereDoing.Battery(
                level * 100 / scale,
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }.getOrNull()
        PeerWhereDoing.of(fix, anchors.value, battery, shareLow)
    }
}

internal fun AppContainer.peerFace(peerId: String): String? =
    if (peerPhotos.has(peerId)) java.io.File(applicationContext.filesDir, "peer_photos/" + PeerPhotoStore.nameOf(peerId)).path else null
