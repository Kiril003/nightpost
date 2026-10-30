package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import local.phantom.companion.PhantomApp

/**
 * Лист, покладений у сховок, лежить там доти, доки цей телефон по нього не
 * прийде. Поки сітка була підняті лише разом із застосунком, «по нього прийде»
 * означало «якщо людина відкриє застосунок» — тобто слово могло пролежати всі
 * сім днів і згинути за годину до того, як його побачили.
 */
class PeerRelayWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull()
            ?: return Result.success()
        round(container.mesh)
        return Result.success()
    }

    companion object {
        private const val TAG = "PeerRelayWorker"

        internal suspend fun round(mesh: PeerMesh) {
            runCatching { mesh.collectRelay() }
                .onFailure { Log.w(TAG, "relay poll failed", it) }
            runCatching { mesh.drainOutbox() }
                .onFailure { Log.w(TAG, "outbox drain failed", it) }
        }
    }
}
