package local.phantom.companion.self

import java.io.File
import local.phantom.companion.core.voice.live.CallPhase
import local.phantom.companion.core.voice.live.TurnCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Досяжність живого дзвінка: кнопка дзвінка → накладка → [PhantomCall] → токен
 * від ПК (інакше ключ телефона) → звук з платформним AEC → стенограма в чат.
 *
 * GATE LAW: Compose, AudioRecord і WebSocket у юніт-тесті `:app` не підняти —
 * перевіряється текст коду там, де інакше лишилась би лише віра.
 */
class PhantomCallWiringTest {

    private fun source(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь дерева не знайдено") as Nothing, rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText().lineSequence()
            .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")
    }

    private val pkg = "app/src/main/java/local/phantom/companion"

    @Test
    fun `екран дзвінка веде живий дзвінок, а не старий дуплекс`() {
        val screen = source("$pkg/ui/home/PhantomCallScreen.kt")
        assertTrue("екран не починає дзвінок", "call.begin()" in screen)
        assertTrue("екран не кладе дзвінок, зникаючи", "call.end()" in screen)
        assertFalse("екран досі вмикає старий голос", "phantomVoice.start()" in screen)
        val container = source("$pkg/data/AppContainer.kt")
        assertTrue("PhantomCall(this)" in container)
    }

    @Test
    fun `токен — спершу ПК, потім ключ телефона, потім сервер`() {
        val call = source("$pkg/self/PhantomCall.kt")
        val pc = call.indexOf("PcTokenSource { c.api.liveToken() }")
        val own = call.indexOf("OwnerKeyTokenSource(")
        val srv = call.indexOf("ServerTokenSource {")
        assertTrue("ПК не питають", pc > 0)
        assertTrue("ПК має йти першим", own > pc)
        assertTrue("сервер має йти після ключа власника", srv > own)
        val api = source("$pkg/net/PhantomApi.kt")
        assertTrue("/api/v1/live/token" in api.substringAfter("suspend fun liveToken"))
    }

    @Test
    fun `звук — VOICE_COMMUNICATION з AEC угору, той самий потік униз`() {
        val audio = source("$pkg/self/LiveCallAudio.kt")
        assertTrue("MediaRecorder.AudioSource.VOICE_COMMUNICATION" in audio)
        assertTrue("AcousticEchoCanceler.create" in audio)
        assertTrue("AudioAttributes.USAGE_VOICE_COMMUNICATION" in audio)
        assertTrue("MODE_IN_COMMUNICATION" in audio)
        assertTrue("LiveProtocol.IN_RATE" in audio && "LiveProtocol.OUT_RATE" in audio)
    }

    @Test
    fun `руки — драбина тіла і справжній месенджер, стенограма — у розмову з PHANTOM`() {
        val call = source("$pkg/self/PhantomCall.kt")
        val hands = call.substringAfter("class AppCallHands").substringBefore("class ChatTranscriptSink")
        assertTrue("BodyLadder.of(c.applicationContext).judge(verb)" in hands)
        assertTrue("c.mesh.send(personId, text)" in hands)
        assertTrue("c.memoryFactStore.recallFor(" in hands)
        // Мапа — тими самими воротами інструментів, що й розмова в месенджері.
        assertTrue("c.agencyManager.runGuarded(tool" in hands)
        assertFalse("tool.execute(" in hands)
        val sink = call.substringAfter("class ChatTranscriptSink")
        assertTrue("c.mesh.ingestDirect(" in sink)
        assertTrue("AgencySessionManager.PHANTOM_AGENT_ID" in sink)
    }

    @Test
    fun `слова стану — слухаю, думаю, говорю`() {
        assertEquals(CallUi.Word.LISTENING, CallUi.word(CallPhase.LISTENING))
        assertEquals(CallUi.Word.THINKING, CallUi.word(CallPhase.THINKING))
        assertEquals(CallUi.Word.SPEAKING, CallUi.word(CallPhase.SPEAKING))
        assertEquals(CallUi.Word.CONNECTING, CallUi.word(CallPhase.RECONNECTING))
        assertEquals(CallUi.Word.HOLD_TO_TALK, CallUi.word(TurnCall.Phase.IDLE))
        assertEquals(CallUi.Word.LISTENING, CallUi.word(TurnCall.Phase.RECORDING))
    }
}
