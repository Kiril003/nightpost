package local.phantom.companion.core.context

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.bus.PhantomNervousBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextFrameBuilderTest {

    private fun makeInput(
        accel: Float? = null,
        charging: Boolean = false,
        battery: Int? = 75,
        activeApp: String? = "com.example.app",
        sounds: List<String> = emptyList(),
    ) = ContextInput(
        nowMs = System.currentTimeMillis(),
        localHour = 14,
        localDayOfWeek = 3,
        accelMagnitude = accel,
        batteryLevel = battery,
        isCharging = charging,
        activeAppPackage = activeApp,
        detectedSounds = sounds,
    )

    private fun makeSnapshot(state: SystemState = SystemState.FOCUS) = ContextSnapshot(
        timestampMs = System.currentTimeMillis(),
        state = state,
        confidence = 0.8f,
        rationale = listOf("test"),
    )

    // ── QA-fix 2026-07-17 — the activeAppPackage gate ────────────────
    // A raw accessibility dump was rendered on Presence as the operator's
    // «активна занятість» because three seams passed non-package text
    // into a field that promises a package name. These pins hold the gate.

    @Test
    fun `a raw screen dump never passes as the active app`() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val dump = "[ 111 , 719 , 87 , 51 ] 21:13 | [ 115 , 811 , 37 , 37 ] ще раз |"
        val frame = builder.build(makeInput(activeApp = dump), makeSnapshot())
        assertEquals(null, frame.what.activeAppPackage)
    }

    @Test
    fun `phantom watching his own screen is a mirror not занятість`() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val frame = builder.build(
            makeInput(activeApp = "local.phantom.companion.debug"),
            makeSnapshot(),
        )
        assertEquals(null, frame.what.activeAppPackage)
    }

    @Test
    fun `a real foreign package passes the gate untouched`() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val frame = builder.build(
            makeInput(activeApp = "com.android.chrome"),
            makeSnapshot(),
        )
        assertEquals("com.android.chrome", frame.what.activeAppPackage)
    }

    @Test
    fun `package shape validator rejects json and dumps and accepts packages`() {
        assertTrue(ContextFrameBuilder.looksLikePackage("com.android.chrome"))
        assertTrue(ContextFrameBuilder.looksLikePackage("ua.gov.diia.app"))
        assertEquals(false, ContextFrameBuilder.looksLikePackage("""{"pkg":"com.x"}"""))
        assertEquals(false, ContextFrameBuilder.looksLikePackage("[ 1 , 2 ] label |"))
        assertEquals(false, ContextFrameBuilder.looksLikePackage("chrome"))
        assertEquals(false, ContextFrameBuilder.looksLikePackage(""))
    }

    @Test
    fun buildProducesValidFrame() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device") { "profile-1" }
        val input = makeInput(battery = 82, charging = true, accel = 0.1f)
        val snapshot = makeSnapshot(SystemState.SHADOW)

        val frame = builder.build(input, snapshot)

        assertEquals("SHADOW", frame.state)
        assertEquals("profile-1", frame.who.profileId)
        assertEquals(82, frame.device.batteryLevel)
        assertTrue(frame.device.isCharging)
        assertEquals(ContextFrameBuilder.PLACE_HOME, frame.where.placeLabel)
    }

    @Test
    fun buildMergesDetectedSounds() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val input = makeInput(sounds = listOf("Speech", "Music"))
        val snapshot = makeSnapshot()

        val frame = builder.build(input, snapshot)

        assertTrue(frame.what.detectedSounds.containsAll(listOf("Speech", "Music")))
    }

    @Test
    fun buildPublishesFrameEventToBus() = runTest {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val collected = mutableListOf<String>()

        val job = launch {
            bus.events.collect { event ->
                collected.add(event.type)
            }
        }

        builder.build(makeInput(), makeSnapshot())
        testScheduler.advanceUntilIdle()

        assertTrue(collected.contains(ContextFrameBuilder.TYPE_CONTEXT_FRAME))
        job.cancel()
    }

    @Test
    fun latestFrameStateFlowUpdates() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")

        assertEquals(null, builder.latestFrame.value)

        builder.build(makeInput(), makeSnapshot(SystemState.GHOST))

        val frame = builder.latestFrame.value
        assertNotNull(frame)
        assertEquals("GHOST", frame!!.state)
    }

    @Test
    fun inferPlaceLabelMoving() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val input = makeInput(accel = 3.5f, charging = false)

        val frame = builder.build(input, makeSnapshot())

        assertEquals(ContextFrameBuilder.PLACE_MOVING, frame.where.placeLabel)
    }

    @Test
    fun inferPlaceLabelUnknown() {
        val bus = PhantomNervousBus()
        val builder = ContextFrameBuilder(bus, "test-device")
        val input = makeInput(accel = 0.5f, charging = false)

        val frame = builder.build(input, makeSnapshot())

        assertEquals(ContextFrameBuilder.PLACE_UNKNOWN, frame.where.placeLabel)
    }
}
