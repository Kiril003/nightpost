package local.phantom.companion.core.sensor.nav.replay

import org.junit.Test
import java.io.File

class NavReplayBaselineTest {

    @Test
    fun `таблиця всіх ланцюгів по всіх сценаріях`() {
        val why = StringBuilder()
        val scores = (Scenarios.all + Scenarios.table(1.0)).flatMap { sc ->
            val chains = listOf(LiveDisplayChain(), EngineChain(sc.body == Body.WALK), BlindChain(sc.graph), BlindChain(sc.graph, raw = true))
            ReplayRun(sc, chains).run().also {
                chains.filter { it.spoofWhy.isNotEmpty() }.forEach { why.appendLine("${sc.name} / ${it.name}: ${it.spoofWhy}") }
            }
        }
        val table = formatScores(scores) + "\n" + why
        println(table)
        File("build/replay").mkdirs()
        File("build/replay/baseline.txt").writeText(table)
    }
}
