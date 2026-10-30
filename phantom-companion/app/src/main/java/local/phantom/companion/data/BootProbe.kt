package local.phantom.companion.data

import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.BuildConfig

/**
 * Прилад для одного питання: ХТО бере нативну купу до першого екрана.
 *
 * «Очі-2» зміряли на релізі 690 МБ нативної купи на екрані ПІНу, карта памʼяті
 * показала malloc (scudo), шість регіонів ≥100 МБ і спалах на ЧЕТВЕРТІЙ секунді
 * холодного старту — до дотику, WebView 0, Graphics 0. Недосяжного 3 КБ, тобто
 * це не витік, а навмисний робочий набір. Назвати крок читанням не вийшло:
 * майже все в `boot()` іде в корутинах, і порядок не збігається з текстом файла.
 *
 * Тому тут ДВА інструменти, і кожен закриває сліпоту іншого:
 *  - [mark] — іменована межа на синхронному хребті старту;
 *  - [startSampling] — рівний відлік, який ловить спалах усередині корутини,
 *    де жодної межі немає.
 *
 * Лише для debug: у релізі обидва виходять на першому рядку, тож упакунку
 * від цього не лишається нічого, крім перевірки прапорця.
 */
internal object BootProbe {

    private const val TAG = "PhantomBoot"
    private val startedAtMs = System.currentTimeMillis()

    private fun mb(): Long = Debug.getNativeHeapAllocatedSize() / 1_048_576L

    /** Іменована межа: «після цього кроку нативна купа була така». */
    fun mark(step: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "+${System.currentTimeMillis() - startedAtMs}ms $step native=${mb()}MB")
    }

    /**
     * Рівний відлік перших секунд. Крок 250 мс — спалах на 4-й секунді видно
     * як стрибок між двома сусідніми рядками, з часом від старту процесу.
     */
    fun startSampling(scope: CoroutineScope) {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            var last = -1L
            repeat(SAMPLES) {
                val now = mb()
                // Друкуємо лише зміни ≥8 МБ: рівний шум ховає стрибок.
                if (last < 0 || kotlin.math.abs(now - last) >= 8) {
                    Log.i(TAG, "+${System.currentTimeMillis() - startedAtMs}ms sample native=${now}MB")
                    last = now
                }
                delay(STEP_MS)
            }
            Log.i(TAG, "+${System.currentTimeMillis() - startedAtMs}ms sampling done native=${mb()}MB")
        }
    }

    private const val STEP_MS = 250L
    private const val SAMPLES = 80 // 20 секунд
}
