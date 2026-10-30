package local.phantom.companion.core.tool.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.permission.HttpTarget

/**
 * Makes HTTP requests (GET/POST/PUT/DELETE/HEAD) to allowlisted hosts.
 *
 * Requires [ToolPermission.NETWORK] in [ToolContext.approvedPermissions].
 *
 * Hosts must be explicitly approved in [hostAllowlist] before a request
 * is dispatched. Subdomain matching is supported — approving `"example.com"`
 * also allows `"api.example.com"`.
 *
 * Streaming: for `text/plain` and `application/json` response bodies the
 * content is forwarded as [ToolEvent.Chunk] in one shot (Ktor does not
 * expose a line-by-line SSE API here — chunked streaming is Phase 1.D).
 *
 * @param httpClient    Ktor [HttpClient] — caller owns lifecycle.
 * @param hostAllowlist Operator-managed host allowlist.
 */
class HttpTool(
    httpClient: HttpClient,
    private val hostAllowlist: HostAllowlist,
) : Tool {

    private val client: HttpClient = httpClient.config {
        followRedirects = false
    }

    override val id = "http"

    override val description =
        "HTTP-запит (GET/POST/PUT/DELETE/HEAD) до дозволеного вузла. Вузол має бути в списку " +
            "дозволених — інакше запит навіть не піде, і я скажу, який саме вузол треба дозволити. " +
            "/ HTTP request to an allowlisted host."

    override val argsSchema: JsonObject = buildJsonObject {
        put("url", kotlinx.serialization.json.JsonPrimitive("string"))
        put("method", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("body", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("headers", kotlinx.serialization.json.JsonPrimitive("object?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)

    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true

    override val streaming: Boolean = true

    override val manifest = FeatureManifest(
        id = "tool.http",
        tier = 1,
        displayName = "HTTP-запит",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startMs

        val urlString = args["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Err(
                "bad_args",
                "запиту не було: не передано url. Дай повну адресу з протоколом (https://…).",
                elapsed(),
            )

        val parsedTarget = HttpTarget.parseUrl(urlString)
            ?: return ToolResult.Err(
                "bad_url",
                "адресу «$urlString» не вдалось розібрати або вона містить заборонені символи. " +
                    "Перевір протокол (http/https) і відсутність userinfo чи зворотних слешів.",
                elapsed(),
            )
        val targetHost = parsedTarget.normalizedHost

        if (!hostAllowlist.contains(targetHost)) {
            val allowed = hostAllowlist.snapshot()
            return ToolResult.Err(
                "forbidden_host",
                "запит не пішов: вузол $targetHost не в дозволених. " +
                    (if (allowed.isEmpty()) {
                        "Список порожній — жоден вузол ще не дозволений."
                    } else {
                        "Зараз дозволені: ${allowed.sorted().joinToString(", ")}."
                    }) +
                    " Щоб цей запит пройшов, оператор має дозволити $targetHost у налаштуваннях мережі.",
                elapsed(),
            )
        }

        val method = args["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val bodyText = args["body"]?.jsonPrimitive?.content
        val headersJson = args["headers"]?.jsonObject

        return try {
            val response: HttpResponse = when (method) {
                "GET" -> client.get(urlString) {
                    headersJson?.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
                }

                "POST" -> client.post(urlString) {
                    headersJson?.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
                    if (bodyText != null) setBody(bodyText)
                }

                "PUT" -> client.put(urlString) {
                    headersJson?.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
                    if (bodyText != null) setBody(bodyText)
                }

                "DELETE" -> client.delete(urlString) {
                    headersJson?.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
                }

                "HEAD" -> client.head(urlString) {
                    headersJson?.forEach { (k, v) -> header(k, v.jsonPrimitive.content) }
                }

                else -> return ToolResult.Err(
                    "bad_method",
                    "метод $method не підтримується. Дозволені: GET, POST, PUT, DELETE, HEAD.",
                    elapsed(),
                )
            }

            val took = elapsed()
            val statusCode = response.status.value

            if (statusCode in 300..399) {
                val location = response.headers[HttpHeaders.Location].orEmpty()
                val text = if (location.isNotEmpty()) {
                    "HTTP $statusCode ${response.status.description}\nLocation: $location"
                } else {
                    "HTTP $statusCode ${response.status.description}"
                }
                return ToolResult.Ok(
                    output = text,
                    structured = buildJsonObject {
                        put("status", statusCode)
                        put("host", targetHost)
                        put("method", method)
                        if (location.isNotEmpty()) put("location", location)
                        put("redirect_followed", false)
                        put("source", "network_live")
                    },
                    durationMs = took,
                )
            }

            if (!response.status.isSuccess()) {
                // "non-2xx" told the operator nothing it did not already know.
                // The server's own words and the class of fault are what decide
                // whether retrying, fixing the URL or waiting is the next move.
                val snippet = runCatching { response.bodyAsText() }.getOrNull()
                    ?.trim()?.replace(Regex("\\s+"), " ")?.take(ERROR_BODY_CHARS)
                return ToolResult.Err(
                    "http_$statusCode",
                    buildString {
                        append("$targetHost відповів $statusCode ${response.status.description}. ")
                        append(nextStepFor(statusCode))
                        if (!snippet.isNullOrBlank()) append(" Відповідь сервера: «$snippet»")
                    },
                    took,
                )
            }

            val contentTypeHeader = response.contentType()
            val contentTypeStr = contentTypeHeader?.toString() ?: ""
            val isTextBody = contentTypeHeader?.match(ContentType.Text.Plain) == true ||
                contentTypeHeader?.match(ContentType.Application.Json) == true

            val body = response.bodyAsText()
            val truncated = body.length > MAX_BODY_CHARS
            val shown = if (truncated) body.take(MAX_BODY_CHARS) else body

            if (isTextBody) {
                emit(ToolEvent.Chunk(shown))
            }

            ToolResult.Ok(
                output = if (truncated) {
                    "$shown\n[обрізано: показано $MAX_BODY_CHARS з ${body.length} символів]"
                } else {
                    body
                },
                structured = buildJsonObject {
                    put("status", statusCode)
                    put("contentType", contentTypeStr)
                    put("host", targetHost)
                    put("method", method)
                    put("body_chars", body.length)
                    put("truncated", truncated)
                    put("source", "network_live")
                },
                durationMs = took,
            )
        } catch (e: Exception) {
            ToolResult.Err(
                "network_error",
                "до $targetHost не достукався: ${e.message ?: e::class.simpleName ?: "зʼєднання обірвалось"}. " +
                    "Схоже на відсутність звʼязку або блокування — перевір мережу і повтори.",
                elapsed(),
            )
        }
    }

    /** What the operator can actually do about this status, in one clause. */
    private fun nextStepFor(status: Int): String = when {
        status == 401 || status == 403 ->
            "Доступ закрито — потрібні інші облікові дані або дозвіл; повтор не допоможе."
        status == 404 -> "Такого ресурсу немає — перевір адресу."
        status == 429 -> "Ліміт запитів вичерпано — зачекай і повтори пізніше."
        status in 500..599 -> "Це збій на боці сервера, не в запиті — має сенс повторити пізніше."
        status in 300..399 -> "Сервер переадресовує — візьми адресу з заголовка Location."
        else -> "Запит відхилено — перевір адресу, метод і тіло запиту."
    }

    private companion object {
        /** Context is a budget; a megabyte of HTML helps nobody downstream. */
        const val MAX_BODY_CHARS = 20_000
        const val ERROR_BODY_CHARS = 300
    }
}
