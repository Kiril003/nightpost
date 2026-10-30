package local.phantom.companion.core.tool.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
import local.phantom.companion.core.vision.engine.ImageInspector
import local.phantom.companion.core.vision.engine.VisionRead

/**
 * Світлина ЛЮДИНИ — рівно те, що [DocumentTool] чесно відмовлявся читати.
 *
 * Рушій розпізнавання на пристрої стояв у дереві весь час, але жив лише на
 * екрані «Лінза»: сфотографований документ ФАНТОМ прочитати не міг жодним
 * дієсловом, хоча телефон фізично вмів. Тут зведено два наявні кінці, а не
 * додано нову спроможність.
 *
 * **Чого цей інструмент НЕ робить, і це головне.** Він не описує зображення.
 * На пристрої немає моделі, яка бачить, тож «на світлині кімната з вікном»
 * було б вигадкою. Дієслово віддає **витяг**: текст латиницею (OCR) і вміст
 * кодів (QR/штрих). Усе інше — чесна порожнеча з названою причиною.
 *
 * **Названа межа:** OCR читає **лише латиницю**. Українську сторінку рушій не
 * прочитає, і саме тому порожнеча тут приходить із поясненням: інакше модель
 * прийняла б «нічого не знайдено» за «на аркуші нічого не написано».
 */
class ImageTool(
    private val inspector: ImageInspector = ImageInspector(),
) : Tool {

    override val id = "image"

    override val description =
        "Витягти з ФОТОГРАФІЇ, яку дала людина, те, що пристрій справді бачить: текст (OCR, " +
            "ЛИШЕ латиниця) і вміст кодів QR/штрих. uri = content://… або шлях вкладення зі скріпки. " +
            "Повертає found, text, codes, script_support. Інструмент НЕ описує зображення — моделі, " +
            "яка бачить, на пристрої немає. Якщо found=nothing, скажи це людині словами й назви " +
            "причину з reason; переказувати вміст світлини заборонено."

    override val argsSchema: JsonObject = buildJsonObject {
        put("uri", JsonPrimitive("string"))
        put("max_chars", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.FILE_READ)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.image",
        tier = 1,
        displayName = "Світлини людини",
        erodesOperatorAgency = false,
        // Світлина не покидає пристрій: розпізнавання цілком на ньому.
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
            val staged = stagedAttachment(raw, attachRoot)
            if (!raw.startsWith("content://") && staged == null) {
                return@withContext refused(
                    "дивлюсь лише на те, що людина дала сама: content:// або вкладення зі скріпки; " +
                        "решта диска закрита",
                    null,
                )
            }

            val cap = (args["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CHARS)
                .coerceIn(500, MAX_CHARS)
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
                ?: return@withContext refused("не розбираю це посилання", null)

            val resolver = ctx.appContext.contentResolver
            val mime = if (staged != null) "" else runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            val name = staged?.name ?: displayName(ctx, uri)
            if (!looksLikeImage(mime, name)) {
                return@withContext refused(
                    "це не світлина: ${mime.ifBlank { "невідомий тип" }}. Текстовий документ читає дієслово doc",
                    name,
                )
            }

            val bitmap = runCatching {
                decodeDownsampled(
                    openBytes = { staged?.inputStream() ?: resolver.openInputStream(uri) },
                )
            }.getOrNull() ?: return@withContext refused("система не дала відкрити цю світлину", name)

            val read = runCatching { inspector.inspect(bitmap) }
                .getOrElse { VisionRead.Failed(it.message ?: "рушій розпізнавання впав") }
            bitmap.recycle()

            when (read) {
                is VisionRead.Failed -> refused("рушій розпізнавання не подивився: ${read.reason}", name)

                is VisionRead.Empty -> ToolResult.Ok(
                    output = "нічого не розпізнав на ${name ?: "світлині"}: $LATIN_NOTE",
                    structured = buildJsonObject {
                        put("uri", JsonPrimitive(raw))
                        put("name", JsonPrimitive(name ?: ""))
                        put("found", JsonPrimitive("nothing"))
                        put("reason", JsonPrimitive(LATIN_NOTE))
                        put("script_support", JsonPrimitive(SCRIPT_SUPPORT))
                    },
                    durationMs = System.currentTimeMillis() - startMs,
                )

                is VisionRead.Found -> {
                    val text = if (read.text.length > cap) read.text.substring(0, cap) else read.text
                    val what = when {
                        text.isNotEmpty() && read.codes.isNotEmpty() -> "text+codes"
                        text.isNotEmpty() -> "text"
                        else -> "codes"
                    }
                    ToolResult.Ok(
                        output = "зі світлини ${name ?: ""}: ${text.length} символів тексту, " +
                            "${read.codes.size} код(ів)",
                        structured = buildJsonObject {
                            put("uri", JsonPrimitive(raw))
                            put("name", JsonPrimitive(name ?: ""))
                            put("found", JsonPrimitive(what))
                            put("text", JsonPrimitive(text))
                            put("truncated", JsonPrimitive(read.text.length > cap))
                            put("codes", JsonArray(read.codes.map { JsonPrimitive(it) }))
                            put("script_support", JsonPrimitive(SCRIPT_SUPPORT))
                        },
                        durationMs = System.currentTimeMillis() - startMs,
                    )
                }
            }
        }

    private fun displayName(ctx: ToolContext, uri: Uri): String? = runCatching {
        ctx.appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Двічі відкриваємо потік навмисно: спершу лише межі, щоб не тягнути
     * 12-мегапіксельний кадр у памʼять цілком — телефон у полі має менше
     * запасу, ніж стенд.
     */
    private fun decodeDownsampled(openBytes: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openBytes()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        while (w / sample > MAX_EDGE_PX || h / sample > MAX_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return openBytes()?.use { BitmapFactory.decodeStream(it, null, opts) }
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
        const val MAX_EDGE_PX = 2_048
        const val SCRIPT_SUPPORT = "latin-only"
        const val LATIN_NOTE =
            "розпізнавання на пристрої читає лише латиницю, кирилицю не читає; " +
                "порожнеча тут означає «не зміг прочитати», а не «на світлині нічого немає»"
    }
}

/**
 * Чи схоже це на світлину — за mime І за розширенням: постачальники часто
 * віддають `application/octet-stream`, а вкладення зі скріпки приходить
 * узагалі без mime.
 *
 * Винесено з класу навмисно: тести цього модуля чисто-JVM, без Android, а
 * межа, якої не можна перевірити, — не межа.
 */
internal fun looksLikeImage(mime: String, name: String?): Boolean {
    if (mime.startsWith("image/")) return true
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return ext in IMAGE_EXTS
}

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif", "tiff", "tif",
)
