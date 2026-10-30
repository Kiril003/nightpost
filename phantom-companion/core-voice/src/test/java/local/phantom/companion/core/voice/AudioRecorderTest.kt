package local.phantom.companion.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecorderTest {

    @Test
    fun testSampleRate() {
        assertEquals(16000, AudioRecorder.SAMPLE_RATE)
    }
}
