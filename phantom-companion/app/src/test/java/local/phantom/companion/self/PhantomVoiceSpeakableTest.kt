package local.phantom.companion.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhantomVoiceSpeakableTest {

    @Test
    fun `code is not read aloud, the voice says where to find it`() {
        val said = PhantomVoice.speakable(
            "Питання 1. Що виведе цей код?\n\nint x = 5;\nx += 3;\nConsole.WriteLine(x * 2);\n\nНапиши лише відповідь.",
            "Код у розмові.",
        )
        assertFalse(said.contains("Console"))
        assertEquals("Питання 1. Що виведе цей код?\n\nНапиши лише відповідь.\n\nКод у розмові.", said)
    }

    @Test
    fun `bold marks are not read aloud`() {
        assertEquals("Склав переказ: Олена · 5 USDC.", PhantomVoice.speakable("Склав переказ: **Олена · 5 USDC**.", "x"))
    }

    @Test
    fun `plain words pass untouched`() {
        assertEquals("Запамʼятав: Ти любиш каву.", PhantomVoice.speakable("Запамʼятав: Ти любиш каву.", "x"))
    }
}
