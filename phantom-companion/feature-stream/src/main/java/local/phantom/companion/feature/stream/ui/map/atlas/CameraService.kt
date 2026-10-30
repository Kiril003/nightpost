package local.phantom.companion.feature.stream.ui.map.atlas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ATLAS D4 — the one camera owner.
 *
 * [frame] is called once per follow-loop frame and performs **at most one**
 * write to [MapPort]. Everything else that wanted the camera this frame is
 * told why it did not get it, so «two cameras arguing over one frame» becomes
 * a value a test can assert on instead of a jitter the operator reports.
 *
 * Three rules carry most of the weight:
 *  - the operator's hand preempts everything, and keeps preempting for
 *    [GESTURE_FRESH_MS] after the fingers lift (a missed gesture-end callback
 *    would wedge a flag forever; a timestamp heals itself);
 *  - a zoom nudge during a follow is a *bias on the envelope*, never a
 *    competing animation — the button that was dead in a drive;
 *  - a one-shot in flight is not interrupted by anything of lower priority.
 */
internal class CameraService(
    private val port: MapPort,
    private val now: () -> Long,
) {

    var followArmed: Boolean = false
        private set

    /** Last intent dropped for age. Telemetry, not control flow. */
    var lastExpired: CameraIntent? = null
        private set

    private var pending: CameraIntent? = null
    private var pendingZoomLevels = 0.0
    private var gestureAtMs = Long.MIN_VALUE / 2
    private var flightEndsAtMs = 0L
    private var flightPriority: CameraPriority? = null
    private var lastWritten: CameraPose? = null

    /**
     * Nudges accumulate rather than queue: a double tap is one glide of two
     * levels, not two fights. Everything else keeps a single slot — a newer
     * intent of equal or higher priority replaces the older one, because the
     * operator's latest word is the operative one.
     */
    fun submit(intent: CameraIntent) {
        if (intent is CameraIntent.ZoomNudge) {
            pendingZoomLevels += intent.levels
            return
        }
        val current = pending
        if (current == null || intent.priority.ordinal <= current.priority.ordinal) {
            pending = intent
        }
    }

    /**
     * Is somebody waiting for a frame? The applier paces itself down to ~6 fps
     * over a still map, so it asks this to stay awake while an intent is queued
     * — otherwise a tap on a search result would sit in the slot for a whole
     * idle tick before the map moved.
     */
    val hasPending: Boolean get() = pending != null || pendingZoomLevels != 0.0

    /** A pinch, rotate or shove. Not a pan — a pan submits [CameraIntent.FollowDisarm]. */
    fun onGesture() {
        gestureAtMs = now()
    }

    fun gestureFresh(atMs: Long = now()): Boolean = atMs - gestureAtMs < GESTURE_FRESH_MS

    /**
     * @param followPose the pose the follow physics wants this frame, or null
     * when no follow producer is running. Handing it in (rather than letting
     * the loop write it) is what makes «exactly one consumer per frame»
     * enforceable.
     */
    fun frame(followPose: CameraPose? = null): CameraOutcome {
        val t = now()
        val gesturing = gestureFresh(t)
        expire(t)

        applyModeIntent(t, gesturing)

        val oneShot = takeOneShot(t, gesturing)
        if (oneShot != null) return apply(t, oneShot)

        val nudge = pendingZoomLevels
        if (nudge != 0.0) {
            pendingZoomLevels = 0.0
            // Following and the fingers are off: the envelope absorbs it and
            // the step's own easing carries it. Otherwise nobody is writing
            // the camera, so MapLibre's animation is the right answer.
            return if (followArmed && !gesturing) {
                CameraOutcome.FollowBias(nudge)
            } else {
                port.zoomBy(nudge, ZOOM_NUDGE_MS)
                CameraOutcome.Applied(CameraIntent.ZoomNudge(nudge, issuedAtMs = t))
            }
        }

        if (!followArmed) return CameraOutcome.Suppressed(SuppressReason.FOLLOW_DISARMED)
        if (gesturing) return CameraOutcome.Suppressed(SuppressReason.GESTURE_FRESH)
        if (t < flightEndsAtMs) return CameraOutcome.Suppressed(SuppressReason.ONESHOT_IN_FLIGHT)
        val pose = followPose ?: return CameraOutcome.Idle
        if (withinDeadband(lastWritten, pose)) return CameraOutcome.Suppressed(SuppressReason.DEADBAND)
        port.move(pose)
        lastWritten = pose
        return CameraOutcome.FollowWrote(pose)
    }

    /** Re-arming must not inherit an old deadband anchor. */
    fun reset() {
        pending = null
        pendingZoomLevels = 0.0
        flightEndsAtMs = 0L
        flightPriority = null
        lastWritten = null
    }

    private fun expire(t: Long) {
        val p = pending ?: return
        if (t - p.issuedAtMs > p.staleAfterMs) {
            lastExpired = p
            pending = null
        }
    }

    /**
     * Arming and disarming change who may write; they are not writes, so they
     * do not consume the frame. Arming a follow and writing its first pose in
     * the same frame is still one write.
     */
    private fun applyModeIntent(t: Long, gesturing: Boolean) {
        val p = pending
        if (p !is CameraIntent.FollowArm && p !is CameraIntent.FollowDisarm) return
        if (gesturing && p.priority != CameraPriority.OPERATOR) return
        pending = null
        setFollow(armed = p is CameraIntent.FollowArm, t = t)
    }

    private fun takeOneShot(t: Long, gesturing: Boolean): CameraIntent? {
        val p = pending ?: return null
        if (gesturing && p.priority != CameraPriority.OPERATOR) return null
        // Ambient rules never argue with the drive.
        if (followArmed && p.priority == CameraPriority.AMBIENT) return null
        val flying = t < flightEndsAtMs
        val incumbent = flightPriority
        if (flying && incumbent != null && p.priority.ordinal > incumbent.ordinal) return null
        pending = null
        return p
    }

    private fun apply(t: Long, intent: CameraIntent): CameraOutcome {
        if (intent.disarmsFollow) setFollow(armed = false, t = t)
        // A one-shot moved the camera, so the deadband's anchor is now a pose
        // the map is no longer at — keeping it would let the first follow write
        // after the animation be skipped as «unchanged».
        if (intent.durationMs > 0) lastWritten = null
        when (intent) {
            is CameraIntent.Focus -> port.flyTo(intent.target, intent.zoom, intent.durationMs)
            is CameraIntent.FitBounds -> port.fit(intent.bounds, intent.padding, intent.durationMs)
            is CameraIntent.Flyover -> port.flyover(intent.path, intent.durationMs)
            is CameraIntent.BearingReset -> port.bearingTo(0.0, intent.durationMs)
            is CameraIntent.IdlePitch -> port.tiltTo(intent.tiltDeg, intent.durationMs)
            is CameraIntent.ZoomNudge,
            is CameraIntent.FollowArm,
            is CameraIntent.FollowDisarm,
            -> Unit
        }
        if (intent.durationMs > 0) {
            flightEndsAtMs = t + intent.durationMs
            flightPriority = intent.priority
        }
        return CameraOutcome.Applied(intent)
    }

    private fun setFollow(armed: Boolean, t: Long) {
        if (followArmed == armed) return
        followArmed = armed
        lastWritten = null
        if (!armed) {
            flightEndsAtMs = minOf(flightEndsAtMs, t)
        }
    }

    /**
     * At a standstill the pose is constant — rewriting the identical camera
     * 60×/s is how a parked map cost 42 °C of skin.
     */
    private fun withinDeadband(previous: CameraPose?, next: CameraPose): Boolean {
        val p = previous ?: return false
        val bearingDelta = abs(((next.bearingDeg - p.bearingDeg + 540.0) % 360.0) - 180.0)
        return metresBetween(p.lat, p.lon, next.lat, next.lon) < DEADBAND_M &&
            abs(next.zoom - p.zoom) < DEADBAND_ZOOM &&
            abs(next.tiltDeg - p.tiltDeg) < DEADBAND_TILT_DEG &&
            bearingDelta < DEADBAND_BEARING_DEG
    }

    companion object {
        /**
         * The freshness window every camera authority yields inside. It used
         * to be a second copy on `MapUiState`; 0b deleted that one — freshness
         * is a question for the owner of the camera, not a state holder.
         */
        const val GESTURE_FRESH_MS = 400L

        const val INTENT_STALE_MS = 2_000L
        const val FOCUS_MS = 700
        const val FIT_MS = 700
        const val BEARING_MS = 300
        const val PITCH_MS = 650
        const val ZOOM_NUDGE_MS = 250

        const val DEADBAND_M = 0.4
        const val DEADBAND_ZOOM = 0.01
        const val DEADBAND_TILT_DEG = 0.15
        const val DEADBAND_BEARING_DEG = 0.15

        private const val EARTH_R_M = 6_371_000.0

        internal fun metresBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val dLat = Math.toRadians(bLat - aLat)
            val dLon = Math.toRadians(bLon - aLon)
            val la1 = Math.toRadians(aLat)
            val la2 = Math.toRadians(bLat)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_R_M * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
