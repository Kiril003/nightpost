package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route

/**
 * Власний ключ людини, який Google відкинув (невірний, прострочений, вимкнений,
 * вичерпана квота чи пробний період), не глушить помічника. Поки ключ мертвий,
 * його не питають, а [NightpostBrainRoute.mayAsk] пускає питання до сервера.
 * Квота оживає за [QUOTA_MS], відмова — за [REFUSED_MS] або одразу з новим ключем.
 * Збій мережі чи 5xx — не про ключ: тоді питання до сервера не йде.
 */
class OwnKeyGuard(
    private val delegate: AIRouter,
    private val key: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AIRouter {

    private class Dead(val key: String, val untilMs: Long)

    @Volatile private var dead: Dead? = null

    fun dead(): Boolean {
        val k = key()?.trim().orEmpty()
        val d = dead ?: return false
        return k.isNotEmpty() && d.key == k && nowMs() < d.untilMs
    }

    override suspend fun availableRoutes(): Set<Route> = if (dead()) emptySet() else delegate.availableRoutes()

    override fun complete(intent: AIIntent): Flow<AIChunk> = delegate.complete(intent).onEach { chunk ->
        ((chunk as? AIChunk.ErrorChunk)?.cause as? AIError.CloudRejected)?.let(::note)
    }

    /** Відмова Google під цим ключем з будь-якої дороги (текст, токен живої розмови). */
    fun note(cause: AIError.CloudRejected) {
        val k = key()?.trim().orEmpty()
        val ms = deadFor(cause) ?: return
        if (k.isNotEmpty()) dead = Dead(k, nowMs() + ms)
    }

    companion object {
        const val QUOTA_MS = 15 * 60_000L
        const val REFUSED_MS = 6 * 60 * 60_000L

        private val KEY_REFUSED = listOf("API_KEY_INVALID", "API key not valid", "API key expired", "API_KEY_SERVICE_BLOCKED")

        fun deadFor(cause: AIError.CloudRejected): Long? = when {
            cause.httpCode == 429 -> QUOTA_MS
            cause.httpCode == 401 || cause.httpCode == 403 -> REFUSED_MS
            cause.httpCode == 400 && KEY_REFUSED.any { cause.body.contains(it, ignoreCase = true) } -> REFUSED_MS
            else -> null
        }
    }
}
