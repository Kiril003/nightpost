package local.phantom.companion.core.nav

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NAV-Ω2 — Sentinel Geofence & Hive Sentry Engine.
 *
 * Manages sovereign, on-device geographic perimeters (safe havens, hazard zones, anchor perimeters)
 * and evaluates boundary transitions (ENTER, EXIT, DWELL) to trigger tactical audio cues,
 * notifications, and mesh hive alerts.
 */
class SentinelFenceEngine(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    enum class FenceKind {
        SAFE_HAVEN,      // Leaving this zone triggers an alert (e.g. base perimeter breach)
        HAZARD_ZONE,     // Entering this zone triggers an immediate alarm
        ANCHOR_PERIMETER // Radius around a stationary asset/camp
    }

    enum class TransitionType {
        ENTER,
        EXIT,
        DWELL
    }

    data class Geofence(
        val id: String = UUID.randomUUID().toString(),
        val label: String,
        val centerLat: Double,
        val centerLon: Double,
        val radiusM: Double,
        val kind: FenceKind,
        val notifyHive: Boolean = true,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class FenceEvent(
        val fence: Geofence,
        val transition: TransitionType,
        val distanceM: Double,
        val timestampMs: Long,
        val messageUk: String,
    )

    private val fences = mutableMapOf<String, Geofence>()
    private val insideStates = mutableMapOf<String, Boolean>()
    private val enterTimestamps = mutableMapOf<String, Long>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun addFence(fence: Geofence) {
        fences[fence.id] = fence
        _count.value = fences.size
    }

    fun removeFence(id: String): Boolean {
        insideStates.remove(id)
        enterTimestamps.remove(id)
        val removed = fences.remove(id) != null
        _count.value = fences.size
        return removed
    }

    fun clearAll() {
        fences.clear()
        insideStates.clear()
        enterTimestamps.clear()
        _count.value = 0
    }

    fun listFences(): List<Geofence> = fences.values.toList()

    /**
     * Evaluates current position against all active geofences.
     * Returns a list of newly triggered transition events.
     */
    fun evaluate(lat: Double, lon: Double): List<FenceEvent> {
        val now = clockMs()
        val events = mutableListOf<FenceEvent>()

        for (fence in fences.values) {
            val dist = haversineM(lat, lon, fence.centerLat, fence.centerLon)
            val isInside = dist <= fence.radiusM
            val wasInside = insideStates[fence.id]

            if (wasInside == null) {
                // First observation: initialize state
                insideStates[fence.id] = isInside
                if (isInside) {
                    enterTimestamps[fence.id] = now
                    if (fence.kind == FenceKind.HAZARD_ZONE) {
                        events += FenceEvent(
                            fence = fence,
                            transition = TransitionType.ENTER,
                            distanceM = dist,
                            timestampMs = now,
                            messageUk = "Увага: ви увійшли в зону небезпеки «${fence.label}».",
                        )
                    }
                }
                continue
            }

            if (!wasInside && isInside) {
                // ENTER transition
                insideStates[fence.id] = true
                enterTimestamps[fence.id] = now
                val msg = when (fence.kind) {
                    FenceKind.HAZARD_ZONE -> "Тривога: вхід у небезпечну зону «${fence.label}»!"
                    FenceKind.SAFE_HAVEN -> "Ви повернулись у безпечну зону «${fence.label}»."
                    FenceKind.ANCHOR_PERIMETER -> "Вхід у периметр «${fence.label}»."
                }
                events += FenceEvent(
                    fence = fence,
                    transition = TransitionType.ENTER,
                    distanceM = dist,
                    timestampMs = now,
                    messageUk = msg,
                )
            } else if (wasInside && !isInside) {
                // EXIT transition
                insideStates[fence.id] = false
                enterTimestamps.remove(fence.id)
                val msg = when (fence.kind) {
                    FenceKind.SAFE_HAVEN -> "Увага: вихід за межі безпечної зони «${fence.label}»!"
                    FenceKind.HAZARD_ZONE -> "Ви вийшли з небезпечної зони «${fence.label}»."
                    FenceKind.ANCHOR_PERIMETER -> "Вихід за межі периметра «${fence.label}»."
                }
                events += FenceEvent(
                    fence = fence,
                    transition = TransitionType.EXIT,
                    distanceM = dist,
                    timestampMs = now,
                    messageUk = msg,
                )
            }
        }

        return events
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return EARTH_RADIUS_M * c
        }
    }
}
