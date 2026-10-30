package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.UUID

/**
 * R6.2 PhantomCanvas — the model authors a whole artefact here, over several
 * real passes, and may hand a heavy part of it to a sub-agent turn.
 *
 * `canvas.build` план → делегування розділів підагентам → звід і самокритика.
 * Each step is an independent model turn; the supervision loop stops early
 * when the supervisor answers ГОТОВО, so «multi-pass» is iteration, not a
 * two-line script.
 *
 * `canvas.patch` evolves an EXISTING canvas: the current spec goes in, the
 * revised one comes out, the version bumps and the SAME card on screen is
 * rewritten (StreamToolRunner) — no second card, no divergence between the
 * registry and the pixels.
 *
 * Identity is durable because it is keyed to what persists. [CanvasRegistry]
 * caches specs per process, but on a miss it asks [CanvasRegistry.bindOnScreen]'s
 * lookup — bound to the canvas CARDS in the stream, which are restored from
 * the database on cold launch. So «зроби ширшим» still works the morning after
 * a restart, and a canvas whose card is gone honestly answers canvas_not_found
 * instead of editing a ghost.
 */
object CanvasRegistry {
    private const val MAX_ENTRIES = 32
    private val entries = LinkedHashMap<String, String>()

    /** Resolves a canvas from the persisted stream: id → spec JSON, or the
     *  newest canvas when the id is null. Bound once by StreamToolRunner. */
    @Volatile
    private var onScreen: ((canvasId: String?) -> String?)? = null

    fun bindOnScreen(lookup: (canvasId: String?) -> String?) {
        onScreen = lookup
    }

    @Synchronized
    fun put(canvasId: String, specJson: String) {
        entries.remove(canvasId)
        entries[canvasId] = specJson
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    private fun cached(canvasId: String): String? = entries[canvasId]

    @Synchronized
    private fun lastCached(): Pair<String, String>? =
        entries.entries.lastOrNull()?.let { it.key to it.value }

    // Not @Synchronized: the lookup reaches into UI state and must not run
    // under this object's monitor.
    fun get(canvasId: String): String? =
        cached(canvasId) ?: onScreen?.invoke(canvasId)?.also { put(canvasId, it) }

    /** What a bare «зроби ширшим» means: the newest canvas still on screen. */
    fun latest(): Pair<String, String>? {
        val fromScreen = onScreen?.invoke(null)
        if (fromScreen != null) {
            val id = (firstJsonObject(fromScreen)?.get("canvas_id") as? JsonPrimitive)?.contentOrNull
            if (!id.isNullOrBlank()) {
                put(id, fromScreen)
                return id to fromScreen
            }
        }
        return lastCached()
    }

    @Synchronized
    fun clear() = entries.clear()
}

/** Shared node-grammar block both canvas tools inject into their system prompts. */
internal const val CANVAS_GRAMMAR = """
Ти будуєш СПЕЦИФІКАЦІЮ ПОЛОТНА — JSON-обʼєкт {"title":"...","nodes":[...]}.
Доступні вузли (поле "n"):
- {"n":"text","text":"...","style":"title|body|caption|code"}
- {"n":"row","children":[...]} / {"n":"col","children":[...]} — row ділить ширину порівну;
  дай дитині "w":2 щоб задати пропорцію, "w":0 — щоб стисла до вмісту
- {"n":"stat","label":"...","value":"8 412","unit":"₴","delta":"+12%"} — велике число
- {"n":"kv","pairs":[["ключ","значення"],["ключ2","значення2"]]} — вирівняні пари
- {"n":"gauge","value":0.72,"label":"..."} — шкала 0..1
- {"n":"chart","chart":{"kind":"line|bar","title":"...","data":[{"x":0,"y":68}]}} — графік
- {"n":"badge","text":"...","tone":"info|ok|warn|bad"} — маленька пігулка-статус
- {"n":"table","cols":["день","км"],"rows":[["пн","4,2"],["вт","6,1"]]} — таблиця, широка прокручується вбік
- {"n":"list","style":"bullet|number|check","items":[{"text":"аптечка","done":true}]} — перелік/чеклист
- {"n":"steps","items":[{"text":"вихід","state":"done"},{"text":"перехід","state":"now"},{"text":"привал","state":"next"}]} — послідовність
- {"n":"note","title":"увага","text":"...","tone":"info|ok|warn|bad"} — виносний блок, те що конче треба прочитати
- {"n":"divider"} / {"n":"gap","h":12}
- {"n":"button","label":"...","tool":"<tool-id>","args":{...}} — жива кнопка-дія
- {"n":"button","label":"...","reply":"текст питання"} — кнопка-підказка: текст ЛЯЖЕ У ПОЛЕ ВВОДУ
  оператора, і надішле його він сам. Пиши її як питання від тебе, не як слова оператора.
ПРАВИЛА:
- ЛИШЕ реальні дані з запиту/контексту; нічого не вигадуй. Нема числа — не малюй stat.
- Компактність: 5-25 вузлів зазвичай; максимум 120, вкладеність ≤ 6.
- Кнопки-tool лише з інструментами, які реально існують (tool-id з контексту місії).
- Відповідай ОДНИМ JSON-обʼєктом без пояснень і без markdown-фенсів.
"""

/** Pass 1 of a full build: the plan, not the pixels. */
private const val CANVAS_PLAN_GRAMMAR = """
Ти ГОЛОВНИЙ архітектор полотна. Ще НЕ малюй вузли — розбий роботу на розділи,
кожен з яких окремо збере підагент.
Поверни ОДИН JSON-обʼєкт:
{"title":"назва полотна","sections":[{"title":"назва розділу","brief":"що саме показати в цьому розділі, з якими даними"}]}
ПРАВИЛА:
- 2-5 розділів. Менше — якщо матеріалу мало; порожніх розділів не вигадуй.
- brief пиши конкретно: які саме числа/факти з наданих даних туди йдуть.
- Якщо даних на розділ нема — не створюй його.
- Без пояснень і без markdown-фенсів.
"""

/** Extract the first balanced `{…}` object (fences tolerated), no "type" requirement. */
internal fun firstJsonObject(raw: String): JsonObject? {
    val trimmed = raw.trim().let { t ->
        if (!t.startsWith("```")) t
        else {
            val nl = t.indexOf('\n')
            if (nl < 0) t else {
                val body = t.substring(nl + 1)
                val close = body.lastIndexOf("```")
                if (close < 0) body else body.substring(0, close)
            }
        }
    }
    val start = trimmed.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until trimmed.length) {
        val c = trimmed[i]
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
                if (depth == 0) {
                    val candidate = trimmed.substring(start, i + 1)
                    return runCatching {
                        Json { ignoreUnknownKeys = true; isLenient = true }
                            .parseToJsonElement(candidate) as? JsonObject
                    }.getOrNull()
                }
            }
        }
    }
    return null
}

/** Structural sanity: a spec (bare or `{type,payload}`-wrapped) with a non-empty nodes[]. */
internal fun unwrapCanvasSpec(obj: JsonObject): JsonObject? {
    val spec = (obj["payload"] as? JsonObject) ?: obj
    val nodes = spec["nodes"] as? JsonArray ?: return null
    if (nodes.isEmpty()) return null
    return spec
}

/** Sub-agent answers carry only the block: `{"nodes":[…]}`. */
private fun sectionNodes(raw: String): List<JsonElement>? {
    val obj = firstJsonObject(raw) ?: return null
    val spec = (obj["payload"] as? JsonObject) ?: obj
    val nodes = spec["nodes"] as? JsonArray ?: return null
    return nodes.takeIf { it.isNotEmpty() }?.toList()
}

private data class CanvasSection(val title: String, val brief: String)

private fun planSections(plan: JsonObject): List<CanvasSection> =
    (plan["sections"] as? JsonArray).orEmpty().mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val brief = (obj["brief"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (brief.isBlank() && title.isBlank()) null else CanvasSection(title, brief)
    }.take(5)

private fun headingNode(text: String): JsonObject = buildJsonObject {
    put("n", JsonPrimitive("text"))
    put("text", JsonPrimitive(text))
    put("style", JsonPrimitive("title"))
}

/** The gap made visible: a section the sub-agent failed to build says so. */
private fun missingNode(titles: List<String>): JsonObject = buildJsonObject {
    put("n", JsonPrimitive("note"))
    put("title", JsonPrimitive("не зібрано"))
    put(
        "text",
        JsonPrimitive(
            "Розділи, які не вдалось зібрати: ${titles.joinToString(", ")}. " +
                "Тут нема даних — не читай це полотно як повне.",
        ),
    )
    put("tone", JsonPrimitive("warn"))
}

private fun specWith(
    spec: JsonObject,
    canvasId: String,
    version: Int,
    extraNodes: List<JsonElement> = emptyList(),
): JsonObject = buildJsonObject {
    spec.forEach { (k, v) ->
        if (k == "canvas_id" || k == "version") return@forEach
        if (k == "nodes" && extraNodes.isNotEmpty()) {
            put("nodes", JsonArray((v as? JsonArray).orEmpty().toList() + extraNodes))
        } else {
            put(k, v)
        }
    }
    put("canvas_id", JsonPrimitive(canvasId))
    put("version", JsonPrimitive(version))
}

private fun nodeCount(spec: JsonObject): Int = (spec["nodes"] as? JsonArray)?.size ?: 0

private fun canvasManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 1,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

private fun JsonObject.arg(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/**
 * `canvas.build(goal, data?, mode?, passes?)` — supervised multi-pass authoring.
 *
 * @param complete One model turn: (systemPrompt, userPrompt) → final text.
 *                 AppContainer binds this to the live AIRouter. Each call is
 *                 an independent turn — that is what makes a section build a
 *                 sub-agent and not a continuation of the parent's context.
 */
class CanvasBuildTool(
    private val complete: suspend (system: String, user: String) -> String,
    private val newCanvasId: () -> String = { UUID.randomUUID().toString().take(8) },
) : Tool {

    override val id = "canvas.build"

    override val description =
        "Побудувати ПОЛОТНО — цілий нативний застосунок-картку з тексту, чисел, графіків, " +
            "шкал, таблиць і живих кнопок. Багатопрохідно: план → розділи збирають підагенти " +
            "→ звід і самокритика. goal = що показати (РЕАЛЬНІ дані клади в data). " +
            "mode=\"quick\" — один прохід без підагентів; passes=1..3 — скільки раундів зводу."

    override val argsSchema: JsonObject = buildJsonObject {
        put("goal", JsonPrimitive("string"))
        put("data", JsonPrimitive("string?"))
        put("mode", JsonPrimitive("\"full\"|\"quick\"?"))
        put("passes", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = true

    override val manifest = canvasManifest("tool.canvas.build", "Полотно")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val goal = args.arg("goal")
            ?: return ToolResult.Err("bad_args", "goal required", 0L)
        val data = args.arg("data").orEmpty()
        val quick = args.arg("mode") == "quick"
        val rounds = ((args["passes"] as? JsonPrimitive)?.intOrNull ?: if (quick) 1 else 2)
            .coerceIn(1, 3)

        var turns = 0
        val failedSections = mutableListOf<String>()
        var delegated = 0
        var assembled: JsonObject? = null

        if (!quick) {
            emit(ToolEvent.Notice("прохід 1 — план: ділю полотно на розділи"))
            val planRaw = runCatching {
                complete(
                    CANVAS_PLAN_GRAMMAR,
                    buildString {
                        append("Мета полотна: ").append(goal)
                        if (data.isNotBlank()) append("\nДані, які маємо:\n").append(data.take(4000))
                    },
                )
            }.getOrNull()
            turns++
            val plan = planRaw?.let(::firstJsonObject)
            val sections = plan?.let(::planSections).orEmpty()
            if (sections.isNotEmpty()) {
                val nodes = mutableListOf<JsonElement>()
                sections.forEachIndexed { i, section ->
                    val label = section.title.ifBlank { "розділ ${i + 1}" }
                    emit(ToolEvent.Notice("підагент ${i + 1}/${sections.size}: $label"))
                    val raw = runCatching {
                        complete(
                            CANVAS_GRAMMAR,
                            "Ти ПІДАГЕНТ. Збери ЛИШЕ один розділ великого полотна, не все полотно.\n" +
                                "Полотно загалом: $goal\n" +
                                "Твій розділ: $label\n" +
                                "Що в ньому має бути: ${section.brief}\n" +
                                (if (data.isNotBlank()) "Дані:\n${data.take(4000)}\n" else "") +
                                "Поверни {\"nodes\":[...]} — 2-8 вузлів свого розділу, без заголовка розділу.",
                        )
                    }.getOrNull()
                    turns++
                    val built = raw?.let(::sectionNodes)
                    if (built == null) {
                        failedSections += label
                        emit(ToolEvent.Notice("розділ «$label» не зібрався — лишаю чесну прогалину"))
                    } else {
                        if (section.title.isNotBlank()) nodes += headingNode(section.title)
                        nodes += built
                        delegated++
                    }
                }
                if (nodes.isNotEmpty()) {
                    val planTitle = (plan?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    assembled = buildJsonObject {
                        put("title", JsonPrimitive(planTitle.ifBlank { goal.take(60) }))
                        put("nodes", JsonArray(nodes))
                    }
                }
            }
        }

        // Plan or every sub-agent failed — one honest single-pass draft beats
        // reporting nothing at all.
        var current: JsonObject = assembled ?: run {
            emit(ToolEvent.Notice("будую каркас одним проходом"))
            val draftRaw = runCatching {
                complete(
                    CANVAS_GRAMMAR,
                    buildString {
                        append("Мета полотна: ").append(goal)
                        if (data.isNotBlank()) {
                            append("\nРеальні дані для полотна:\n").append(data.take(4000))
                        }
                    },
                )
            }.getOrElse { t ->
                return ToolResult.Err("builder_error", "модель недоступна: ${t.message}", elapsed())
            }
            turns++
            firstJsonObject(draftRaw)?.let(::unwrapCanvasSpec)
                ?: return ToolResult.Err(
                    "bad_draft",
                    "чернетка полотна не зібралась (модель не повернула валідний nodes[]) — " +
                        "скажи оператору чесно або спробуй ще раз із конкретнішим goal",
                    elapsed(),
                )
        }
        for (round in 1..rounds) {
            emit(ToolEvent.Notice("звід $round/$rounds: самокритика"))
            val raw = runCatching {
                complete(
                    CANVAS_GRAMMAR,
                    "Ти СУПЕРВІЗОР полотна. Ось поточна збірка:\n$current\n" +
                        (if (failedSections.isEmpty()) "" else
                            "Розділи без даних (їх не вигадуй): ${failedSections.joinToString(", ")}\n") +
                        "Мета полотна: $goal\n" +
                        "Знайди і виправ: порожні вузли, вигадані числа (ВИДАЛИ), поганий порядок " +
                        "(суть зверху), дублікати від різних підагентів, зламані children, " +
                        "перевантаження (>25 вузлів без потреби).\n" +
                        "Якщо виправляти нема чого — відповідай одним словом ГОТОВО. " +
                        "Інакше поверни ПОВНУ виправлену специфікацію одним JSON-обʼєктом.",
                )
            }.getOrNull()
            turns++
            val next = raw?.let(::firstJsonObject)?.let(::unwrapCanvasSpec)
            if (next == null) break // ГОТОВО, or a pass that returned nothing usable
            if (next.toString() == current.toString()) break
            current = next
        }

        val canvasId = newCanvasId()
        val extra = if (failedSections.isEmpty()) emptyList() else listOf(missingNode(failedSections))
        val finalSpec = specWith(current, canvasId, version = 1, extraNodes = extra)
        val title = (finalSpec["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val nodes = nodeCount(finalSpec)
        CanvasRegistry.put(canvasId, finalSpec.toString())
        emit(ToolEvent.Notice("готово: $nodes вузлів, $turns проходів"))

        val widgetJson = buildJsonObject {
            put("type", JsonPrimitive("canvas"))
            put(
                "say",
                JsonPrimitive(
                    (if (title.isBlank()) "зібрав полотно" else "зібрав полотно «$title»") +
                        " — $nodes вузлів за $turns проходів" +
                        (if (delegated > 0) ", $delegated розділів від підагентів" else ""),
                ),
            )
            put("payload", finalSpec)
        }
        return ToolResult.Ok(
            output = widgetJson.toString(),
            structured = buildJsonObject {
                put("canvas_id", JsonPrimitive(canvasId))
                put("version", JsonPrimitive(1))
                put("nodes", JsonPrimitive(nodes))
                put("passes", JsonPrimitive(turns))
                put("delegated_sections", JsonPrimitive(delegated))
                put("failed_sections", JsonPrimitive(failedSections.size))
            },
            durationMs = elapsed(),
        )
    }
}

/**
 * `canvas.patch(instruction, canvas_id?, section?)` — evolve the artefact.
 *
 * The card on screen is REWRITTEN in place (StreamToolRunner finds it by
 * canvas_id), so version 3 replaces version 2 instead of stacking beside it.
 * With [section] the heavy block is authored by a sub-agent turn first and
 * the patch turn only splices it in; a final verify turn checks the
 * instruction was actually carried out.
 */
class CanvasPatchTool(
    private val complete: suspend (system: String, user: String) -> String,
) : Tool {

    override val id = "canvas.patch"

    override val description =
        "Змінити вже показане полотно за інструкцією («зроби графік ширшим», «додай суму», " +
            "«перероби розділ витрат») — оновиться ТА САМА картка, версія підніметься. " +
            "canvas_id не обовʼязковий: без нього беру останнє полотно у стрічці. " +
            "section=\"назва розділу\" — важкий розділ перезбере підагент."

    override val argsSchema: JsonObject = buildJsonObject {
        put("instruction", JsonPrimitive("string"))
        put("canvas_id", JsonPrimitive("string?"))
        put("section", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = true

    override val manifest = canvasManifest("tool.canvas.patch", "Полотно · правка")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val instruction = args.arg("instruction")
            ?: return ToolResult.Err("bad_args", "instruction required", 0L)
        val askedId = args.arg("canvas_id")
        val section = args.arg("section")

        val resolved: Pair<String, String> = if (askedId != null) {
            val spec = CanvasRegistry.get(askedId)
                ?: return ToolResult.Err(
                    "canvas_not_found",
                    "полотна $askedId нема ні в памʼяті, ні картки у стрічці — " +
                        "збери його заново через canvas.build",
                    elapsed(),
                )
            askedId to spec
        } else {
            CanvasRegistry.latest()
                ?: return ToolResult.Err(
                    "canvas_not_found",
                    "у стрічці нема жодного полотна, яке можна правити — спершу canvas.build",
                    elapsed(),
                )
        }
        val (canvasId, currentJson) = resolved

        var turns = 0
        var handed: List<JsonElement>? = null
        if (section != null) {
            emit(ToolEvent.Notice("підагент перезбирає розділ: $section"))
            val raw = runCatching {
                complete(
                    CANVAS_GRAMMAR,
                    "Ти ПІДАГЕНТ. Перезбери ОДИН розділ полотна — «$section».\n" +
                        "Поточне полотно (контекст, не переписуй його цілком):\n$currentJson\n" +
                        "Що зробити з розділом: $instruction\n" +
                        "Поверни {\"nodes\":[...]} — лише вузли цього розділу.",
                )
            }.getOrNull()
            turns++
            handed = raw?.let(::sectionNodes)
            if (handed == null) {
                emit(ToolEvent.Notice("підагент не дав вузлів — правлю сам, без делегування"))
            }
        }

        emit(ToolEvent.Notice("правлю полотно: $instruction"))
        val patchedRaw = runCatching {
            complete(
                CANVAS_GRAMMAR,
                "Поточна специфікація полотна:\n$currentJson\n\n" +
                    "Інструкція оператора: $instruction\n" +
                    (handed?.let {
                        "Готовий блок від підагента для розділу «$section» — встав ЙОГО " +
                            "замість старих вузлів того розділу, не переписуючи:\n" +
                            JsonArray(it) + "\n"
                    } ?: "") +
                    "Внеси МІНІМАЛЬНІ зміни, що виконують інструкцію (не перебудовуй з нуля), " +
                    "збережи решту вузлів як є. Поверни ПОВНУ оновлену специфікацію одним JSON.",
            )
        }.getOrElse { t ->
            return ToolResult.Err("builder_error", "модель недоступна: ${t.message}", elapsed())
        }
        turns++
        var patched = firstJsonObject(patchedRaw)?.let(::unwrapCanvasSpec)
            ?: return ToolResult.Err(
                "bad_patch",
                "правка не зібралась у валідну специфікацію — полотно лишилось як було",
                elapsed(),
            )

        val before = firstJsonObject(currentJson)?.let(::nodeCount) ?: 0
        emit(ToolEvent.Notice("перевіряю правку: $before → ${nodeCount(patched)} вузлів"))
        val verifyRaw = runCatching {
            complete(
                CANVAS_GRAMMAR,
                "Ти СУПЕРВІЗОР правки полотна.\nБуло ($before вузлів):\n$currentJson\n" +
                    "Стало (${nodeCount(patched)} вузлів):\n$patched\n" +
                    "Інструкція була: $instruction\n" +
                    "Перевір: чи інструкцію справді виконано; чи не зникли вузли, яких " +
                    "прибирати не просили; чи не зʼявились вигадані числа.\n" +
                    "Якщо все гаразд — відповідай одним словом ГОТОВО. Інакше поверни " +
                    "ВИПРАВЛЕНУ повну специфікацію одним JSON-обʼєктом.",
            )
        }.getOrNull()
        turns++
        verifyRaw?.let(::firstJsonObject)?.let(::unwrapCanvasSpec)?.let { patched = it }

        val currentVersion = runCatching {
            (firstJsonObject(currentJson)?.get("version") as? JsonPrimitive)?.intOrNull
        }.getOrNull() ?: 1
        val nextVersion = currentVersion + 1
        val finalSpec = specWith(patched, canvasId, nextVersion)
        CanvasRegistry.put(canvasId, finalSpec.toString())

        val title = (finalSpec["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val widgetJson = buildJsonObject {
            put("type", JsonPrimitive("canvas"))
            put(
                "say",
                JsonPrimitive(
                    (if (title.isBlank()) "оновив полотно" else "оновив полотно «$title»") +
                        " до v$nextVersion — та сама картка у стрічці",
                ),
            )
            put("payload", finalSpec)
        }
        return ToolResult.Ok(
            output = widgetJson.toString(),
            structured = buildJsonObject {
                put("canvas_id", JsonPrimitive(canvasId))
                put("version", JsonPrimitive(nextVersion))
                put("nodes", JsonPrimitive(nodeCount(finalSpec)))
                put("passes", JsonPrimitive(turns))
                put("delegated", JsonPrimitive(handed != null))
            },
            durationMs = elapsed(),
        )
    }
}
