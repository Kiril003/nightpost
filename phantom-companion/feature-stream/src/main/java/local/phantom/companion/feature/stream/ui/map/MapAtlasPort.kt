package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.suspendCancellableCoroutine
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPose
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.MapPort
import local.phantom.companion.feature.stream.ui.map.atlas.MapStyleHandle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.coroutines.resume

/**
 * ATLAS-0b — the two thin adapters that let the `atlas` package stay free of
 * MapLibre types (and therefore testable on the JVM).
 */
internal class StyleHandle(val style: Style) : MapStyleHandle

/**
 * The ONE place allowed to write this map's camera — now literally one, with
 * no second name on the allow-list.
 *
 * Everything else states an intent; [local.phantom.companion.feature.stream.ui.map.atlas.CameraService]
 * decides who gets the frame and calls exactly one method here. The ratchet
 * (`CameraWriterFirewallTest`) fails the build on a direct
 * `animateCamera`/`moveCamera`/`easeCamera` anywhere else in the map package.
 */
internal class MapLibreCameraPort(
    private val map: MapLibreMap,
) : MapPort {

    /**
     * The multi-leg route flight. Set after construction because the flight
     * itself now writes THROUGH this port ([animateAwait]) and so cannot be
     * handed in before the port exists.
     */
    var runFlyover: (List<GeoPoint>) -> Unit = {}

    override fun pose(): CameraPose? {
        val cp = map.cameraPosition
        val target = cp.target ?: return null
        return CameraPose(
            lat = target.latitude,
            lon = target.longitude,
            zoom = cp.zoom,
            tiltDeg = cp.tilt,
            bearingDeg = cp.bearing,
        )
    }

    override fun move(pose: CameraPose) {
        // Padding rides THIS write. A separate ease for it fought the follow
        // loop frame by frame, and the tilted extrusions drifted off the base
        // map while the two argued.
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(pose.lat, pose.lon))
                    .zoom(pose.zoom)
                    .tilt(pose.tiltDeg)
                    .bearing(pose.bearingDeg)
                    .build(),
            ),
        )
    }

    override fun flyTo(target: GeoPoint, zoom: Double?, durationMs: Int) {
        val update = if (zoom == null) {
            CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lon))
        } else {
            CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), zoom)
        }
        runCatching { if (durationMs <= 0) map.moveCamera(update) else map.animateCamera(update, durationMs) }
    }

    override fun fit(bounds: GeoBounds, padding: EdgeInsets, durationMs: Int) {
        runCatching {
            val b = LatLngBounds.Builder()
                .include(LatLng(bounds.southLat, bounds.westLon))
                .include(LatLng(bounds.northLat, bounds.eastLon))
                .build()
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    b,
                    padding.left,
                    padding.top,
                    padding.right,
                    padding.bottom,
                ),
                durationMs,
            )
        }
    }

    override fun zoomBy(levels: Double, durationMs: Int) {
        runCatching { map.animateCamera(CameraUpdateFactory.zoomBy(levels), durationMs) }
    }

    override fun bearingTo(degrees: Double, durationMs: Int) {
        runCatching { map.animateCamera(CameraUpdateFactory.bearingTo(degrees), durationMs) }
    }

    override fun tiltTo(degrees: Double, durationMs: Int) {
        runCatching {
            val at = map.cameraPosition
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(at).tilt(degrees).build(),
                ),
                durationMs,
            )
        }
    }

    override fun flyover(path: List<GeoPoint>, durationMs: Int) {
        runFlyover(path)
    }

    override suspend fun animateAwait(pose: CameraPose, durationMs: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(pose.lat, pose.lon))
                        .zoom(pose.zoom)
                        .tilt(pose.tiltDeg)
                        .bearing(pose.bearingDeg)
                        .build(),
                ),
                durationMs,
                object : MapLibreMap.CancelableCallback {
                    override fun onCancel() {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onFinish() {
                        if (cont.isActive) cont.resume(true)
                    }
                },
            )
        }
}
