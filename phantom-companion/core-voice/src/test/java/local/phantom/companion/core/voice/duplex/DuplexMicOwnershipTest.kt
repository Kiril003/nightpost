package local.phantom.companion.core.voice.duplex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.SttRouteKind
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.VoiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Хто володіє мікрофоном у живій розмові.
//
// `stopCapturing` тримає дві ролі — закрити репліку і закрити вухо. Поки канал
// відкритий, друга роль належить лише йому: натисни-і-говори поверх живого
// діалогу мусить закривати репліку, а не глушити чужий AudioRecord.
//
// Звідси невидима в коді залежність від порядку: прапорець duplex має впасти
// ДО зупинки запису, інакше вимкнений живий діалог лишає мікрофон відкритим
// назавжди. Саме її тут і закріплено.
// ---------------------------------------------------------------------------

private class RecordingVoiceController : VoiceController {
    override val sttResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val partialResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val audioLevel = MutableStateFlow(0f)
    override val activeEngine = MutableStateFlow(SttRouteKind.None)

    val calls = mutableListOf<String>()
    var duplex = false
        private set

    override fun setDuplex(enabled: Boolean) {
        duplex = enabled
        calls += "setDuplex($enabled)"
    }

    override fun startCapturing() {
        calls += "startCapturing"
    }

    override suspend fun stopCapturing(): String? {
        calls += "stopCapturing(duplex=$duplex)"
        return null
    }
}

private class SilentTts : TtsEngine {
    override suspend fun speak(text: String, locale: String): Result<Unit> = Result.success(Unit)
    override fun stop() = Unit
    override suspend fun isReady(): Boolean = true
    override val displayName: String = "silent"
}

@OptIn(ExperimentalCoroutinesApi::class)
class DuplexMicOwnershipTest {

    @Test
    fun `closing the channel drops the duplex flag before the recorder is stopped`() =
        runTest(UnconfinedTestDispatcher()) {
            val voice = RecordingVoiceController()
            val engine = DuplexVoiceEngine(
                voice = voice,
                tts = SilentTts(),
                scope = backgroundScope,
            )

            engine.start()
            advanceUntilIdle()
            assertTrue(
                "канал мусить відкритись у дуплексі",
                voice.calls.contains("setDuplex(true)"),
            )

            engine.stop()
            advanceUntilIdle()

            assertEquals(
                "поки прапорець стоїть, stopCapturing лише закриває репліку — " +
                    "мікрофон лишився б відкритим після вимкнення живого діалогу",
                "stopCapturing(duplex=false)",
                voice.calls.last { it.startsWith("stopCapturing") },
            )
            assertTrue(
                "прапорець мусить впасти РАНІШЕ за зупинку запису",
                voice.calls.indexOf("setDuplex(false)") <
                    voice.calls.indexOfFirst { it.startsWith("stopCapturing") },
            )
        }

    @Test
    fun `an open channel opens the recorder exactly once`() =
        runTest(UnconfinedTestDispatcher()) {
            val voice = RecordingVoiceController()
            val engine = DuplexVoiceEngine(
                voice = voice,
                tts = SilentTts(),
                scope = backgroundScope,
            )

            engine.start()
            engine.start()
            advanceUntilIdle()

            assertEquals(
                "повторний start не має відкривати другий запис",
                1,
                voice.calls.count { it == "startCapturing" },
            )
            engine.stop()
            advanceUntilIdle()
        }
}
