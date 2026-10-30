package local.phantom.companion.core.tool.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import java.net.URI
import java.net.URLEncoder

/**
 * `research.deep` — the depth `web.search` deliberately lacks.
 *
 * web.search is one DDG-Lite page of title+url+snippet — exactly the
 * «Google auto-AI» ceiling the operator called out. This tool is a real
 * research pipeline in one call:
 *
 *   1. DECOMPOSE — one model turn splits the question into 2–4 targeted
 *      search queries (different facets, uk/en as appropriate).
 *   2. SEARCH — each query hits DDG Lite (same keyless backend, same
 *      honest bot-wall handling as web.search).
 *   3. READ — the top distinct-domain result pages are actually fetched
 *      (https-only, size-capped) and stripped to text. Snippets lie;
 *      pages don't.
 *   4. SYNTHESIZE — one model turn folds question + page digests into a
 *      sourced answer; sources are FILTERED to URLs that were really
 *      visited/found, so it cannot cite an invented link.
 *
 * Output is a widget-shaped `{"type":"citation-answer",…}` so the answer
 * renders as the sourced facet immediately (runToolCall step 5) and the
 * chain continues for the model's own follow-up.
 *
 * Progress is streamed as [ToolEvent.Notice] («шукаю: …», «читаю 2/4…»)
 * into the visible tool-trace line — the operator watches the research
 * happen instead of staring at a spinner.
 */
class DeepResearchTool(
    private val httpClient: HttpClient,
    private val complete: suspend (system: String, user: String) -> String,
    private val endpoint: String = WebSearchTool.DDG_LITE_ENDPOINT,
    private val maxPages: Int = 4,
) : Tool {

    override val id = "research.deep"

    override val description =
        "ГЛИБОКЕ дослідження: розкладає питання на кілька пошуків, ЧИТАЄ топові сторінки " +
            "(не лише сніпети) і синтезує відповідь з реальними джерелами. Використовуй для " +
            "«розберись/дослідь/порівняй детально», багатогранних чи спірних питань. " +
            "Для швидкого одиночного факту досить web.search. " +
            "Відповідь несе pages_read / pages_attempted / sources_count і basis — скільки з обіцяної " +
            "глибини справді сталося; якщо сторінки не відкрились, це сказано вголос, а не приховано."

    override val argsSchema: JsonObject = buildJsonObject {
        put("question", JsonPrimitive("string"))
        put("depth", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = true

    override val manifest = FeatureManifest(
        id = "tool.research.deep",
        tier = 1,
        displayName = "Глибокий пошук",
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
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                code = "bad_args",
                message = "порожнє question — нема чого досліджувати. " +
                    "Наступний крок — сформулюй питання оператора одним реченням і виклич ще раз.",
                durationMs = 0L,
            )
        val depth = (args["depth"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 3)
        val queryBudget = 1 + depth // depth 1→2 queries … depth 3→4
        val pageBudget = (maxPages * depth / 2).coerceIn(2, 6)

        // ── 1. Decompose ─────────────────────────────────────────────────
        emit(ToolEvent.Notice("розкладаю питання на пошуки"))
        val queries = decompose(question, queryBudget)

        // ── 2. Search each facet ─────────────────────────────────────────
        val hits = mutableListOf<WebSearchTool.SearchHit>()
        var searchFailures = 0
        for (q in queries) {
            emit(ToolEvent.Notice("шукаю: $q"))
            val page = ddgSearch(q)
            if (page == null) { searchFailures++; continue }
            hits += WebSearchTool.parseDdgLite(page).take(5)
        }
        if (hits.isEmpty()) {
            return ToolResult.Err(
                code = "research_unavailable",
                message = if (searchFailures == queries.size) {
                    "пошук недоступний: жоден із ${queries.size} запитів не пройшов (мережа або бот-стіна). " +
                        "Це збій звʼязку, а не відсутність відповіді — чесно скажи оператору, НЕ вигадуй. " +
                        "Наступний крок — повтори за кілька хвилин або відповідай із власних знань, назвавши це припущенням."
                } else {
                    "за жодним із ${queries.size} запитів немає результатів. Скажи чесно, НЕ вигадуй. " +
                        "Наступний крок — переформулюй питання іншими словами (або іншою мовою) і виклич ще раз."
                },
                durationMs = elapsed(),
            )
        }

        // ── 3. Read the top pages (distinct domains first) ───────────────
        val candidates = hits
            .distinctBy { it.url }
            .filter { it.url.startsWith("https://") }
        val picked = mutableListOf<WebSearchTool.SearchHit>()
        val seenDomains = mutableSetOf<String>()
        for (h in candidates) {
            val domain = runCatching { URI(h.url).host }.getOrNull() ?: continue
            if (seenDomains.add(domain)) picked += h
            if (picked.size >= pageBudget) break
        }
        // Fill up with same-domain extras if distinct domains ran short.
        if (picked.size < pageBudget) {
            picked += candidates.filter { it !in picked }.take(pageBudget - picked.size)
        }

        data class PageDigest(val title: String, val url: String, val text: String)
        val digests = mutableListOf<PageDigest>()
        picked.forEachIndexed { i, hit ->
            val host = runCatching { URI(hit.url).host }.getOrNull() ?: hit.url
            emit(ToolEvent.Notice("читаю ${i + 1}/${picked.size}: $host"))
            val html = fetchPage(hit.url) ?: return@forEachIndexed
            val text = htmlToText(html).take(PAGE_DIGEST_CHARS)
            if (text.length >= 200) digests += PageDigest(hit.title, hit.url, text)
        }

        // ── 4. Synthesize with real sources only ─────────────────────────
        emit(ToolEvent.Notice("синтезую відповідь із ${digests.size} стор. + ${hits.size} сніпетів"))
        val corpus = buildString {
            if (digests.isNotEmpty()) {
                append("ПРОЧИТАНІ СТОРІНКИ:\n")
                digests.forEachIndexed { i, d ->
                    append("[${i + 1}] ${d.title} — ${d.url}\n${d.text}\n\n")
                }
            } else {
                append("Сторінки прочитати не вдалось — лише пошукові сніпети (скажи це у відповіді):\n")
            }
            append("СНІПЕТИ ПОШУКУ:\n")
            hits.take(12).forEach { append("- ${it.title} — ${it.url}: ${it.snippet}\n") }
        }
        val synthRaw = runCatching {
            complete(
                SYNTH_PROMPT,
                "ПИТАННЯ ОПЕРАТОРА: $question\n\n$corpus",
            )
        }.getOrElse { t ->
            return ToolResult.Err(
                code = "synth_error",
                message = "джерела зібрано (${digests.size} стор., ${hits.size} сніпетів), але модель не склала " +
                    "з них відповіді: ${t.message?.take(120) ?: "без деталей"}. " +
                    "Наступний крок — виклич ще раз або звузь питання до однієї грані.",
                durationMs = elapsed(),
            )
        }
        val synth = firstJsonObject(synthRaw)
        val knownUrls = (digests.map { it.url } + hits.map { it.url }).toSet()
        val essence = (synth?.get("essence") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: synthRaw.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
        val answer = (synth?.get("answer") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: synthRaw.take(1500)
        // Law 1: only URLs that actually exist in the visited/found set.
        val sources = ((synth?.get("sources") as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (url !in knownUrls) return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: url
            title to url
        }.take(5).ifEmpty {
            digests.take(3).map { it.title to it.url }
        })

        // Скільки з обіцяної глибини справді сталося — це і є впевненість цієї відповіді.
        val basis = when {
            digests.isEmpty() ->
                "жодної сторінки прочитати не вдалось — відповідь стоїть лише на пошукових сніпетах"
            digests.size < picked.size ->
                "прочитано ${digests.size} сторінок із ${picked.size} відкритих; решта не відкрилась"
            else -> "прочитано ${digests.size} сторінок повністю"
        }
        val answerWithBasis = if (digests.isEmpty()) "$answer\n\nПідстава: $basis." else answer

        val provenance = buildJsonObject {
            put("queries", buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } })
            put("queries_failed", JsonPrimitive(searchFailures))
            put("pages_attempted", JsonPrimitive(picked.size))
            put("pages_read", JsonPrimitive(digests.size))
            put("hits", JsonPrimitive(hits.size))
            put("sources_count", JsonPrimitive(sources.size))
            put("basis", JsonPrimitive(basis))
        }

        // Картка citation-answer малює порожню відсутність, якщо джерел нема, —
        // відповідь із нульовими джерелами доходить до оператора лише як проза.
        if (sources.isEmpty()) {
            return ToolResult.Ok(
                output = "$essence\n\n$answer\n\nДжерел підтвердити не вдалось: $basis.",
                structured = provenance,
                durationMs = elapsed(),
            )
        }

        val widgetJson = buildJsonObject {
            put("type", JsonPrimitive("citation-answer"))
            put("say", JsonPrimitive(essence))
            put("payload", buildJsonObject {
                put("essence", JsonPrimitive(essence))
                put("answer", JsonPrimitive(answerWithBasis))
                put("sources", buildJsonArray {
                    sources.forEach { (title, url) ->
                        add(buildJsonObject {
                            put("title", JsonPrimitive(title))
                            put("url", JsonPrimitive(url))
                        })
                    }
                })
            })
        }
        return ToolResult.Ok(
            output = widgetJson.toString(),
            structured = provenance,
            durationMs = elapsed(),
        )
    }

    private suspend fun decompose(question: String, budget: Int): List<String> {
        val raw = runCatching { complete(DECOMPOSE_PROMPT, question) }.getOrNull()
            ?: return listOf(question)
        val arr = firstJsonObject(raw)?.get("queries") as? JsonArray
        val queries = arr.orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(budget)
        return queries.ifEmpty { listOf(question) }
    }

    private suspend fun ddgSearch(query: String): String? = runCatching {
        val url = "$endpoint/lite/?q=" + URLEncoder.encode(query, "UTF-8")
        val resp: HttpResponse = httpClient.get(url) { header("User-Agent", USER_AGENT) }
        if (!resp.status.isSuccess()) null else resp.bodyAsText()
    }.getOrNull()

    private suspend fun fetchPage(url: String): String? = runCatching {
        val resp: HttpResponse = httpClient.get(url) { header("User-Agent", USER_AGENT) }
        if (!resp.status.isSuccess()) return@runCatching null
        val type = resp.headers["Content-Type"].orEmpty()
        if (type.isNotEmpty() && !type.contains("text/") && !type.contains("html")) {
            return@runCatching null
        }
        resp.bodyAsText().take(MAX_PAGE_BYTES)
    }.getOrNull()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile) PhantomCompanion/3.0"
        private const val MAX_PAGE_BYTES = 300_000
        private const val PAGE_DIGEST_CHARS = 6_000

        private val SCRIPT_STYLE = Regex(
            "<(script|style|noscript|svg|head)\\b[\\s\\S]*?</\\1>",
            RegexOption.IGNORE_CASE,
        )
        private val TAGS = Regex("<[^>]+>")
        private val ENTITY_NUM = Regex("&#(\\d+);")

        /** Crude but dependency-free HTML → readable text. */
        fun htmlToText(html: String): String = html
            .replace(SCRIPT_STYLE, " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|h[1-6]|tr)>", RegexOption.IGNORE_CASE), "\n")
            .replace(TAGS, " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(ENTITY_NUM) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()

        private const val DECOMPOSE_PROMPT = """
Ти — плановик пошуку. Розклади питання оператора на 2-4 КОРОТКІ пошукові запити,
що покривають різні грані (факти, порівняння, свіжі новини, першоджерела).
Мова запитів = мова, якою найімовірніше існує найкраща відповідь (укр/англ).
Відповідай ЛИШЕ JSON: {"queries":["...","..."]}
"""

        private const val SYNTH_PROMPT = """
Ти — дослідник. Синтезуй ВІДПОВІДЬ на питання оператора ВИКЛЮЧНО з наданих
сторінок і сніпетів. Правила:
- Жодних фактів поза наданим корпусом; суперечності між джерелами назви явно.
- Якщо корпус не відповідає на питання — скажи чесно, що знайти не вдалось.
- Цитуй ЛИШЕ url, що присутні в корпусі, дослівно.
Відповідай ЛИШЕ JSON:
{"essence":"суть одним реченням","answer":"розгорнута відповідь 3-10 речень",
 "sources":[{"title":"...","url":"..."}]}
"""
    }
}
