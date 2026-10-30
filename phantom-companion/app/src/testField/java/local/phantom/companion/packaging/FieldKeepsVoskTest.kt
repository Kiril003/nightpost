package local.phantom.companion.packaging

import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.hearingModelForRam
import local.phantom.companion.core.voice.VoskRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldKeepsVoskTest {

    @Test
    fun `field keeps vosk for a big phone`() {
        assertTrue(VoskRuntime.shipped)
        assertEquals(ModelType.VOSK, hearingModelForRam(8L shl 30, VoskRuntime.shipped))
    }
}
