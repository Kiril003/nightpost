package local.phantom.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAllowListTest {

    private val allowed = setOf(
        // повідомлення і дзвінки від людей
        "app/src/main/java/local/phantom/companion/service/PeerNotifications.kt",
        "app/src/main/java/local/phantom/companion/service/PeerCallNotifications.kt",
        "app/src/main/java/local/phantom/companion/service/PeerCallService.kt",
        // нагадування, які людина поставила сама
        "app/src/main/java/local/phantom/companion/service/ReminderAlarms.kt",
        "app/src/main/java/local/phantom/companion/service/ReminderReceiver.kt",
        "app/src/main/java/local/phantom/companion/self/PhantomVisits.kt",
        "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/NotificationTool.kt",
        // тривога, яку людина ввімкнула в налаштуваннях
        "app/src/main/java/local/phantom/companion/service/AirAlertNotifications.kt",
        "app/src/main/java/local/phantom/companion/service/AirAlertService.kt",
        // питання і «знайди телефон» від ПК власника
        "app/src/main/java/local/phantom/companion/net/SymbioteExecutor.kt",
        // обовʼязковий рядок служби, що справді працює
        "app/src/main/java/local/phantom/companion/service/StatusShade.kt",
        "app/src/main/java/local/phantom/companion/service/NavigationService.kt",
        "app/src/main/java/local/phantom/companion/service/FalsePositionService.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/live/LiveShareService.kt",
        "feature-ambient/src/main/java/local/phantom/companion/feature/ambient/service/WhisperLineService.kt",
        "feature-ambient/src/main/java/local/phantom/companion/feature/ambient/service/WhisperLineNotification.kt",
    )

    private val post = Regex("""(?<!fun )\bnotify\(|\bstartForeground\(|NotificationCompat\.Builder\(|Notification\.Builder\(""")

    @Test
    fun `new notification call sites need a policy decision`() {
        val root = repoRoot()
        val found = root.listFiles { f -> f.isDirectory }!!
            .flatMap { module ->
                File(module, "src/main").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { post.containsMatchIn(stripComments(it.readText())) }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()
            }
            .toSet()
        assertEquals("сповіщення поза списком дозволених: ${found - allowed}", emptySet<String>(), found - allowed)
        assertTrue("зі списку зникли файли: ${allowed - found}", (allowed - found).all { File(root, it).isFile })
    }

    @Test
    fun `the foreground row is one silent min-importance line without status`() {
        val src = stripComments(File(repoRoot(), "app/src/main/java/local/phantom/companion/service/StatusShade.kt").readText())
        assertTrue(src.contains("IMPORTANCE_MIN"))
        assertFalse(src.contains("setContentText"))
        assertFalse(Regex("""R\.string\.status_(link|mesh|sky|voice)""").containsMatchIn(src))
        val strings = File(repoRoot(), "app/src/main/res").walkTopDown()
            .filter { it.name == "strings.xml" }.joinToString("\n") { it.readText() }
        for (gone in listOf("status_link_", "status_mesh_", "status_sky_", "status_voice_")) {
            assertFalse("$gone повернулось", strings.contains("name=\"$gone"))
        }
    }

    @Test
    fun `diagnostics never post to the shade`() {
        val root = repoRoot()
        val voice = stripComments(File(root, "app/src/main/java/local/phantom/companion/service/VoiceFallbackNotice.kt").readText())
        assertFalse(voice.contains("notify("))
        val container = stripComments(File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText())
        assertFalse("PositionNotice повернувся", container.contains("PositionNotice"))
        val visits = stripComments(File(root, "app/src/main/java/local/phantom/companion/self/PhantomVisits.kt").readText())
        assertTrue("порада без нагадування знову в шторці", visits.contains("if (!reminder) return"))
        val symbiote = stripComments(File(root, "app/src/main/java/local/phantom/companion/net/SymbioteExecutor.kt").readText())
        assertFalse("звіт ПК знову в шторці", symbiote.contains("showPlain("))
    }

    private fun stripComments(s: String): String =
        s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "корінь дерева не знайдено" }
    }
}
