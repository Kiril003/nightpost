package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp

/**
 * DA-5 — WorkManager replacement for the W4-5 coroutine-loop
 * digest scheduler. The Worker delegates to
 * [NotificationDigestScheduler.runOnceIfDue] so the once-per-day +
 * 9 am gating, the per-pkg formatter, and the marking/cleanup of
 * delivered rows all stay in one place. The «надіслати дайджест
 * зараз» Settings hook stays usable: it bypasses the Worker by
 * calling `runOnceIfDue(force = true)` directly on the scheduler
 * instance.
 *
 * Fires every ~30 minutes per [WorkScheduling.digestRequest]. Most
 * passes are no-ops (gated by `shouldEmit`) — that's correct; the
 * Worker just needs to be RUN so when the local clock crosses 9 am
 * we don't miss the window. WorkManager handles the «process was
 * killed; restart on next eligible window» case automatically,
 * which is the entire reason this commit exists.
 */
class NotificationDigestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }
            .onFailure { Log.w(TAG, "container not ready; skipping pass") }
            .getOrNull()
            ?: return Result.success()

        val emitted = runCatching { container.notificationDigestScheduler.runOnceIfDue() }
            .onFailure { Log.w(TAG, "digest emit failed", it) }
            .getOrDefault(0)
        if (emitted > 0) Log.d(TAG, "digest emitted rows=$emitted")
        return Result.success()
    }

    companion object {
        private const val TAG = "NotificationDigestWorker"
    }
}
