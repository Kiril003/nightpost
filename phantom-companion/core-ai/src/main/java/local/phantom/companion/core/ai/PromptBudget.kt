package local.phantom.companion.core.ai

/**
 * Trims the system prompt to what the turn can actually use.
 *
 * The full brief is ~12 300 tokens — 35 widget schemas, the canvas spec, the
 * report spec, the agent protocol — and every one of them was sent for «котра
 * година». That is not just cost: a model that reads a catalogue before it
 * reads the question answers the catalogue. Sections are dropped ONLY when the
 * turn plainly cannot reach them, and the drop is always recoverable — the next
 * turn re-evaluates from scratch.
 *
 * The cue lists are a budget gate, not a decision about the answer: they read
 * the operator's sentence before any tool has run. What keeps that honest is
 * that only the FIRST turn is trimmed — every turn after a tool result is built
 * by StreamChainRunner from the untrimmed brief, so the moment data lands the
 * whole catalogue is back.
 */
object PromptBudget {

    /**
     * Widget ids that carry a heavy spec and are almost never the answer. The
     * catalogue is numbered, so a whole-section cut would eat its neighbours —
     * these are removed ENTRY by entry, from the id to the next numbered line.
     */
    private val HEAVY = mapOf(
        "canvas" to listOf(
            "полотн", "canvas", "намалюй", "діаграм", "схем", "візуаліз", "графік",
            "інтерактив", "прототип", "макет", "html",
        ),
        "photo-hero" to listOf("фото", "зображен", "картинк", "знімок", "скрін", "photo"),
        "report" to listOf(
            "звіт", "доповід", "аналітик", "розділ", "документ", "порівняльн", "дослід",
        ),
        "code-runner" to listOf("код", "скрипт", "порахуй", "обчисл", "python", "code"),
    )

    private val AGENT_CUES = listOf(
        "дослід", "збери", "проаналізуй", "порівняй", "знайди все", "моніторь",
        "відстеж", "щогодини", "багатокрок",
    )

    /** Words that turn a mention of a form into an order to emit it. */
    private val ORDERS = Regex("→|поверни|використовуй|виклич|обирай|зібра|збери")

    private val SENTENCE = Regex("(?<=[.!?])\\s+")

    fun trim(prompt: String, userText: String, hasAttachments: Boolean = false): String {
        val t = userText.lowercase()
        var out = prompt
        HEAVY.forEach { (id, cues) ->
            val wanted = cues.any { stemHit(t, it) } ||
                (id == "photo-hero" && hasAttachments)
            if (wanted) return@forEach
            val cut = dropEntry(out, id)
            // The order must die with the schema. Left alone, the classifier
            // line still says «багаторозділовий розбір → report» on a turn
            // whose report schema is gone — and the model invents the shape.
            out = if (cut.length == out.length) cut else dropOrders(cut, id)
        }
        if (AGENT_CUES.none { stemHit(t, it) }) out = dropSection(out, "АГЕНТИ (sub-agents):")
        return out
    }

    /** A cue is a word STEM, not any substring: «код» hits «кодом», not «закодував». */
    private fun stemHit(text: String, cue: String): Boolean {
        var at = text.indexOf(cue)
        while (at >= 0) {
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            at = text.indexOf(cue, at + 1)
        }
        return false
    }

    /**
     * Removes every remaining sentence that ORDERS a form whose schema was
     * just cut, plus the section heading left orphaned above it. A sentence
     * carrying a schema of its own is never touched: a half-cut schema is the
     * thing this file exists to avoid.
     */
    private fun dropOrders(prompt: String, id: String): String {
        val named = Regex(
            "(?<![\\p{L}\\p{N}.-])" + Regex.escape(id) + "(?![\\p{L}\\p{N}-])(?!\\.\\p{L})"
        )
        return prompt.lines().mapNotNull { line ->
            if (!named.containsMatchIn(line)) return@mapNotNull line
            if (line.trimEnd().endsWith(":")) return@mapNotNull null
            val clauses = line.split(";").map { clause ->
                if (!named.containsMatchIn(clause)) return@map clause
                clause.split(SENTENCE)
                    .filterNot {
                        named.containsMatchIn(it) && ORDERS.containsMatchIn(it) &&
                            "\"type\":\"" !in it
                    }
                    .joinToString(" ")
            }
            clauses.filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(";")
        }.joinToString("\n")
    }

    /**
     * Removes one numbered catalogue entry: from the line carrying `"type":"id"`
     * back to its number, forward to the next numbered line. Anything unexpected
     * leaves the prompt alone — a half-cut schema is worse than a long one.
     */
    private fun dropEntry(prompt: String, id: String): String {
        val marker = "\"type\":\"$id\""
        val at = prompt.indexOf(marker)
        if (at < 0) return prompt
        val start = prompt.lastIndexOf('\n', at).let { if (it < 0) return prompt else it + 1 }
        if (!Regex("^\\d+\\.").containsMatchIn(prompt.substring(start, at))) return prompt
        var i = at
        while (true) {
            val nl = prompt.indexOf('\n', i)
            if (nl < 0) return prompt.removeRange(start, prompt.length)
            val lineStart = nl + 1
            val lineEnd = prompt.indexOf('\n', lineStart).let { if (it < 0) prompt.length else it }
            val line = prompt.substring(lineStart, lineEnd)
            if (Regex("^\\d+\\.").containsMatchIn(line) || (line.isNotBlank() && !line.first().isWhitespace() && line.trimEnd().endsWith(":"))) {
                return prompt.removeRange(start, lineStart)
            }
            i = lineStart
        }
    }

    private fun dropSection(prompt: String, heading: String): String {
        val start = prompt.indexOf(heading)
        if (start < 0) return prompt
        val lines = prompt.substring(start).lines()
        var consumed = lines.first().length + 1
        for (i in 1 until lines.size) {
            val l = lines[i]
            val isNextHeading = l.isNotBlank() &&
                !l.first().isWhitespace() &&
                !l.startsWith("-") &&
                !l.startsWith("{") &&
                l.trimEnd().endsWith(":")
            if (isNextHeading) break
            consumed += l.length + 1
        }
        return prompt.removeRange(start, (start + consumed).coerceAtMost(prompt.length))
    }
}
