package local.phantom.companion.core.net.maplayer

/**
 * Map-symbiosis — process-wide holder for the PC layer-pack link session.
 *
 * Same shape as [local.phantom.companion.core.net.roadpack.RoadPackLink]:
 * `:app`'s `AppContainer` installs this once at boot (`installPcLayerLink`,
 * mirroring the existing `installRoadPackLink`), so `:feature-stream` —
 * which cannot depend on `:app` (the module graph runs the other way) —
 * reads it directly rather than threading pairing state through
 * `MapUiState`/nav-graph plumbing.
 *
 * `null` means "never installed" (should not happen once `boot()` has run)
 * — callers treat that the same as "PC unreachable", never as a crash.
 */
object PcLayerLink {
    @Volatile
    var session: PcLayerSession? = null
}

/**
 * Live functions, not a snapshot — the base URL can change under LAN
 * roaming / relay fallback, the device JWT can be refreshed, and
 * [connected] must reflect the *current* link state. Capturing values
 * instead of suppliers at install time would go stale the moment any of
 * those changed without a re-install call nobody would remember to make
 * (same reasoning as `RoadPackSession`).
 */
data class PcLayerSession(
    val client: PcLayerCatalogClient,
    val baseUrl: () -> String?,
    val deviceJwt: () -> String?,
    val connected: () -> Boolean,
)
