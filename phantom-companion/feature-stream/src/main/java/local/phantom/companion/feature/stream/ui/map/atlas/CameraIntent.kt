package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D4 — every camera writer becomes an intent.
 *
 * There are ~15 places today that write the camera directly, around one real
 * owner: the per-frame follow loop driving `CameraDirector`. They do not know
 * about each other, so they take turns trampling: the zoom buttons were dead
 * in the one mode a driver uses them, the idle auto-pitch fought the follow
 * loop for tilt, a sheet's fit fought the drive it was fitting.
 *
 * An intent says what the caller *wants*. [CameraService] decides who gets the
 * frame. The physics stays in `CameraDirector` — it is good, and it is not
 * what was broken.
 */
internal sealed interface CameraIntent {
    val priority: CameraPriority

    /** Monotonic clock at issue. Compared only against [staleAfterMs]. */
    val issuedAtMs: Long

    /**
     * A replayed request from an hour ago is not an instruction. Today only
     * the AI focus path checks this; here every intent has to say how long it
     * is still true for.
     */
    val staleAfterMs: Long

    /** How long the map is busy with it. 0 = a mode change, not a write. */
    val durationMs: Int

    /** Browsing ends a follow. A nudge does not — that is the whole point. */
    val disarmsFollow: Boolean

    /** Operator taps, search results, zoom buttons, compass reset. */
    data class Focus(
        val target: GeoPoint,
        val zoom: Double? = null,
        override val priority: CameraPriority = CameraPriority.OPERATOR,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
        override val durationMs: Int = CameraService.FOCUS_MS,
        override val disarmsFollow: Boolean = true,
    ) : CameraIntent

    data class FitBounds(
        val bounds: GeoBounds,
        val padding: EdgeInsets,
        override val priority: CameraPriority = CameraPriority.NAVIGATION,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
        override val durationMs: Int = CameraService.FIT_MS,
        override val disarmsFollow: Boolean = true,
    ) : CameraIntent

    /**
     * A zoom step from a control that is not the map. Never queued and never a
     * write of its own while following — the service folds it into the
     * follow envelope as a zoom bias, which is what `CameraDirector` already
     * knows how to absorb.
     */
    data class ZoomNudge(
        val levels: Double,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
    ) : CameraIntent {
        override val priority = CameraPriority.OPERATOR
        override val durationMs = 0
        override val disarmsFollow = false
    }

    /**
     * The locate tap is [CameraPriority.OPERATOR]; guidance arming itself at
     * the start of a drive is [CameraPriority.NAVIGATION] and therefore yields
     * to a live gesture — which is why the priority is a parameter here.
     */
    data class FollowArm(
        override val priority: CameraPriority = CameraPriority.OPERATOR,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
    ) : CameraIntent {
        override val durationMs = 0
        override val disarmsFollow = false
    }

    /** A pan. Browsing ends the follow, whoever started it. */
    data class FollowDisarm(
        override val priority: CameraPriority = CameraPriority.OPERATOR,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
    ) : CameraIntent {
        override val durationMs = 0
        override val disarmsFollow = true
    }

    /** The route flight. [durationMs] is the sum of its legs. */
    data class Flyover(
        val path: List<GeoPoint>,
        override val durationMs: Int,
        override val priority: CameraPriority = CameraPriority.NAVIGATION,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
        override val disarmsFollow: Boolean = true,
    ) : CameraIntent

    data class BearingReset(
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
        override val durationMs: Int = CameraService.BEARING_MS,
    ) : CameraIntent {
        override val priority = CameraPriority.OPERATOR
        override val disarmsFollow = true
    }

    /** The zoom→tilt rule, re-asked after a gesture settles. Never argues. */
    data class IdlePitch(
        val tiltDeg: Double,
        override val issuedAtMs: Long = 0L,
        override val staleAfterMs: Long = CameraService.INTENT_STALE_MS,
        override val durationMs: Int = CameraService.PITCH_MS,
    ) : CameraIntent {
        override val priority = CameraPriority.AMBIENT
        override val disarmsFollow = false
    }
}

/**
 * Lower ordinal wins. The operator's own hand outranks the drive; the drive
 * outranks the model; ambient rules never argue with anything.
 */
internal enum class CameraPriority { OPERATOR, NAVIGATION, ASSISTANT, AMBIENT }

internal data class GeoPoint(val lat: Double, val lon: Double)

internal data class GeoBounds(
    val southLat: Double,
    val westLon: Double,
    val northLat: Double,
    val eastLon: Double,
)

internal data class EdgeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class CameraPose(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val tiltDeg: Double,
    val bearingDeg: Double,
)

/**
 * The thin adapter. MapLibre lives on the other side of this and nowhere in
 * this package — which is what makes the arbiter a JVM unit test instead of a
 * device session.
 */
internal interface MapPort {
    fun pose(): CameraPose?
    fun move(pose: CameraPose)
    fun flyTo(target: GeoPoint, zoom: Double?, durationMs: Int)
    fun fit(bounds: GeoBounds, padding: EdgeInsets, durationMs: Int)
    fun zoomBy(levels: Double, durationMs: Int)
    fun bearingTo(degrees: Double, durationMs: Int)
    fun tiltTo(degrees: Double, durationMs: Int)
    fun flyover(path: List<GeoPoint>, durationMs: Int)

    /**
     * ATLAS-1 — one animation, awaited to its end. `false` means it was CUT:
     * the operator's finger, or another writer taking the camera. A multi-leg
     * flight is built from these and stops on the first `false`, which is how
     * the finger keeps winning now that the flight no longer holds the map.
     */
    suspend fun animateAwait(pose: CameraPose, durationMs: Int): Boolean
}

/** What the one arbitrated frame actually did. */
internal sealed interface CameraOutcome {
    data class Applied(val intent: CameraIntent) : CameraOutcome

    data class FollowWrote(val pose: CameraPose) : CameraOutcome

    /** A nudge folded into the follow envelope instead of fighting it. */
    data class FollowBias(val levels: Double) : CameraOutcome

    data class Suppressed(val reason: SuppressReason) : CameraOutcome

    object Idle : CameraOutcome
}

internal enum class SuppressReason {
    GESTURE_FRESH,
    ONESHOT_IN_FLIGHT,
    FOLLOW_DISARMED,
    FOLLOW_ARMED,
    DEADBAND,
}
