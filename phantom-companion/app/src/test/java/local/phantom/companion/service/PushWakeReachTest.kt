package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakeReachTest {

    private val companion: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun text(rel: String) = File(companion, rel).readText()

    private fun code(rel: String) = text(rel)
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private val stub = File(companion, "app/fcm/FcmGlue.kt.stub")

    @Test
    fun `шов Firebase лежить одним файлом поза зібраними теками`() {
        assertTrue("немає app/fcm/FcmGlue.kt.stub", stub.isFile)
        val head = stub.readText().lineSequence().takeWhile { it.startsWith("//") || it.isBlank() }.joinToString("\n")
        assertTrue(head.contains("not compiled until the owner approves the Firebase fetch"))
        for (marker in listOf("FirebaseMessagingService", "com.google.firebase.MESSAGING_EVENT")) {
            assertTrue("у шапці шва немає $marker", head.contains(marker))
        }
        assertTrue(head.contains("com.google.gms.google-services"))
        assertTrue(head.contains("firebase-bom"))
        assertTrue(head.contains("firebase-messaging"))
        assertTrue(head.contains("google-services.json"))
        assertFalse("шов складається Gradle-ом", stub.name.endsWith(".kt"))
    }

    @Test
    fun `шов лише передає пінг обробнику і не читає його вмісту`() {
        val body = stub.readText().lineSequence().filterNot { it.startsWith("//") }.joinToString("\n")
        assertTrue(body.contains("PushWake.shared(applicationContext).onData(message.data)"))
        assertFalse("шов не мусить торкатись notification", body.contains("message.notification"))
    }

    @Test
    fun `у дереві немає нічого, що вимагає незавантаженого`() {
        for (rel in listOf("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", "gradle/libs.versions.toml")) {
            val gradle = text(rel)
            assertFalse("$rel вже тягне google-services", gradle.contains("google-services"))
            assertFalse("$rel вже тягне firebase-messaging", gradle.contains("firebase-messaging"))
        }
        assertFalse(text("app/src/main/AndroidManifest.xml").contains("MESSAGING_EVENT"))
        val committed = companion.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
            .filter { it.isFile && it.name == "google-services.json" }
            .toList()
        assertEquals("google-services.json не живе в дереві", emptyList<File>(), committed)
    }

    @Test
    fun `google-services json ніколи не потрапляє в git`() {
        val ignore = generateSequence(companion) { it.parentFile }
            .map { File(it, ".gitignore") }
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertTrue(ignore.lines().any { it.trim() == "google-services.json" })
    }

    @Test
    fun `обробник будує спільний екземпляр із забору й служби сітки`() {
        val wake = code("app/src/main/java/local/phantom/companion/service/PushWake.kt")
        assertTrue("забір не йде в сітку", wake.contains("pollRelayNow()"))
        assertTrue("мертва сітка не піднімається", wake.contains("PeerMeshService.ensureRunning("))
        assertTrue("налаштування не звіряються", wake.contains("PushConfig.present("))
    }
}
