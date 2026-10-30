package local.phantom.companion.packaging

import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.hearingModelForRam
import local.phantom.companion.core.voice.VoskRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoreWithoutVoskTest {

    @Test
    fun `store classpath carries no vosk or jna`() {
        assertFalse(VoskRuntime.shipped)
        assertFalse(runCatching { Class.forName("com.sun.jna.Native", false, javaClass.classLoader) }.isSuccess)
    }

    @Test
    fun `store ear is moonshine even on a big phone`() {
        assertEquals(ModelType.MOONSHINE_UK, hearingModelForRam(8L shl 30, VoskRuntime.shipped))
    }
}
