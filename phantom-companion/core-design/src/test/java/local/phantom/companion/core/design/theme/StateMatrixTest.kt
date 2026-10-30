package local.phantom.companion.core.design.theme

import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.design.sound.PhantomSoundEvent
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W1.5-5 — pin the per-state matrix.
 *
 * 03.10, native theme bridge: v1.2 has ONE accent, and it marks an action,
 * never a state. The DESIGN_BRIEF §IV hues (cyan, coral, grey, warm-dim,
 * violet), the per-state dimming and re-glassing and the breathing periods are
 * gone; a state still owns its motion scale and its sound. A refactor that
 * brings a per-state hue back is caught here, loudly.
 */
class StateMatrixTest {

    private data class Row(val state: SystemState, val motion: Float, val sound: PhantomSoundEvent?)

    private val matrix = listOf(
        Row(SystemState.FOCUS, 1.0f, PhantomSoundEvent.CrystalNotice),
        Row(SystemState.DIALOGUE, 1.1f, PhantomSoundEvent.CrystalNotice),
        Row(SystemState.SENTINEL, 1.3f, PhantomSoundEvent.SpikeAlert),
        Row(SystemState.GHOST, 0.8f, null),
        Row(SystemState.SHADOW, 0.6f, null),
        Row(SystemState.DREAM, 0.4f, PhantomSoundEvent.ChimeBell),
    )

    @Test
    fun `every state draws the one accent and never dims or re-glasses the UI`() {
        for (row in matrix) {
            val a = stateAccentForSunrise(row.state)
            assertEquals(row.state, a.state)
            assertEquals("${row.state}: one accent, not a mood hue", NightColors.accent, a.color)
            assertEquals("${row.state}: uiOpacity", 1.0f, a.uiOpacity)
            assertEquals("${row.state}: glassAlpha", 1.0f, a.glassAlpha)
            assertEquals("${row.state}: nothing breathes on a state period", 0, a.animationPeriodMs)
        }
    }

    @Test
    fun `a state still owns its motion and its sound`() {
        for (row in matrix) {
            val a = stateAccentForSunrise(row.state)
            assertEquals("${row.state}: motion", row.motion, a.motionScale)
            assertEquals("${row.state}: sound", row.sound, a.soundEvent)
        }
        assertNull("GHOST must be silent", stateAccentForSunrise(SystemState.GHOST).soundEvent)
        assertNull("SHADOW must be silent", stateAccentForSunrise(SystemState.SHADOW).soundEvent)
    }

    /**
     * A providerless scope must land IN the matrix, not beside it. The default
     * used to be a hand-written FOCUS in deep bronze with no sound — one state
     * with two appearances, and the wrong one is what previews and the
     * wallpaper/orb services rendered. A CompositionLocal's default cannot be
     * read outside a composition, so this reads how it is built.
     */
    @Test
    fun `the composition-local default is the matrix's FOCUS`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "core-design").isDirectory) dir = dir.parentFile
        val src = java.io.File(
            dir!!,
            "core-design/src/main/java/local/phantom/companion/core/design/theme/StateAccent.kt",
        ).readText()
        val default = Regex("""val LocalStateAccent = compositionLocalOf \{([^}]*)}""")
            .find(src)?.groupValues?.get(1)?.trim()
        assertEquals(
            "the default must resolve through the matrix, not restate it",
            "stateAccentForSunrise(SystemState.FOCUS)",
            default,
        )
    }

    @Test
    fun `every SystemState has a matrix entry — no resolver gap`() {
        // Resolver is exhaustive (when-expression on enum); this test
        // is a meta-check that re-runs after every enum addition. If a
        // new state lands and stateAccentForSunrise misses it, the
        // when-expression is non-exhaustive and the file won't compile.
        SystemState.entries.forEach { state ->
            val accent = stateAccentForSunrise(state)
            assertEquals("matrix entry must round-trip the state", state, accent.state)
        }
    }
}
