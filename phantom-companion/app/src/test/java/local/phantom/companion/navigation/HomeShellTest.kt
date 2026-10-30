package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell the owner asked for (05.10): Розмови · Мапа · Гаманець · Профіль, chats first, nothing floating over them.
 * Read from source — `:app` tests compose nothing (no Robolectric, no ui-test).
 */
class HomeShellTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `bootstrap lands on home and home opens on chats`() {
        // Розвилка старту живе в LaunchRoute (збірка 16): її кличуть і Bootstrap, і перевірка короткого шляху.
        assertTrue(src("navigation/LaunchRoute.kt").contains("else -> Routes.Home.path"))
        assertTrue(src("navigation/PhantomNavGraph.kt").contains("LaunchRoute.target(list)"))
        assertTrue(src("ui/home/HomeScreen.kt").contains("initialTab: HomeTab = HomeTab.CHATS"))
    }

    @Test
    fun `four tabs - chats, map, wallet, profile`() {
        // Рішення власника 05.10: «месенджер, мапа й гаманець» — звʼязок першим,
        // гаманець — своя вкладка, а не рядок у Профілі.
        assertEquals(listOf("CHATS", "MAP", "WALLET", "PROFILE"), local.phantom.companion.ui.home.HomeTab.entries.map { it.name })
        val bar = src("ui/home/HomeBottomBar.kt")
        assertEquals(4, Regex("""TabSpec\(HomeTab\.""").findAll(bar).count())
    }

    @Test
    fun `no island, orb or chest over the graph`() {
        val graph = src("navigation/PhantomNavGraph.kt")
        listOf("IslandHost", "OrbActor", "ChestSheet", "ContinuumHost").forEach {
            assertFalse("$it is back in the graph", graph.contains(it))
        }
        assertFalse(File(root, "app/src/main/java/local/phantom/companion/ui/PhantomIsland.kt").exists())
    }

    @Test
    fun `back from the chat list leaves the app instead of opening the map`() {
        val home = src("ui/home/HomeScreen.kt")
        assertTrue(home.contains("moveTaskToBack(true)"))
        assertTrue(home.contains("BackHandler(enabled = tab != HomeTab.CHATS || slot != null)"))
        assertTrue(home.contains("if (slot != null) closeSlot() else tab = HomeTab.CHATS"))
    }

    @Test
    fun `the messenger webview sleeps behind other tabs`() {
        val host = src("ui/messenger/WebMessengerHost.kt")
        assertTrue(host.contains("webView.onPause()"))
        assertTrue(host.contains("BackHandler(enabled = active"))
    }
}
