package local.phantom.companion.ui.messenger

import local.phantom.companion.ui.home.HomeTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Профіль › Допомога › «Скарги й звернення»: рідна оболонка відчиняє веб-Допомогу (статуси скарг, звернення, обмеження). */
class WebNavHelpDoorTest {

    @After fun clear() {
        WebNav.pending.value = null
    }

    @Test fun `the help door calls the web door by its exact name`() {
        val js = WebNav.script(WebNav.Door.OpenHelp)
        assertTrue(js, "window.__phantomOpenHelp" in js && "'missing'" in js && "'opened'" in js)
    }

    @Test fun `opening help queues the door and switches to the chats tab where the web lives`() {
        val tabs = mutableListOf<HomeTab>()
        WebNav.openHelp(nowMs = 5_000L) { tabs += it }
        assertEquals(WebNav.Ask(WebNav.Door.OpenHelp, 5_000L + WebNav.TTL_MS), WebNav.pending.value)
        assertEquals(listOf(HomeTab.CHATS), tabs)
    }
}
