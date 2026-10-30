package local.phantom.companion.core.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioClipDecoderTest {

    @Test
    fun `the raw budget is one minute of the clip's own format`() {
        assertEquals(60L * 48_000 * 2 * 2, AudioClipDecoder.rawByteBudget(48_000, 2))
        assertEquals(60L * 16_000 * 1 * 2, AudioClipDecoder.rawByteBudget(16_000, 1))
    }

    @Test
    fun `a broken format still gets a finite budget`() {
        assertEquals(60L * 1 * 1 * 2, AudioClipDecoder.rawByteBudget(0, 0))
    }
}
