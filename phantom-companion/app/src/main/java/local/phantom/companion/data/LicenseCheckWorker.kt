package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp

/**
 * Ліцензія й пропозиція оновлення звірялися РІВНО раз — на старті процесу.
 * Екіпаж, що не перезапускає застосунок тижнями, ніколи не дізнавався про
 * збірку, яка вийшла тим часом.
 */
class LicenseCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull()
            ?: return Result.success()
        // force=false лишає рішення за власним заслоном LicenseService
        // (CHECK_IN_INTERVAL_MS): прокидання дешеве, мережа — лише коли час.
        runCatching { container.license.checkIn(force = false) }
            .onFailure { Log.w(TAG, "license check-in failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "LicenseCheckWorker"
    }
}
