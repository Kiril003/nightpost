package local.phantom.companion.core.tool.permission

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ToolPermissionStore] implementation.
 *
 * Used in two contexts:
 * 1. Unit tests — no Android Context required.
 * 2. Production fallback — instantiated when [EncryptedToolPermissionStore]
 *    fails to initialise (Keystore unavailable / corrupted). Decisions are
 *    not persisted across process restarts in this mode, which means the
 *    operator will be asked again on next launch — degraded but safe.
 */
class InMemoryToolPermissionStore : ToolPermissionStore {

    private val map = ConcurrentHashMap<String, Decision>()

    override fun decision(toolId: String): Decision =
        map.getOrDefault(toolId, Decision.ASK)

    override fun setDecision(toolId: String, decision: Decision) {
        map[toolId] = decision
    }

    override fun clearAll() {
        map.clear()
    }
}
