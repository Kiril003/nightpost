package local.phantom.companion.ui.messenger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.data.memory.MemoryFactEntity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolExecutor
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.diary.DecisionEntry
import local.phantom.companion.core.tool.permission.ConsentPath
import local.phantom.companion.core.tool.permission.GateOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.R
import local.phantom.companion.assistant.Approval
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.buildProductToolset
import local.phantom.companion.data.messengerToolset
import local.phantom.companion.data.sentPayments
import local.phantom.companion.data.sharedWalletOf
import local.phantom.companion.data.moneyHands
import local.phantom.companion.data.words
import local.phantom.companion.net.PhantomApi
import java.util.UUID

/**
 * Мультиагентний оркестратор агентських сесій (Agency Workspace) та
 * інтеграції ШІ-Симбіонта в Месенджер.
 *
 * Дозволяє оператору вести масштабні проекти розробки, делегуючи задачі
 * системі спеціалізованих автономних агентів:
 * 1. 🏛️ System Designer (Architect) — архітектура, модулі, API-контракти
 * 2. 🛡️ Red Team / Challenger — вразливості, race conditions, edge cases
 * 3. ⚡ Performance Profiler — складність O(N), VRAM/RAM, батарея, ПК
 * 4. 🔮 Lead Synthesizer — підсумковий консенсус, генерація коду та артефактів
 */
class AgencySessionManager(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val toolExecutor = ToolExecutor()

    private val messengerTools get() = messengerToolset(container.toolRegistry.tools.value)

    private val product get() = container.buildProductToolset(messengerToolset(container.toolRegistry.tools.value)) { appLanguage() == "en" }

    private val money by lazy { local.phantom.companion.data.MoneyAssistant(container.moneyHands()) }

    data class AgencyAgent(
        val id: String,
        /** Імʼя й значок — ресурси: їх читає людина, тож вони йдуть мовою розмови. */
        @androidx.annotation.StringRes val name: Int,
        val handle: String,
        val avatar: String,
        @androidx.annotation.StringRes val badge: Int,
        val color: String,
        val systemPrompt: String,
    )

    data class AgencySession(
        val id: String,
        val title: String,
        val createdAtMs: Long,
        val topic: String,
        val isDefault: Boolean = false,
    )

    companion object {
        const val PHANTOM_AGENT_ID = "peer_phantom_agent"
        const val PHANTOM_AGENT_NAME = "Nightpost"

        const val COUNCIL_SESSION_ID = "agency_council"
        const val COUNCIL_SESSION_TITLE = "🏛️ Колегія Агентів"

        /** Вбудована сесія, вона існує від першого запуску — не мить створення. */
        const val COUNCIL_CREATED_AT_MS = 0L

        val ARCHITECT = AgencyAgent(
            id = "agent_architect",
            name = R.string.agency_agent_architect,
            handle = "@SystemArchitect",
            avatar = "🏛️",
            badge = R.string.agency_agent_architect_badge,
            color = "#E87A42",
            systemPrompt = """
                Ти — Головний Архітектор Систем (System Designer) у команді Nightpost.
                Твоє завдання: розібрати задачу оператора на високорівневу топологію, модулі, протоколи,
                схеми даних та API-контракти. Сформулюй чітку, елегантну та масштабовану технічну архітектуру.
                Будь конкретним: наводь схеми, ендпоінти, моделі даних. Пиши українською мовою.
            """.trimIndent(),
        )

        val RED_TEAM = AgencyAgent(
            id = "agent_red_team",
            name = R.string.agency_agent_red_team,
            handle = "@RedTeam",
            avatar = "🛡️",
            badge = R.string.agency_agent_red_team_badge,
            color = "#E11D48",
            systemPrompt = """
                Ти — Провідний Експерт Безпеки та Стрес-Тестування (Red Team) у команді Nightpost.
                Твоє завдання: критично проаналізувати пропозицію Архітектора, знайти вразливості, race conditions,
                небезпеку split-brain при втраті зʼєднання, витоки памʼяті та вектори атак. Запропонуй надійні контрзаходи
                та валідацію. Будь безкомпромісним до багів і слабких місць. Пиши українською мовою.
            """.trimIndent(),
        )

        val HARDWARE = AgencyAgent(
            id = "agent_hardware",
            name = R.string.agency_agent_hardware,
            handle = "@HardwareProfiler",
            avatar = "⚡",
            badge = R.string.agency_agent_hardware_badge,
            color = "#059669",
            systemPrompt = """
                Ти — Інженер Продуктивності та Профілювання (Performance Profiler) команди Nightpost.
                Твоє завдання: оцінити обчислювальну складність O(N)/O(1), споживання VRAM/RAM,
                навантаження на процесор телефона й спареного ПК, енергоспоживання
                батареї та запропонувати оптимізації кешування й асинхронності. Пиши українською мовою.
            """.trimIndent(),
        )

        val SYNTHESIZER = AgencyAgent(
            id = "agent_synthesizer",
            name = R.string.agency_agent_synthesizer,
            handle = "@Synthesizer",
            avatar = "🔮",
            badge = R.string.agency_agent_synthesizer_badge,
            color = "#7C3AED",
            systemPrompt = """
                Ти — Головний Синтезатор та Інженер Рішень (Lead Synthesizer / Coder) у команді Nightpost.
                Твоє завдання: звести висновки Архітектора, зауваження Red Team та оптимізації Performance у
                фінальний узгоджений результат — згенерувати готовий, чистий робочий код або покроковий план впровадження.
                Надай завершений артефакт, готовий до запуску. Пиши українською мовою.
            """.trimIndent(),
        )

        val ALL_AGENTS = listOf(ARCHITECT, RED_TEAM, HARDWARE, SYNTHESIZER)
    }

    // ── те, що зараз думається ───────────────────────────────────────────

    /**
     * Що з відповіддю ПРЯМО ЗАРАЗ.
     *
     * Доти відповідь зʼявлялась брилою: `collect` збирав увесь потік у
     * `StringBuilder`, і лише після останнього шматка один `ingestDirect` клав
     * готовий абзац у стрічку. Між питанням і абзацом екран не казав нічого —
     * ні що модель узялась, ні що вона ще пише, ні що пішла виконувати
     * інструмент. Для місцевої моделі це десятки секунд мовчання, яке нічим не
     * відрізняється від «застосунок завис».
     *
     * Потік НЕ ходить через [PeerMesh.ingestDirect] і не може: кожен `publish`
     * переписує ВЕСЬ журнал шифрованим на диск, тож лист на тисячу лексем
     * коштував би тисячі перезаписів журналу — по одному на слово. Тому слова
     * ростуть тут, у памʼяті, а в журнал лягає один готовий лист наприкінці.
     */
    data class Live(
        val conversationId: String,
        val messageId: String,
        val phase: Phase,
        val text: String,
        /** Назва інструмента, поки [Phase.TOOL]. Порожньо в решті випадків. */
        val toolName: String = "",
        /** Людське речення про поразку, поки [Phase.FAILED]. */
        val reason: String = "",
        /** Лист питання: поразка стає знаком на ньому. */
        val questionId: String? = null,
        val startedAtMs: Long,
        val changedAtMs: Long,
    )

    enum class Phase {
        /** Питання пішло, жодної лексеми ще немає. */
        THINKING,

        /** Слова йдуть. */
        WRITING,

        /** Модель попросила інструмент, і він зараз виконується. */
        TOOL,

        /**
         * Відповіді не буде, і причина названа словом.
         *
         * Рядок лягає в розмову, щоб причину було видно, але «мережі немає» — факт
         * про цей апарат у цю хвилину, а не те, що модель сказала: в її історію
         * він не йде ([AssistantNotices], [DialogueTurns.history]).
         */
        FAILED,
    }

    private val assistantNotices by lazy { AssistantNotices.load(container.applicationContext) }

    /**
     * Чому питання без відповіді: ярлик під бульбашкою питання, а не лист від імені помічника.
     * Лише для питань, що самі лежать у журналі; після перезапуску лишається знак «не пішло».
     */
    private val failedAsks = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun failureOf(questionId: String): String? = failedAsks[questionId]

    private fun failAsk(messageId: String, reason: String, meId: String, questionId: String?) {
        if (questionId == null) {
            failLive(messageId, reason, meId)
            return
        }
        failedAsks[questionId] = reason
        container.mesh.markDirect(questionId, PeerMessage.State.FAILED)
        failLive(messageId, reason)
    }

    /**
     * «Ще раз» на питанні, що лишилось без відповіді: те саме питання, той самий рядок журналу,
     * той самий шлях до моделі. Нового листа від людини не зʼявляється.
     */
    fun retryAsk(question: PeerMessage, meId: String) {
        failedAsks.remove(question.id)
        container.mesh.markDirect(question.id, PeerMessage.State.DELIVERED)
        dispatchPhantomDialogue(question.body, meId, questionId = question.id)
    }

    private val asks = AskGate()

    private val board = AssistantLiveBoard()
    val live: StateFlow<Live?> = board.live
    val liveFeed: SharedFlow<Live?> = board.feed

    /** Людина прочитала поразку — прибираємо її з екрана. */
    fun dismissLive(conversationId: String) = board.dismiss(conversationId)

    private fun beginLive(conversationId: String, messageId: String, questionId: String? = null) =
        board.begin(conversationId, messageId, questionId)

    private fun pushLive(messageId: String, phase: Phase, text: String, toolName: String = "") =
        board.push(messageId, phase, text, toolName)

    private fun failLive(messageId: String, reason: String, recipientId: String? = null) {
        board.fail(messageId, reason)
        if (recipientId != null && reason.isNotBlank()) {
            val already = container.mesh.outbox.value.any { it.id == messageId }
            if (!already) {
                container.mesh.ingestDirect(
                    PeerMessage(
                        id = messageId,
                        fromId = PHANTOM_AGENT_ID,
                        toId = recipientId,
                        body = reason,
                        sentAtMs = System.currentTimeMillis(),
                        state = PeerMessage.State.DELIVERED,
                    ),
                )
            }
        }
    }

    private fun endLive(messageId: String) = board.end(messageId)

    /** Чому відповіді немає — словами розмови ([FailureWords]). */
    private fun words(cause: Throwable?): String = FailureWords.of(cause) { id, args -> str(id, *args) }

    /**
     * Чи є цьому апарату чим думати взагалі.
     *
     * Питається ДО того, як екран покаже «думає»: обертати крапки над порожнім
     * маршрутом — це вигляд роботи без роботи.
     */
    suspend fun canAnswer(): Boolean =
        runCatching { container.aiRouter.availableRoutes().isNotEmpty() }.getOrDefault(false)

    private val customSessions = mutableMapOf<String, AgencySession>()

    fun allSessions(): List<AgencySession> {
        val defaultCouncil = AgencySession(
            id = COUNCIL_SESSION_ID,
            title = COUNCIL_SESSION_TITLE,
            createdAtMs = COUNCIL_CREATED_AT_MS,
            topic = "Комплексна розробка систем та мультиагентний консенсус",
            isDefault = true,
        )
        return listOf(defaultCouncil) + customSessions.values
    }

    fun findSession(id: String): AgencySession? {
        if (id == COUNCIL_SESSION_ID) return allSessions().first()
        return customSessions[id]
    }

    fun createSession(title: String, topic: String): AgencySession {
        // Захист від захаращення чатами та переповнення памʼяті: макс 20 кастомних сесій (LRU)
        if (customSessions.size >= 20) {
            val oldestKey = customSessions.minByOrNull { it.value.createdAtMs }?.key
            if (oldestKey != null) {
                customSessions.remove(oldestKey)
            }
        }
        val id = "agency_" + UUID.randomUUID().toString().take(8)
        val session = AgencySession(
            id = id,
            title = "🚀 $title",
            createdAtMs = System.currentTimeMillis(),
            topic = topic,
            isDefault = false,
        )
        customSessions[id] = session
        return session
    }

    fun deleteSession(id: String): Boolean {
        return customSessions.remove(id) != null
    }

    /**
     * Обробник швидких команд керування агентством та ПК-симбіотом (!pc, !council, !memory тощо).
     */
    private suspend fun tryHandleSpecialCommand(
        prompt: String,
        targetId: String,
        meId: String,
    ): Boolean {
        if (!prompt.startsWith("!")) return false

        val parts = prompt.removePrefix("!").trim().split("\\s+".toRegex(), limit = 2)
        val cmd = parts.firstOrNull()?.lowercase() ?: return false
        val arg = if (parts.size > 1) parts[1].trim() else ""

        val responseText = when (cmd) {
            "help" -> """
                ⚡ **Командний центр Nightpost (Агентство & Симбіоз з ПК)**:
                • `!pc.open <url>` — відкрити посилання на робочому столі ПК
                • `!pc.clipboard <текст>` — скопіювати текст у буфер обміну ПК
                • `!pc.type <текст>` — набрати текст на клавіатурі ПК
                • `!pc.notify <заголовок> | <текст>` — надіслати сповіщення на екран ПК
                • `!pc.lock` — заблокувати екран ПК
                • `!pc.state` — перевірити статус підключення десктопу
                • `!agency.council <завдання>` — скликати Колегію Агентів (Architect, Red Team, Hardware, Synthesizer)
                • `!memory` — переглянути факти з довгострокової памʼяті оператора
                • `!status` — стан системи, звʼязок та маршрутизація ШІ
            """.trimIndent()

            "pc.open" -> {
                if (arg.isBlank()) str(R.string.agency_pc_open_usage)
                else {
                    val res = runCatching {
                        container.api.sendCommand(PhantomApi.CommandIn(verb = "pc.open", args = mapOf("target" to arg, "url" to arg)))
                    }.getOrNull()
                    if (res != null && res.ok) str(R.string.agency_pc_open_ok, arg)
                    else str(R.string.agency_pc_open_fail, res?.detail ?: str(R.string.agency_pc_no_link))
                }
            }

            "pc.clipboard" -> {
                if (arg.isBlank()) str(R.string.agency_pc_clipboard_usage)
                else {
                    val res = runCatching {
                        container.api.sendCommand(PhantomApi.CommandIn(verb = "pc.clipboard", args = mapOf("text" to arg)))
                    }.getOrNull()
                    if (res != null && res.ok) str(R.string.agency_pc_clipboard_ok, arg.take(40))
                    else str(R.string.agency_pc_clipboard_fail, res?.detail ?: str(R.string.agency_pc_no_link))
                }
            }

            "pc.type" -> {
                if (arg.isBlank()) str(R.string.agency_pc_type_usage)
                else {
                    val res = runCatching {
                        container.api.sendCommand(PhantomApi.CommandIn(verb = "pc.type", args = mapOf("text" to arg)))
                    }.getOrNull()
                    if (res != null && res.ok) str(R.string.agency_pc_type_ok, arg)
                    else str(R.string.agency_pc_type_fail, res?.detail ?: str(R.string.agency_pc_no_link))
                }
            }

            "pc.lock" -> {
                val res = runCatching {
                    container.api.sendCommand(PhantomApi.CommandIn(verb = "pc.lock", args = emptyMap()))
                }.getOrNull()
                if (res != null && res.ok) str(R.string.agency_pc_lock_ok)
                else str(R.string.agency_pc_lock_fail, res?.detail ?: str(R.string.agency_pc_no_link))
            }

            "pc.notify" -> {
                val splitArgs = arg.split("|", limit = 2)
                val title = splitArgs.firstOrNull()?.trim() ?: str(R.string.agency_pc_notify_title)
                val body = if (splitArgs.size > 1) splitArgs[1].trim() else str(R.string.agency_pc_notify_body)
                val res = runCatching {
                    container.api.sendCommand(PhantomApi.CommandIn(verb = "pc.notify", args = mapOf("title" to title, "body" to body)))
                }.getOrNull()
                if (res != null && res.ok) str(R.string.agency_pc_notify_ok, title)
                else str(R.string.agency_pc_notify_fail, res?.detail ?: str(R.string.agency_pc_no_link))
            }

            "pc.state" -> {
                val paired = container.store.load()
                if (paired == null) {
                    str(R.string.agency_pc_not_paired)
                } else {
                    str(R.string.agency_pc_state, paired.username, paired.lanUrl, container.link.connected.value)
                }
            }

            "memory", "facts" -> {
                val facts = runCatching { container.memoryFactStore.list(30) }.getOrDefault(emptyList())
                if (facts.isEmpty()) str(R.string.agency_memory_empty)
                else str(R.string.agency_memory_header, facts.size) + "\n" +
                    facts.joinToString("\n") { "• `[${it.category}]` ${it.text}" }
            }

            "status" -> {
                val paired = container.store.load()
                val pcStatus = if (paired != null) str(R.string.agency_pc_link, container.link.connected.value)
                else str(R.string.agency_pc_unpaired)
                val factCount = runCatching { container.memoryFactStore.list(100).size }.getOrDefault(0)
                val routes = runCatching { container.aiRouter.availableRoutes() }.getOrDefault(emptySet())
                val aiRoutes = if (routes.isEmpty()) str(R.string.agency_no_ai_route)
                else routes.joinToString(", ") { it.name }
                """
                    📊 **Статус цифрового симбіонта**:
                    • Маршрути ШІ: $aiRoutes
                    • ПК: $pcStatus
                    • База знань оператора: $factCount фактів
                    • Сесії колегії: ${customSessions.size + 1}
                """.trimIndent()
            }

            "agency.council", "council" -> {
                if (arg.isNotBlank()) {
                    dispatchAgencyCouncil(targetId, arg, meId)
                    return true
                } else {
                    str(R.string.agency_council_usage)
                }
            }

            else -> null
        }

        if (responseText != null) {
            val msg = PeerMessage(
                id = "msg_" + UUID.randomUUID().toString().take(12),
                fromId = PHANTOM_AGENT_ID,
                toId = targetId,
                body = responseText,
                sentAtMs = System.currentTimeMillis(),
                state = PeerMessage.State.DELIVERED,
            )
            container.mesh.ingestDirect(msg)
            return true
        }
        return false
    }

    /**
     * Прямий діалог з Фантомом у Месенджері.
     *
     * Слова йдуть у [live] по дорозі, а в журнал лягає один готовий лист — див.
     * [Live] про те, чому потік не може йти через `ingestDirect`.
     */
    fun dispatchPhantomDialogue(
        userText: String,
        meId: String,
        voice: local.phantom.companion.self.VoiceSink? = null,
        /** Лист питання в журналі: поразка стає знаком на ньому, а не листом помічника. */
        questionId: String? = null,
    ) {
        // Опис вкладення несе ключ шифру: модель бачить лише підпис чи розшифровку.
        val asked = AssistantInput.promptOf(userText) ?: run {
            // Пачці відповідає її перший аркуш: підпис, якщо є, лежить у ньому.
            if (AssistantInput.isBatchTail(userText)) return
            val noticeId = "msg_" + UUID.randomUUID().toString().take(12)
            val notice = str(R.string.assistant_sees_text_only)
            // Кілька вкладень поспіль без жодного питання між ними — один рядок, а не по рядку на кожне.
            val log = container.mesh.conversation(PHANTOM_AGENT_ID)
            val lastNotice = log.indexOfLast { it.fromId == PHANTOM_AGENT_ID && it.body == notice }
            val repeat = lastNotice >= 0 &&
                log.drop(lastNotice + 1).all { it.fromId != PHANTOM_AGENT_ID && AssistantInput.promptOf(it.body) == null }
            if (!repeat) container.mesh.ingestDirect(
                PeerMessage(
                    id = noticeId,
                    fromId = PHANTOM_AGENT_ID,
                    toId = meId,
                    body = notice,
                    sentAtMs = System.currentTimeMillis(),
                    state = PeerMessage.State.DELIVERED,
                ),
            )
            voice?.begin(noticeId)
            voice?.done(notice, noticeId)
            return
        }
        // Голос питає вголос і чекає відповіді сам — повтор тих самих слів у дзвінку не дубль.
        val ticket = asks.admit(questionId, asked, retry = voice != null) ?: run {
            Log.i("AgencyManager", "dispatchPhantomDialogue: same question already answered or in flight")
            return
        }
        val messageId = "msg_" + UUID.randomUUID().toString().take(12)
        voice?.begin(messageId)
        beginLive(PHANTOM_AGENT_ID, messageId, questionId)
        scope.launch {
            var failed = false
            try {
                val prompt = asked.trim()
                if (tryHandleSpecialCommand(prompt, PHANTOM_AGENT_ID, meId)) {
                    voice?.done(null, messageId)
                    endLive(messageId)
                    return@launch
                }

                // Людина одним поглядом: усе між вами вже на телефоні; невідоме імʼя йде далі, до розуму.
                if (voice == null) {
                    val glance = runCatching { personGlance(prompt) }
                        .onFailure { Log.w("AgencyManager", "glance: failed, plain reply follows", it) }
                        .getOrNull()
                    if (glance != null) {
                        container.mesh.ingestDirect(PeerMessage(id = messageId, fromId = PHANTOM_AGENT_ID, toId = meId,
                            body = glance, sentAtMs = System.currentTimeMillis(), state = PeerMessage.State.DELIVERED))
                        endLive(messageId)
                        return@launch
                    }
                }

                // Гроші — до моделі: суму й людину читають правила, а не генерація.
                // Журнал — для розробника (logcat), не для екрана: латиницею, щоб
                // сторож кириличних літералів під /ui/ рахував лише копію інтерфейсу.
                val heard = runCatching {
                    withTimeoutOrNull(RULE_PRECHECK_TIMEOUT_MS) { money.hear(prompt) }
                }
                    .onFailure { android.util.Log.w("PhantomMoney", "money: parse failed, plain reply follows", it) }
                    .getOrNull()?.also { android.util.Log.i("PhantomMoney", "money: ${it::class.simpleName}") }
                val selfSaid = if (heard == null && !local.phantom.companion.assistant.AssistantMoneyCard.asks(prompt)) {
                    runCatching {
                        withTimeoutOrNull(RULE_PRECHECK_TIMEOUT_MS) { container.phantomSelf.answer(prompt) }
                    }.getOrNull()
                } else null
                (heard?.words(replyContext()) ?: selfSaid)?.let { said ->
                    (heard as? local.phantom.companion.data.MoneyAssistant.Reply.Drafted)
                        ?.takeIf { !it.cash }
                        ?.let { local.phantom.companion.data.DraftNote.of(it.draft, System.currentTimeMillis()) }
                        ?.let { local.phantom.companion.data.MoneyDraftNotes.put(container.applicationContext, messageId, it) }
                    container.commitments.bindLastNoteToMessage(container.applicationContext, messageId)
                    container.mesh.ingestDirect(
                        PeerMessage(
                            id = messageId,
                            fromId = PHANTOM_AGENT_ID,
                            toId = meId,
                            body = said,
                            sentAtMs = System.currentTimeMillis(),
                            state = PeerMessage.State.DELIVERED,
                        ),
                    )
                    voice?.done(said, messageId)
                    endLive(messageId)
                    if (heard != null) {
                        delay(local.phantom.companion.data.MoneyAssistant.READ_MS)
                        runCatching { money.act(heard) }
                    }
                    return@launch
                }

                // Гроші одним поглядом: баланс, перекази за тиждень і частки рахунків — без моделі.
                if (voice == null && local.phantom.companion.assistant.AssistantMoneyCard.asks(prompt)) {
                    val now = System.currentTimeMillis()
                    val cardBody = runCatching { moneyCard(now, meId) }
                        .onFailure { Log.w("AgencyManager", "money card: failed, plain reply follows", it) }
                        .getOrNull()
                    if (cardBody != null) {
                        container.mesh.ingestDirect(
                            PeerMessage(
                                id = messageId,
                                fromId = PHANTOM_AGENT_ID,
                                toId = meId,
                                body = cardBody,
                                sentAtMs = now,
                                state = PeerMessage.State.DELIVERED,
                            ),
                        )
                        endLive(messageId)
                        return@launch
                    }
                }

                // День відповідає карткою: пункти бере стрічка живими, модель тут нічого не додасть.
                if (voice == null && local.phantom.companion.assistant.AssistantDayCard.asks(prompt)) {
                    val now = System.currentTimeMillis()
                    container.mesh.ingestDirect(
                        PeerMessage(
                            id = messageId,
                            fromId = PHANTOM_AGENT_ID,
                            toId = meId,
                            body = local.phantom.companion.assistant.AssistantDayCard.body(now, java.time.ZoneId.systemDefault()),
                            sentAtMs = now,
                            state = PeerMessage.State.DELIVERED,
                        ),
                    )
                    endLive(messageId)
                    return@launch
                }

                val lower = prompt.lowercase()
                if (lower.contains("колегі") || lower.contains("рада агентів") || lower.contains("агентств") || lower.contains("розподіли між агент")) {
                    val noticeMsg = PeerMessage(
                        id = "msg_" + UUID.randomUUID().toString().take(12),
                        fromId = PHANTOM_AGENT_ID,
                        toId = meId,
                        body = str(R.string.agency_council_handoff),
                        sentAtMs = System.currentTimeMillis(),
                        state = PeerMessage.State.DELIVERED,
                    )
                    container.mesh.ingestDirect(noticeMsg)
                    voice?.done(noticeMsg.body, messageId)
                    endLive(messageId)
                    dispatchAgencyCouncil(PHANTOM_AGENT_ID, prompt, meId)
                    return@launch
                }

                // Памʼять у промпт кладе сам BrainChain (`grounded = true`): блок під бюджетом
                // 1500 токенів з мітками [m:id], а без опори на питання про власника — «не знаю».
                val aiHistory = DialogueTurns.history(
                    container.mesh.conversation(PHANTOM_AGENT_ID),
                    meId,
                    prompt,
                    assistantNotices,
                ) { m -> container.phantomVoice.forPrompt(m.id, m.body) }

                // Мовне правило окремим полем, а не в кінець systemPrompt: тоді воно справді останнє —
                // після блоку памʼяті й правил інструментів, що дописуються по дорозі.
                val intent = DialogueTurns.messengerIntent(
                    prompt = prompt,
                    history = aiHistory,
                    shown = product.offered,
                    all = messengerTools,
                    appLanguage = appLanguage(),
                    languageRule = languageRuleFor(prompt, aiHistory),
                    alias = { id -> product.resolve(id)?.id },
                )

                val rounds = ToolRounds(
                    pass = { turn -> stream(turn, messageId, voice) },
                    run = { said, calls -> calls.map { call -> runCall(messageId, said, call) } },
                    voice = voice,
                    endsTurn = { c -> product.resolve(c.name)?.let { product.approvalOf(it.id) } == Approval.PROPOSAL },
                )
                when (val outcome = rounds.answer(intent)) {
                    // Поразка з причиною, а не лист від імені моделі. Рядок лягає в
                    // розмову, але в історію моделі не повертається.
                    is ToolRounds.Outcome.Failed -> {
                        failed = true
                        val errText = words(outcome.cause)
                        voice?.failed(errText)
                        failAsk(messageId, errText, meId, questionId)
                    }
                    is ToolRounds.Outcome.Answer -> {
                        container.mesh.ingestDirect(
                            PeerMessage(
                                // Той САМИЙ id, що й у потоці: веб прибирає бульбашку, яка
                                // росла, рівно тоді, коли в стрічці зʼявився лист із цим
                                // id — без миті, коли видно обидві або жодної.
                                id = messageId,
                                fromId = PHANTOM_AGENT_ID,
                                toId = meId,
                                body = outcome.text,
                                sentAtMs = System.currentTimeMillis(),
                                state = PeerMessage.State.DELIVERED,
                            ),
                        )
                        voice?.done(outcome.text, messageId)
                        endLive(messageId)
                    }
                }
            } catch (t: Throwable) {
                failed = true
                Log.e("AgencyManager", "dispatchPhantomDialogue failed", t)
                val errText = words(t)
                voice?.failed(errText)
                failAsk(messageId, errText, meId, questionId)
            } finally {
                asks.settle(ticket, failed)
            }
        }
    }

    private fun personGlance(prompt: String): String? {
        val said = local.phantom.companion.assistant.AssistantPersonCard.who(prompt) ?: return null
        val people = container.peers.peers.value.filter { it.kind == local.phantom.companion.core.net.peer.PeerIdentity.Kind.PERSON }
        val who = local.phantom.companion.assistant.AssistantPersonCard.pick(said, people, { it.name }) { p ->
            container.mesh.conversation(p.id).lastOrNull()?.sentAtMs ?: 0L
        } ?: return null
        val sent = container.sentPayments().filter { it.first == who.id }.map { it.second }
        val g = local.phantom.companion.assistant.AssistantPersonCard.glance(
            peerId = who.id,
            name = who.name,
            conversation = container.mesh.conversation(who.id),
            isText = { body -> WebMessengerKinds.fields(body).let { it.size == 1 && it[WebMessengerKinds.KIND] == WebMessengerKinds.TEXT } },
            open = container.commitmentStore.listOpen(),
            sent = sent,
            cluster = local.phantom.companion.core.solana.SolanaLayer.state.value.cluster,
            nowMs = System.currentTimeMillis(),
        )
        return local.phantom.companion.assistant.AssistantPersonCard.body(g)
    }

    private fun moneyCard(nowMs: Long, meId: String): String {
        val state = local.phantom.companion.core.solana.SolanaLayer.state.value
        val people = container.peers.peers.value
            .filter { it.kind == local.phantom.companion.core.net.peer.PeerIdentity.Kind.PERSON }
            .map { it.id to it.name }
        val s = local.phantom.companion.assistant.AssistantMoneyCard.summary(
            me = container.mesh.selfIdFlow.value.ifBlank { meId },
            people = people,
            walletOf = container::sharedWalletOf,
            log = container.mesh.outbox.value,
            walletReady = local.phantom.companion.core.solana.SolanaLayer.installed && state.address != null,
            balances = state.balances,
            cluster = state.cluster,
            nowMs = nowMs,
        )
        return local.phantom.companion.assistant.AssistantMoneyCard.body(s)
    }

    /**
     * Один прохід моделі зі словами, що ростуть на екрані.
     *
     * [AIChunk.ErrorChunk] тут НЕ ковтається. Доти він лише писався в `Log.e`,
     * а людина бачила «модель не відповіла» — те саме речення і на відсутню
     * мережу, і на відсутню модель, і на відмову хмари за ключем. Три різні
     * поразки під одним словом — це те саме, що мовчання.
     */
    private suspend fun stream(
        intent: AIIntent.Text,
        messageId: String,
        voice: local.phantom.companion.self.VoiceSink? = null,
    ): ToolRounds.Pass {
        val fold = board.stream(messageId, onToken = { voice?.token(it) }) { container.aiRouter.complete(intent) }
        return ToolRounds.Pass(text = fold.text, calls = fold.calls, error = fold.failure, route = fold.route)
    }

    /** Один виклик моделі — через ворота ([runGuarded]); у живому рядку його імʼя. */
    private suspend fun runCall(messageId: String, said: String, activeToolCall: AIChunk.ToolCall): String {
        val tool = product.resolve(activeToolCall.name)
            ?: messengerTools.firstOrNull { it.id == activeToolCall.name }
        val liveToolId = tool?.let { product.backingId(it.id) ?: it.id } ?: activeToolCall.name
        pushLive(messageId, Phase.TOOL, said, liveToolId)
        if (tool == null) return str(R.string.agency_tool_missing, activeToolCall.name)
        val parsedArgs = runCatching { Json.parseToJsonElement(activeToolCall.arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return when (val res = runGuarded(tool, parsedArgs)) {
            is ToolResult.Ok -> res.output.ifBlank { res.structured?.toString() ?: "OK" }
            is ToolResult.Err -> res.message
            is ToolResult.PermissionDenied -> str(R.string.agency_tool_denied, res.permission.name)
            is ToolResult.Cancelled -> str(R.string.agency_tool_cancelled)
        }
    }

    /**
     * У групі помічник не відповідає: будь-який маршрут моделі несе чужі
     * рядки з іменами з апарата без згоди учасників. Лише чесний рядок тому,
     * хто покликав, — і нічого з групи в параметрах.
     */
    fun refuseGroupAsk(groupId: String) {
        val messageId = "msg_" + UUID.randomUUID().toString().take(12)
        beginLive(groupId, messageId)
        failLive(messageId, str(R.string.ai_group_unavailable))
    }

    /**
     * Мультиагентна сесія Колегії Агентів (Agency Deliberation).
     * Послідовно запускає 4 агентів:
     * 1. 🏛️ System Designer (Architect)
     * 2. 🛡️ Red Team / Challenger
     * 3. ⚡ Performance Profiler
     * 4. 🔮 Lead Synthesizer
     */
    fun dispatchAgencyCouncil(sessionId: String, userTask: String, meId: String) {
        scope.launch {
            try {
                val task = AssistantInput.promptOf(userTask)?.trim() ?: run {
                    if (!AssistantInput.isBatchTail(userTask)) post(sessionId, PHANTOM_AGENT_ID, str(R.string.assistant_sees_text_only))
                    return@launch
                }

                val contextHistory = StringBuilder()
                contextHistory.append("ЗАВДАННЯ ОПЕРАТОРА: ").append(task).append("\n\n")

                var spoken = 0
                for (agent in ALL_AGENTS) {
                    val agentPrompt = """
                        $contextHistory
                        ---
                        Твоя черга виступити як ${str(agent.name)} (${agent.avatar} ${str(agent.badge)}).
                        Надай свій професійний висновок щодо вищеописаного завдання та попередніх реплік.
                    """.trimIndent()

                    val intent = AIIntent.Text(
                        prompt = agentPrompt,
                        systemPrompt = agent.systemPrompt,
                        maxTokens = 800,
                        temperature = 0.7f,
                    )

                    val sb = StringBuilder()
                    var agentResult: String? = null
                    var failure: Throwable? = null

                    runCatching {
                        container.aiRouter.complete(intent).collect { chunk ->
                            when (chunk) {
                                is AIChunk.Token -> sb.append(chunk.text)
                                is AIChunk.Final -> agentResult = chunk.text
                                is AIChunk.ErrorChunk -> failure = chunk.cause
                                else -> Unit
                            }
                        }
                    }.onFailure {
                        Log.w("AgencyManager", "Agent ${agent.id} inference failed", it)
                        failure = it
                    }

                    val text = agentResult?.takeIf { it.isNotBlank() } ?: sb.toString().trim()
                    if (text.isBlank()) {
                        // Заготовлена «думка» під іменем агента — вигадка від чужої особи,
                        // і читається вона як висновок фахівця.
                        post(sessionId, PHANTOM_AGENT_ID, str(R.string.agency_council_halted, str(agent.name), failure?.let { words(it) } ?: str(R.string.agency_no_reply)))
                        return@launch
                    }

                    spoken++
                    contextHistory.append("\n[${agent.avatar} ${str(agent.name)}]:\n").append(text).append("\n")
                    post(
                        sessionId,
                        agent.id,
                        "${agent.avatar} **${str(agent.name)}** [${str(agent.badge)}]:\n\n$text",
                    )
                }

                post(sessionId, PHANTOM_AGENT_ID, str(R.string.agency_council_finished, spoken))

            } catch (t: Throwable) {
                Log.e("AgencyManager", "dispatchAgencyCouncil failed", t)
            }
        }
    }

    /**
     * Другі двері до інструментів. До 12.09 цей шлях кликав `tool.execute`
     * напряму: без воріт, без сторожового таймера, без запобіжника, без сліду,
     * і сам собі видавав `approvedPermissions = tool.permissions`. Тобто
     * розмова у веб-месенджері виконувала що завгодно, поки Стрічка питала.
     * Ворота мусять бути одні на всі двері. Дзвінок PHANTOM
     * ([local.phantom.companion.self.AppCallHands]) іде цими ж дверима.
     */
    internal suspend fun runGuarded(
        tool: local.phantom.companion.core.tool.Tool,
        args: JsonObject,
    ): ToolResult {
        val preview = args.toString().take(160)
        val outcome = container.toolPermissionGate.check(tool, preview)
        if (outcome is GateOutcome.Denied) {
            container.toolDecisionDiary.write(
                DecisionEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolId = tool.id,
                    args = args.toString().take(500),
                    outcome = "denied",
                    outputDigest = outcome.reason.take(200),
                    agentId = PHANTOM_AGENT_ID,
                    agentDepth = 0,
                    risk = tool.risk.name.lowercase(),
                    decision = ConsentPath.DENIED.wire,
                ),
            )
            return ToolResult.Err(code = "denied", message = outcome.reason, durationMs = 0L)
        }
        val via = (outcome as GateOutcome.Granted).via
        val result = toolExecutor.run(
            tool = tool,
            args = args,
            context = ToolContext(
                appContext = container.applicationContext,
                operatorId = PHANTOM_AGENT_ID,
                requestId = UUID.randomUUID().toString(),
                agentDepth = 0,
                approvedPermissions = outcome.approved,
            ),
            emit = {},
        )
        container.toolDecisionDiary.write(
            DecisionEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = tool.id,
                args = args.toString().take(500),
                outcome = when (result) {
                    is ToolResult.Ok -> "ok"
                    is ToolResult.Err -> "err:${result.code}"
                    is ToolResult.PermissionDenied -> "denied"
                    is ToolResult.Cancelled -> "cancelled"
                },
                outputDigest = when (result) {
                    is ToolResult.Ok -> result.output.take(200)
                    is ToolResult.Err -> result.message.take(200)
                    else -> ""
                },
                agentId = PHANTOM_AGENT_ID,
                agentDepth = 0,
                risk = tool.risk.name.lowercase(),
                decision = via.wire,
            ),
        )
        return result
    }

    private fun post(toId: String, fromId: String, body: String) {
        container.mesh.ingestDirect(
            PeerMessage(
                id = "msg_" + UUID.randomUUID().toString().take(12),
                fromId = fromId,
                toId = toId,
                body = body,
                sentAtMs = System.currentTimeMillis(),
                state = PeerMessage.State.DELIVERED,
            )
        )
    }

    /**
     * Усе, що помічник каже в розмову, — мовою месенджера, а не системи
     * ([ReplyLanguage]): на англійському телефоні без вибору мови для
     * Nightpost відповідь лишається українською, як і екран довкола неї.
     */
    private fun replyContext(): android.content.Context =
        local.phantom.companion.core.data.locale.ReplyLanguage.context(container.applicationContext)

    private fun str(resId: Int, vararg args: Any): String =
        replyContext().getString(resId, *args)

    private fun appLanguage(): String = local.phantom.companion.core.data.locale.ReplyLanguage.locale(
        local.phantom.companion.core.data.locale.ReplyLanguage.picked(container.applicationContext),
    ).language

    /**
     * Мова відповіді: остання репліка людини, а «ok» і «👍» — мовою її попередніх реплік,
     * і лише потім мовою застосунку. Без цього «ok» посеред англійської розмови
     * перекидало відповідь в українську.
     */
    private fun languageRuleFor(latest: String, history: List<AIIntent.Text.Turn>): String =
        SystemPrompts.languageRule(
            latest,
            appLanguage(),
            earlier = history.filter { it.role == AIIntent.Text.Role.USER }.map { it.content },
        )
}

private const val RULE_PRECHECK_TIMEOUT_MS = 3_000L
