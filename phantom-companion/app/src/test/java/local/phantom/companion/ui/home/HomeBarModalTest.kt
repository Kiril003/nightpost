package local.phantom.companion.ui.home

import java.io.File
import local.phantom.companion.data.AppEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBarModalTest {

    @Test
    fun `модальний шар веба ховає смугу лише на вкладці чатів`() {
        assertTrue(homeBarVisible(HomeTab.CHATS, inConversation = false, modalOpen = false, ime = false))
        assertFalse(homeBarVisible(HomeTab.CHATS, inConversation = false, modalOpen = true, ime = false))
        assertFalse(homeBarVisible(HomeTab.CHATS, inConversation = true, modalOpen = false, ime = false))
        assertTrue(homeBarVisible(HomeTab.MAP, inConversation = false, modalOpen = true, ime = false))
        assertFalse(homeBarVisible(HomeTab.MAP, inConversation = false, modalOpen = false, ime = true))
    }

    @Test
    fun `прапорець шару стоїть у шині подій і скидається`() {
        val bus = AppEventBus()
        assertFalse(bus.webModalOpen.value)
        bus.webModalOpen.value = true
        assertEquals(true, bus.webModalOpen.value)
    }

    @Test
    fun `міст приймає POST chrome і віддає прапорець у шину, а домівка читає його`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue(bridge.contains("seg[1] == \"chrome\" && seg.size == 2 && method == \"POST\" -> markChrome(body)"))
        assertTrue(bridge.contains("container.events.webModalOpen.value = hidden"))
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home/HomeScreen.kt").readText()
        assertTrue(home.contains("container.events.webModalOpen.collectAsStateWithLifecycle()"))
        assertTrue(home.contains("homeBarVisible(tab, inConversation, modalOpen,"))
    }
}
