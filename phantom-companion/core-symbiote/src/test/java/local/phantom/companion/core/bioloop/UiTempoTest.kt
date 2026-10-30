package local.phantom.companion.core.bioloop

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTempoTest {

    @Test
    fun `60 bpm is relaxed`() = assertEquals(UiTempo.RELAXED, UiTempo.fromBpm(60))

    @Test
    fun `61 bpm is steady`() = assertEquals(UiTempo.STEADY, UiTempo.fromBpm(61))

    @Test
    fun `80 bpm is steady`() = assertEquals(UiTempo.STEADY, UiTempo.fromBpm(80))

    @Test
    fun `81 bpm is stress`() = assertEquals(UiTempo.STRESS, UiTempo.fromBpm(81))

    @Test
    fun `cycle seconds map`() {
        assertEquals(6.0, UiTempo.RELAXED.breathingCycleSeconds, 0.001)
        assertEquals(4.0, UiTempo.STEADY.breathingCycleSeconds, 0.001)
        assertEquals(0.0, UiTempo.STRESS.breathingCycleSeconds, 0.001)
    }
}
