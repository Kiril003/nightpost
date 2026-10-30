package local.phantom.companion.core.tool.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.File

/**
 * File system tool scoped to a sandbox directory.
 *
 * Supported operations: `read`, `write`, `append`, `list`, `delete`, `mkdir`.
 *
 * **Sandbox policy**: all paths are resolved relative to
 * [sandboxProvider]`()` and must remain inside it. Canonical path
 * comparison catches `../` traversal attempts.
 *
 * **Permission mapping**:
 * - Read / list → [ToolPermission.FILE_READ]
 * - Write / append / delete / mkdir → [ToolPermission.FILE_WRITE]
 *
 * To keep this class JVM-pure (no Android runtime required in unit tests)
 * the sandbox root is supplied via the [sandboxProvider] lambda rather
 * than reading `applicationContext.filesDir` directly.
 *
 * @param sandboxProvider Returns the sandbox root [File] on demand.
 *   In production: `{ File(applicationContext.filesDir, "phantom/agent") }`.
 *   In tests: `{ tempDir }`.
 */
class FileTool(
    private val sandboxProvider: () -> File,
) : Tool {

    override val id = "file"

    override val description =
        "Файли в пісочниці агента: op = read | write | append | list | delete | mkdir. " +
            "Кожна відповідь несе op, path і розміри; read позначає truncated, якщо віддав лише початок " +
            "(max_bytes); list дає entries[name,size,isDir], теки першими. " +
            "Порожня відповідь ніколи не мовчить — тека, якої не вдалося прочитати, це помилка, а не «порожньо»."

    override val argsSchema: JsonObject = buildJsonObject {
        put("op", JsonPrimitive("string"))
        put("path", JsonPrimitive("string"))
        put("content", JsonPrimitive("string?"))
        put("recursive", JsonPrimitive("boolean?"))
        put("max_bytes", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.FILE_READ, ToolPermission.FILE_WRITE)

    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.file",
        tier = 1,
        displayName = "Файли",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 120,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        val op = args["op"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return@withContext ToolResult.Err(
                "bad_args",
                "не вказано op. Доступні: $SUPPORTED_OPS. Наступний крок — обери дію і виклич ще раз.",
                0L,
            )

        val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Err(
                "bad_args",
                "не вказано path — шлях відносно пісочниці, напр. «нотатки/план.txt». " +
                    "Наступний крок — виклич op=list з path=\".\", щоб побачити, що там уже є.",
                0L,
            )

        // Permission check per operation
        val requiredPermission = permissionFor(op)
            ?: return@withContext ToolResult.Err(
                "bad_op",
                "невідома дія «$op». Доступні: $SUPPORTED_OPS. Наступний крок — обери одну з них.",
                System.currentTimeMillis() - startMs,
            )

        if (requiredPermission !in context.approvedPermissions) {
            return@withContext ToolResult.PermissionDenied(
                permission = requiredPermission,
                durationMs = System.currentTimeMillis() - startMs,
            )
        }

        // Sandbox resolution + traversal guard
        val sandbox = sandboxProvider()
        sandbox.mkdirs()

        val target = File(sandbox, pathArg)
        val canonicalTarget = target.canonicalPath
        val canonicalSandbox = sandbox.canonicalPath

        if (!canonicalTarget.startsWith(canonicalSandbox + File.separator) &&
            canonicalTarget != canonicalSandbox
        ) {
            return@withContext ToolResult.Err(
                "traversal",
                "шлях «$pathArg» виводить за межі пісочниці — туди доступу немає і не буде. " +
                    "Наступний крок — прибери «..» і працюй усередині пісочниці.",
                System.currentTimeMillis() - startMs,
            )
        }

        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val isSandboxRoot = canonicalTarget == canonicalSandbox

        when (op) {
            "read" -> {
                if (!target.exists()) {
                    return@withContext ToolResult.Err(
                        "not_found",
                        "файла «$pathArg» у пісочниці немає. " +
                            "Наступний крок — op=list на батьківській теці, щоб побачити справжні назви.",
                        elapsed(),
                    )
                }
                if (!target.isFile) {
                    return@withContext ToolResult.Err(
                        "not_a_file",
                        "«$pathArg» — це тека, а не файл. Наступний крок — op=list, щоб побачити вміст.",
                        elapsed(),
                    )
                }
                val total = target.length()
                val cap = args["max_bytes"]?.jsonPrimitive?.intOrNull
                    ?.coerceIn(1, MAX_READ_BYTES.toInt())?.toLong()
                if (cap == null && total > MAX_READ_BYTES) {
                    return@withContext ToolResult.Err(
                        "too_large",
                        "файл «$pathArg» має $total Б, ліміт за раз — $MAX_READ_BYTES Б. " +
                            "Наступний крок — виклич ще раз із max_bytes (до $MAX_READ_BYTES), " +
                            "прочитаєш початок і зрозумієш, чи це той файл.",
                        elapsed(),
                    )
                }
                val wanted = (cap ?: total).coerceAtMost(MAX_READ_BYTES)
                val raw = readHead(target, wanted)
                if (raw.asSequence().take(BINARY_PROBE_BYTES).any { it == 0.toByte() }) {
                    return@withContext ToolResult.Err(
                        "binary",
                        "«$pathArg» — двійковий файл; як текст він дав би сміття, а не вміст. " +
                            "Наступний крок — не читай його, повідом оператору розмір ($total Б) і тип за розширенням.",
                        elapsed(),
                    )
                }
                val content = String(raw, Charsets.UTF_8)
                val truncated = raw.size.toLong() < total
                ToolResult.Ok(
                    output = if (truncated) {
                        "$content\n\n[обрізано: показано ${raw.size} Б із $total Б]"
                    } else {
                        content
                    },
                    structured = buildJsonObject {
                        put("op", "read")
                        put("path", pathArg)
                        put("bytesRead", raw.size.toLong())
                        put("bytes_total", total)
                        put("truncated", truncated)
                    },
                    durationMs = elapsed(),
                )
            }

            "write" -> {
                val content = args["content"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext ToolResult.Err(
                        "bad_args",
                        "для write потрібен content. Наступний крок — передай текст, який треба записати " +
                            "(порожній рядок теж передається явно).",
                        elapsed(),
                    )
                val previous = if (target.isFile) target.length() else null
                target.parentFile?.mkdirs()
                val written = runCatching { target.writeText(content); content.toByteArray().size }
                    .getOrElse { t ->
                        return@withContext ToolResult.Err(
                            "write_failed",
                            "не вдалося записати «$pathArg»: ${t.message ?: "без деталей"}. " +
                                "Наступний крок — перевір, чи існує батьківська тека (op=mkdir), і спробуй ще раз.",
                            elapsed(),
                        )
                    }
                ToolResult.Ok(
                    output = "Записано $written Б у «$pathArg»" +
                        (previous?.let { ", попередній вміст ($it Б) перезаписано" }.orEmpty()) + ".",
                    structured = buildJsonObject {
                        put("op", "write")
                        put("path", pathArg)
                        put("bytesWritten", written)
                        put("replaced", previous != null)
                        previous?.let { put("previous_bytes", it) }
                    },
                    durationMs = elapsed(),
                )
            }

            "append" -> {
                val content = args["content"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext ToolResult.Err(
                        "bad_args",
                        "для append потрібен content. Наступний крок — передай текст, який дописати.",
                        elapsed(),
                    )
                val before = if (target.isFile) target.length() else 0L
                target.parentFile?.mkdirs()
                val added = runCatching { target.appendText(content); content.toByteArray().size }
                    .getOrElse { t ->
                        return@withContext ToolResult.Err(
                            "append_failed",
                            "не вдалося дописати в «$pathArg»: ${t.message ?: "без деталей"}. " +
                                "Наступний крок — перевір шлях і права, потім спробуй ще раз.",
                            elapsed(),
                        )
                    }
                ToolResult.Ok(
                    output = "Дописано $added Б у «$pathArg», стало ${before + added} Б.",
                    structured = buildJsonObject {
                        put("op", "append")
                        put("path", pathArg)
                        put("bytesAppended", added)
                        put("bytes_before", before)
                        put("bytes_after", before + added)
                    },
                    durationMs = elapsed(),
                )
            }

            "list" -> {
                if (!target.exists()) {
                    return@withContext ToolResult.Err(
                        "not_found",
                        "теки «$pathArg» немає. Наступний крок — op=mkdir, якщо її треба створити.",
                        elapsed(),
                    )
                }
                if (!target.isDirectory) {
                    return@withContext ToolResult.Err(
                        "not_a_dir",
                        "«$pathArg» — це файл, а не тека. Наступний крок — op=read, щоб побачити вміст.",
                        elapsed(),
                    )
                }
                // listFiles() віддає null на помилці читання. Згорнути це в порожній
                // список означало б сказати «тут нічого нема» там, де просто не видно.
                val listed = target.listFiles()
                    ?: return@withContext ToolResult.Err(
                        "list_failed",
                        "теку «$pathArg» не вдалося прочитати (права або I/O). " +
                            "Це НЕ означає, що вона порожня. Наступний крок — перевір шлях і спробуй ще раз.",
                        elapsed(),
                    )
                val entries = listed.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                val jsonArray = buildJsonArray {
                    for (entry in entries) {
                        add(buildJsonObject {
                            put("name", entry.name)
                            put("size", if (entry.isFile) entry.length() else 0L)
                            put("isDir", entry.isDirectory)
                        })
                    }
                }
                ToolResult.Ok(
                    output = if (entries.isEmpty()) "Тека «$pathArg» порожня."
                    else entries.joinToString("\n") { it.name },
                    structured = buildJsonObject {
                        put("op", "list")
                        put("path", pathArg)
                        put("entries", jsonArray)
                        put("count", entries.size)
                        put("dirs", entries.count { it.isDirectory })
                        put("files", entries.count { it.isFile })
                        put("total_bytes", entries.filter { it.isFile }.sumOf { it.length() })
                    },
                    durationMs = elapsed(),
                )
            }

            "delete" -> {
                if (isSandboxRoot) {
                    return@withContext ToolResult.Err(
                        "refused",
                        "це корінь пісочниці — стерти його означає стерти всю памʼять агента разом. " +
                            "Наступний крок — вкажи конкретну теку або файл усередині.",
                        elapsed(),
                    )
                }
                if (!target.exists()) {
                    return@withContext ToolResult.Err(
                        "not_found",
                        "«$pathArg» немає — стирати нічого. Наступний крок — op=list, щоб знайти справжню назву.",
                        elapsed(),
                    )
                }
                val recursive = args["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val wasDir = target.isDirectory
                val deleted = if (recursive) target.deleteRecursively() else target.delete()
                if (!deleted) {
                    return@withContext ToolResult.Err(
                        "delete_failed",
                        "«$pathArg» не стерто" +
                            (if (wasDir && !recursive) " — тека не порожня. Наступний крок — повтори з recursive=true, якщо оператор цього хоче."
                            else ". Наступний крок — перевір права і спробуй ще раз."),
                        elapsed(),
                    )
                }
                ToolResult.Ok(
                    output = if (wasDir) "Теку «$pathArg» стерто." else "Файл «$pathArg» стерто.",
                    structured = buildJsonObject {
                        put("op", "delete")
                        put("path", pathArg)
                        put("deleted", pathArg)
                        put("was_dir", wasDir)
                        put("recursive", recursive)
                    },
                    durationMs = elapsed(),
                )
            }

            "mkdir" -> {
                val existed = target.isDirectory
                val created = target.mkdirs()
                if (!created && !target.isDirectory) {
                    return@withContext ToolResult.Err(
                        "mkdir_failed",
                        "теку «$pathArg» не створено" +
                            (if (target.isFile) " — за цим шляхом уже лежить файл. Наступний крок — обери іншу назву."
                            else ". Наступний крок — перевір шлях і права."),
                        elapsed(),
                    )
                }
                ToolResult.Ok(
                    output = if (existed) "Тека «$pathArg» уже була." else "Теку «$pathArg» створено.",
                    structured = buildJsonObject {
                        put("op", "mkdir")
                        put("path", pathArg)
                        put("created", !existed)
                    },
                    durationMs = elapsed(),
                )
            }

            else -> ToolResult.Err(
                "bad_op",
                "невідома дія «$op». Доступні: $SUPPORTED_OPS.",
                elapsed(),
            )
        }
    }

    private fun permissionFor(op: String): ToolPermission? = when (op) {
        "read", "list" -> ToolPermission.FILE_READ
        "write", "append", "delete", "mkdir" -> ToolPermission.FILE_WRITE
        else -> null
    }

    /** Читає лише [wanted] байтів, щоб max_bytes не тягнув у памʼять весь файл. */
    private fun readHead(file: File, wanted: Long): ByteArray {
        val size = wanted.coerceAtMost(MAX_READ_BYTES).toInt()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        var filled = 0
        file.inputStream().use { input ->
            while (filled < size) {
                val n = input.read(buffer, filled, size - filled)
                if (n < 0) break
                filled += n
            }
        }
        return if (filled == size) buffer else buffer.copyOf(filled)
    }

    private companion object {
        /** Maximum bytes readable in a single `read` call (256 KB). */
        const val MAX_READ_BYTES = 256 * 1024L
        const val BINARY_PROBE_BYTES = 4096
        const val SUPPORTED_OPS = "read, write, append, list, delete, mkdir"
    }
}
