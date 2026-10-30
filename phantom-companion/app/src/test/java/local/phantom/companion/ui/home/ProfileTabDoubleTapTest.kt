package local.phantom.companion.ui.home

import java.io.File
import local.phantom.companion.ui.messenger.WebNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подвійний тап по «Я» повертає попередній профіль; сам профіль і є відповіддю, без плашки (T64). */
class ProfileTabDoubleTapTest {

    @Test fun `two taps inside the window are one double tap, a third starts over`() {
        val taps = DoubleTap(windowMs = 350L)
        assertFalse(taps.tap(1_000L))
        assertTrue(taps.tap(1_300L))
        assertFalse(taps.tap(1_400L))
    }

    @Test fun `slow taps are never a double tap`() {
        val taps = DoubleTap(windowMs = 350L)
        assertFalse(taps.tap(1_000L))
        assertFalse(taps.tap(1_500L))
        assertFalse(taps.tap(2_000L))
    }

    @Test fun `the back door calls the web store and an answer with the name counts as opened`() {
        val js = WebNav.script(WebNav.Door.ProfilesBack)
        assertTrue(js, "__phantomProfiles" in js && "back()" in js && "'missing'" in js)
        assertEquals(WebNav.Outcome.OPENED, WebNav.outcome("\"opened:Робота\""))
        assertEquals(WebNav.Outcome.REFUSED, WebNav.outcome("\"refused\""))
    }

    @Test fun `the bottom bar and home screen are wired to the door`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val bar = File(root, "app/src/main/java/local/phantom/companion/ui/home/HomeBottomBar.kt").readText()
        assertTrue("бар не рахує подвійний тап по «Я»", "onProfileDoubleTap" in bar && "DoubleTap(" in bar)
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home/HomeScreen.kt").readText()
        assertTrue("дім не відчиняє двері назад", "WebNav.request(WebNav.Door.ProfilesBack)" in home)
        assertFalse("плашка з назвою профілю над смугою (T64)", "Toast.makeText" in home)
    }
}
