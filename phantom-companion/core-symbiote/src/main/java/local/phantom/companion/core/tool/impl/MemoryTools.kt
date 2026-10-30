package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.memory.MemoryFactEntity
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * The operator's long-term memory facts as tools:
 * `memory.list`, `memory.forget`, `memory.save`.
 */

private fun MemoryFactEntity.toJson() = buildJsonObject {
    put("id", id)
    put("text", text)
    put("category", category)
    put("confidence", confidence)
    put("reinforceCount", reinforceCount)
    put("lastConfirmedMs", lastConfirmedMs)
}

/** `memory.list` — list facts the AI remembers about the operator. */
class MemoryListTool(private val store: MemoryFactStore) : Tool {
    override val id = "memory.list"
    override val description =
        "Переглянути факти та знання про оператора, збережені у довготривалій памʼяті. " +
            "category — опціональний фільтр за категорією (наприклад: bio, preference, work, habit); " +
            "limit — максимальна кількість фактів (за замовчуванням 50)."
    override val argsSchema: JsonObject = buildJsonObject {
        put("category", JsonPrimitive("string?"))
        put("limit", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = memoryManifest("tool.memory.list", "Перегляд памʼяті")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: 50
        val categoryFilter = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val all = store.list(limit = limit)
        val filtered = if (categoryFilter != null) {
            all.filter { it.category.equals(categoryFilter, ignoreCase = true) }
        } else {
            all
        }

        val elapsed = System.currentTimeMillis() - start
        if (filtered.isEmpty()) {
            val note = if (categoryFilter != null) {
                "У памʼяті немає фактів у категорії «$categoryFilter»."
            } else {
                "Памʼять порожня — жодного факту ще не збережено."
            }
            return ToolResult.Ok(
                note,
                buildJsonObject {
                    put("count", 0)
                    put("facts", buildJsonArray {})
                },
                elapsed,
            )
        }

        val summary = buildString {
            append("Знайдено ${filtered.size} фактів про оператора:\n")
            filtered.forEachIndexed { i, f ->
                append("${i + 1}. [${f.category}] ${f.text} (впевненість: ${(f.confidence * 100).toInt()}%)\n")
            }
        }.trimEnd()

        return ToolResult.Ok(
            summary,
            buildJsonObject {
                put("count", filtered.size)
                put("facts", buildJsonArray { filtered.forEach { add(it.toJson()) } })
            },
            elapsed,
        )
    }
}

/** `memory.forget` — forget a specific fact by ID or matching query. */
class MemoryForgetTool(private val store: MemoryFactStore) : Tool {
    override val id = "memory.forget"
    override val description =
        "Видалити факт із довготривалої памʼяті оператора. " +
            "id — точний ідентифікатор факту, або query — фрагмент тексту для пошуку та видалення."
    override val argsSchema: JsonObject = buildJsonObject {
        put("id", JsonPrimitive("string?"))
        put("query", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = memoryManifest("tool.memory.forget", "Забути факт")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val query = args["query"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        if (id == null && query == null) {
            return ToolResult.Err(
                "missing_arg",
                "Вкажіть `id` факту або `query` для пошуку і видалення з памʼяті.",
                elapsed(),
            )
        }

        if (id != null) {
            store.forget(id)
            return ToolResult.Ok(
                "Факт видалено з памʼяті (ID: $id).",
                buildJsonObject { put("forgotten_id", id) },
                elapsed(),
            )
        }

        // Match by query text
        val matches = store.list().filter { it.text.contains(query!!, ignoreCase = true) }
        if (matches.isEmpty()) {
            return ToolResult.Ok(
                "Не знайдено фактів, що містять «$query».",
                buildJsonObject { put("forgotten_count", 0) },
                elapsed(),
            )
        }

        matches.forEach { store.forget(it.id) }
        val report = "Видалено ${matches.size} фактів за запитом «$query»:\n" +
            matches.joinToString("\n") { "• ${it.text}" }

        return ToolResult.Ok(
            report,
            buildJsonObject {
                put("forgotten_count", matches.size)
                put("forgotten_ids", buildJsonArray { matches.forEach { add(JsonPrimitive(it.id)) } })
            },
            elapsed(),
        )
    }
}

/** `memory.save` — explicitly record a fact about the operator. */
class MemorySaveTool(
    private val store: MemoryFactStore,
    private val embedder: (suspend (String) -> IntArray?)? = null,
) : Tool {
    override val id = "memory.save"
    override val description =
        "Зберегти новий факт про оператора у довготривалу памʼять. " +
            "text — зміст факту (наприклад: «Оператор віддає перевагу каві без цукру»); " +
            "category — категорія (bio, preference, work, habit, fact, etc., default: general)."
    override val argsSchema: JsonObject = buildJsonObject {
        put("text", JsonPrimitive("string"))
        put("category", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = memoryManifest("tool.memory.save", "Зберегти в памʼять")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val text = args["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolResult.Err("empty_text", "Текст факту не може бути порожнім.", elapsed())
        }
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "general"

        val embedding = embedder?.invoke(text)
        val id = store.remember(
            text = text,
            category = category,
            source = "tool.memory.save",
            embedding = embedding,
            nowMs = System.currentTimeMillis(),
        )

        return ToolResult.Ok(
            "Факт збережено у памʼять: «$text» (категорія: $category).",
            buildJsonObject {
                put("id", id)
                put("text", text)
                put("category", category)
            },
            elapsed(),
        )
    }
}

private fun memoryManifest(id: String, displayName: String) =
    local.phantom.companion.core.constitution.FeatureManifest(
        id = id,
        tier = 2,
        displayName = displayName,
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = -1,
        surfaces = setOf(local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM),
    )
