package local.phantom.companion.core.agent

/**
 * Suspend callback invoked by [AgentSupervisor] for every step transition.
 *
 * Implemented as a `fun interface` so Kotlin callers can pass a lambda:
 * ```kotlin
 * val sink = AgentProgressSink { progress -> uiChannel.send(progress) }
 * ```
 *
 * Implementations must not throw — the supervisor does not catch exceptions
 * propagated from this callback and they would abort the agent run.
 */
fun interface AgentProgressSink {
    suspend fun onStep(progress: AgentProgress)
}
