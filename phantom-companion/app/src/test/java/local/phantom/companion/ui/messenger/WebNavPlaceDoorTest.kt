package local.phantom.companion.ui.messenger

import local.phantom.companion.ui.home.HomeTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-GAP #5: «Мої люди» і «Мої сторінки» живуть у вебі; рідні Налаштування відчиняють їх дверима. */
class WebNavPlaceDoorTest {

    @After fun clear() {
        WebNav.pending.value = null
    }

    @Test fun `the place door calls the web door by its exact name with the key`() {
        val js = WebNav.script(WebNav.Door.OpenPlace(WebNav.PLACE_PEOPLE))
        assertTrue(js, "window.__phantomOpenPlace" in js && "f(\"people\")" in js && "'missing'" in js && "'opened'" in js)
    }

    @Test fun `keys match the web half`() {
        assertEquals("people", WebNav.PLACE_PEOPLE)
        assertEquals("builder", WebNav.PLACE_BUILDER)
    }

    @Test fun `opening a place queues the door and switches to the chats tab`() {
        val tabs = mutableListOf<HomeTab>()
        WebNav.openPlace(WebNav.PLACE_BUILDER, nowMs = 7_000L) { tabs += it }
        assertEquals(WebNav.Ask(WebNav.Door.OpenPlace("builder"), 7_000L + WebNav.TTL_MS), WebNav.pending.value)
        assertEquals(listOf(HomeTab.CHATS), tabs)
    }
}
