package local.phantom.companion.service

import android.content.Context
import android.os.SystemClock
import local.phantom.companion.PhantomApp

/** Налаштування Firebase є, лише коли плагін поклав `google_app_id` із google-services.json. */
object PushConfig {
    private const val APP_ID = "google_app_id"

    fun present(string: (name: String) -> String?): Boolean = !string(APP_ID).isNullOrBlank()

    fun present(context: Context): Boolean = present { name ->
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        if (id == 0) null else runCatching { context.getString(id) }.getOrNull()
    }
}

/**
 * Порожній пінг будить застосунок забрати зі сховка. Вмісту в пінгу немає й
 * бути не може: приймається рівно `{w: 1}`, усе інше ігнорується невідкритим.
 */
class PushWake(
    private val configured: () -> Boolean,
    private val pull: () -> Boolean,
    private val startMesh: () -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class Outcome { OFF, IGNORED, COALESCED, PULLED, STARTED }

    private var lastMs = Long.MIN_VALUE

    @Synchronized
    fun onData(data: Map<String, String>): Outcome {
        if (!configured()) return Outcome.OFF
        if (data.size != 1 || data[KEY] != VALUE) return Outcome.IGNORED
        val now = nowMs()
        if (lastMs != Long.MIN_VALUE && now - lastMs < MIN_GAP_MS) return Outcome.COALESCED
        lastMs = now
        if (pull()) return Outcome.PULLED
        startMesh()
        return Outcome.STARTED
    }

    companion object {
        const val KEY = "w"
        const val VALUE = "1"
        const val MIN_GAP_MS = 2_000L

        @Volatile
        private var instance: PushWake? = null

        fun shared(context: Context): PushWake = instance ?: synchronized(this) {
            instance ?: forApp(context.applicationContext).also { instance = it }
        }

        private fun forApp(app: Context) = PushWake(
            configured = { PushConfig.present(app) },
            pull = { runCatching { PhantomApp.container().mesh.pollRelayNow() }.getOrDefault(false) },
            startMesh = { PeerMeshService.ensureRunning(app) },
        )
    }
}
