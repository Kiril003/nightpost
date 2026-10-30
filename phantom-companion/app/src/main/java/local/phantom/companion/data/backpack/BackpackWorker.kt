package local.phantom.companion.data.backpack

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.net.backpack.Backpack
import java.util.concurrent.TimeUnit

/**
 * Нічний повний рюкзак: раз на добу, лише на Wi-Fi без ліміту і на зарядці
 * (розд. 07 §7.1). Обмеження ставить система — працівник не запуститься на
 * мобільних даних чи від батареї, хоч би що.
 */
class BackpackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return Result.retry()
        container.backpack.pack(Backpack.Trigger.NIGHT)
        return Result.success()
    }

    companion object {
        const val NAME = "phantom_backpack_night"

        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackpackWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .addTag(NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
