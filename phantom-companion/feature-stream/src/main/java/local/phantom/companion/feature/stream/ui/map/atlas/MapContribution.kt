package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D3 — the unit of everything visible on the map.
 *
 * One declaration answers all five questions that used to be answered in five
 * different files: where it draws ([tier]), when it is allowed to
 * ([lensTiers]), what it costs ([density] via the tier), what a finger on it
 * means ([tap]), and how it gets onto the style ([install]/[update]).
 */
internal data class MapContribution(
    /** Stable across releases: it is the key for install-order tests and logs. */
    val id: String,
    val tier: MapTier,
    /**
     * The tiers whose Lens visibility governs this contribution — ALL must be
     * lit. Defaults to its own tier; a contribution that only makes sense
     * alongside another (maneuver pins without a route line are decoration)
     * names both.
     */
    val lensTiers: Set<MapTier> = setOf(tier),
    val tap: TapSpec? = null,
    val install: ContributionInstall? = null,
    val update: ContributionUpdate? = null,
) {
    val density: DensityClass get() = tier.density

    fun visibleIn(frame: LensFrame): Boolean = frame.tiers.containsAll(lensTiers)
}

/**
 * What a finger on this contribution means.
 *
 * [layerIds] are the engine layers the single hit-test pass must ask about —
 * the registry collects them so 0b makes ONE `queryRenderedFeatures` call for
 * the whole map instead of the five chained ones the canvas makes today (each
 * of which is a main-thread render pass).
 */
internal data class TapSpec(
    val rank: TapRank,
    val layerIds: List<String> = emptyList(),
    /**
     * Claims hits that no other contribution owns. Exactly one contribution
     * may be the catch-all — today's «whatever the base style drew» probe.
     */
    val catchAll: Boolean = false,
    /**
     * Which geometries may win the finger. A polygon under the thumb is
     * terrain, not a target — the country-wide alert rings once ate every tap
     * in Ukraine because nobody had written that down anywhere but a comment.
     */
    val geometry: Set<TapGeometry> = TapGeometry.ANY,
    /** A hit whose layer id contains any of these is not a tap target. */
    val excludesLayers: Set<String> = emptySet(),
    /**
     * Ordered preference groups. A feature carrying a property from group 0
     * beats one from group 1 whatever the distance; a feature in no group at
     * all is not a target. Empty means «take anything».
     */
    val prefers: List<Set<String>> = emptyList(),
)

internal enum class TapGeometry {
    POINT,
    LINE,
    AREA,
    ;

    companion object {
        val ANY: Set<TapGeometry> = entries.toSet()
        val POINTS_ONLY: Set<TapGeometry> = setOf(POINT)
    }
}

/**
 * The engine handle, opaque to this package on purpose: the arbitration logic
 * stays JVM-pure and MapLibre types live only in 0b's implementation of this
 * interface. The registry never inspects it — it passes it through and
 * isolates whatever the hook does with it.
 */
internal interface MapStyleHandle

/** Install runs once per style load, in tier order. */
internal fun interface ContributionInstall {
    fun install(style: MapStyleHandle)
}

/** Update runs whenever data or the Lens frame changes, in the same order. */
internal fun interface ContributionUpdate {
    fun update(style: MapStyleHandle, frame: LensFrame)
}

/**
 * One hit from the single tolerance-box pass.
 *
 * [distancePx] is the squared-or-linear screen distance from the tap point —
 * the registry only ever compares it, never interprets it. Area features that
 * have no meaningful point distance report [AREA_DISTANCE_PX], which sorts
 * them behind every point: «a polygon under the finger is terrain, not a tap
 * target» is today's rule and it stays.
 */
internal data class TapHit(
    val layerId: String,
    val distancePx: Float,
    val payload: Any? = null,
    /**
     * Which [TapSpec.prefers] group this hit landed in. Sorts before distance
     * so «the named thing under the finger» keeps beating «the nearer thing
     * with only a housenumber», which is what the two chained `firstOrNull`
     * probes in the old catch-all meant.
     */
    val preference: Int = 0,
) {
    companion object {
        const val AREA_DISTANCE_PX = 25_000f
    }
}

/** Who won the tap, and on what. */
internal data class TapResolution(
    val contributionId: String,
    val rank: TapRank,
    val hit: TapHit,
)

/** One isolated step's verdict — the `mapStyleStep` contract, made reportable. */
internal data class StepOutcome(
    val id: String,
    val error: Throwable? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * The result of one install or update sweep. A failing contribution must never
 * blank out the rest — the sweep records it and carries on, which is exactly
 * what `mapStyleStep` does today, only now the failure is a value somebody can
 * assert on rather than a line in logcat.
 */
internal data class SweepReport(val steps: List<StepOutcome>) {
    val failed: List<StepOutcome> get() = steps.filter { !it.ok }
    val allOk: Boolean get() = failed.isEmpty()
}
