package local.phantom.companion.feature.onboarding.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** БП-01: перший запуск не просить ні екрана, ні доступності, ні камери, ні PIN. */
class FirstRunAsksNothingTest {

    private val forbidden = listOf(
        "ACCESSIBILITY", "MediaProjection", "createScreenCaptureIntent",
        "NOTIFICATION_LISTENER", "IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.", "RequestPermission", "androidx.camera", "startActivity(",
        "бачити твій екран", "PinSubmitted", "StartPairing",
    )

    private fun mainSources(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-onboarding").isDirectory) dir = dir.parentFile
        val root = File(dir ?: fail("feature-onboarding/ not found") as Nothing, "feature-onboarding/src/main")
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()
        assertTrue("сторож дивиться в порожнє: $root", files.size >= 5)
        return files
    }

    @Test
    fun `onboarding never asks for the screen, accessibility, camera or a PIN`() {
        val hits = mainSources().flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `an empty name still lets the person in`() {
        assertEquals("", submittedName("   "))
        assertEquals("Оля", submittedName("  Оля "))
        val screen = mainSources().first { it.name == "OnboardingScreen.kt" }.readText()
        assertTrue("onb_name_unnamed must not be used in OnboardingScreen", "onb_name_unnamed" !in screen)
    }

    @Test
    fun `NameStep and ProfileTab share the 32-grapheme QR name limit`() {
        assertEquals(32, local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME)
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-onboarding").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root not found") as Nothing
        val nameStep = File(root, "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/NameStep.kt").readText()
        val profileTab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        assertTrue("NameStep must reference CAP_QR_NAME", "CAP_QR_NAME" in nameStep)
        assertTrue("ProfileTab must reference CAP_QR_NAME", "CAP_QR_NAME" in profileTab)
        assertTrue("NameStep must not use MAX_DISPLAY_NAME_LENGTH", "MAX_DISPLAY_NAME_LENGTH" !in nameStep)
        assertTrue("NameStep must use PeerName.fit with CAP_QR_NAME", "PeerName.fit(" in nameStep)
    }
}
