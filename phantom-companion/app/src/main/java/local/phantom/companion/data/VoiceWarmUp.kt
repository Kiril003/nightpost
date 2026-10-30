package local.phantom.companion.data

import android.app.ActivityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.core.voice.route.RoutedTts
import java.util.concurrent.atomic.AtomicBoolean

private val voiceWarmStarted = AtomicBoolean(false)

internal const val VOICE_WARM_DELAY_MS = 4_000L

/**
 * Раз на процес, після першого кадру: прогріти Supertonic, щоб перша ж репліка
 * помічника звучала живим голосом. Тісна памʼять — пропускаємо: голос завантажиться
 * на першій фразі, як і раніше.
 */
internal fun AppContainer.warmLiveVoiceAfterFirstFrame() {
    if (!voiceWarmStarted.compareAndSet(false, true)) return
    scope.launch {
        delay(VOICE_WARM_DELAY_MS)
        val am = applicationContext.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        if (am == null || am.isLowRamDevice || mem.lowMemory) return@launch
        runCatching { (ttsEngine as? RoutedTts)?.warmUp() }
    }
}
