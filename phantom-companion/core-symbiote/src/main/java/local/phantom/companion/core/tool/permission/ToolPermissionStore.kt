package local.phantom.companion.core.tool.permission

/**
 * Storage contract for per-tool [Decision]s.
 *
 * The interface is intentionally thin so that:
 * - [EncryptedToolPermissionStore] can back it with Android Keystore-wrapped
 *   EncryptedSharedPreferences in production.
 * - [InMemoryToolPermissionStore] can satisfy it with a plain [HashMap] in
 *   unit tests and as a safe fallback when EncryptedSharedPreferences fails to
 *   initialise (e.g. after a Keystore wipe on MIUI/Samsung).
 */
interface ToolPermissionStore {

    /**
     * Returns the stored [Decision] for [toolId], or [Decision.ASK] if no
     * decision has been persisted yet.
     */
    fun decision(toolId: String): Decision

    /**
     * Persists [decision] for [toolId], overwriting any previous value.
     */
    fun setDecision(toolId: String, decision: Decision)

    /**
     * Removes all stored decisions (used by Settings → Reset).
     */
    fun clearAll()
}
