package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.errors.PhantomError

class SymbioteAuditWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull()
            ?: return Result.success()
        if (!container.symbiotePrefs.auditEnabled.value) return Result.success()
        val report = runCatching { container.symbioteSelfAudit.runOnce() }
            .onFailure { Log.w(TAG, "symbiote audit failed", it) }
            .getOrNull() ?: return Result.success()
        if (!report.isClean) {
            container.errorBus.emit(
                source = "SymbioteAudit",
                severity = PhantomError.Severity.Warn,
                message = applicationContext.getString(
                    local.phantom.companion.R.string.err_symbiote_audit_drift,
                    report.failures.size,
                    report.sunsetExpired.size,
                ),
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SymbioteAuditWorker"
    }
}
