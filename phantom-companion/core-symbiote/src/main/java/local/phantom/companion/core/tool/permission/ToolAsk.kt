package local.phantom.companion.core.tool.permission

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import local.phantom.companion.core.tool.ToolRisk

/**
 * Питання до оператора перед виконанням інструмента з наслідком.
 *
 * Поля [fields] витягуються у фіксованому порядку: отримувач, сума, адреса (URL),
 * метод, короткі параметри, і наприкінці повне тіло без обрізання.
 */
data class ToolAsk(
    val toolId: String,
    val description: String,
    val risk: ToolRisk,
    val argsPreview: String,
    val askEveryCall: Boolean = false,
    val allowedKeys: Set<String>? = null,
    val fields: List<Field> = ToolAskFields.extract(toolId, argsPreview, allowedKeys),
) {
    enum class FieldKind {
        RECIPIENT,
        AMOUNT,
        URL,
        METHOD,
        TARGET,
        BODY,
    }

    data class Field(
        val kind: FieldKind,
        val key: String,
        val value: String,
    ) {
        val isScrollableBody: Boolean get() = kind == FieldKind.BODY
    }
}

object ToolAskFields {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val RECIPIENT_KEYS = setOf(
        "who",
        "people",
        "recipient",
        "to",
        "number",
        "phone",
        "peer",
        "peer_id",
        "peerid",
        "contact",
        "destination",
    )

    private val AMOUNT_KEYS = setOf(
        "amount",
        "sum",
        "lamports",
        "sol",
        "usdc",
    )

    private val CURRENCY_KEYS = setOf(
        "currency",
        "token",
        "asset",
    )

    private val URL_KEYS = setOf(
        "url",
        "uri",
        "endpoint",
        "href",
        "host",
        "data",
    )

    private val TARGET_PINNED_KEYS = setOf(
        "package",
    )

    private val METHOD_KEYS = setOf(
        "method",
        "op",
    )

    private val BODY_KEYS = setOf(
        "body",
        "text",
        "content",
        "cmd",
        "command",
        "notes",
        "note",
        "message",
        "actions",
        "payload",
        "extras",
        "headers",
    )

    private const val LONG_VALUE_THRESHOLD = 120

    fun extract(
        toolId: String,
        argsPreview: String,
        allowedKeys: Set<String>? = null,
    ): List<ToolAsk.Field> {
        val raw = argsPreview.trim()
        if (raw.isEmpty() || raw == "{}") return emptyList()

        val obj = runCatching {
            lenientJson.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return listOf(
            ToolAsk.Field(
                kind = ToolAsk.FieldKind.BODY,
                key = "args",
                value = argsPreview,
            ),
        )

        if (obj.isEmpty()) return emptyList()

        val declaredLower = allowedKeys?.map { it.lowercase(Locale.ROOT) }?.toSet()
        fun isDeclared(lowerKey: String): Boolean =
            declaredLower == null || lowerKey in declaredLower

        val currencySuffix = obj.entries
            .firstOrNull { isDeclared(it.key.lowercase(Locale.ROOT)) && it.key.lowercase(Locale.ROOT) in CURRENCY_KEYS }
            ?.let { (_, el) -> (el as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val hasAmountKey = obj.keys.any {
            val l = it.lowercase(Locale.ROOT)
            isDeclared(l) && l in AMOUNT_KEYS
        }

        val fields = mutableListOf<ToolAsk.Field>()
        var hasMethod = false

        for ((key, element) in obj.entries) {
            if (element is JsonNull) continue
            val lower = key.lowercase(Locale.ROOT)
            val declared = isDeclared(lower)
            if (declared && hasAmountKey && lower in CURRENCY_KEYS) continue

            val rawVal = when (element) {
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
            if (rawVal.isEmpty()) continue

            val kind = when {
                !declared -> ToolAsk.FieldKind.BODY
                lower in RECIPIENT_KEYS -> ToolAsk.FieldKind.RECIPIENT
                lower == "address" && hasAmountKey -> ToolAsk.FieldKind.RECIPIENT
                lower in AMOUNT_KEYS -> ToolAsk.FieldKind.AMOUNT
                lower in URL_KEYS -> {
                    if (lower == "data" && toolId != "launch" && !looksLikeUri(rawVal)) {
                        if (rawVal.length > LONG_VALUE_THRESHOLD || '\n' in rawVal) {
                            ToolAsk.FieldKind.BODY
                        } else {
                            ToolAsk.FieldKind.TARGET
                        }
                    } else {
                        ToolAsk.FieldKind.URL
                    }
                }
                lower in TARGET_PINNED_KEYS -> ToolAsk.FieldKind.TARGET
                lower in METHOD_KEYS -> {
                    hasMethod = true
                    ToolAsk.FieldKind.METHOD
                }
                lower in BODY_KEYS || rawVal.length > LONG_VALUE_THRESHOLD || '\n' in rawVal ->
                    ToolAsk.FieldKind.BODY
                else -> ToolAsk.FieldKind.TARGET
            }

            val formattedValue = when (kind) {
                ToolAsk.FieldKind.AMOUNT -> {
                    if (currencySuffix != null && !rawVal.contains(currencySuffix, ignoreCase = true)) {
                        "$rawVal $currencySuffix"
                    } else {
                        rawVal
                    }
                }
                ToolAsk.FieldKind.METHOD -> {
                    if (toolId == "http") rawVal.trim().uppercase(Locale.ROOT) else rawVal
                }
                else -> rawVal
            }

            fields += ToolAsk.Field(
                kind = kind,
                key = key,
                value = formattedValue,
            )
        }

        if (toolId == "http" && !hasMethod) {
            fields += ToolAsk.Field(
                kind = ToolAsk.FieldKind.METHOD,
                key = "method",
                value = "GET",
            )
        }

        return fields.sortedBy { it.kind.ordinal }
    }

    private fun looksLikeUri(value: String): Boolean {
        val v = value.trim()
        return v.contains("://") ||
            v.startsWith("tel:", ignoreCase = true) ||
            v.startsWith("smsto:", ignoreCase = true) ||
            v.startsWith("mailto:", ignoreCase = true) ||
            v.startsWith("content:", ignoreCase = true) ||
            v.startsWith("geo:", ignoreCase = true)
    }
}
