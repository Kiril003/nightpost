package local.phantom.companion.core.sensor.nav.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers the replay instrument holds the display to. Each was red on
 * e15ba6e (see the baseline table in the commit history) and is a floor now.
 */
class NavReplayTargetsTest {

    private fun shown(sc: Scenario): Score =
        ReplayRun(sc, listOf(BlindChain(sc.graph))).run().single()

    private fun named(part: String) = Scenarios.all.first { it.name.contains(part) }

    @Test
    fun `a phone on a table for eight hours raises no alarm in the notice, the chip or the dot`() {
        val sc = Scenarios.table(8.0)
        val chains = listOf(LiveDisplayChain(), EngineChain(false), BlindChain(null))
        val scores = ReplayRun(sc, chains).run()
        for ((s, c) in scores.zip(chains)) {
            assertEquals("${s.chain}: хибна підміна ${c.spoofWhy}", 0, s.falseSpoof)
            assertEquals("${s.chain}: хибне глушіння", 0, s.falseJam)
        }
    }

    @Test
    fun `sixty seconds of tunnel by car stays on the right road within 60 m`() {
        val s = shown(named("тунель 60 с"))
        assertTrue("max ${s.errEventMaxM}", s.errEventMaxM <= 60.0)
        assertEquals(100.0, s.onRoadPct, 0.0)
    }

    @Test
    fun `a queue inside the tunnel does not run the dot ahead`() {
        val s = shown(named("затор"))
        assertTrue("max ${s.errEventMaxM}", s.errEventMaxM <= 80.0)
        assertEquals(100.0, s.onRoadPct, 0.0)
    }

    @Test
    fun `turns underground in the city are followed`() {
        val s = shown(named("з поворотами"))
        assertTrue("max ${s.errEventMaxM}", s.errEventMaxM <= 60.0)
    }

    @Test
    fun `two minutes underground on foot stays within 25 m`() {
        val s = shown(named("метро 120"))
        assertTrue("max ${s.errEventMaxM}", s.errEventMaxM <= 25.0)
    }

    @Test
    fun `a jump of the sky in a moving car is caught at once and the dot does not follow`() {
        val s = shown(named("стрибком 1,5"))
        assertEquals(0.0, s.detectS!!, 1.0)
        assertTrue("max ${s.errEventMaxM}", s.errEventMaxM <= 60.0)
    }

    @Test
    fun `a confident jump of a phone lying on a table is called a spoof and the dot stays on the table`() {
        val sc = named("на столі, підміна")
        val live = ReplayRun(sc, listOf(LiveDisplayChain())).run().single()
        assertTrue("виявлено за ${live.detectS}", live.detectS != null && live.detectS!! <= 5.0)
        assertTrue("max ${live.errEventMaxM}", live.errEventMaxM <= 30.0)
    }

    @Test
    fun `a tunnel is never called jamming or spoofing`() {
        for (sc in Scenarios.all.filter { it.event != EventKind.SPOOF && !it.jamming }) {
            for (s in ReplayRun(sc, listOf(LiveDisplayChain(), EngineChain(sc.body == Body.WALK))).run()) {
                assertEquals("${sc.name} / ${s.chain}", 0, s.falseSpoof + s.falseJam)
            }
        }
    }

    @Test
    fun `the returning sky joins without a leap`() {
        for (part in listOf("тунель 60 с", "затор", "метро 120")) {
            val s = shown(named(part))
            assertTrue("$part: ${s.mergeJumpM}", s.mergeJumpM <= 30.0)
        }
    }
}
