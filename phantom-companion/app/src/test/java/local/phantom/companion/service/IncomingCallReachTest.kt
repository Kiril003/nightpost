package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun code(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `лінія з сітки доходить до системного дзвінка`() {
        val container = code("data/AppContainer.kt")
        assertTrue("callHub не віддає лінію в onCallLine", container.contains("onLine = ::onCallLine"))
        assertTrue("onCallLine не кличе show", Regex("""private fun onCallLine.*?PeerCallNotifications\.show\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(container))
        val notifications = code("service/PeerCallNotifications.kt")
        assertTrue("дзвінок не віддається системі", notifications.contains("IncomingCallTelecom.announce("))
        assertTrue("лінія не веде системне з'єднання", notifications.contains("IncomingCallTelecom.follow(line)"))
    }

    @Test
    fun `кнопки системи ходять тими ж дверима, що й кнопки плашки`() {
        val service = code("service/PeerCallConnectionService.kt")
        assertTrue(service.contains("PeerCallActionReceiver.decline("))
        assertTrue(service.contains("PeerCallNotifications.openCall("))
        assertTrue(service.contains("PeerCallNotifications.postRinging("))
    }

    @Test
    fun `дозвіл на власні дзвінки оголошено`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.MANAGE_OWN_CALLS"))
    }
}
