package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.data.patterns.PatternEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Phase 3-D: TaskerEngine.
 *
 * Detects patterns in user behavior and triggers background AI logic
 * or proactively suggests actions in the Stream.
 */
private const val BURST_SUMMARY_COOLDOWN_MS: Long = 10L * 60L * 1000L

class TaskerEngine(
    private val aiRouter: AIRouter? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * W4-7 — pattern detector. AppContainer wires the production
     * instance backed by [PatternRepository]; tests can pass null
     * (the engine simply skips pattern observation in that case).
     */
    private val patternDetector: PatternDetector? = null,
    private val isScreenAwarenessEnabled: () -> Boolean = { false },
) {

    private val _suggestedActions = MutableSharedFlow<TaskerAction>(extraBufferCapacity = 16)
    val suggestedActions = _suggestedActions.asSharedFlow()

    var currentScreenContext: String? = null
        private set

    /** QA-fix 2026-07-17 — the REAL active package from the snapshot.
     *  Consumers that need «which app» must read THIS, never a line
     *  sliced off [currentScreenContext] (that slice is how a raw
     *  accessibility dump ended up rendered as «АКТИВНА ЗАНЯТІСТЬ»). */
    var currentScreenPackage: String? = null
        private set

    private var lastAiAnalysisTime = 0L
    private var lastLocalParseTime = 0L
    /** P4-3-fix — per-package cooldown for notification-burst summaries
     *  so the chat doesn't get 3 near-identical «Стисло: …» bubbles in
     *  a row when the OS fires multiple bursts from the same app. */
    private val lastBurstSummaryByPackage = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            GodModeEventBus.events.collect { event ->
                if (event is GodModeEvent.ScreenSnapshot) {
                    val oldContext = currentScreenContext
                    currentScreenContext = event.textContent
                    currentScreenPackage = event.pkg
                    
                    if (shouldAnalyzeWithAi(oldContext, event.textContent)) {
                        analyzeScreenWithAi(event.pkg, event.textContent)
                    }
                }
                processEvent(event)
            }
        }
    }

    private fun shouldAnalyzeWithAi(old: String?, new: String): Boolean {
        // Completely disabled in quality hardening to guarantee no background quota leakage
        return false
    }

    private fun analyzeScreenWithAi(pkg: String, content: String) {
        lastAiAnalysisTime = System.currentTimeMillis()
        
        scope.launch {
            val prompt = """
                Аналізуй вміст екрана мобільного додатка ($pkg). 
                Вміст: ${content.take(2000)}
                
                Твоє завдання: якщо ти бачиш щось важливе (запланована зустріч, важливе питання, цікава новина), 
                запропонуй дію. Якщо нічого особливого — не відповідай нічим.
                
                Якщо хочеш щось запропонувати, відповідай ЛИШЕ в форматі JSON:
                {"type":"notice", "text":"Твій короткий коментар або порада"}
                АБО
                {"type":"question", "text":"Питання до користувача", "options":["Опція 1", "Опція 2"]}
            """.trimIndent()

            try {
                aiRouter?.complete(AIIntent.Text(prompt = prompt))?.collect { chunk ->
                    if (chunk is AIChunk.Final) {
                        val text = chunk.text.trim()
                        if (text.startsWith("{") && text.endsWith("}")) {
                            parseAndEmitAiAction(text)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TaskerEngine", "AI Screen analysis failed", e)
            }
        }
    }

    private suspend fun parseAndEmitAiAction(jsonStr: String) {
        runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val parsed = json.parseToJsonElement(jsonStr) as? JsonObject
            if (parsed != null) {
                val type = parsed["type"]?.jsonPrimitive?.content
                val text = parsed["text"]?.jsonPrimitive?.content ?: ""
                
                when (type) {
                    "notice" -> _suggestedActions.emit(TaskerAction.Notice("ai_${System.currentTimeMillis()}", text))
                    "question" -> {
                        val options = parsed["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                        _suggestedActions.emit(TaskerAction.Question("ai_${System.currentTimeMillis()}", text, options))
                    }
                }
            }
        }
    }

    private suspend fun processEvent(event: GodModeEvent) {
        when (event) {
            is GodModeEvent.Notification -> {
                if (event.pkg.contains("bank") && event.title.contains("Payment")) {
                    _suggestedActions.emit(
                        TaskerAction.Notice(
                            id = "notif_${System.currentTimeMillis()}",
                            text = "Фінансова транзакція виявлена. Рекомендую закрити ПРИМАРУ vault."
                        )
                    )
                }
            }
            is GodModeEvent.NotificationBurst -> {
                // Completely disabled in quality hardening to guarantee no background quota leakage
            }
            is GodModeEvent.ScreenChanged -> {
                // W4-7 — observe app-launch as a pattern signal.
                // Each TYPE_WINDOW_STATE_CHANGED event with a
                // resolved package becomes a single observation in
                // the «app» bucket; the detector surfaces an
                // InsightCard once the bucket crosses the 3-occurrence
                // threshold within the rolling 7-day window.
                val pkg = event.pkg.takeIf { it.isNotBlank() } ?: return
                val detector = patternDetector ?: return
                scope.launch {
                    runCatching {
                        val surfaced = detector.observe(
                            PatternEvent(
                                kind = PatternEntity.Kind.APP,
                                keyValue = pkg,
                                suggestedAction = "помітив часті відкриття «$pkg»",
                            ),
                        )
                        // Pattern-engine emits intentionally suppressed at the
                        // chat surface — they used to spam «помітив часті
                        // відкриття...» every app launch even after operator
                        // tapped «не зараз», because state-only removal didn't
                        // persist the dismissal. The detector still upserts
                        // into Room so the bucket count keeps growing for the
                        // future opt-in surface (Settings → Patterns), but
                        // nothing lands in the diary by default.
                        if (false && surfaced != null) {
                            @Suppress("UNREACHABLE_CODE")
                            _suggestedActions.emit(
                                TaskerAction.Question(
                                    id = "pattern_${surfaced!!.id}",
                                    text = "помітив: ${surfaced.suggestedAction} → нагадати?",
                                    options = listOf(
                                        "Так, нагадай",
                                        "Не зараз",
                                        "Не пиши про це більше",
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            // Consumed in [init] (currentScreenContext / currentScreenPackage);
            // the branch stays only to keep the sealed `when` exhaustive.
            is GodModeEvent.ScreenSnapshot -> Unit
        }
    }
}

sealed class TaskerAction {
    abstract val id: String
    
    data class Question(
        override val id: String,
        val text: String,
        val options: List<String>
    ) : TaskerAction()

    data class Notice(
        override val id: String,
        val text: String
    ) : TaskerAction()

    data class Chart(
        override val id: String,
        val label: String,
        val values: List<Float>,
        val trend: String? = null
    ) : TaskerAction()
}
