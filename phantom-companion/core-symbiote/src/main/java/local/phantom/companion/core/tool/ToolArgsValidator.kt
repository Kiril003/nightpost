package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks a tool call's arguments against the tool's own [Tool.argsSchema].
 *
 * The schema was rendered into the system prompt and then never read again:
 * every tool parsed its own args, so a missing required field or a wrong type
 * arrived as a default and the tool ran on arguments the model never sent. The
 * operator saw a confident answer computed from nothing.
 */
object ToolArgsValidator {

    /**
     * Schema values are `"<type>"` or `"<type>?"`, where `?` marks optional.
     * Two tools trail prose after the type, so only the first word is read.
     */
    private fun typeOf(raw: String): Pair<String, Boolean> {
        val head = raw.trim().substringBefore(' ').substringBefore('—').trim()
        val optional = head.endsWith('?')
        return head.removeSuffix("?").lowercase() to optional
    }

    private fun matches(type: String, value: kotlinx.serialization.json.JsonElement): Boolean =
        when (type) {
            "any", "" -> true
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            // The wire has no types: a local model writes 55.7 as "55.7" and a
            // cloud one as 55.7, and both mean the same number. Accept either
            // rather than reject a call the tool would have parsed fine.
            "number" -> value is JsonPrimitive &&
                (value.contentOrNull?.trim()?.toDoubleOrNull() != null)
            "boolean" -> value is JsonPrimitive &&
                value.contentOrNull?.trim()?.lowercase() in setOf("true", "false")
            "string" -> value is JsonPrimitive && value.contentOrNull != null
            else -> true
        }

    /**
     * @return human-readable violations, empty when the call is admissible.
     *   Unknown keys are deliberately not violations — models pad calls with
     *   commentary fields, and refusing those would break working tools.
     */
    fun validate(schema: JsonObject, args: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        for ((key, decl) in schema) {
            val raw = (decl as? JsonPrimitive)?.contentOrNull ?: continue
            val (type, optional) = typeOf(raw)
            val value = args[key]
            if (value == null || value is JsonNull) {
                if (!optional) problems += "не вистачає обовʼязкового '$key' (${typeWord(type)})"
                continue
            }
            if (!matches(type, value)) {
                problems += "'$key' очікує ${typeWord(type)}, а прийшло ${describe(value)}"
            }
        }
        return problems
    }

    private fun describe(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonObject -> "обʼєкт"
        is JsonArray -> "список"
        else -> "«${value.jsonPrimitive.contentOrNull?.take(24).orEmpty()}»"
    }

    private fun typeWord(type: String): String = when (type) {
        "string" -> "рядок"
        "number" -> "число"
        "boolean" -> "так/ні"
        "object" -> "обʼєкт"
        "array" -> "список"
        else -> type
    }

    /** Type words a schema may use. `TypeVocabularyTest` pins every tool to these. */
    val KNOWN_TYPES = setOf("string", "number", "boolean", "object", "array", "any")
}
