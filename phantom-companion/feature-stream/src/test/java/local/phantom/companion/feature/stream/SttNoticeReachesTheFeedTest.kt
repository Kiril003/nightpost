package local.phantom.companion.feature.stream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож проти каналу, який ніхто не слухає.
 *
 * 12.09 `MoonshineSttEngine` навчився казати, що частину репліки втрачено, —
 * і писав це у `SharedFlow`, якого НІХТО не збирав. Подія існувала в коді,
 * не існувала для людини: та бачила коротший текст і жодного слова про те,
 * що решту зʼїли. Це рівно та вада, яку подія мала закрити.
 *
 * Тому кожна ланка ланцюга перевіряється окремо — двигун, [HybridSttEngine],
 * [VoiceManager] і ця ViewModel. Розрив у будь-якій із них знову зробить
 * попередження німим, і жоден тест поведінки цього не побачить: мовчання
 * виглядає як «попереджати не було про що».
 *
 * 02.10 рідна стрічка пішла разом зі StreamViewModel і рядками stt_notice_*;
 * останньої ланки зараз немає — `VoiceManager.sttNotices` не збирає ніхто.
 */
class SttNoticeReachesTheFeedTest {

    private fun src(module: String, path: String): String {
        val f = File("../$module/src/main/java/local/phantom/companion/$path")
        assertTrue("нема файла ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `the engine emits the notice`() {
        val t = src("core-voice", "core/voice/moonshine/MoonshineSttEngine.kt")
        assertTrue(
            "MoonshineSttEngine більше не повідомляє про втрачену частину",
            t.contains("_notices.tryEmit") &&
                t.contains("SttNotice.PartOfUtteranceLost") &&
                t.contains("SttNotice.NothingRecognised"),
        )
    }

    @Test
    fun `the hybrid forwards what its engines could not make out`() {
        val t = src("core-voice", "core/voice/HybridSttEngine.kt")
        assertTrue(
            "HybridSttEngine не переносить notices точнішого двигуна далі",
            t.contains("accurate?.let") && t.contains("notices.collect"),
        )
    }

    @Test
    fun `the manager republishes it`() {
        val t = src("core-voice", "core/voice/VoiceManager.kt")
        assertTrue(
            "VoiceManager не збирає hybridEngine.notices у sttNotices",
            t.contains("hybridEngine?.notices?.collect") && t.contains("_sttNotices.emit"),
        )
    }
}
