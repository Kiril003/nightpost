package local.phantom.companion.data.applock

import java.io.File
import local.phantom.companion.service.PeerNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Замок доходить до екрана, до «Недавніх» і до шторки — і шторка з ним мовчить. */
class AppLockReachTest {

    private fun src(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test fun `зі схованим вмістом плашка не знає ні імені, ні тексту, і відповісти з неї не можна`() {
        val hidden = PeerNotifications.shown(hideContent = true, senderName = "Оксана", body = "код від воріт 2045")
        assertTrue(hidden.hidden)
        assertNull(hidden.sender)
        assertNull(hidden.text)
        assertEquals(false, hidden.canReply)
        val open = PeerNotifications.shown(hideContent = false, senderName = "Оксана", body = "привіт")
        assertEquals("Оксана", open.sender)
        assertEquals("привіт", open.text)
    }

    @Test fun `сповіщення питає замок ДО того, як складати розмову`() {
        val n = src("service/PeerNotifications.kt")
        val ask = n.indexOf("AppLockStore.of(context).hidesNotificationContent()")
        val person = n.indexOf("Person.Builder().setName(senderName)")
        assertTrue("сповіщення не питає замок", ask > 0)
        assertTrue("імʼя складається раніше, ніж питається замок", ask < person)
        // Плашка без вмісту не несе ні стилю розмови, ні ярлика з іменем.
        val hidden = n.substring(n.indexOf("private fun postHidden"), n.indexOf("Відповідь зі шторки вже стала"))
        assertTrue(!hidden.contains("setStyle") && !hidden.contains("setShortcutId") && !hidden.contains("replyAction"))
    }

    @Test fun `пелена стоїть над графом, а замок рахує вихід і повернення`() {
        val a = src("MainActivity.kt")
        assertTrue(a.contains("ui.applock.AppLockGate(appLock)"))
        assertTrue(a.contains("appLock.onForeground()") && a.contains("appLock.onBackground()"))
        assertTrue(a.contains("setRecentsScreenshotEnabled(!appLock.enabled)"))
        assertTrue(src("ui/home/ProfileSections.kt").contains("ProfileSection.APP_LOCK -> local.phantom.companion.ui.applock.AppLockSection(back)"))
    }
}
