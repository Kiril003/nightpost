package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Власне імʼя доїжджає до тих, хто вже тримає нас.
 *
 * Запрошення несе нове імʼя лише новим знайомим; решта бачила «PHANTOM-A52»
 * назавжди, бо кадр профілю імені не мав, а перейменування його не слало.
 *
 * GATE LAW: читає файли як текст, коментарі знімаються.
 */
class WebMessengerProfileNameReachTest {

    private val bridge by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        File(dir!!, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
            .readText().lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private fun body(fn: String) = bridge.substringAfter("fun $fn(").substringBefore("\n    private ")

    @Test
    fun `свій профіль іде через глашатая, що складає повний кадр з іменем`() {
        assertTrue(body("publishProfile").contains("container.profileHerald.publish("))
    }

    @Test
    fun `перейменування саме розсилає кадр усім`() {
        assertTrue("updateIdentity не розсилає кадр", body("updateIdentity").contains("container.profileHerald.bump()"))
    }
}
