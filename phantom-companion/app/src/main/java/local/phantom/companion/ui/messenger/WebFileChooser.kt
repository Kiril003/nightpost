package local.phantom.companion.ui.messenger

import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Що відкрити на `<input type="file">` у листі.
 *
 * Раніше всі три плитки («Фото», «Камера», «Файл») вели в один і той самий
 * системний вибір: `capture` WebView ігнорував, а фото й документи не
 * різнились. Тепер `capture` — це камера, лише фото й відео — системний
 * Photo Picker (без дозволу на всю галерею), решта — вибір документів.
 */
internal sealed interface ChooserPlan {
    data class Camera(val video: Boolean) : ChooserPlan
    data class Media(val images: Boolean, val video: Boolean, val multiple: Boolean) : ChooserPlan
    data class Documents(val mimes: List<String>, val multiple: Boolean) : ChooserPlan
}

internal object WebFileChooser {

    fun plan(accept: Array<String>?, capture: Boolean, multiple: Boolean): ChooserPlan {
        val types = accept.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val images = types.any { it.startsWith("image/") }
        val video = types.any { it.startsWith("video/") }
        val visual = types.isNotEmpty() && types.all { it.startsWith("image/") || it.startsWith("video/") }
        return when {
            capture -> ChooserPlan.Camera(video = video && !images)
            visual -> ChooserPlan.Media(images = images, video = video, multiple = multiple)
            else -> ChooserPlan.Documents(
                types.filter { '/' in it }.ifEmpty { listOf("*/*") },
                multiple,
            )
        }
    }

    fun plan(params: FileChooserParams?): ChooserPlan = plan(
        params?.acceptTypes,
        params?.isCaptureEnabled == true,
        params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
    )

    fun mediaRequest(plan: ChooserPlan.Media): PickVisualMediaRequest = PickVisualMediaRequest(
        when {
            plan.images && !plan.video -> PickVisualMedia.ImageOnly
            plan.video && !plan.images -> PickVisualMedia.VideoOnly
            else -> PickVisualMedia.ImageAndVideo
        },
    )

    /** Куди камера кладе знімок: у ту ж теку копій, яку чистить [WebPickedFiles.sweep]. */
    fun captureTarget(context: Context, video: Boolean, nowMs: Long = System.currentTimeMillis()): Uri {
        val root = File(context.cacheDir, WebPickedFiles.DIR).apply { mkdirs() }
        WebPickedFiles.sweep(root, nowMs)
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(nowMs))
        val file = File(dir, if (video) "VID_$stamp.mp4" else "IMG_$stamp.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

/**
 * Один відкритий вибір: тримає `filePathCallback` WebView і віддає йому
 * рівно одну відповідь — файли, або null, коли людина передумала чи камеру
 * не дозволено. Без відповіді WebView більше не відкриє жодного вибору.
 */
internal class WebFileChooserSession(
    private val askCamera: ((Boolean) -> Unit) -> Unit,
    private val captureTarget: (video: Boolean) -> Uri?,
    private val takePhoto: (Uri) -> Unit,
    private val takeVideo: (Uri) -> Unit,
    private val pickMedia: (PickVisualMediaRequest, multiple: Boolean) -> Unit,
    private val openDocuments: (mimes: Array<String>, multiple: Boolean) -> Unit,
) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var pending: Uri? = null

    fun show(filePathCallback: ValueCallback<Array<Uri>>?, plan: ChooserPlan): Boolean {
        answer(null)
        callback = filePathCallback
        return try {
            when (plan) {
                is ChooserPlan.Camera -> askCamera { ok ->
                    val target = if (ok) captureTarget(plan.video) else null
                    if (target == null) {
                        answer(null)
                    } else {
                        pending = target
                        runCatching { if (plan.video) takeVideo(target) else takePhoto(target) }
                            .onFailure { answer(null) }
                    }
                }
                is ChooserPlan.Media -> pickMedia(WebFileChooser.mediaRequest(plan), plan.multiple)
                is ChooserPlan.Documents -> openDocuments(plan.mimes.toTypedArray(), plan.multiple)
            }
            true
        } catch (_: Exception) {
            answer(null)
            false
        }
    }

    fun onCaptured(ok: Boolean) {
        val shot = pending
        pending = null
        answer(if (ok && shot != null) arrayOf(shot) else null)
    }

    /** Вибір повернувся: порожній — відповідь одразу, інакше колбек для стабільних копій. */
    fun takePicked(uris: Array<Uri>): ValueCallback<Array<Uri>>? {
        val cb = callback
        callback = null
        if (uris.isEmpty()) {
            cb?.onReceiveValue(null)
            return null
        }
        return cb
    }

    private fun answer(uris: Array<Uri>?) {
        val cb = callback
        callback = null
        cb?.onReceiveValue(uris)
    }
}
