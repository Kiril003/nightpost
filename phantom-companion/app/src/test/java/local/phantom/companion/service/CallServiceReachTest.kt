package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Нова поведінка мусить бути досяжна зі справжнього входу, а не лише з тесту. */
class CallServiceReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun code(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `вузол віддає зміну відео службі дзвінка`() {
        val container = code("data/AppContainer.kt")
        assertTrue(
            "callHub створюється без onVideo: відео посеред розмови не дійде до служби",
            Regex("""onVideo\s*=\s*\{[^}]*videoChanged\(""").containsMatchIn(container),
        )
    }

    @Test
    fun `маршрут звуку слухає зміни пристрою, поки служба тримає розмову`() {
        val service = code("service/PeerCallService.kt")
        val route = code("ui/messenger/CallAudioRoute.kt")
        assertTrue(route.contains("addOnCommunicationDeviceChangedListener"))
        assertTrue(route.contains("removeOnCommunicationDeviceChangedListener"))
        assertTrue("служба мусить заводити розмову через settle", service.contains(".settle("))
    }

    @Test
    fun `месенджер на екрані з відкритою розмовою заводить службу, якщо з фону її не завели`() {
        val host = code("ui/messenger/WebMessengerHost.kt")
        val effect = Regex("""repeatOnLifecycle\(Lifecycle\.State\.RESUMED\)\s*\{[^}]*holdCall\(""").find(host)
        assertTrue("holdCall ніхто не кличе з екрана", effect != null)
    }

    @Test
    fun `кінець розмови скидає маршрут звуку й без служби`() {
        val notifications = code("service/PeerCallNotifications.kt")
        val over = Regex("""is PeerCallLine\.Line\.Over, PeerCallLine\.Line\.Idle -> \{.*?\n            \}""", RegexOption.DOT_MATCHES_ALL)
            .find(notifications)?.value.orEmpty()
        assertTrue("гілка кінця розмови не знайдена", over.isNotBlank())
        assertTrue(over.contains("CallAudioRoute.shared(context).release()"))
    }

    @Test
    fun `підпис третьої кнопки влізає в картку на 360 dp`() {
        for (dir in listOf("values-uk", "values")) {
            val xml = File(root, "app/src/main/res/$dir/strings.xml").readText()
            val label = Regex("""<string name="peer_call_allow_full_screen">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$dir: рядка peer_call_allow_full_screen немає", label != null)
            assertTrue("$dir: «$label» задовгий для третьої кнопки CallStyle", label!!.length <= 14)
        }
    }
}
