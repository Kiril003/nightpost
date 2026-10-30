package local.phantom.companion.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.bioloop.UiTempo
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.embodiment.RecordingHapticEmitter
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SymbioteRuntimeTest {

    @Test
    fun `hrv tempo gated by toggle`() = runTest(UnconfinedTestDispatcher()) {
        val haptic = MutableStateFlow(false)
        val hrv = MutableStateFlow(false)
        val rt = SymbioteRuntime(
            hapticEnabled = haptic,
            hrvTempoEnabled = hrv,
            systemState = MutableStateFlow(SystemState.FOCUS),
            hapticEmitter = RecordingHapticEmitter(),
        )
        rt.applyHrv(120)
        assertEquals(UiTempo.STEADY.breathingCycleSeconds, rt.tempoCycleSeconds.value, 0.001)
        hrv.value = true
        rt.applyHrv(90)
        assertEquals(UiTempo.STRESS.breathingCycleSeconds, rt.tempoCycleSeconds.value, 0.001)
    }

    @Test
    fun `haptic disabled produces no emissions`() = runTest(UnconfinedTestDispatcher()) {
        val emitter = RecordingHapticEmitter()
        val rt = SymbioteRuntime(
            hapticEnabled = MutableStateFlow(false),
            hrvTempoEnabled = MutableStateFlow(false),
            systemState = MutableStateFlow(SystemState.FOCUS),
            hapticEmitter = emitter,
        )
        rt.start(backgroundScope)
        assertEquals(0, emitter.history.size)
    }
}
