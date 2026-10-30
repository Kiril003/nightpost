package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.effectiveProfile

// ---------------------------------------------------------------------------
// Gemini REST request shapes (internal — callers use AIChunk).
// ---------------------------------------------------------------------------

@Serializable
private data class GeminiPart(
    @SerialName("text") val text: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null,
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
)

@Serializable
private data class GeminiInlineData(
    @SerialName("mime_type") val mimeType: String,
    @SerialName("data") val data: String,
)

@Serializable
private data class GeminiFunctionCall(
    @SerialName("name") val name: String,
    @SerialName("args") val args: JsonObject,
)

@Serializable
private data class GeminiFunctionResponse(
    @SerialName("name") val name: String,
    @SerialName("response") val response: JsonObject,
)

@Serializable
private data class GeminiContent(
    @SerialName("role") val role: String? = null,
    @SerialName("parts") val parts: List<GeminiPart>,
)

/**
 * One entry of the request `tools` array. Only Google-Search grounding
 * (R1.3) rides here — PHANTOM's own tools are described in the prompt and
 * exchanged as text, never declared natively. The request serializer runs
 * with `explicitNulls = false`; Gemini rejects explicit nulls here.
 */
@Serializable
private data class GeminiTool(
    @SerialName("google_search") val googleSearch: JsonObject? = null,
)

@Serializable
private data class GeminiGenerationConfig(
    @SerialName("maxOutputTokens") val maxOutputTokens: Int,
    @SerialName("temperature") val temperature: Float,
)

@Serializable
private data class GeminiRequest(
    @SerialName("contents") val contents: List<GeminiContent>,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig,
    @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
    @SerialName("tools") val tools: List<GeminiTool>? = null,
)

// ---------------------------------------------------------------------------
// GeminiCloudRoute
// ---------------------------------------------------------------------------

/**
 * Streams completions from the Gemini REST endpoint, walking [models] in
 * order on HTTP 404 (model not available for this key).
 *
 * URL: `https://generativelanguage.googleapis.com/v1beta/models/<model>:streamGenerateContent`
 *
 * The API key is supplied as a lazy [apiKeyProvider] so this module
 * has no dependency on `:app` or any key-store implementation. The
 * caller (typically the DI layer in `:app`) binds the provider to
 * `EncryptedSharedPreferences`.
 *
 * Streaming works via Server-Sent Events: the full response body is
 * read as text, then split line-by-line. Each `data: <json>` line is
 * parsed and its `text` parts emitted as [AIChunk.Token]. After all
 * lines are processed a [AIChunk.Final] closes the flow.
 *
 * Phase 3.C additions:
 * - [offlineQueueSink]: when an [IOException] occurs the intent is handed
 *   to this closure for persistence; the route then emits an
 *   [AIChunk.Notice] + tombstone [AIChunk.Final] so the caller knows the
 *   request is queued rather than lost.
 * - [streamOnce] is exposed as `internal` so that `:core-resilience`'s
 *   [local.phantom.companion.core.resilience.StreamResumer] can wrap this
 *   route with retry-with-accumulated-prefix semantics from `AppContainer`
 *   without creating a circular module dependency.
 *
 * @param apiKeyProvider   Suspend lambda that returns the Gemini API key
 *   or `null` when none is configured.
 * @param httpClient       Ktor client to use. Defaults to [defaultClient].
 *   Pass a `MockEngine`-backed client in tests.
 * @param offlineQueueSink Optional closure called with the intent when an
 *   [IOException] exhausts all retries. The caller is responsible for
 *   persisting and later draining the intent. When `null` the IOException
 *   is converted to an [AIChunk.ErrorChunk].
 */
class GeminiCloudRoute(
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: HttpClient = defaultClient(),
    private val offlineQueueSink: ((AIIntent.Text) -> Unit)? = null,
    private val modelFor: (BrainProfile) -> String = BrainProfiles::modelFor,
    private val fixedProfile: BrainProfile? = null,
    private val endpointBase: String = DEFAULT_ENDPOINT_BASE,
    private val headers: (suspend () -> Map<String, String>)? = null,
    private val requestHeaders: (suspend (model: String, profile: BrainProfile, bodyBytes: ByteArray) -> Map<String, String>)? = null,
) : AIRouter {

    /**
     * METAMORPHOSIS P1 — predictive connection warming. The first cloud
     * message used to pay DNS + TCP + TLS on the spot; this primes OkHttp's
     * connection pool with a throwaway unauthenticated GET to the API host
     * (403/404 response, but the handshake is cached). Response body and
     * status are deliberately ignored — the handshake IS the payload.
     * Call at app start when a key exists; failures are silent (warming is
     * an optimization, never a requirement).
     */
    suspend fun warmUp() {
        // Перевірка ключа жила ЛИШЕ на місці виклику, тож будь-хто з посиланням
        // на маршрут відкривав TLS до хмари без ключа.
        if (apiKeyProvider().isNullOrBlank()) return
        runCatching { httpClient.get(WARMUP_URL) }
    }

    override fun complete(intent: AIIntent): Flow<AIChunk> =
        streamOnce(intent).catch { t ->
            if (t is java.io.IOException) {
                val intentText = intent as? AIIntent.Text
                if (intentText != null && offlineQueueSink != null) {
                    // Offline queue available — persist intent + notify caller.
                    offlineQueueSink.invoke(intentText)
                    emit(
                        AIChunk.Notice(
                            text = QUEUED_NOTICE,
                            route = Route.CLOUD_GEMINI,
                        )
                    )
                    // The answer itself stays empty: the turn is queued, not
                    // answered. It used to carry the raw IOException, so a
                    // dropped connection reached the operator as PHANTOM
                    // saying «Unable to resolve host generativelanguage…».
                    emit(
                        AIChunk.Final(
                            text = "",
                            tokensUsed = 0,
                            route = Route.CLOUD_GEMINI,
                            durationMs = 0,
                        )
                    )
                } else {
                    // No sink — surface as ErrorChunk so callers that
                    // don't check exceptions still see a meaningful failure.
                    emit(
                        AIChunk.ErrorChunk(
                            cause = AIError.CloudRejected(0, t.message ?: "io error"),
                            route = Route.CLOUD_GEMINI,
                        )
                    )
                }
            } else {
                throw t
            }
        }

    /**
     * Single attempt at streaming a completion for [intent].
     *
     * Exposed as `internal` so [local.phantom.companion.core.resilience.StreamResumer]
     * (in `:core-resilience`) can build a resumable wrapper around it from
     * `AppContainer`, passing `intent.withResumePrefix(prefix)` on each retry
     * without creating a circular module dependency.
     *
     * Re-throws [java.io.IOException] so the [StreamResumer] retry loop can
     * catch and re-invoke; all other [Throwable]s are converted to
     * [AIChunk.ErrorChunk] for backward-compatibility.
     */
    fun streamOnce(intent: AIIntent): Flow<AIChunk> = flow {
        val startMs = System.currentTimeMillis()
        val hasCustomHeaders = headers != null || requestHeaders != null
        val apiKey = if (hasCustomHeaders) "" else apiKeyProvider()?.takeIf { it.isNotBlank() } ?: run {
            emit(
                AIChunk.ErrorChunk(
                    cause = AIError.CloudRejected(401, "no API key configured"),
                    route = Route.CLOUD_GEMINI,
                )
            )
            return@flow
        }

        val toolsDeclared = (intent as? AIIntent.Text)?.tools?.isNotEmpty() == true
        val request = try {
            buildRequest(intent)
        } catch (t: Throwable) {
            android.util.Log.e("GeminiRoute", "buildRequest THREW", t)
            emit(
                AIChunk.ErrorChunk(
                    cause = AIError.Broken("buildRequest: ${t.message.orEmpty()}"),
                    route = Route.CLOUD_GEMINI,
                ),
            )
            return@flow
        }
        android.util.Log.d(
            "GeminiRoute",
            "request built contents=${request.contents.size} tools=${request.tools?.size ?: 0}",
        )
        val profile = fixedProfile ?: intent.effectiveProfile
        val model = modelFor(profile)

        val bodyText = json.encodeToString(GeminiRequest.serializer(), request)
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)

        val endpoint = "$endpointBase/v1beta/models/$model:streamGenerateContent"
        val url = if (hasCustomHeaders) "$endpoint?alt=sse" else "$endpoint?key=$apiKey&alt=sse"

        try {
            val extraHeaders = buildMap {
                headers?.invoke()?.let(::putAll)
                requestHeaders?.invoke(model, profile, bodyBytes)?.let(::putAll)
            }
            httpClient.preparePost(url) {
                extraHeaders.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                setBody(bodyText)
            }.execute { response ->
                android.util.Log.d("GeminiRoute", "Model $model HTTP ${response.status.value}")
                if (response.status != HttpStatusCode.OK) {
                    val lastStatus = response.status.value
                    val lastErrorBody = response.bodyAsText()
                    android.util.Log.w("GeminiRoute", "Model $model HTTP $lastStatus body=${lastErrorBody.take(800)}")
                    this@flow.emit(
                        AIChunk.ErrorChunk(
                            cause = AIError.CloudRejected(response.status.value, lastErrorBody),
                            route = Route.CLOUD_GEMINI,
                        )
                    )
                    return@execute
                }

                val channel = response.bodyAsChannel()
                val accumulated = StringBuilder()
                var totalTokens = 0
                var lastFinishReason: String? = null
                var sseLines = 0
                // R1.3 — grounding citations accumulated across SSE chunks,
                // deduped by URL, emitted once before Final.
                val citations = LinkedHashMap<String, String>()

                while (!channel.isClosedForRead) {
                    val line = try {
                        withContext(Dispatchers.IO) {
                            withTimeout(STREAM_READ_TIMEOUT_MS) { channel.readUTF8Line() }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw java.net.SocketTimeoutException(
                            "Gemini SSE stream stalled: no data for ${STREAM_READ_TIMEOUT_MS}ms",
                        )
                    } ?: break
                    if (!line.startsWith("data: ")) continue
                    val jsonText = line.removePrefix("data: ").trim()
                    if (jsonText.isEmpty() || jsonText == "[DONE]") continue

                    val chunk = runCatching { json.parseToJsonElement(jsonText).jsonObject }
                        .getOrNull() ?: continue
                    sseLines++
                    runCatching {
                        chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("finishReason")?.jsonPrimitive?.content
                    }.getOrNull()?.let { lastFinishReason = it }

                    val candidateParts = chunk.extractCandidateParts()
                    val timestamp = System.currentTimeMillis()
                    var callCounter = 0

                    for (partElement in candidateParts) {
                        val part = partElement.jsonObject

                        // Handle text content
                        part["text"]?.jsonPrimitive?.content?.let { text ->
                            accumulated.append(text)
                            this@flow.emit(
                                AIChunk.Token(
                                    text = text,
                                    route = Route.CLOUD_GEMINI
                                )
                            )
                        }

                        // Handle tool call (functionCall in Gemini)
                        part["functionCall"]?.jsonObject?.let { fnCall ->
                            val name = fnCall["name"]?.jsonPrimitive?.content ?: ""
                            val args = fnCall["args"]?.jsonObject?.toString() ?: "{}"
                            // Use a unique ID per call in this turn
                            val callId = "call_${timestamp}_${callCounter++}"
                            this@flow.emit(
                                AIChunk.ToolCall(
                                    name = name,
                                    arguments = args,
                                    callId = callId,
                                    route = Route.CLOUD_GEMINI
                                )
                            )
                        }
                    }
                    totalTokens += chunk.extractTotalTokens()
                    chunk.extractGroundingCitations().forEach { (url, title) ->
                        citations.putIfAbsent(url, title)
                    }
                }

                if (citations.isNotEmpty()) {
                    this@flow.emit(
                        AIChunk.Grounding(
                            citations = citations.map { (url, title) ->
                                AIChunk.Grounding.Citation(title = title, url = url)
                            },
                            route = Route.CLOUD_GEMINI,
                        )
                    )
                }

                // Every call of the turn, taken apart here and handed over as
                // calls. No reader downstream runs a call left in the text, so
                // a single one used to reach the chat as raw JSON.
                val scan = if (toolsDeclared) ToolBatchScan.of(accumulated.toString()) else null
                val finalText = if (scan != null && scan.calls.isNotEmpty()) {
                    val ts = System.currentTimeMillis()
                    scan.calls.forEachIndexed { i, call ->
                        this@flow.emit(
                            AIChunk.ToolCall(
                                name = call.name,
                                arguments = call.args,
                                callId = "call_${ts}_$i",
                                route = Route.CLOUD_GEMINI,
                            ),
                        )
                    }
                    // The calls must NOT stay in the final text as well: the
                    // consumer mints a tool run from the first object it finds
                    // there, and the same call would execute twice.
                    scan.prose
                } else accumulated.toString()

                android.util.Log.d(
                    "GeminiRoute",
                    "FINAL accLen=${accumulated.length} sseLines=$sseLines tokens=$totalTokens " +
                        "finishReason=$lastFinishReason batched=${scan?.calls?.size ?: 0}",
                )
                this@flow.emit(
                    AIChunk.Final(
                        text = finalText,
                        tokensUsed = totalTokens,
                        route = Route.CLOUD_GEMINI,
                        durationMs = System.currentTimeMillis() - startMs,
                    )
                )
            }
        } catch (t: Throwable) {
            if (t is java.io.IOException || t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.w("GeminiRoute", "stream failed", t)
            emit(
                AIChunk.ErrorChunk(
                    cause = brokenOrOffline(t),
                    route = Route.CLOUD_GEMINI,
                )
            )
        }
    }

    override suspend fun availableRoutes(): Set<Route> =
        if (apiKeyProvider()?.isNotBlank() == true) setOf(Route.CLOUD_GEMINI) else emptySet()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildRequest(intent: AIIntent): GeminiRequest {
        return when (intent) {
            is AIIntent.Text -> {
                val systemInstruction = buildString {
                    intent.systemPrompt?.let { append(it) }
                    // Only when this turn actually has tools: without them the
                    // clause would teach a protocol nothing can answer, and a
                    // model that quotes it costs the operator a hop.
                    if (intent.tools.isNotEmpty()) {
                        append("\n\n")
                        append(BATCH_PROTOCOL)
                    }
                    intent.context?.let { ctx ->
                        append("\n\nПОТОЧНИЙ КОНТЕКСТ (Екран/Оточення):\n")
                        append(ctx)
                    }
                    // Останнє слово — за мовою людини: усе вище написане українською.
                    intent.languageRule?.takeIf { it.isNotBlank() }?.let { rule ->
                        if (isNotEmpty()) append("\n\n")
                        append(rule)
                    }
                }.takeIf { it.isNotBlank() }?.let { sys ->
                    GeminiContent(parts = listOf(GeminiPart(text = sys)))
                }
                
                val rawContents = buildList {
                    for (turn in intent.history) {
                        val role = when (turn.role) {
                            AIIntent.Text.Role.USER -> "user"
                            AIIntent.Text.Role.ASSISTANT -> "model"
                            AIIntent.Text.Role.SYSTEM -> "user"
                            // NOT "function": the endpoint rejects it —
                            // «Role 'function' is not supported. Please use a
                            // valid role: SYSTEM, …, USER, MODEL» (HTTP 400,
                            // seen on device 2026-07-26). Every turn that
                            // called a tool died there, so no tool-backed card
                            // could ever reach the operator. The functionResponse
                            // part still carries the pairing; only the label
                            // changes.
                            AIIntent.Text.Role.TOOL -> "user"
                        }
                        
                        // The tool exchange is replayed as TEXT, in the very
                        // shape SystemPrompts documents to the model
                        // (`{"type":"tool-call"…}` → `[TOOL_RESULT …]`).
                        //
                        // It used to ride Gemini's native functionCall /
                        // functionResponse parts, but those turns are
                        // SYNTHESISED by us (the model answers in our JSON
                        // protocol, so the API never issued a real call) —
                        // and Gemini 3 rejects a replayed functionCall that
                        // carries no thought_signature: HTTP 400 «Function
                        // call is missing a thought_signature», every single
                        // tool-using turn, so no tool-backed card could ever
                        // reach the operator. A signature we never received
                        // cannot be echoed back; text has no such demand.
                        val parts = buildList {
                            if (turn.role == AIIntent.Text.Role.TOOL) {
                                val line = toolResultLine(turn)
                                if (line.isNotBlank()) {
                                    add(GeminiPart(text = line))
                                }
                            } else {
                                if (turn.content.isNotBlank()) {
                                    add(GeminiPart(text = turn.content))
                                }
                                turn.toolCalls?.forEach { call ->
                                    val line = toolCallLine(call)
                                    if (line.isNotBlank()) {
                                        add(GeminiPart(text = line))
                                    }
                                }
                            }
                        }
                        if (parts.isNotEmpty()) {
                            add(GeminiContent(role = role, parts = parts))
                        }
                    }
                    val promptParts = buildList {
                        intent.attachments.forEach { att ->
                            add(
                                GeminiPart(
                                    inlineData = GeminiInlineData(
                                        mimeType = att.mimeType,
                                        data = att.base64Data,
                                    ),
                                ),
                            )
                        }
                        if (intent.prompt.isNotBlank()) {
                            add(GeminiPart(text = intent.prompt))
                        }
                    }
                    val safePromptParts = if (promptParts.isEmpty()) {
                        listOf(GeminiPart(text = "Продовжуй виконання завдання з урахуванням результатів."))
                    } else {
                        promptParts
                    }
                    add(GeminiContent(role = "user", parts = safePromptParts))
                }

                val contents = buildList<GeminiContent> {
                    for (item in rawContents) {
                        val validParts = item.parts.filter {
                            (it.text != null && it.text.isNotBlank()) ||
                                it.inlineData != null ||
                                it.functionCall != null ||
                                it.functionResponse != null
                        }
                        if (validParts.isEmpty()) continue
                        val cleanedItem = item.copy(parts = validParts)
                        val last = lastOrNull()
                        if (last != null && last.role == cleanedItem.role) {
                            removeAt(size - 1)
                            add(last.copy(parts = last.parts + cleanedItem.parts))
                        } else {
                            add(cleanedItem)
                        }
                    }
                }
                val safeContents = if (contents.isEmpty()) {
                    listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = "Привіт."))))
                } else {
                    contents
                }

                val toolDefs = buildList {
                    // No functionDeclarations, deliberately. This route replays a
                    // tool exchange as text (see toolCallLine/toolResultLine) because
                    // the functionCall turn is synthesised here and can never carry
                    // the thought_signature Gemini 3 demands on replay — a hard 400.
                    // Declaring the tools natively only invites the model to answer
                    // in a shape we cannot echo back, and costs ~16k chars a turn.
                    // R1.3 — Google-Search grounding is a different thing and stays.
                    if (intent.webSearch) {
                        add(GeminiTool(googleSearch = JsonObject(emptyMap())))
                    }
                }.takeIf { it.isNotEmpty() }

                GeminiRequest(
                    contents = safeContents,
                    generationConfig = GeminiGenerationConfig(
                        maxOutputTokens = intent.maxTokens.coerceAtLeast(GEMINI_OUTPUT_FLOOR),
                        temperature = intent.temperature,
                    ),
                    systemInstruction = systemInstruction,
                    tools = toolDefs
                )
            }
        }
    }
    private fun JsonObject.extractCandidateParts(): JsonArray {
        return runCatching {
            this["candidates"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray
                ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
    }

    /**
     * R1.3 — pulls `candidates[0].groundingMetadata.groundingChunks[]
     * .web.{uri,title}` pairs out of one SSE chunk. Returns url→title;
     * missing/malformed metadata yields an empty map (grounding is
     * optional on every response).
     */
    private fun JsonObject.extractGroundingCitations(): Map<String, String> = runCatching {
        val chunks = this["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("groundingMetadata")?.jsonObject
            ?.get("groundingChunks")?.jsonArray
            ?: return@runCatching emptyMap()
        buildMap {
            for (c in chunks) {
                val web = c.jsonObject["web"]?.jsonObject ?: continue
                val uri = web["uri"]?.jsonPrimitive?.content ?: continue
                val title = web["title"]?.jsonPrimitive?.content ?: uri
                put(uri, title)
            }
        }
    }.getOrDefault(emptyMap())

    private fun JsonObject.extractTotalTokens(): Int {
        return runCatching {
            this["usageMetadata"]
                ?.jsonObject
                ?.get("totalTokenCount")
                ?.jsonPrimitive
                ?.content
                ?.toInt()
                ?: 0
        }.getOrDefault(0)
    }

    companion object {
        internal const val QUEUED_NOTICE = "Мережа недоступна — відповім, щойно вона зʼявиться."

        /**
         * Read by the model, not by the operator — Ukrainian to match the rest
         * of the brief. Paired with [ToolBatchScan], which is the only reader
         * of the shape it promises; changing one without the other silently
         * turns a batched turn back into dropped calls.
         */
        internal const val BATCH_PROTOCOL =
            "ПАРАЛЕЛЬНІ ІНСТРУМЕНТИ:\n" +
                "- Якщо для відповіді потрібно кілька НЕЗАЛЕЖНИХ інструментів — поверни їх " +
                "ОДНИМ зворотом, кожен окремим обʼєктом підряд, без тексту між ними: " +
                """{"type":"tool-call","tool":"a","args":{}}{"type":"tool-call","tool":"b","args":{}}""" + "\n" +
                "- Незалежні = жодному з них НЕ потрібен результат іншого. Якщо другий виклик " +
                "залежить від результату першого — НЕ обʼєднуй, викликай по черзі.\n" +
                "- Усі [TOOL_RESULT …] прийдуть разом у наступному звороті, у тому ж порядку.\n" +
                "- Не більше 4 викликів в одному звороті."

        const val DEFAULT_ENDPOINT_BASE = "https://generativelanguage.googleapis.com"

        private const val WARMUP_URL = "https://generativelanguage.googleapis.com/"

        /**
         * Output ceiling floor for Gemini models.
         */
        private const val GEMINI_OUTPUT_FLOOR = 2048

        /** Max silence between SSE lines before we treat the stream as stalled. */
        private const val STREAM_READ_TIMEOUT_MS = 45_000L

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // R1.3 — drop null fields instead of sending "field": null.
            // Needed for the two-shape GeminiTool (functionDeclarations
            // XOR google_search): Gemini 400s on an explicit null member.
            explicitNulls = false
        }

        /**
         * Creates a default [HttpClient] backed by OkHttp.
         *
         * `requestTimeoutMillis` bounds the WHOLE streamed request, not a
         * single read — it used to be 30s, which is *shorter* than
         * [STREAM_READ_TIMEOUT_MS] (45s), so Ktor's own blunt timeout was
         * killing legitimately-long turns (tool calls, grounded/search
         * answers, longer replies) before the app's smarter per-line
         * stall-watchdog ever got a chance to run. It also throws
         * `HttpRequestTimeoutException`, a `CancellationException` (NOT an
         * `IOException`), which doesn't reliably hit the IOException-based
         * retry/resume paths below — the turn could just hang silently.
         * Set well above the stall-watchdog so it only fires as a last
         * resort against a truly pathological hang.
         */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 120_000
            }
            expectSuccess = false
        }
    }

    /** The model's own emission shape, replayed back to it verbatim. */
    private fun toolCallLine(call: AIIntent.Text.ToolCall): String =
        """{"type":"tool-call","tool":"${call.name}","args":${call.arguments}}"""

    /** `[TOOL_RESULT id=… ok=… output=…]` — the contract SystemPrompts
     *  already promises the model for the turn after a tool-call. */
    private fun toolResultLine(turn: AIIntent.Text.Turn): String {
        val id = turn.toolCallId ?: "unknown"
        val parsed = runCatching { json.parseToJsonElement(turn.content) }.getOrNull() as? JsonObject
        val ok = (parsed?.get("ok") as? JsonPrimitive)?.content
        // A structured output — every geo.* result carries a whole `map` object
        // — is not a JsonPrimitive, and the old `as?` sent the envelope back as
        // its own output field: [TOOL_RESULT … output={"ok":true,"output":{…}}].
        val output = when (val element = parsed?.get("output")) {
            null -> turn.content
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> element.toString()
        }
        return if (ok != null) "[TOOL_RESULT id=$id ok=$ok output=$output]"
        else "[TOOL_RESULT id=$id output=$output]"
    }
}
