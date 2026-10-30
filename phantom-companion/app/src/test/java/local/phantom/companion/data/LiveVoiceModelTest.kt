package local.phantom.companion.data

import local.phantom.companion.core.ai.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceModelTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `phones under 8 GB get the economical voice`() {
        assertEquals(ModelType.SUPERTONIC_LITE, liveVoiceModelFor(6 * gb))
        assertEquals(ModelType.SUPERTONIC_LITE, liveVoiceModelFor(4 * gb))
    }

    @Test
    fun `8 GB and up keep the full voice, unknown RAM does not guess low`() {
        assertEquals(ModelType.SUPERTONIC, liveVoiceModelFor(8 * gb))
        assertEquals(ModelType.SUPERTONIC, liveVoiceModelFor(12 * gb))
        assertEquals(ModelType.SUPERTONIC, liveVoiceModelFor(0))
    }

    /**
     * 19.10, власник: «роботизований системний голос замість Supertonic». На
     * телефоні лежить повний голос (models/supertonic), а ОЗП до 8 ГБ вибирала
     * економний, якого на диску немає, — і кожна репліка йшла системному роботу.
     */
    @Test
    fun `a phone holding only the full voice speaks it instead of waiting for the lite one`() {
        val onDisk = setOf(ModelType.SUPERTONIC)
        assertEquals(ModelType.SUPERTONIC, liveVoiceModelFor(6 * gb) { it in onDisk })
    }

    @Test
    fun `the lite voice wins on a small phone once it is there, and nothing on disk keeps the RAM choice`() {
        assertEquals(ModelType.SUPERTONIC_LITE, liveVoiceModelFor(6 * gb) { true })
        assertEquals(ModelType.SUPERTONIC_LITE, liveVoiceModelFor(6 * gb) { false })
        assertEquals(ModelType.SUPERTONIC, liveVoiceModelFor(12 * gb) { false })
    }
}
