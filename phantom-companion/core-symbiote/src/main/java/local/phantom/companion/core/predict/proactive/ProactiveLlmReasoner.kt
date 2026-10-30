package local.phantom.companion.core.predict.proactive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The real LLM judge behind [ProactiveReasoner]. The model — reached through the
 * injected [generate] seam — alone decides whether anything is worth interrupting
 * the operator for. Parsing is fail-safe: any ambiguity stays SILENT.
 */
class ProactiveLlmReasoner(
    private val generate: suspend (prompt: String) -> String,
    private val persona: String = DEFAULT_PROACTIVE_PERSONA,
) : ProactiveReasoner {

    override suspend fun judge(
        frame: ProactiveFrame,
        recentDialogue: String,
        recentlySaid: List<String>,
    ): ProactiveUtterance? = parse(generate(buildPrompt(frame, recentDialogue, recentlySaid)))

    private fun buildPrompt(
        frame: ProactiveFrame,
        recentDialogue: String,
        recentlySaid: List<String>,
    ): String {
        val signals = frame.signals.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            .ifBlank { "(немає сигналів)" }
        val alreadySaid = recentlySaid.joinToString("\n") { "- $it" }
            .ifBlank { "(нічого)" }
        val dialogue = recentDialogue.ifBlank { "(тиша)" }
        return buildString {
            appendLine(persona)
            appendLine()
            appendLine("ПОТОЧНІ СИГНАЛИ:")
            appendLine(signals)
            appendLine()
            appendLine("ОСТАННІЙ ДІАЛОГ:")
            appendLine(dialogue)
            appendLine()
            appendLine("ТИ ВЖЕ ЦЕ КАЗАВ (не повторюйся):")
            appendLine(alreadySaid)
            appendLine()
            appendLine("Ти — єдиний суддя. Немає списку правил. Виріши сам, чи є зараз щось")
            appendLine("справді варте того, щоб урвати оператора без запиту. Якщо ні — відповідай")
            appendLine("одним словом: SILENT.")
            appendLine("Якщо так — коротка репліка українською, голосом Nightpost, у вигляді JSON:")
            append("""{"say":"<коротка репліка>","tool":"<optional tool id or omit>","urgency":"AMBIENT|NOTABLE|CRITICAL"}""")
        }
    }

    private fun parse(reply: String): ProactiveUtterance? {
        val trimmed = reply.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.trim('"', '\'', '.', '!', '`').equals("SILENT", ignoreCase = true)) return null

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = trimmed.substring(start, end + 1)

        val decoded = runCatching { LENIENT.decodeFromString<Reply>(json) }.getOrNull() ?: return null
        val say = decoded.say.trim()
        if (say.isBlank()) return null

        val urgency = when (decoded.urgency?.trim()?.uppercase()) {
            "AMBIENT" -> Urgency.AMBIENT
            "CRITICAL" -> Urgency.CRITICAL
            "NOTABLE" -> Urgency.NOTABLE
            else -> Urgency.NOTABLE
        }
        return ProactiveUtterance(say, decoded.tool?.trim()?.ifBlank { null }, urgency)
    }

    @Serializable
    private data class Reply(val say: String = "", val tool: String? = null, val urgency: String? = null)

    companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true }

        const val DEFAULT_PROACTIVE_PERSONA: String =
            "Ти — Nightpost, симбіот оператора. Це твій проактивний канал: ти говориш без запиту, " +
                "лише коли це справді корисно. За замовчуванням ти мовчиш. Порожня балаканина гірша за тишу."
    }
}
