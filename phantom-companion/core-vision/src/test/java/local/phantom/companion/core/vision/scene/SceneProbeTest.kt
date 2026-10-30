package local.phantom.companion.core.vision.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сліпий рушій і порожня стіна не мають права виглядати однаково. */
class SceneProbeTest {

    private val anything = Detection(
        box = VisionBox(0f, 0f, 1f, 1f),
        kind = ObjectKind.TEXT,
        label = "щось",
    )

    @Test
    fun everyProbeFailedMeansBlind() {
        val look = combine(listOf(Probe.Failed("текст"), Probe.Failed("коди")))
        assertTrue(look is SceneLook.Blind)
        assertEquals("текст", (look as SceneLook.Blind).reason)
    }

    /**
     * Найдорожчий випадок: один зонд упав, другий чесно не знайшов нічого.
     * Це все ще ПОГЛЯД — просто вужчий. Якщо звести його до [SceneLook.Blind],
     * розділ почне кричати «не бачу» над порожнім столом.
     */
    @Test
    fun oneSurvivorKeepsItASeeing() {
        val look = combine(listOf(Probe.Failed("текст"), Probe.Done(emptyList())))
        assertEquals(SceneLook.Seen(emptyList()), look)
    }

    @Test
    fun nothingAskedIsNotBlindness() {
        assertEquals(SceneLook.Seen(emptyList()), combine(listOf(Probe.Skipped, Probe.Skipped)))
    }

    @Test
    fun skippedProbeCannotCauseBlindness() {
        // Рушій, у якого вимкнули один зонд, не стає сліпим від падіння іншого,
        // якщо решта відпрацювала.
        val look = combine(listOf(Probe.Skipped, Probe.Done(listOf(anything))))
        assertEquals(1, (look as SceneLook.Seen).objects.size)
    }

    @Test
    fun everyProbeSkippedButOneFailedIsBlind() {
        val look = combine(listOf(Probe.Skipped, Probe.Failed("коди")))
        assertTrue(look is SceneLook.Blind)
    }

    @Test
    fun findingsFromEveryProbeArriveTogether() {
        val code = anything.copy(kind = ObjectKind.CODE, label = "код")
        val look = combine(listOf(Probe.Done(listOf(anything)), Probe.Done(listOf(code))))
        assertEquals(listOf(anything, code), (look as SceneLook.Seen).objects)
    }
}
