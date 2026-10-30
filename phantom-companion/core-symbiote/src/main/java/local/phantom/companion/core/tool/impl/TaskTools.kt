package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.tasks.TaskEntity
import local.phantom.companion.core.data.tasks.TaskStore
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Tasks + checklists as verbs: `task.add`, `task.list`, `task.done`,
 * `task.remove`. Items group into named checklists via `list`. Offline, local DB.
 */

private fun TaskEntity.toJson() = buildJsonObject {
    put("id", id)
    put("title", title)
    put("done", done)
    put("priority", priority)
    put("list", listName)
    dueMs?.let { put("due_ms", it) }
    notes?.let { put("notes", it) }
}

/** `task.add` — add a task/checklist item. */
class TaskAddTool(private val store: TaskStore) : Tool {
    override val id = "task.add"
    override val description =
        "Додати задачу або пункт чеклиста. title — текст; list — назва списку (типово inbox); " +
            "priority — 0 звичайна/1 висока/2 термінова; local_due — дедлайн СЛОВАМИ оператора у МІСЦЕВОМУ часі, " +
            "\"18:00\" або \"2026-07-27 09:00\" (пристрій сам переведе — надавай перевагу ЦЬОМУ полю, epoch не рахуй); " +
            "due_ms — дедлайн epoch ms, лише якщо він точно відомий; notes — деталі. Повертає id."
    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string"))
        put("list", JsonPrimitive("string?"))
        put("priority", JsonPrimitive("number?"))
        put("local_due", JsonPrimitive("string?"))
        put("due_ms", JsonPrimitive("number?"))
        put("notes", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.task.add", "Додати задачу")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "немає title — задача без тексту нічого не означає. Повтори виклик, поклавши " +
                    "у title те, що оператор просив зробити.",
                System.currentTimeMillis() - start,
            )
        // local_due first: it is the only one the model gets right.
        val localDue = args["local_due"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val parsedLocal = localDue?.let { resolveLocalTime(it, start) }
        // An unparseable local_due used to fall through in silence: the operator
        // said «о 18:00», the task was saved without any deadline, and the answer
        // said «додав» as if the time had been kept.
        if (localDue != null && parsedLocal == null && args["due_ms"] == null) {
            return ToolResult.Err(
                "bad_time",
                "не розібрав час «$localDue», а вигадувати дедлайн не буду. Повтори виклик у форматі " +
                    "«18:00» або «2026-08-20 09:00» — або без local_due, якщо строку насправді нема.",
                System.currentTimeMillis() - start,
            )
        }
        val dueMs = parsedLocal ?: args["due_ms"]?.jsonPrimitive?.longOrNull
        val listName = args["list"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "inbox"
        val priority = (args["priority"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 2)
        val id = runCatching {
            store.add(
                title = title,
                listName = listName,
                priority = priority,
                dueMs = dueMs,
                notes = args["notes"]?.jsonPrimitive?.content,
            )
        }.getOrElse { e ->
            return ToolResult.Err(
                "store_error",
                "не зміг записати задачу до сховища (${e.message ?: "невідома причина"}). " +
                    "Скажи оператору, що вона НЕ збережена, і запропонуй повторити.",
                System.currentTimeMillis() - start,
            )
        }
        if (id.isEmpty()) {
            return ToolResult.Err(
                "store_error",
                "сховище не повернуло ідентифікатор — задача не збережена. Скажи це прямо і повтори.",
                System.currentTimeMillis() - start,
            )
        }
        val dueWord = dueMs?.let {
            val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.forLanguageTag("uk"))
            " Строк: ${fmt.format(java.util.Date(it))}."
        } ?: " Строку нема."
        return ToolResult.Ok(
            output = "Додав задачу «$title» до списку «$listName».$dueWord",
            structured = buildJsonObject {
                put("id", id)
                put("title", title)
                put("list", listName)
                put("priority", priority)
                dueMs?.let { put("due_ms", it) }
                put("has_due", dueMs != null)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/** `task.list` — pending tasks, optionally scoped to one checklist. */
class TaskListTool(private val store: TaskStore) : Tool {
    override val id = "task.list"
    override val description =
        "Показати задачі. list — назва списку (без нього — всі відкриті за пріоритетом); " +
            "limit — скільки (типово 20); include_done — чи показувати вже виконані (типово ні). " +
            "Повертає, скільки всього і скільки показано, — не видавай показане за все."
    override val argsSchema: JsonObject = buildJsonObject {
        put("list", JsonPrimitive("string?"))
        put("limit", JsonPrimitive("number?"))
        put("include_done", JsonPrimitive("boolean?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.task.list", "Список задач")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val list = args["list"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
        val includeDone = args["include_done"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val all = runCatching {
            if (list != null) store.list(list) else store.pending(limit)
        }.getOrElse { e ->
            return ToolResult.Err(
                "store_error",
                "не зміг прочитати сховище задач (${e.message ?: "невідома причина"}). " +
                    "Не кажи «задач нема» — ти їх просто не бачив; запропонуй повторити.",
                System.currentTimeMillis() - start,
            )
        }
        // The scoped branch returned the whole checklist, done rows included, and
        // ignored limit — «покажи 3» printed forty, half of them already ticked.
        val visible = if (includeDone) all else all.filter { !it.done }
        val tasks = visible.take(limit)
        val doneCount = all.count { it.done }
        val scope = list?.let { "«$it»" } ?: "усіх списках"
        val text = when {
            tasks.isEmpty() && doneCount > 0 && !includeDone ->
                "У $scope відкритих задач нема; $doneCount вже виконано (include_done:true, щоб побачити)."
            tasks.isEmpty() -> "У $scope задач нема."
            else -> tasks.joinToString("\n") { "${if (it.done) "☑" else "☐"} ${it.title}" }
        }
        val more = if (visible.size > tasks.size) " Показано ${tasks.size} з ${visible.size}." else ""
        return ToolResult.Ok(
            output = text + more,
            // `list` rides along so the card re-reads THIS checklist, not all tasks.
            structured = buildJsonObject {
                list?.let { put("list", it) }
                put("tasks", buildJsonArray { tasks.forEach { add(it.toJson()) } })
                put("shown", tasks.size)
                put("open_total", visible.size)
                put("done_total", doneCount)
                put("include_done", includeDone)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/** `task.done` — mark a task done (or undone with `done:false`), by id or by title. */
class TaskDoneTool(private val store: TaskStore) : Tool {
    override val id = "task.done"
    override val description =
        "Позначити задачу виконаною. title — частина тексту задачі («купити хліб»), НАДІЙНІШИЙ спосіб: id оператор не бачить. " +
            "id — точний ідентифікатор з task.list/task.add. done:false — зняти позначку. " +
            "Якщо під title підходить кілька — інструмент поверне їх список, перепитай оператора."
    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string?"))
        put("id", JsonPrimitive("string?"))
        put("done", JsonPrimitive("boolean?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.task.done", "Задача виконана")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null && title == null) {
            return ToolResult.Err(
                "bad_args",
                "не вказано ні title, ні id — незрозуміло, яку задачу закрити. Повтори виклик, " +
                    "поклавши у title частину її тексту.",
                System.currentTimeMillis() - start,
            )
        }
        val done = args["done"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        fun dur() = System.currentTimeMillis() - start
        return runCatching {
            if (id != null && store.setDone(id, done)) {
                val row = store.get(id)
                return@runCatching ToolResult.Ok(
                    output = if (done) "Задача «${row?.title ?: id}» виконана." else "Зняв позначку з «${row?.title ?: id}».",
                    structured = buildJsonObject {
                        put("updated", 1)
                        put("id", id)
                        row?.title?.let { put("title", it) }
                        put("done", done)
                    },
                    durationMs = dur(),
                )
            }
            val needle = title ?: id!!
            // Only rows this call would actually change — «виконано» must not match already-ticked ones.
            val matches = matchByText(store.list(null).filter { it.done != done }, needle) { it.title }
            when (matches.size) {
                0 -> ToolResult.Err(
                    "not_found",
                    "серед " + (if (done) "відкритих" else "виконаних") + " задач нема жодної схожої на «$needle». " +
                        "Наступний крок: виклич task.list і назви оператору те, що є насправді.",
                    dur(),
                )
                1 -> {
                    store.setDone(matches[0].id, done)
                    ToolResult.Ok(
                        output = if (done) "Задача «${matches[0].title}» виконана." else "Зняв позначку з «${matches[0].title}».",
                        structured = buildJsonObject {
                            put("updated", 1)
                            put("id", matches[0].id)
                            put("title", matches[0].title)
                            put("done", done)
                        },
                        durationMs = dur(),
                    )
                }
                else -> ToolResult.Err(
                    "ambiguous",
                    "під «$needle» підходить ${matches.size}: " + matches.joinToString("; ") { "«${it.title}»" } +
                        ". Нічого не змінив. Наступний крок: спитай оператора, яку саме.",
                    dur(),
                )
            }
        }.getOrElse { e ->
            ToolResult.Err(
                "store_error",
                "сховище задач не відповіло (${e.message ?: "невідома причина"}) — позначку НЕ поставлено. " +
                    "Скажи це прямо і запропонуй повторити.",
                dur(),
            )
        }
    }
}

/** `task.remove` — delete a task outright (wrong row, not a finished one). */
class TaskRemoveTool(private val store: TaskStore) : Tool {
    override val id = "task.remove"
    override val description =
        "Видалити задачу зовсім (помилкову, не виконану — для виконаної є task.done). " +
            "title — частина тексту задачі, НАДІЙНІШИЙ спосіб; id — точний ідентифікатор, якщо він у тебе є. " +
            "Якщо під title підходить кілька — інструмент поверне їх список, перепитай оператора."
    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string?"))
        put("id", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.task.remove", "Видалити задачу")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null && title == null) {
            return ToolResult.Err(
                "bad_args",
                "не вказано ні title, ні id — незрозуміло, що видаляти, а видалення незворотне. " +
                    "Повтори виклик, поклавши у title частину тексту задачі.",
                System.currentTimeMillis() - start,
            )
        }
        fun dur() = System.currentTimeMillis() - start
        return runCatching {
            if (id != null) {
                val row = store.get(id)
                if (store.remove(id)) {
                    return@runCatching ToolResult.Ok(
                        output = "Видалив задачу «${row?.title ?: id}» — назавжди.",
                        structured = buildJsonObject {
                            put("removed", 1)
                            put("id", id)
                            row?.title?.let { put("title", it) }
                        },
                        durationMs = dur(),
                    )
                }
            }
            val needle = title ?: id!!
            val matches = matchByText(store.list(null), needle) { it.title }
            when (matches.size) {
                0 -> ToolResult.Err(
                    "not_found",
                    "задачі, схожої на «$needle», у сховищі нема — нічого не видалив. " +
                        "Наступний крок: виклич task.list і назви оператору те, що є насправді.",
                    dur(),
                )
                1 -> {
                    store.remove(matches[0].id)
                    ToolResult.Ok(
                        output = "Видалив задачу «${matches[0].title}» — назавжди.",
                        structured = buildJsonObject { put("removed", 1); put("id", matches[0].id); put("title", matches[0].title) },
                        durationMs = dur(),
                    )
                }
                else -> ToolResult.Err(
                    "ambiguous",
                    "під «$needle» підходить ${matches.size}: " + matches.joinToString("; ") { "«${it.title}»" } +
                        ". Нічого не видалив — видалення незворотне. Наступний крок: спитай оператора, яку саме.",
                    dur(),
                )
            }
        }.getOrElse { e ->
            ToolResult.Err(
                "store_error",
                "сховище задач не відповіло (${e.message ?: "невідома причина"}) — задачу НЕ видалено. " +
                    "Скажи це прямо і запропонуй повторити.",
                dur(),
            )
        }
    }
}
