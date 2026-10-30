package local.phantom.companion.core.tool.permission

import io.ktor.http.Url
import java.net.IDN
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Єдиний парсер URL для [ToolPermissionGate] та [local.phantom.companion.core.tool.impl.HttpTool].
 *
 * Використовує `io.ktor.http.Url` разом із `IDN.toASCII` та відхиляє:
 * - схеми поза `http` / `https`;
 * - будь-які зворотні слеші `\` в рядку URL;
 * - наявність userinfo (`user`, `password` або `@` у частині авторитету);
 * - порожній або невалідний хост.
 */
internal object HttpTarget {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class ParsedUrl(
        val url: Url,
        val normalizedHost: String,
    )

    data class CallTarget(
        val method: String,
        val host: String?,
        val validUrl: Boolean,
    ) {
        val isGetWithHost: Boolean get() = validUrl && method == "GET" && !host.isNullOrBlank()
        val scopedGrantKey: String? get() = if (isGetWithHost) "http:GET:$host" else null
        val scopedHostNeverKey: String? get() = host?.takeIf { validUrl && it.isNotBlank() }?.let { "http:$it" }
    }

    fun parseUrl(rawUrl: String): ParsedUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || '\\' in trimmed) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = trimmed.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
            .let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return null

        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.protocol.name.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (parsed.user != null || parsed.password != null ||
            parsed.encodedUser != null || parsed.encodedPassword != null
        ) {
            return null
        }

        val rawHost = parsed.host.trim().removeSuffix(".")
        if (rawHost.isEmpty()) return null
        if (rawHost.any { it.isWhitespace() || it == '/' || it == '\\' || it == '@' || it == ':' }) {
            return null
        }

        val asciiHost = normalizeHost(rawHost) ?: return null
        return ParsedUrl(url = parsed, normalizedHost = asciiHost)
    }

    fun normalizeHost(rawHost: String): String? {
        val cleaned = rawHost.trim().removeSuffix(".")
        if (cleaned.isEmpty()) return null
        val labels = cleaned.split('.')
        if (labels.any { it.isEmpty() }) return null
        val asciiLabels = labels.map { label ->
            val converted = if (label.all { it.code < 128 }) {
                label.lowercase(Locale.ROOT)
            } else {
                runCatching { IDN.toASCII(label, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) }.getOrNull()
                    ?: return null
            }
            if (converted.isEmpty() || converted.any { it.isWhitespace() || it.code <= 0x20 || it.code >= 0x7f }) {
                return null
            }
            converted
        }
        return asciiLabels.joinToString(".")
    }

    fun extractHost(urlString: String): String? = parseUrl(urlString)?.normalizedHost

    fun parseFromArgsPreview(argsPreview: String): CallTarget {
        val obj = runCatching {
            lenientJson.parseToJsonElement(argsPreview.trim()) as? JsonObject
        }.getOrNull()
        val rawMethod = (obj?.get("method") as? JsonPrimitive)?.contentOrNull?.trim()
        val method = if (rawMethod.isNullOrEmpty()) "GET" else rawMethod.uppercase(Locale.ROOT)
        val rawUrl = (obj?.get("url") as? JsonPrimitive)?.contentOrNull?.trim()
        val parsed = rawUrl?.let { parseUrl(it) }
        return CallTarget(
            method = method,
            host = parsed?.normalizedHost,
            validUrl = parsed != null,
        )
    }
}
