package local.phantom.companion.core.tool.impl

import android.net.Uri
import java.io.File
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * Документ ЛЮДИНИ, а не пісочниця агента.
 *
 * [FileTool] читає теку, куди пише сам агент. Того, що людина дала зі свого
 * телефона — угоду, рахунок, витяг, конспект, — ФАНТОМ прочитати не міг
 * жодним інструментом. Власник назвав це прямо: «аналізувати медіа та інші
 * документи і типи файлів» — і цього не було.
 *
 * Тут навмисно немає власного «розуміння». Інструмент дістає **текст**;
 * думає над ним модель. Так само, як `geo.route` не оцінює відстань сам.
 *
 * **Межа доступу.** Читається лише `content://`, тобто рівно те, що людина
 * щойно вибрала й на що система видала дозвіл. Прямі шляхи на диск
 * відхиляються: інакше дієслово стало б обхідним читанням усього телефона.
 */
class DocumentTool : Tool {

    override val id = "doc"

    override val description =
        "Прочитати документ, який людина дала з телефона: uri = content://… або шлях вкладення, " +
            "що приїхало зі скріпки (воно лежить у теці attachments). Повертає text, name, mime, chars, truncated. " +
            "Двійкові типи (pdf, docx, зображення) НЕ вигадуються — приходить refused із назвою типу, " +
            "і про це треба сказати людині словами, а не переказувати вміст, якого не бачив."

    override val argsSchema: JsonObject = buildJsonObject {
        put("uri", JsonPrimitive("string"))
        put("max_chars", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.FILE_READ)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.doc",
        tier = 1,
        displayName = "Документи людини",
        erodesOperatorAgency = false,
        // Документ не покидає пристрій цим дієсловом: воно лише дістає текст.
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 120,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        ctx: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val raw = args["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (raw.isEmpty()) return@withContext refused("uri порожній", null)
            val attachRoot = File(ctx.appContext.filesDir, "attachments")
            val staged = stagedFile(raw, attachRoot)
            if (!raw.startsWith("content://") && staged == null) {
                return@withContext refused(
                    "читаю лише те, що людина дала сама: content:// або вкладення зі скріпки; " +
                        "решта диска закрита",
                    null,
                )
            }

            val cap = (args["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CHARS)
                .coerceIn(1_000, MAX_CHARS)
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
                ?: return@withContext refused("не розбираю це посилання", null)

            val resolver = ctx.appContext.contentResolver
            val mime = if (staged != null) "" else runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            val name = staged?.name ?: displayName(ctx, uri)

            if (!readableAsText(mime, name)) {
                // Чесна відмова з назвою типу. Мовчазна порожнеча тут була б
                // гірша за все: модель отримала б «нічого» і переказала б
                // документ, якого не бачила.
                return@withContext refused(
                    "цей тип я ще не розбираю: ${mime.ifBlank { "невідомий" }}",
                    name,
                )
            }

            val text = runCatching {
                (staged?.inputStream() ?: resolver.openInputStream(uri))?.use { input ->
                    val buf = CharArray(cap + 1)
                    val reader = input.reader(Charsets.UTF_8)
                    var read = 0
                    while (read < buf.size) {
                        val n = reader.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                }
            }.getOrNull() ?: return@withContext refused("система не дала прочитати цей файл", name)

            val truncated = text.length > cap
            val payload = buildJsonObject {
                    put("uri", JsonPrimitive(raw))
                    put("name", JsonPrimitive(name ?: ""))
                    put("mime", JsonPrimitive(mime))
                    put("chars", JsonPrimitive(minOf(text.length, cap)))
                    put("truncated", JsonPrimitive(truncated))
                put("text", JsonPrimitive(if (truncated) text.substring(0, cap) else text))
            }
            ToolResult.Ok(
                output = "прочитано ${name ?: "документ"}: ${minOf(text.length, cap)} символів" +
                    if (truncated) " (початок)" else "",
                structured = payload,
                durationMs = System.currentTimeMillis() - startMs,
            )
        }

    private fun stagedFile(raw: String, root: File): File? = stagedAttachment(raw, root)

    private fun displayName(ctx: ToolContext, uri: Uri): String? = runCatching {
        ctx.appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Тип визначаємо і за mime, і за розширенням: постачальники часто віддають octet-stream. */
    private fun readableAsText(mime: String, name: String?): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in TEXTUAL_MIMES) return true
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in TEXTUAL_EXTS
    }

    private fun refused(reason: String, name: String?) = ToolResult.Ok(
        output = reason,
        structured = buildJsonObject {
            put("refused", JsonPrimitive(true))
            put("reason", JsonPrimitive(reason))
            put("name", JsonPrimitive(name ?: ""))
        },
        durationMs = 0L,
    )

    private companion object {
        const val DEFAULT_CHARS = 20_000
        const val MAX_CHARS = 120_000

        val TEXTUAL_MIMES = setOf(
            "application/json", "application/xml", "application/xhtml+xml",
            "application/x-yaml", "application/yaml", "application/javascript",
            "application/sql", "application/csv",
        )
        val TEXTUAL_EXTS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "xml", "yml", "yaml",
            "log", "ini", "conf", "cfg", "toml", "html", "htm", "sql", "srt", "vtt",
            "kt", "java", "py", "js", "ts", "tsx", "sh", "gradle", "properties",
        )
    }
}

/**
 * Вкладення зі скріпки, і тільки воно. Порівнюємо КАНОНІЧНІ шляхи: без цього
 * `attachments/../../databases/keys` вивів би дієслово за межі теки, куди
 * людина сама поклала файл, і читання документа стало б читанням телефона.
 *
 * Винесено з класу навмисно: у цьому модулі тести чисто-JVM, без Android, і
 * межа, яку не можна перевірити, — не межа.
 */
internal fun stagedAttachment(raw: String, root: File): File? {
    val f = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
    val r = runCatching { root.canonicalFile }.getOrNull() ?: return null
    if (!f.path.startsWith(r.path + File.separator)) return null
    return f.takeIf { it.isFile && it.canRead() }
}
