package local.phantom.companion.core.ai.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every `{"type":"tool-call",…}` object in one finished cloud answer, and the
 * prose left once they are taken out.
 *
 * This route declares no `functionDeclarations` (see [GeminiCloudRoute]'s
 * buildRequest: a replayed functionCall needs a thought_signature that a turn
 * we synthesise can never carry), so the provider can never send a native
 * multi-call turn. The one place several calls CAN arrive is the text protocol
 * the system prompt already teaches — and the reader on the other side
 * (AssistantJsonExtractor) takes the first balanced object only, so a model
 * that asked for three tools had two of them silently dropped.
 *
 * Deliberately stricter than "contains tool-call": same rules as
 * a wrong-typed `args` is not guessed around, because
 * nothing downstream validates args against the tool's schema.
 */
internal object ToolBatchScan {

    data class Call(val name: String, val args: String)

    data class Scan(val calls: List<Call>, val prose: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun of(raw: String): Scan {
        if (!raw.contains("tool-call")) return Scan(emptyList(), raw)
        val calls = mutableListOf<Call>()
        val kept = StringBuilder()
        var cursor = 0
        while (true) {
            val span = objectSpan(raw, cursor) ?: break
            val call = callOf(raw.substring(span.first, span.last + 1))
            if (call != null) {
                calls += call
                kept.append(raw, cursor, span.first)
            } else {
                kept.append(raw, cursor, span.last + 1)
            }
            cursor = span.last + 1
        }
        if (calls.isEmpty()) return Scan(emptyList(), raw)
        kept.append(raw, cursor, raw.length)
        return Scan(calls, cleanProse(kept.toString()))
    }

    private fun callOf(objectText: String): Call? {
        val parsed = runCatching { json.parseToJsonElement(objectText).jsonObject }.getOrNull()
            ?: return null
        if (parsed["type"]?.jsonPrimitive?.contentOrNull != "tool-call") return null
        val name = parsed["tool"]?.jsonPrimitive?.contentOrNull?.trim()
        if (name.isNullOrEmpty()) return null
        val args = parsed["args"]
        val rendered = when {
            args == null -> "{}"
            args is JsonObject -> args.toString()
            else -> return null
        }
        return Call(name, rendered)
    }

    /** The fences the objects sat in are left behind by the removal, and a
     *  bare ``` line reads as a broken code block in the bubble. */
    private fun cleanProse(s: String): String = s
        .lineSequence()
        .filterNot { it.trim().let { t -> t == "```" || t == "```json" } }
        .joinToString("\n")
        .trim()

    /** Span of the first balanced `{…}` at or after [from], string-aware so a
     *  brace inside `"…{…}…"` cannot skew the depth count. */
    private fun objectSpan(s: String, from: Int): IntRange? {
        val start = s.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return start..i
                }
            }
        }
        return null
    }
}
