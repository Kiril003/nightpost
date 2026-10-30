package local.phantom.companion.core.data.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns recent conversation into durable facts: the local model extracts lasting
 * statements about the operator, each is embedded and folded into [MemoryFactStore]
 * (near-duplicates reinforce). Runs off the hot path — on idle / end of session.
 * Parsing is fail-safe: any ambiguity yields no facts rather than garbage memory.
 *
 * Факт ще й приписується тому, про кого він: [entities] заводить або впізнає
 * людину/річ/місце, а `entity_id` факту стає на неї посиланням — інакше
 * «сестра» і «Марта» лишаються двома незнайомими словами.
 */
class MemoryConsolidator(
    private val store: MemoryFactStore,
    private val generate: suspend (prompt: String) -> String,
    private val embed: suspend (text: String) -> IntArray?,
    private val entities: MemoryEntityStore? = null,
    private val persona: String = DEFAULT_PERSONA,
) {

    suspend fun consolidate(recentDialogue: String, nowMs: Long): Int {
        val dialogue = recentDialogue.trim()
        if (dialogue.isEmpty()) return 0
        val facts = parse(generate(buildPrompt(dialogue)))
        val spoken = FactMention.tokens(dialogue)
        var stored = 0
        for (f in facts) {
            val text = f.text.trim()
            if (text.length < 4) continue
            val category = f.category.ifBlank { "fact" }
            store.remember(
                text,
                category,
                "conversation",
                embed(text),
                nowMs,
                entityId = resolveEntity(f.about, category, spoken, nowMs),
            )
            stored++
        }
        return stored
    }

    /**
     * Кого факт стосується. Нову сутність заводимо лише там, де рід відомий із
     * категорії: вгадати рід не можна, бо «Марта»-людина і «Марта»-річ уже
     * ніколи не зійдуться в одну. Факт іншої категорії тільки чіпляється до вже
     * знайомого імені.
     *
     * Імені, якого не було в розмові, не існує: модель охоче доповість «сестра
     * Марта» там, де ніхто Марти не називав, а вигадана людина в памʼяті гірша
     * за відсутню.
     */
    private suspend fun resolveEntity(
        about: String,
        category: String,
        spoken: List<String>,
        nowMs: Long,
    ): String? {
        val known = entities ?: return null
        val name = about.trim()
        if (name.isEmpty() || name.length > NAME_CAP) return null
        val named = FactMention.tokens(name)
        if (named.isNotEmpty() && !named.all { n -> spoken.any { FactMention.matches(it, n) } }) return null
        val kind = KINDS[category.lowercase()]
            ?: return known.mentioned(named).firstOrNull()?.entity?.id
        return known.observe(name, kind, nowMs).ifEmpty { null }
    }

    private fun buildPrompt(dialogue: String): String = buildString {
        appendLine(persona)
        appendLine()
        appendLine("РОЗМОВА:")
        appendLine(dialogue)
        appendLine()
        appendLine("Поверни JSON-масив довговічних фактів (порожній [], якщо таких нема):")
        append(
            """[{"text":"<короткий факт українською>","category":"preference|vehicle|person|place|routine|fact",""" +
                """"about":"<чиє це імʼя або назва, рівно як прозвучало в розмові; порожньо, якщо факт про самого оператора>"}]""",
        )
    }

    private fun parse(reply: String): List<Fact> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching { LENIENT.decodeFromString<List<Fact>>(reply.substring(start, end + 1)) }
            .getOrDefault(emptyList())
            .filter { it.text.isNotBlank() }
    }

    @Serializable
    private data class Fact(val text: String = "", val category: String = "fact", val about: String = "")

    companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val NAME_CAP = 48

        /** Категорії, що самі називають рід сутності. Решта роду не знає. */
        private val KINDS: Map<String, String> = mapOf(
            "person" to MemoryEntityEntity.PERSON,
            "place" to MemoryEntityEntity.PLACE,
            "vehicle" to MemoryEntityEntity.THING,
        )

        const val DEFAULT_PERSONA: String =
            "Ти аналізуєш розмову оператора з Nightpost і виділяєш ДОВГОВІЧНІ факти про оператора — " +
                "те, що варто памʼятати місяцями: уподобання, авто, люди, рутини, місця, важливі рішення. " +
                "НЕ виділяй ефемерне: погоду, разові дії, поточний стан, тривоги."
    }
}
