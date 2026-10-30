package local.phantom.companion.ui.messenger

import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.ai.route.OnDeviceToolRules
import local.phantom.companion.self.VoiceSink

/**
 * Одне питання до помічника: прохід моделі, її виклики, результати назад у
 * модель — і межа, скільки разів питати знову.
 *
 * Сервер Nightpost рахує кожен запит у денний ліміт людини, тому після
 * інструментів він отримує один повторний запит ([SERVER_FOLLOW_UPS]), свій
 * ключ — до [SystemPrompts.MAX_TOOL_ROUNDS]. Що просять понад межу, не
 * виконується: людина бачить результати, які вже зібрано, а не мовчання.
 */
internal class ToolRounds(
    private val pass: suspend (AIIntent.Text) -> Pass,
    private val run: suspend (said: String, calls: List<AIChunk.ToolCall>) -> List<String>,
    private val voice: VoiceSink? = null,
    private val rules: OnDeviceToolRules = OnDeviceToolRules(),
    private val endsTurn: (AIChunk.ToolCall) -> Boolean = { false },
) {

    class Pass(
        val text: String,
        val calls: List<AIChunk.ToolCall>,
        val error: Throwable?,
        val route: Route? = null,
    )

    sealed interface Outcome {
        data class Answer(val text: String) : Outcome

        /** `cause == null` — модель домовчала до кінця. */
        data class Failed(val cause: Throwable?) : Outcome
    }

    suspend fun answer(first: AIIntent.Text): Outcome {
        var turn = first
        var spoken = pass(turn)
        var server = false
        var rounds = 0
        val collected = mutableListOf<String>()
        while (true) {
            spoken.error?.let { return Outcome.Failed(it) }
            if (spoken.route == Route.NIGHTPOST_SERVER) server = true
            if (spoken.calls.isEmpty()) break
            if (rounds >= if (server) SERVER_FOLLOW_UPS else SystemPrompts.MAX_TOOL_ROUNDS) {
                return said(listOfNotNull(spoken.text.takeIf { it.isNotBlank() }, rules.outcomes(collected)).joinToString("\n\n"))
            }
            rounds++
            voice?.nextRound()
            if (rounds == 1) voice?.acting()
            val results = run(spoken.text, spoken.calls)
            collected += results
            if (spoken.calls.any(endsTurn)) {
                return said(listOfNotNull(spoken.text.takeIf { it.isNotBlank() }, rules.outcomes(collected)).joinToString("\n\n"))
            }
            turn = DialogueTurns.followUp(turn, spoken.text, spoken.calls, results)
            spoken = pass(turn)
        }
        return said(spoken.text)
    }

    private fun said(text: String): Outcome = if (text.isBlank()) Outcome.Failed(null) else Outcome.Answer(text)

    companion object {
        const val SERVER_FOLLOW_UPS = 1
    }
}
