package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D3 — the composer.
 *
 * One installer walks the registry in [MapTier] order, so lazy installation
 * stops deciding z-position. One tap dispatcher resolves the whole map from a
 * single hit list, so five tap paths stop competing for the same finger.
 *
 * Everything here is pure: no MapLibre, no Compose, no clock. 0b supplies a
 * [MapStyleHandle] and the hit list; this decides order and outcome.
 */
internal class ContributionRegistry(
    /** Called for every step that threw. 0b routes it to logcat, tests to a list. */
    private val onStepFailure: (id: String, error: Throwable) -> Unit = { _, _ -> },
) {

    private val byId = LinkedHashMap<String, MapContribution>()

    /**
     * @throws IllegalArgumentException on a duplicate id — two contributions
     * under one name is two installers writing the same layers, which is how
     * the duplicate POI pins got drawn twice.
     */
    fun register(contribution: MapContribution): ContributionRegistry {
        require(!byId.containsKey(contribution.id)) {
            "duplicate contribution id '${contribution.id}'"
        }
        val clash = contribution.tap?.takeIf { it.catchAll }?.let {
            byId.values.firstOrNull { c -> c.tap?.catchAll == true }
        }
        require(clash == null) {
            "'${contribution.id}' claims catch-all taps already held by '${clash?.id}'"
        }
        byId[contribution.id] = contribution
        return this
    }

    fun registerAll(contributions: Iterable<MapContribution>): ContributionRegistry {
        contributions.forEach(::register)
        return this
    }

    fun contribution(id: String): MapContribution? = byId[id]

    val size: Int get() = byId.size

    /**
     * Deterministic and total: tier ordinal first, registration order as the
     * tie-break. Two contributions on one tier therefore keep a stable
     * relative order across runs without anybody inventing sub-tiers.
     */
    fun installOrder(): List<MapContribution> =
        byId.values.withIndex()
            .sortedWith(compareBy({ it.value.tier.ordinal }, { it.index }))
            .map { it.value }

    /** Install every contribution once, in order, each isolated from the rest. */
    fun install(style: MapStyleHandle): SweepReport =
        sweep(installOrder()) { c -> c.install?.install(style) }

    /**
     * Update every contribution the frame allows, in the same order. A
     * contribution the Lens has darkened is still updated — it has to be told
     * it is dark; that is the only way «hidden» stops meaning «stale data
     * frozen on screen» the next time it lights up.
     */
    fun update(style: MapStyleHandle, frame: LensFrame): SweepReport =
        sweep(installOrder()) { c -> c.update?.update(style, frame) }

    private inline fun sweep(
        order: List<MapContribution>,
        step: (MapContribution) -> Unit,
    ): SweepReport {
        val steps = ArrayList<StepOutcome>(order.size)
        for (c in order) {
            val error = runCatching { step(c) }.exceptionOrNull()
            if (error != null) onStepFailure(c.id, error)
            steps += StepOutcome(c.id, error)
        }
        return SweepReport(steps)
    }

    // ── Tap ───────────────────────────────────────────────────────────────

    /**
     * The layers ONE `queryRenderedFeatures` pass must ask about for this
     * frame, in tap-priority order. Layers of darkened contributions are left
     * out: querying them costs a render pass for a hit that must not win.
     *
     * An empty result does not mean «query nothing» — the catch-all still
     * wants the unrestricted pass; see [catchAllActive].
     */
    fun tapLayerIds(frame: LensFrame): List<String> =
        tappable(frame).flatMap { it.tap?.layerIds.orEmpty() }.distinct()

    fun catchAllActive(frame: LensFrame): Boolean =
        tappable(frame).any { it.tap?.catchAll == true }

    private fun tappable(frame: LensFrame): List<MapContribution> =
        byId.values
            .filter { it.tap != null && it.visibleIn(frame) }
            .sortedWith(compareBy({ it.tap!!.rank.ordinal }, { it.tier.ordinal }))

    /**
     * The whole map's answer to one finger, from one hit list.
     *
     * Priority is [TapRank] first — a route alternative outranks a POI even
     * when the POI's pixel is nearer, because the operator chose to look at
     * two routes. Distance decides only within a rank, so two POIs under one
     * fat finger resolve to the nearer one instead of whichever the engine
     * happened to list first.
     */
    fun resolveTap(hits: List<TapHit>, frame: LensFrame): TapResolution? {
        if (hits.isEmpty()) return null
        val candidates = tappable(frame)
        val owner = HashMap<String, MapContribution>()
        for (c in candidates) c.tap!!.layerIds.forEach { owner.putIfAbsent(it, c) }
        val catchAll = candidates.firstOrNull { it.tap!!.catchAll }

        var best: TapResolution? = null
        for (hit in hits) {
            val c = owner[hit.layerId] ?: catchAll ?: continue
            val rank = c.tap!!.rank
            val current = best
            val better = current == null ||
                rank.ordinal < current.rank.ordinal ||
                (
                    rank.ordinal == current.rank.ordinal && (
                        hit.preference < current.hit.preference ||
                            (
                                hit.preference == current.hit.preference &&
                                    hit.distancePx < current.hit.distancePx
                                )
                        )
                    )
            if (better) best = TapResolution(c.id, rank, hit)
        }
        return best
    }
}
