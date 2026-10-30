package local.phantom.companion.core.tool.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Executes an arbitrary shell command via `sh -c <cmd>` and streams
 * stdout line-by-line as [ToolEvent.Chunk] events.
 *
 * Requires [ToolPermission.SHELL_EXEC] in [ToolContext.approvedPermissions].
 *
 * Termux integration: when [termuxAvailable] is true the constructor
 * documents that Termux RPC (run-as com.termux) is available, but this
 * class still uses [ProcessBuilder] for Phase 1.C.
 * TODO(Phase 1.D): route through Termux:API RPC when termuxAvailable=true
 *   for access to Android-specific commands (termux-battery-status, etc.).
 *
 * @param termuxAvailable Pass `true` when Termux is installed. Detected at
 *   the call-site via `packageManager.getPackageInfo("com.termux", 0)`.
 */
class ShellTool(
    private val termuxAvailable: Boolean = false,
) : Tool {

    override val id = "shell"

    override val description: String =
        "Виконати команду оболонки через sh -c у пісочниці застосунку. Повертає stdout+stderr разом, " +
            "exit_code і кількість рядків; ненульовий код — це помилка, а не результат. " +
            "Доступні лише каталоги застосунку — це не root і не весь пристрій." +
            if (termuxAvailable) " Termux встановлено (розширені команди — Phase 1.D)." else ""

    override val argsSchema: JsonObject = buildJsonObject {
        put("cmd", JsonPrimitive("string"))
        put("timeoutMs", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.SHELL_EXEC)

    override val risk: ToolRisk = ToolRisk.OUTWARD

    override val askEveryCall: Boolean = true

    override val streaming: Boolean = true

    override val manifest = FeatureManifest(
        id = "tool.shell",
        tier = 1,
        displayName = "Оболонка",
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

        val cmd = args["cmd"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "порожній cmd — виконувати нема чого. Наступний крок: повтори виклик, поклавши " +
                    "у cmd саму команду.",
                System.currentTimeMillis() - startMs,
            )

        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        // Integer seconds turned every sub-second budget into «за 0с» — a limit
        // the operator cannot act on and a number the data does not support.
        fun prettyBudget(ms: Long) = if (ms >= 1000) "${ms / 1000} с" else "$ms мс"

        return withContext(Dispatchers.IO) {
            val proc = runCatching {
                ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse { e ->
                return@withContext ToolResult.Err(
                    "spawn_failed",
                    "не зміг запустити оболонку: ${e.message ?: e::class.simpleName}. " +
                        "На цьому Android sh може бути недоступний — зроби це іншим інструментом.",
                    System.currentTimeMillis() - startMs,
                )
            }

            val output = StringBuilder()
            var exitCode: Int? = null

            val timedOut = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val chunk = line + "\n"
                            output.append(chunk)
                            line = reader.readLine()
                        }
                    }
                    // The exit status used to be read and dropped, so a command
                    // that failed came back as Ok with whatever it printed.
                    exitCode = proc.waitFor()
                }
                // emit chunks after runInterruptible since emit is a suspend fn
                output.toString().lines().filter { it.isNotEmpty() }.forEach { line ->
                    emit(ToolEvent.Chunk(line + "\n"))
                }
            } == null

            val elapsed = System.currentTimeMillis() - startMs
            val text = output.toString()
            val lineCount = text.lines().count { it.isNotBlank() }
            val code = exitCode

            when {
                timedOut -> {
                    proc.destroyForcibly()
                    ToolResult.Err(
                        "timeout",
                        "команда не завершилась за ${prettyBudget(timeoutMs)} і була вбита. " +
                            "Встигла надрукувати $lineCount рядк(ів) — вони неповні. " +
                            "Наступний крок: додай timeoutMs або звузь команду.",
                        elapsed,
                    )
                }
                // Ok, not Err: a failing command says nothing about the tool's
                // health, and three failures in a row — ordinary while debugging —
                // would open ToolExecutor's breaker and take the shell away.
                // The failure is carried by the words and by exit_code instead.
                code != null && code != 0 -> ToolResult.Ok(
                    output = "НЕ ВИКОНАЛОСЬ: «$cmd» завершилась кодом $code" +
                        (if (text.isBlank()) " і нічого не надрукувала." else ":\n" + text.trim().takeLast(1200)) +
                        "\nНаступний крок: виправ команду за цим текстом, не повторюй ту саму.",
                    structured = buildJsonObject {
                        put("cmd", JsonPrimitive(cmd))
                        put("exit_code", JsonPrimitive(code))
                        put("ok", JsonPrimitive(false))
                        put("output_lines", JsonPrimitive(lineCount))
                    },
                    durationMs = elapsed,
                )
                text.isBlank() -> ToolResult.Ok(
                    output = "«$cmd» відпрацювала успішно (код 0) і не надрукувала нічого. " +
                        "Порожній вивід тут означає «зробила», а не «нічого не знайшла».",
                    structured = buildJsonObject {
                        put("cmd", JsonPrimitive(cmd))
                        put("exit_code", JsonPrimitive(0))
                        put("ok", JsonPrimitive(true))
                        put("output_lines", JsonPrimitive(0))
                    },
                    durationMs = elapsed,
                )
                else -> ToolResult.Ok(
                    output = text,
                    structured = buildJsonObject {
                        put("cmd", JsonPrimitive(cmd))
                        put("exit_code", JsonPrimitive(code ?: 0))
                        put("ok", JsonPrimitive(true))
                        put("output_lines", JsonPrimitive(lineCount))
                    },
                    durationMs = elapsed,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
