package local.phantom.companion.core.resilience

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import local.phantom.companion.core.ai.withResumePrefix

/**
 * Власний ключ: обірваний потік продовжується з того, що вже сказано. Мережа так
 * і не повернулась — це «немає мережі», а не черга офлайн-запитів стрічки: її
 * порожня відповідь закривала ланцюг до правил телефона й казала «модель промовчала».
 */
class ResumableCloud(
    private val cloud: GeminiCloudRoute,
    private val resumer: StreamResumer = StreamResumer(RetryPolicy.Default),
) : AIRouter {

    override fun complete(intent: AIIntent): Flow<AIChunk> = resumer.resumable(
        firstSource = cloud.streamOnce(intent),
        resumeFactory = { prefix -> cloud.streamOnce(intent.withResumePrefix(prefix)) },
    ).catch { t ->
        if (t !is java.io.IOException) throw t
        emit(AIChunk.ErrorChunk(cause = AIError.NoNetwork(), route = Route.CLOUD_GEMINI))
    }

    override suspend fun availableRoutes() = cloud.availableRoutes()
}
