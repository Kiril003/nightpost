/**
 * `:core-agent` — Phase 2.A single-agent supervisor.
 *
 * Public surface:
 * - [AgentSpec]           immutable specification fed to the supervisor.
 * - [AgentResult]         sealed outcome: Done / Failed / Cancelled.
 * - [AgentProgress]       per-step progress snapshot.
 * - [AgentProgressSink]   fun interface for progress callbacks.
 * - [AgentSupervisor]     the agentic loop.
 * - [AgentSystemPromptBuilder] helper for building the agent system prompt.
 *
 * Phase 2.B will add parallel dispatch and a richer cancellation API.
 * Phase 2.C will wire this into AppContainer + StreamViewModel.
 */
package local.phantom.companion.core.agent
