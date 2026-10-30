package local.phantom.companion.core.constitution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * Tier 29-F — fires every day at 03:00 local time, walks the
 * registered feature manifests through [AxiomAuditor] + the sunset
 * scheduler, and emits a [SelfAuditReport]. The runtime layer (Stream
 * Notice card, error bus) subscribes to [reports] and surfaces drift.
 *
 * 03:00 is the operator-quiet hour per the vision doc; consult
 * battery profile before changing it.
 */
class SelfAuditScheduler(
    private val manifestsProvider: () -> List<FeatureManifest>,
    private val auditor: AxiomAuditor,
    private val sunset: SunsetScheduler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _reports = MutableSharedFlow<SelfAuditReport>(replay = 1, extraBufferCapacity = 4)
    val reports: SharedFlow<SelfAuditReport> = _reports.asSharedFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                delay(msUntilNextRun())
                runOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun runOnce(): SelfAuditReport {
        val manifests = manifestsProvider()
        val verdicts = manifests.map(auditor::audit)
        val expired = sunset.reconcile(manifests)
        val report = SelfAuditReport(
            timestampMs = clock(),
            verdicts = verdicts,
            sunsetExpired = expired,
        )
        _reports.tryEmit(report)
        return report
    }

    private fun msUntilNextRun(): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = clock()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, AUDIT_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis - now
    }

    companion object {
        const val AUDIT_HOUR = 3
    }
}

data class SelfAuditReport(
    val timestampMs: Long,
    val verdicts: List<AuditVerdict>,
    val sunsetExpired: Set<String>,
) {
    val failures: List<AuditVerdict.Failed>
        get() = verdicts.filterIsInstance<AuditVerdict.Failed>()

    val isClean: Boolean
        get() = failures.isEmpty() && sunsetExpired.isEmpty()
}
