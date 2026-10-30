package local.phantom.companion.core.tool.impl

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import local.phantom.companion.core.tool.permission.HttpTarget

/**
 * Thread-safe allowlist of approved HTTP hosts for [HttpTool].
 *
 * A host is considered allowed only if its normalised name exactly matches an
 * explicit entry in the allowlist — approving `"example.com"` does NOT
 * implicitly approve `"api.example.com"`.
 *
 * State is backed by [MutableStateFlow] so approvals/revocations are
 * propagated atomically without external synchronisation.
 */
class HostAllowlist {

    private val approved = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Returns `true` iff [host] is explicitly in the allowlist (exact match).
     */
    fun contains(host: String): Boolean {
        val h = normalise(host)
        if (h.isEmpty()) return false
        return h in approved.value
    }

    /** Add [host] to the allowlist. Idempotent. */
    fun approve(host: String) {
        val h = normalise(host)
        if (h.isNotEmpty()) {
            approved.value = approved.value + h
        }
    }

    /** Remove [host] from the allowlist. No-op if absent. */
    fun revoke(host: String) {
        approved.value = approved.value - normalise(host)
    }

    // DNS names are case-insensitive; without this «Example.com» approved by
    // the operator silently failed to match the host Ktor hands over.
    private fun normalise(host: String): String =
        HttpTarget.normalizeHost(host) ?: host.trim().removeSuffix(".").lowercase(Locale.ROOT)

    /** Returns an immutable snapshot of the current allowlist. */
    fun snapshot(): Set<String> = approved.value
}
