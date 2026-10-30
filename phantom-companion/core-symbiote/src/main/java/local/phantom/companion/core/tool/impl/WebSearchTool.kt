package local.phantom.companion.core.tool.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `web.search` — PHANTOM's own web search, replacing Gemini's
 * Google-Search grounding.
 *
 * Why own and not grounding: grounding has a SEPARATE tiny free-tier
 * daily quota, and attaching it to every turn exhausted that quota —
 * Google then 429'd every request and the whole app went silent
 * (verified live 2026-07-03: identical request with `google_search`
 * → 429, without → 200). This tool rides ordinary function-calling:
 * the model calls it ONLY when it actually needs fresh web data, and
 * it costs zero Gemini quota.
 *
 * Backend: DuckDuckGo Lite (`lite.duckduckgo.com`) — keyless, stable
 * table markup, no JS. Results are title+url+snippet; that covers the
 * "що там у світі" class of questions without fetching full pages
 * (no arbitrary-host fetch ⇒ no exfiltration surface beyond the query
 * itself). Full-page reading stays with the allowlisted `http` tool.
 *
 * Law 1 honest degradation: DDG bot-wall or network failure returns a
 * ToolResult.Err that tells the model to admit search is down — never
 * to invent results or URLs.
 */
class WebSearchTool(
    private val httpClient: HttpClient,
    private val endpoint: String = DDG_LITE_ENDPOINT,
) : Tool {

    override val id = "web.search"

    override val description =
        "Пошук у вебі (DuckDuckGo): свіжі факти, новини, ціни, події — все, чого нема у твоїх знаннях. " +
            "Повертає results[{title,url,snippet}]. Відповідь на основі результатів подавай фасетом " +
            "citation-answer з РЕАЛЬНИМИ url з results — ніколи не вигадуй джерела."

    override val argsSchema: JsonObject = buildJsonObject {
        put("query", JsonPrimitive("string"))
        put("limit", JsonPrimitive("number?"))
        put("region", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.web.search",
        tier = 1,
        displayName = "Веб-пошук",
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
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "порожній query — шукати нема чого. Наступний крок: повтори виклик, поклавши " +
                    "у query те, що оператор насправді хоче знайти.",
                elapsed(),
            )
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)
        val region = args["region"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.matches(Regex("[a-z]{2}-[a-z]{2}")) }

        val url = buildString {
            append(endpoint)
            append("/lite/?q=")
            append(URLEncoder.encode(query, "UTF-8"))
            if (region != null) append("&kl=").append(region)
        }

        val body = runCatching {
            val resp: HttpResponse = httpClient.get(url) {
                header("User-Agent", USER_AGENT)
            }
            if (!resp.status.isSuccess()) null else resp.bodyAsText()
        }.getOrNull()
            ?: return ToolResult.Err(
                code = "web_unavailable",
                message = "пошук не відповів (мережа або сервіс). Скажи оператору прямо, що вебу зараз " +
                    "нема, і відповідай лише з власних знань, позначивши їх як не свіжі. " +
                    "Результатів і посилань не вигадуй.",
                durationMs = elapsed(),
            )

        if (looksBotBlocked(body)) {
            return ToolResult.Err(
                code = "web_blocked",
                message = "пошуковик показав захист від ботів замість результатів. Скажи це оператору " +
                    "прямо; наступний крок — повтори за кілька хвилин або звузь запит. " +
                    "Результатів не вигадуй.",
                durationMs = elapsed(),
            )
        }

        val hits = parseDdgLite(body).take(limit)
        if (hits.isEmpty()) {
            return ToolResult.Ok(
                output = "За запитом «$query» пошуковик не дав жодного результату. " +
                    "Наступний крок: переформулюй запит іншими словами або іншою мовою.",
                structured = buildJsonObject {
                    put("results", buildJsonArray {})
                    put("query", query)
                    put("source", "DuckDuckGo Lite")
                    put("fetched_at_ms", startMs)
                },
                durationMs = elapsed(),
            )
        }

        val structured = buildJsonObject {
            put("results", buildJsonArray {
                hits.forEach { h ->
                    add(buildJsonObject {
                        put("title", h.title)
                        put("url", h.url)
                        if (h.snippet.isNotBlank()) put("snippet", h.snippet)
                        put("source_host", h.url.substringAfter("://").substringBefore("/"))
                    })
                }
            })
            put("count", hits.size)
            put("query", query)
            region?.let { put("region", it) }
            put("source", "DuckDuckGo Lite")
            put("fetched_at_ms", startMs)
            put("confidence", "заголовки і фрагменти, сторінки не читані")
        }
        return ToolResult.Ok(
            output = "Знайдено ${hits.size}: " +
                hits.take(3).joinToString("; ") { "${it.title} — ${it.url}" } +
                ". Це заголовки і фрагменти видачі — самі сторінки я не читав; " +
                "щоб прочитати, виклич http на конкретний url.",
            structured = structured,
            durationMs = elapsed(),
        )
    }

    data class SearchHit(val title: String, val url: String, val snippet: String)

    companion object {
        const val DDG_LITE_ENDPOINT = "https://lite.duckduckgo.com"

        /** DDG serves the bot-wall to bare/robot UAs; a browser-family UA
         *  with our honest product token keeps the lite page reachable. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile) PhantomCompanion/3.0"

        private val ANCHOR = Regex(
            """<a\b([^>]*)>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val HREF = Regex("""href="([^"]+)"""")
        private val SNIPPET = Regex(
            """<td\s+class=['"]result-snippet['"]\s*>(.*?)</td>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TAGS = Regex("<[^>]+>")
        private val UDDG = Regex("""[?&]uddg=([^&]+)""")

        /**
         * Parses DDG Lite's table markup: anchors carrying
         * `class="result-link"` are result titles (href is either the
         * target URL or a `/l/?uddg=<encoded>` redirect), and the
         * `result-snippet` cell that follows each is its description.
         * Pure function — unit-tested against canned markup; markup
         * drift degrades to an empty list, never a crash.
         */
        fun parseDdgLite(html: String): List<SearchHit> {
            val titles = ANCHOR.findAll(html)
                .filter { it.groupValues[1].contains("result-link") }
                .mapNotNull { m ->
                    val href = HREF.find(m.groupValues[1])?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val title = cleanText(m.groupValues[2])
                    if (title.isBlank()) return@mapNotNull null
                    title to resolveUrl(href)
                }
                .toList()
            val snippets = SNIPPET.findAll(html)
                .map { cleanText(it.groupValues[1]) }
                .toList()
            return titles.mapIndexed { i, (title, url) ->
                SearchHit(
                    title = title,
                    url = url,
                    snippet = snippets.getOrElse(i) { "" },
                )
            }.filter { it.url.startsWith("http") }
        }

        /** DDG wraps some hrefs in `//duckduckgo.com/l/?uddg=<url-encoded>`. */
        private fun resolveUrl(href: String): String {
            if (!href.contains("duckduckgo.com/l/")) {
                return if (href.startsWith("//")) "https:$href" else href
            }
            val encoded = UDDG.find(href)?.groupValues?.get(1) ?: return href
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
        }

        private fun cleanText(raw: String): String = raw
            .replace(TAGS, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("""&#(\d+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun looksBotBlocked(body: String): Boolean =
            !body.contains("result-link") &&
                (body.contains("bots use DuckDuckGo", ignoreCase = true) ||
                    body.contains("anomaly", ignoreCase = true) ||
                    body.contains("captcha", ignoreCase = true))
    }
}
