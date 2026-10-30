package local.phantom.companion.core.net.roadpack

/**
 * Phase 2 (map-plan) — process-wide holder for the road-pack link session.
 *
 * Same shape as [local.phantom.companion.core.net.map.GeoEndpoints]: a
 * `:core-net`-owned singleton `:app`'s `AppContainer` installs once at boot
 * (`installRoadPackLink`, mirroring the existing `installGeoEndpoints`), so
 * `:feature-precision` — which cannot depend on `:app` (the module graph
 * runs the other way) — can read it directly without threading pairing
 * state through `PrecisionUiState`/nav-graph plumbing. Documented in
 * `GeoEndpoints` as "process-wide holder, not DI... Phase-3 DI work replaces
 * this with constructor injection" — this follows the same house pattern
 * rather than inventing a second one.
 *
 * `null` means "never installed" (should not happen once `boot()` has run)
 * — callers treat that the same as "PC unreachable", never as a crash.
 */
object RoadPackLink {
    @Volatile
    var session: RoadPackSession? = null
}

/**
 * Live functions, not a snapshot: the base URL can change under LAN roaming
 * / relay fallback, the device JWT can be refreshed, and [connected] must
 * reflect the *current* WS session state — capturing values instead of
 * suppliers at install time would go stale the moment any of those changed
 * without a re-install call nobody would remember to make.
 */
data class RoadPackSession(
    val client: RoadPackLinkClient,
    val baseUrl: () -> String?,
    val deviceJwt: () -> String?,
    /** Mirrors `PhantomLink.connected.value == LinkStatus.Connected` — the
     *  exact signal the rest of the app already uses for "ПК на звʼязку",
     *  not a second connectivity check invented for this one card. */
    val connected: () -> Boolean,
)
