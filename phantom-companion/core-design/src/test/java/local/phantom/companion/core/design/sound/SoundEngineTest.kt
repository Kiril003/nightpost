package local.phantom.companion.core.design.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1.5-4 — pin the SoundEngine contract:
 *  - SAM works as a fun interface for tests
 *  - GHOST gate suppresses every event (silence is non-negotiable)
 *  - Per-event spec table covers every PhantomSoundEvent enum entry
 *  - Noop drops on the floor without exception
 */
class SoundEngineTest {

    @Test
    fun `Noop fires nothing — runs cleanly`() {
        // Drop-on-floor default; the contract is "no exception, no side
        // effect". Pin both by firing the full enum and proving the
        // method completes.
        PhantomSoundEvent.entries.forEach { ev ->
            SoundEngine.Noop.fire(ev)
        }
    }

    @Test
    fun `SAM-style engine captures fired events in order`() {
        val fired = mutableListOf<PhantomSoundEvent>()
        val engine = SoundEngine { fired += it }
        engine.fire(PhantomSoundEvent.ChimeBell)
        engine.fire(PhantomSoundEvent.SpikeAlert)
        assertEquals(
            listOf(PhantomSoundEvent.ChimeBell, PhantomSoundEvent.SpikeAlert),
            fired,
        )
    }

    @Test
    fun `gated engine suppresses every event when GHOST is true`() {
        val fired = mutableListOf<PhantomSoundEvent>()
        val gated = SoundEngine.gated(
            inner = SoundEngine { fired += it },
            isGhost = { true },
        )
        PhantomSoundEvent.entries.forEach { gated.fire(it) }
        assertEquals("GHOST must suppress every event", emptyList<PhantomSoundEvent>(), fired)
    }

    @Test
    fun `gated engine forwards events when GHOST is false`() {
        val fired = mutableListOf<PhantomSoundEvent>()
        val gated = SoundEngine.gated(
            inner = SoundEngine { fired += it },
            isGhost = { false },
        )
        gated.fire(PhantomSoundEvent.CrystalNotice)
        assertEquals(listOf(PhantomSoundEvent.CrystalNotice), fired)
    }

    @Test
    fun `gated engine flips with the GHOST predicate at fire time`() {
        // The wrapper must consult the predicate per fire — caching it
        // would mean GHOST entry/exit during a session leaks sounds.
        var ghost = false
        val fired = mutableListOf<PhantomSoundEvent>()
        val gated = SoundEngine.gated(
            inner = SoundEngine { fired += it },
            isGhost = { ghost },
        )
        gated.fire(PhantomSoundEvent.ChimeBell)
        ghost = true
        gated.fire(PhantomSoundEvent.SpikeAlert)
        ghost = false
        gated.fire(PhantomSoundEvent.RippleTap)
        assertEquals(
            listOf(PhantomSoundEvent.ChimeBell, PhantomSoundEvent.RippleTap),
            fired,
        )
    }

    @Test
    fun `Android engine spec covers every PhantomSoundEvent`() {
        // Future enum additions must extend the spec map; missing entry
        // here is the test failure that catches the omission.
        PhantomSoundEvent.entries.forEach { ev ->
            assertNotNull(
                "AndroidToneGeneratorSoundEngine.SPEC missing entry for $ev",
                AndroidToneGeneratorSoundEngine.SPEC[ev],
            )
        }
    }

    @Test
    fun `each spec carries a positive duration under one second`() {
        AndroidToneGeneratorSoundEngine.SPEC.forEach { (event, spec) ->
            assertTrue("$event tone duration must be > 0: ${spec.durationMs}",
                spec.durationMs > 0)
            assertTrue("$event tone duration must be < 1000ms: ${spec.durationMs}",
                spec.durationMs < 1000)
        }
    }

    @Test
    fun `RippleTap is the silent-haptic event (vibration zero)`() {
        val spec = AndroidToneGeneratorSoundEngine.SPEC[PhantomSoundEvent.RippleTap]
        assertNotNull(spec)
        assertEquals(
            "RippleTap must not vibrate — rapid taps would buzz the pocket dry",
            0, spec!!.vibrationMs,
        )
    }

    @Test
    fun `ChimeBell pairs sound with haptic for tactile completion`() {
        val spec = AndroidToneGeneratorSoundEngine.SPEC[PhantomSoundEvent.ChimeBell]
        assertNotNull(spec)
        assertTrue(
            "ChimeBell must vibrate (timer finish needs a tactile cue): ${spec}",
            spec!!.vibrationMs > 0,
        )
    }
}
