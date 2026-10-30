package local.phantom.companion.packaging

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadAgentPathsGoneTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val deadFiles = listOf(
        "core-symbiote/src/main/java/local/phantom/companion/core/agent/CognitiveCouncil.kt",
        "core-symbiote/src/main/java/local/phantom/companion/core/agent/ParallelAgentRunner.kt",
        "core-symbiote/src/main/java/local/phantom/companion/core/resilience/queue/OfflineIntentQueue.kt",
        "app/src/main/java/local/phantom/companion/service/AgentMissionService.kt",
        "app/src/main/java/local/phantom/companion/work/OfflineDrainScheduler.kt",
        "app/src/main/java/local/phantom/companion/work/OfflineDrainWorker.kt",
        "core-vision/src/main/java/local/phantom/companion/core/vision/scene/mlkit/MlKitSceneDetector.kt",
        "core-symbiote/src/test/java/local/phantom/companion/core/agent/CognitiveCouncilTest.kt",
        "core-symbiote/src/test/java/local/phantom/companion/core/agent/ParallelAgentRunnerTest.kt",
        "core-symbiote/src/test/java/local/phantom/companion/core/resilience/queue/OfflineIntentQueueTest.kt",
        "app/src/test/java/local/phantom/companion/work/DrainedAnswerTest.kt",
    )

    private val deadContainerVals = listOf(
        "val offlineIntentQueue",
        "val agentSupervisor",
        "val parallelAgentRunner",
        "val cognitiveCouncil",
        "val agentConstitutionGate",
        "val agentCancellationRegistry",
    )

    private val deadNames = Regex(
        """\b(AgentMissionService|OfflineDrainScheduler|OfflineDrainWorker|OfflineIntentQueue|CognitiveCouncil|ParallelAgentRunner|MlKitSceneDetector|CouncilDebate)\b"""
    )

    private fun mainDirs(): List<File> =
        tree.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "app" || it.name.startsWith("core-") || it.name.startsWith("feature-")) }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }

    @Test
    fun `dead files are gone`() {
        val left = deadFiles.filter { File(tree, it).exists() }
        assertTrue("лишились файли: $left", left.isEmpty())
    }

    @Test
    fun `the container no longer builds them`() {
        val container = File(tree, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        val left = deadContainerVals.filter { it in container }
        assertTrue("у AppContainer.kt лишились: $left", left.isEmpty())
    }

    @Test
    fun `no main source names them`() {
        val hits = mainDirs().flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
                .flatMap { f ->
                    f.readLines().mapIndexedNotNull { idx, line ->
                        deadNames.find(line)?.let { "${f.relativeTo(tree).path}:${idx + 1}: ${it.value}" }
                    }
                }
                .toList()
        }
        assertTrue("згадки в src/main: $hits", hits.isEmpty())
    }

    @Test
    fun `the module scan is not empty`() {
        val dirs = mainDirs()
        assertTrue("каталогів src/main замало: ${dirs.size}", dirs.size >= 15)
    }
}
