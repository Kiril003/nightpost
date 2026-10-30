package local.phantom.companion.core.constitution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tier 29-E — auto-disables capabilities that have been idle for
 * longer than their declared sunset window. Idle is "no use-record in
 * the activity log within the window". The actual disable is the
 * caller's responsibility; the scheduler only computes the set of
 * feature ids that should sunset right now.
 *
 * The class is pure compute — wall-clock and activity-log are
 * injected through [clock] and [lastActivity] so tests can drive
 * deterministic time.
 */
class SunsetScheduler(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val lastActivity: (String) -> Long?,
) {

    private val _expired = MutableStateFlow<Set<String>>(emptySet())
    val expired: StateFlow<Set<String>> = _expired.asStateFlow()

    fun reconcile(manifests: Collection<FeatureManifest>): Set<String> {
        val now = clock()
        val out = mutableSetOf<String>()
        for (m in manifests) {
            if (m.sunsetAfterDaysIdle < 0) continue
            val last = lastActivity(m.id) ?: continue
            val ageDays = (now - last) / MS_PER_DAY
            if (ageDays >= m.sunsetAfterDaysIdle) out += m.id
        }
        _expired.value = out
        return out
    }

    companion object {
        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
