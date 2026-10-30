package local.phantom.companion.ui.messenger

import local.phantom.companion.ui.home.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/messenger/NAV_CONTRACT.md: what the web may ask the shell for. */
class WebNavTest {

    @Test
    fun `the four asks of the contract`() {
        assertEquals(WebNav.Act.Tab(HomeTab.CHATS), WebNav.parse("chats", "{}"))
        assertEquals(WebNav.Act.Tab(HomeTab.PROFILE), WebNav.parse("profile", "{}"))
        assertEquals(WebNav.Act.Tab(HomeTab.MAP), WebNav.parse("map", "{}"))
        assertEquals(WebNav.Act.CallPhantom, WebNav.parse("call-phantom", null))
        assertEquals(WebNav.Act.Scan, WebNav.parse("scan", "{}"))
    }

    @Test
    fun `map carries the person to centre on`() {
        assertEquals(
            WebNav.Act.Tab(HomeTab.MAP, peerId = "ab12cd"),
            WebNav.parse("map", """{"peerId": "ab12cd"}"""),
        )
    }

    @Test
    fun `map can be opened to drop the meetup pin`() {
        assertEquals(
            WebNav.Act.Tab(HomeTab.MAP, pickMeetup = true),
            WebNav.parse("map", """{"pick":"meetup"}"""),
        )
        assertEquals(WebNav.Act.Tab(HomeTab.MAP), WebNav.parse("map", """{"pick":"other"}"""))
    }

    @Test
    fun `an unknown ask is refused so the web falls back to its own screen`() {
        assertNull(WebNav.parse("settings", "{}"))
        assertNull(WebNav.parse(null, null))
    }
}
