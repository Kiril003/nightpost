package local.phantom.companion.ui.messenger

import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIIntent.Text.Role
import local.phantom.companion.core.ai.AIIntent.Text.Turn
import local.phantom.companion.core.ai.ToolDefinition
import local.phantom.companion.core.ai.prompt.NightpostPrompt
import local.phantom.companion.core.ai.route.OnDeviceToolRules
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolPromptInjector

/** Що модель бачить із розмови з помічником. */
internal object DialogueTurns {

    const val LIMIT = 10

    /** Рядок без відповіді, свіжіший за це, — частина того самого питання, а не забуте старе. */
    const val UNANSWERED_FRESH_MS = 120_000L

    /** Стеля блоку `[TOOLS: …]`: він їде цілим у кожен запит, зокрема в кожен запит на сервер. */
    const val TOOLS_BLOCK_MAX_CHARS = 14_000
    private const val TOOL_DESCRIPTION_MAX = 300

    /** Стеля блоку інструментів у чаті Nightpost, у байтах: український опис — по два байти на літеру. */
    const val MESSENGER_TOOLS_MAX_BYTES = 3_000
    private const val MESSENGER_DESCRIPTION_MAX = 100

    /**
     * Блок `[TOOLS: …]`, який обіцяє SystemPrompts.DEFAULT_PHANTOM: без нього модель не знає жодного id.
     * Не весь реєстр — інструмент, який правила впізнали в питанні, і ті, яких навчає сам припис.
     */
    fun systemPrompt(base: String, tools: List<Tool>, prompt: String, rules: OnDeviceToolRules = OnDeviceToolRules()): String {
        val shown = promptTools(base, tools, prompt, rules)
        return if (shown.isEmpty()) base else base + "\n\n" + ToolPromptInjector.render(shown)
    }

    /** Хід у чаті Nightpost: коротка настанова, до двох десятків інструментів і мовне правило. */
    fun messengerIntent(
        prompt: String,
        history: List<Turn>,
        shown: List<Tool>,
        all: List<Tool>,
        appLanguage: String,
        languageRule: String,
        rules: OnDeviceToolRules = OnDeviceToolRules(),
        alias: (String) -> String? = { null },
    ): AIIntent.Text {
        val base = NightpostPrompt.forLanguage(appLanguage)
        val first = askedMessenger(shown, all, prompt, rules, alias)
        val ranked = listOfNotNull(first) + shown
        val kept = fit(ranked, MESSENGER_DESCRIPTION_MAX) { ToolPromptInjector.render(it).toByteArray(Charsets.UTF_8).size <= MESSENGER_TOOLS_MAX_BYTES }
        return AIIntent.Text(
            prompt = prompt,
            systemPrompt = if (kept.isEmpty()) base else base + "\n\n" + ToolPromptInjector.render(kept),
            history = history,
            tools = all.map { ToolDefinition(it.id, it.description, it.argsSchema) },
            maxTokens = 1024,
            temperature = 0.7f,
            grounded = true,
            languageRule = languageRule,
        )
    }

    private fun promptTools(base: String, tools: List<Tool>, prompt: String, rules: OnDeviceToolRules): List<Tool> {
        val taught = tools.filter { '.' in it.id }
            .mapNotNull { tool -> Regex("""(?<![\w.])${Regex.escape(tool.id)}(?!\w)""").find(base)?.let { it.range.first to tool } }
            .sortedWith(compareBy<Pair<Int, Tool>>({ it.first }, { it.second.id }))
            .map { it.second }
        return fit(listOfNotNull(asked(tools, prompt, rules)) + taught, TOOL_DESCRIPTION_MAX) { ToolPromptInjector.render(it).length <= TOOLS_BLOCK_MAX_CHARS }
    }

    private fun asked(tools: List<Tool>, prompt: String, rules: OnDeviceToolRules): Tool? =
        rules.toolFor(prompt)?.let { id -> tools.firstOrNull { it.id == id } }

    private fun askedMessenger(
        shown: List<Tool>,
        all: List<Tool>,
        prompt: String,
        rules: OnDeviceToolRules,
        alias: (String) -> String?,
    ): Tool? {
        val probe = AIIntent.Text(
            prompt = prompt,
            tools = (shown + all).distinctBy { it.id }.map { ToolDefinition(it.id, it.description, it.argsSchema) },
        )
        val id = rules.firstCall(probe)?.name ?: rules.toolFor(prompt) ?: return null
        val mapped = alias(id)
        return shown.firstOrNull { it.id == id || (mapped != null && it.id == mapped) }
            ?: all.firstOrNull { it.id == id || (mapped != null && it.id == mapped) }
    }

    private fun fit(candidates: List<Tool>, descriptionMax: Int, fits: (List<Tool>) -> Boolean): List<Tool> {
        val kept = mutableListOf<Tool>()
        for (tool in candidates.distinctBy { it.id }) {
            val short = clipped(tool, descriptionMax)
            if (fits(kept + short)) kept += short
        }
        return kept
    }

    private fun clipped(tool: Tool, max: Int): Tool =
        if (tool.description.length <= max) tool
        else object : Tool by tool {
            override val description: String = tool.description.take(max).trimEnd() + "…"
        }

    /**
     * Розмова до цього питання. Саме питання міст уже поклав у журнал, а модель
     * отримує його окремо — двічі воно читалось як повтор. Службові рядки
     * помічника ([notice]) — не його слова, і в історію моделі не йдуть.
     *
     * Питання з поразкою та питання, що давно лишились без відповіді, теж не
     * йдуть: модель відповідала на них замість нового — на «Hi» розповідала,
     * чому море солоне. Свіжий рядок без відповіді лишається: це серія з
     * кількох листів, і відповідь на неї ще йде.
     *
     * Вкладення йдуть лише підписом чи розшифровкою ([AssistantInput]): опис
     * несе ключ шифру, а без слів лист не йде зовсім.
     */
    fun history(
        raw: List<PeerMessage>,
        meId: String,
        prompt: String,
        notice: (String) -> Boolean,
        voice: (PeerMessage) -> String,
    ): List<Turn> {
        val log = raw.mapNotNull { m -> AssistantInput.promptOf(m.body)?.let { if (it == m.body) m else m.copy(body = it) } }
        val asked = log.indexOfLast { it.fromId == meId }.takeIf { it >= 0 && log[it].body.trim() == prompt.trim() }
        val said = log.filterIndexed { i, m ->
            i != asked && (if (m.fromId == meId) m.state != PeerMessage.State.FAILED else !notice(m.body))
        }
        val answered = said.indexOfLast { it.fromId != meId }
        val askedAt = asked?.let { log[it].sentAtMs } ?: said.lastOrNull()?.sentAtMs ?: 0L
        return said.filterIndexed { i, m -> m.fromId != meId || i < answered || askedAt - m.sentAtMs <= UNANSWERED_FRESH_MS }
            .takeLast(LIMIT)
            .map { m -> if (m.fromId == meId) Turn(Role.USER, m.body) else Turn(Role.ASSISTANT, voice(m)) }
            .dropWhile { it.role == Role.ASSISTANT }
    }

    /** Хід після інструментів: питання, потім виклики, потім їхні результати — у тому самому порядку. */
    fun followUp(
        intent: AIIntent.Text,
        said: String,
        calls: List<AIChunk.ToolCall>,
        results: List<String>,
    ): AIIntent.Text = intent.copy(
        prompt = "",
        history = intent.history +
            listOfNotNull(intent.prompt.takeIf { it.isNotBlank() }?.let { Turn(Role.USER, it) }) +
            Turn(Role.ASSISTANT, said, toolCalls = calls.map { AIIntent.Text.ToolCall(it.callId, it.name, it.arguments) }) +
            calls.zip(results) { call, result -> Turn(Role.TOOL, result, toolCallId = call.callId) },
    )
}
