package local.phantom.companion.core.design.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.phantom.companion.core.design.ComposeRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стрічка шепоту живе в overlay-сервісі з START_STICKY: композиція не
 * зникає, доки живий процес. Тому «анімація є, але нічого не малює» коштує
 * рівно стільки ж, скільки анімація, що малює — кадр за кадром поверх кожного
 * чужого застосунку.
 *
 * Ці тести не питають про прапорець `animate`. Вони питають годинник кадрів:
 * чи ЧЕКАЄ хтось у цій композиції наступного кадру. Це єдиний спостережний
 * наслідок, який має значення, і його не можна вдати.
 */
class WhisperRibbonDriftTest {

    private val cycleMs = 38_000

    @Test
    fun `at rest the ribbon asks for no frames at all`() {
        val rig = ComposeRig()
        var drift = -1f
        rig.setContent { drift = ribbonDrift(cycleMs, animate = false) }

        repeat(8) { rig.settle(it * 16_000_000L) }

        assertEquals("спокій мусить стояти на нулі", 0f, drift, 0f)
        assertFalse(
            "у спокої в композиції не має лишитись нічого, що просить кадр — " +
                "а щось просить, тож перехід усе ще стоїть у композиції",
            rig.wantsFrames(),
        )
        rig.dispose()
    }

    @Test
    fun `with a message in flight the ribbon drifts`() {
        val rig = ComposeRig()
        var drift = -1f
        rig.setContent { drift = ribbonDrift(cycleMs, animate = true) }

        rig.settle(0L)
        assertTrue("рух мусить просити кадри", rig.wantsFrames())

        rig.settle(9_500_000_000L) // чверть циклу
        assertTrue("за чверть циклу стрічка мусить зрушити з нуля", drift > 0.1f)
        rig.dispose()
    }

    @Test
    fun `falling back to rest takes the transition out of the composition`() {
        val rig = ComposeRig()
        var animate by mutableStateOf(true)
        rig.setContent { ribbonDrift(cycleMs, animate = animate) }

        rig.settle(0L)
        assertTrue("поки є що нести — кадри йдуть", rig.wantsFrames())

        animate = false
        repeat(8) { rig.settle(1_000_000_000L + it * 16_000_000L) }

        assertFalse(
            "перехід мусить ПІТИ з композиції, а не лишитись і не малювати: " +
                "щось усе ще чекає кадру після повернення в спокій",
            rig.wantsFrames(),
        )
        rig.dispose()
    }
}
