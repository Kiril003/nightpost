package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapWakeTest {

    @Test
    fun `на Чатах мапа не народжується, доки її не відкрили`() {
        assertFalse(MapWake.wanted(woken = false, tab = HomeTab.CHATS, guiding = false, fences = 0))
        assertFalse(MapWake.wanted(woken = false, tab = HomeTab.WALLET, guiding = false, fences = 0))
        assertFalse(MapWake.wanted(woken = false, tab = HomeTab.PROFILE, guiding = false, fences = 0))
    }

    @Test
    fun `мапа будиться вкладкою, поїздкою або огорожею і далі живе`() {
        assertTrue(MapWake.wanted(woken = false, tab = HomeTab.MAP, guiding = false, fences = 0))
        assertTrue(MapWake.wanted(woken = false, tab = HomeTab.CHATS, guiding = true, fences = 0))
        assertTrue(MapWake.wanted(woken = false, tab = HomeTab.CHATS, guiding = false, fences = 1))
        assertTrue(MapWake.wanted(woken = true, tab = HomeTab.CHATS, guiding = false, fences = 0))
    }

    @Test
    fun `домівка народжує ViewModel і MapSession мапи лише за MapWake`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home/HomeScreen.kt").readText()
            .lineSequence().map { it.substringBefore("//") }.joinToString("\n")
        val gate = home.indexOf("MapWake.wanted(")
        assertTrue("домівка не питає MapWake", gate >= 0)
        assertTrue("ViewModel мапи народжується повз MapWake", home.indexOf("rememberMapViewModel()") > gate)
        assertTrue("ViewModel мапи народжується без умови", "if (mapWanted) rememberMapViewModel()" in home)
        val guarded = home.indexOf("if (mapVm != null) {")
        assertTrue("MapSession живе без ViewModel", guarded >= 0 && home.indexOf("MapSession(mapVm") > guarded)
        assertTrue("поїздка не будить мапу", "navEngine.state" in home)
        assertTrue("огорожа не будить мапу", "sentinelFenceEngine.count" in home)
        assertTrue("проснулась мапа засинає назад", "mapWoken = true" in home)
    }
}
