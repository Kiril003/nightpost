package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp

/**
 * DA-5 — WorkManager replacement for the W2-5 coroutine-loop
 * cleanup pass. The Worker delegates to the existing
 * [StreamCleanupScheduler.runOnceIfDue] so the gating logic
 * (retentionDays = 0 short-circuit, 24h since last-run guard)
 * stays in one place — both the Worker and the «очистити зараз»
 * Settings hook hit the same code path.
 *
 * Fires once per scheduling tick (see [WorkScheduling.cleanupRequest]).
 * Returns [Result.success] regardless of how many rows were deleted —
 * a 0-deletion pass is a healthy outcome (nothing aged out yet) and
 * should not be retried.
 *
 * Lifetime: WorkManager keeps the Worker alive only for the duration
 * of [doWork]. The PeriodicWorkRequest is enqueued once via
 * [WorkScheduling.enqueueAll] from `AppContainer.init`; subsequent
 * cold starts pick up the same enqueued schedule because we use
 * [androidx.work.ExistingPeriodicWorkPolicy.KEEP].
 */
class StreamCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }
            .onFailure { Log.w(TAG, "container not ready; skipping pass") }
            .getOrNull()
            ?: return Result.success()

        val deleted = runCatching { container.streamCleanupScheduler.runOnceIfDue() }
            .onFailure { Log.w(TAG, "stream cleanup failed", it) }
            .getOrDefault(0)
        if (deleted > 0) Log.d(TAG, "stream_items cleanup pass deleted=$deleted")
        return Result.success()
    }

    companion object {
        private const val TAG = "StreamCleanupWorker"
    }
}
