package local.phantom.companion.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for any AI completion. Underlying route is
 * chosen by [RoutingStrategy] — operator code never picks Gemma vs
 * Gemini directly, the router decides per intent based on runtime
 * conditions (network, battery, system state, intent size).
 */
interface AIRouter {
    /**
     * Begin streaming completion for [intent]. The returned [Flow]
     * emits zero or more [AIChunk.Token] chunks, terminated by either
     * an [AIChunk.Final] (success) or an [AIChunk.ErrorChunk] (failure).
     */
    fun complete(intent: AIIntent): Flow<AIChunk>

    /**
     * Returns the set of [Route]s this router can currently reach.
     * An empty set means no inference is possible (e.g. no network
     * and no local model).
     */
    suspend fun availableRoutes(): Set<Route>
}

/**
 * Strategy controlling local vs cloud preference. Each PHANTOM
 * SystemState maps to one of these:
 *   FOCUS  → [LocalFirst]   (privacy-conscious work)
 *   GHOST  → [LocalOnly]    (no byte leaves the device)
 *   DREAM  → [CloudPreferred] (batch summarisation overnight)
 */
sealed interface RoutingStrategy {
    /** Try local first; fall back to cloud if local fails. */
    object LocalFirst : RoutingStrategy

    /** Try cloud first; fall back to local if cloud fails. */
    object CloudFirst : RoutingStrategy

    /** Use only the local model; never contact the cloud. */
    object LocalOnly : RoutingStrategy

    /** Use only the cloud model; never run locally. */
    object CloudOnly : RoutingStrategy
}
