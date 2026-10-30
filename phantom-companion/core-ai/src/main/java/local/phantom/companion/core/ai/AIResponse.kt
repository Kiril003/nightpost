package local.phantom.companion.core.ai

/**
 * Streaming response chunk. The router emits these via Flow so the
 * Stream surface can render token-by-token without waiting for the
 * full completion.
 */
sealed interface AIChunk {
    /** A single token (or partial text segment) from the model. */
    data class Token(
        val text: String,
        val route: Route,
        val isFinal: Boolean = false,
    ) : AIChunk

    /** Signals the end of the stream with aggregated metadata. */
    data class Final(
        val text: String,
        val tokensUsed: Int,
        val route: Route,
        val durationMs: Long,
    ) : AIChunk

    /** The model wants to call an external tool. */
    data class ToolCall(
        val name: String,
        val arguments: String,
        val callId: String,
        val route: Route,
    ) : AIChunk

    /** Emitted when a route fails. Carries the causing [AIError] and whatever partial text was produced. */
    data class ErrorChunk(
        val cause: AIError,
        val partialText: String = "",
        val route: Route,
    ) : AIChunk

    /**
     * Informational notice emitted by routing/resilience layers (e.g.
     * [HotSwapRouter] cloud→local fallback, offline-queue enqueue).
     * Has no displayable text in the chat bubble by default — consumers
     * may surface it as a subtle status indicator.
     */
    data class Notice(
        val text: String,
        val route: Route,
        val durationMs: Long = 0L,
    ) : AIChunk

    /**
     * R1.3 — web sources the answer was grounded on (Gemini
     * `groundingMetadata`). Emitted at most once per turn, before
     * [Final]; consumers render title+URL chips under the answer so a
     * grounded reply is visibly sourced (Law 1: no invisible magic).
     */
    data class Grounding(
        val citations: List<Citation>,
        val route: Route,
    ) : AIChunk {
        data class Citation(val title: String, val url: String)
    }
}

/** Which inference backend produced the response. */
enum class Route {
    CLOUD_GEMINI,

    /** Зіставлення на пристрої без моделі: жодного висновку, лише виклик інструмента і його дані. */
    ON_DEVICE_RULES,

    /** Розум спареного ПК PHANTOM, через `/api/v1/chat/message`. */
    PC_NODE,

    /** Gemini через сервер Nightpost (`/api/brain`): ні свого ключа, ні ПК. */
    NIGHTPOST_SERVER,
}

/** Typed failures surfaced through [AIChunk.ErrorChunk]. */
sealed class AIError(val messageOverride: String? = null) : Exception(messageOverride) {
    class NoNetwork : AIError("network unavailable for cloud route")
    class CloudRejected(val httpCode: Int, val body: String) :
        AIError("cloud rejected: $httpCode $body")
    class Cancelled : AIError("intent cancelled")

    /** Жодного розуму: ні ключа хмари, ні моделі, ні досяжного ПК. */
    class NoBrain : AIError("no brain: no cloud key, no runnable local model, no reachable PC")

    class PcUnavailable(val httpCode: Int, val detail: String) :
        AIError("paired PC brain failed: $httpCode $detail")

    /** Маршрут зламався не через мережу: людина бачить назву збою, а не «немає мережі». */
    class Broken(val what: String) : AIError(what)
}
