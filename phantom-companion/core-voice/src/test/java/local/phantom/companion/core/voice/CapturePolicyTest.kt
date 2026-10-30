package local.phantom.companion.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обробка, яку ОС чіпляє до тракту телефонії, коштує розпізнаванню точності.
 * Ці тести тримають межу: платить за неї лише той, кому вона потрібна.
 */
class CapturePolicyTest {

    @Test
    fun `a solo capture never opens the telephony path`() {
        val chain = CapturePolicy.sources(duplex = false)

        assertFalse(
            "AGC телефонії робить статистику сигналу нестаціонарною, і оцінка " +
                "каналу в моделі виходить неправильною — під натисни-і-говори " +
                "цей тракт не має відкриватись навіть як запасний",
            CaptureSource.VoiceCommunication in chain,
        )
        assertEquals(
            "розпізнаванню — тракт розпізнавання; MIC лишається запасним",
            listOf(CaptureSource.VoiceRecognition, CaptureSource.Mic),
            chain,
        )
    }

    @Test
    fun `duplex still asks for the echo-cancelling path first`() {
        val chain = CapturePolicy.sources(duplex = true)

        assertEquals(
            "без придушення ехо PHANTOM перебиває сам себе — це несуче",
            CaptureSource.VoiceCommunication,
            chain.first(),
        )
        assertEquals(
            "відмова OEM не має лишати діалог без мікрофона взагалі",
            CaptureSource.Mic,
            chain.last(),
        )
    }

    @Test
    fun `communication effects follow the same line as the source`() {
        assertTrue("у дуплексі є що придушувати", CapturePolicy.cancelEcho(true))
        assertTrue(CapturePolicy.suppressNoise(true))

        assertFalse(
            "поза дуплексом придушувач ехо нічого не скасовує, а echoCancelled " +
                "звітував би «так»",
            CapturePolicy.cancelEcho(false),
        )
        assertFalse(
            "придушення шуму вирізає зі спектра тиху деталь приголосних",
            CapturePolicy.suppressNoise(false),
        )
    }
}
