package local.phantom.companion.core.vision.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Результат тотальний — три виходи, кожен шлях резюмує рівно раз: «нічого не
 * знайшов» і «рушій упав» не сміють бути однаковим мовчанням, бо модель, яка
 * не дочекалась відповіді, почне переказувати світлину, якої не бачила.
 */
sealed interface VisionRead {
    /** Знайдено текст (латиниця) та/або коди. Обидва поля можуть бути порожні лише разом із [Empty]. */
    data class Found(val text: String, val codes: List<String>) : VisionRead

    /** Рушій відпрацював і не знайшов нічого. Це ВИМІР, а не збій. */
    object Empty : VisionRead

    /** Рушій не зміг подивитись. Не плутати з [Empty]. */
    data class Failed(val reason: String) : VisionRead
}

/**
 * **Межа, яку треба називати вголос:** `com.google.mlkit:text-recognition`
 * розпізнає **лише латиницю**. Кирилиця цим рушієм не читається — українська
 * сторінка поверне порожнечу або сміття. Це не привід мовчати: інструмент
 * зобовʼязаний сказати, ЩО саме він не вміє, інакше порожнеча знову стане
 * невідрізненною від «нічого там не написано».
 */
class ImageInspector {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build(),
    )

    suspend fun inspect(bitmap: Bitmap): VisionRead {
        val image = InputImage.fromBitmap(bitmap, 0)
        return combine(readText(image), readCodes(image))
    }


    fun release() {
        textRecognizer.close()
        barcodeScanner.close()
    }

    companion object {
        /**
         * Рішення «що ми насправді побачили» — винесено з [inspect] **дослівно**,
         * щоб його можна було перевірити без Android: клієнти MLKit створюються в
         * полях, тож на чистій JVM [inspect] не викликати, і межа лишалась би
         * незастереженою. Тіло не змінене й не «причесане» — тільки переміщене.
         */
        internal fun combine(text: Outcome, codes: Outcome): VisionRead {
            if (text is Outcome.Failed && codes is Outcome.Failed) {
                return VisionRead.Failed(text.reason)
            }
            val foundText = (text as? Outcome.Done)?.value.orEmpty().trim()
            val foundCodes = (codes as? Outcome.Done)?.list.orEmpty()
            return if (foundText.isEmpty() && foundCodes.isEmpty()) {
                VisionRead.Empty
            } else {
                VisionRead.Found(text = foundText, codes = foundCodes)
            }
        }
    }

    internal sealed interface Outcome {
        data class Done(val value: String? = null, val list: List<String> = emptyList()) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private suspend fun readText(image: InputImage): Outcome =
        suspendCancellableCoroutine { cont ->
            textRecognizer.process(image)
                .addOnSuccessListener { cont.resume(Outcome.Done(value = it.text)) }
                .addOnFailureListener { cont.resume(Outcome.Failed(it.message ?: "text recognition failed")) }
                .addOnCanceledListener { cont.resume(Outcome.Failed("text recognition cancelled")) }
        }

    private suspend fun readCodes(image: InputImage): Outcome =
        suspendCancellableCoroutine { cont ->
            barcodeScanner.process(image)
                .addOnSuccessListener { list ->
                    cont.resume(Outcome.Done(list = list.mapNotNull { it.rawValue }.filter { it.isNotBlank() }))
                }
                .addOnFailureListener { cont.resume(Outcome.Failed(it.message ?: "barcode scan failed")) }
                .addOnCanceledListener { cont.resume(Outcome.Failed("barcode scan cancelled")) }
        }
}
