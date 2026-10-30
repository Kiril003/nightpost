package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Заглушка на випадок, коли жодна модель слуху не піднялась.
 *
 * Раніше вона щодесятого кадру писала в partialResults «слухаю... (10)»,
 * «(20)», «(30)» — і власник бачив у стрічці розпізнавання лічильник
 * замість своїх слів, ще й не здогадуючись, що слуху взагалі немає.
 * Заглушка не має права вигадувати транскрипт: вона мовчить, а про
 * відсутність слуху чесно каже [VoiceManager.activeEngine] = None.
 */
class FakeSttEngine : SttEngine {
    override val displayName: String = "Слух не піднявся"

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()

    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val finalResults: SharedFlow<String> = _finalResults.asSharedFlow()

    private var feedCount = 0

    override fun feed(chunk: ByteArray) {
        feedCount++
    }

    override suspend fun transcribe(audio: ByteArray): String? = null

    override fun reset() {
        feedCount = 0
    }

    override fun release() {}
}
