package local.phantom.companion.feature.stream.domain

/**
 * Extension of the Stream architecture to support Tier 4 system items.
 *
 * Phase 1-B — every subtype carries a [threadId] so items can be partitioned
 * across the multi-thread rail (defaults to [ThreadId.NOW]).
 */
sealed class StreamItem(open val id: String) {
    abstract val threadId: String

    data class Text(
        override val id: String,
        val text: String,
        val role: Role,
        val streaming: Boolean = false,
        /**
         * Phase 1-E — true when the message was pushed by PHANTOM OS via
         * `chat/message.proactive` (server-initiated AI), not generated in
         * response to a local prompt. Drives a subtle left-edge accent +
         * `ПРОАКТИВНЕ` micro-tag in the assistant bubble.
         */
        val fromServer: Boolean = false,
        override val threadId: String = ThreadId.NOW,
        /** Chasm Wave 0 — groups a Слово/Дії/Підсумок run into one Доручення
         *  block (see [local.phantom.companion.feature.stream.ui.TurnFold]).
         *  Null for legacy rows and anything not born inside [submit]. */
        val turnId: String? = null,
    ) : StreamItem(id) {
        enum class Role { USER, ASSISTANT, SYSTEM }
    }

    data class Notice(
        override val id: String,
        val text: String,
        val severity: Severity = Severity.INFO,
        override val threadId: String = ThreadId.NOW,
        val turnId: String? = null,
    ) : StreamItem(id) {
        /** [VOICE] — R0.2 «spoken = shown» (Law 2): the muted on-screen
         *  mirror of something TTS said that had no other visible home
         *  (e.g. a widget's `say` summary). */
        enum class Severity { INFO, WARNING, ERROR, SUCCESS, VOICE }
    }

    /**
     * Те, що PHANTOM побачив у шторці, а не те, що йому сказали.
     *
     * Один рядок на відправника, а не картка на кожне повідомлення:
     * лічильник росте на місці. Тип навмисно не має відповідника в
     * таблиці `stream_items` — нічого з цього не лягає на диск, тож
     * [recent] живе рівно стільки, скільки живе процес.
     *
     * One collapsed row per sender. Never persisted, by type.
     */
    data class Ambient(
        override val id: String,
        val source: String,
        val sender: String,
        val count: Int,
        val lastAtMs: Long,
        val recent: List<String> = emptyList(),
        override val threadId: String = ThreadId.NOW,
    ) : StreamItem(id)

    data class Widget(
        override val id: String,
        val type: String,
        val payload: String,
        override val threadId: String = ThreadId.NOW,
        val turnId: String? = null,
    ) : StreamItem(id)

    /**
     * Agent-driven command for the app (e.g. navigation, settings).
     * Usually not rendered as a bubble but processed for side-effects.
     */
    data class Command(
        override val id: String,
        val action: String,
        val params: Map<String, String> = emptyMap(),
        override val threadId: String = ThreadId.NOW,
    ) : StreamItem(id)

    /**
     * Slim system-event card. Surfaces a state transition or a sensor
     * anomaly inside the conversational stream so the user sees the
     * runtime is alive — not just a chat log.
     */
    data class SystemEvent(
        override val id: String,
        val timestamp: Long,
        val kind: Kind,
        val title: String,
        val detail: String? = null,
        override val threadId: String = ThreadId.NOW,
    ) : StreamItem(id) {
        enum class Kind { STATE_CHANGE, SENSOR, LINK, CONTEXT, MAP }
    }

    /**
     * Phase 1-C — proactive insight surfaced from `agent.stream` (kinds
     * `proactive.cycle` / `proactive.pending_action`).
     *
     * Dismiss is a flag, not a remove. Future Phase 3-A "Memory" needs to
     * recall what insights were shown and dismissed; hard-deletion would
     * lose that signal.
     */
    data class Insight(
        override val id: String,
        val title: String,
        val body: String,
        val kind: Kind = Kind.CYCLE,
        val actions: List<InsightAction> = emptyList(),
        val dismissed: Boolean = false,
        /**
         * Why this appeared NOW. An unprompted card that cannot say what
         * triggered it is asking to be trusted on nothing — and the operator's
         * verdict was exactly that: «не бачу щоб щось виконували».
         */
        val why: String? = null,
        /** 0..1, how sure the engine was. Null when it cannot say. */
        val confidence: Float? = null,
        override val threadId: String = ThreadId.NOW,
    ) : StreamItem(id) {
        /**
         * `CYCLE` is the lightweight ambient insight (model thought aloud).
         * `PENDING_ACTION` is heavier — the agent wants explicit operator
         * confirmation before firing a side-effect.
         */
        enum class Kind { CYCLE, PENDING_ACTION }
    }

    data class ConvictionReview(
        override val id: String,
        val decisionId: String,
        val milestoneDays: Int,
        val prompt: String,
        val currentScore: Int = 5,
        val submitted: Boolean = false,
        override val threadId: String = ThreadId.NOW
    ) : StreamItem(id)
}

/**
 * Префікс id рядків, зроблених із чужого сповіщення.
 *
 * Це єдина ознака, за якою і запис на диск, і прибирання при старті
 * впізнають «те, що PHANTOM підслухав». Показати таке можна, зберегти — ні.
 */
const val NOTIFICATION_ITEM_ID_PREFIX: String = "notif-"

/**
 * One inline action chip rendered at the foot of an [StreamItem.Insight].
 * Labels are payload-driven; tapping fires
 * [local.phantom.companion.feature.stream.domain.StreamEvent.InsightAction]
 * so consumers (TaskerEngine, AIRouter, …) can react to the [actionId].
 */
data class InsightAction(
    val label: String,
    val actionId: String,
    val primary: Boolean = false,
)
