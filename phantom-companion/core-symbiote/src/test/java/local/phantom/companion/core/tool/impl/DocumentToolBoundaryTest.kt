package local.phantom.companion.core.tool.impl

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Межа дієслова `doc`: читає лише те, що людина сама поклала у вкладення.
 * Тест бʼє саме у втечу з теки — решта дірок цього класу коштує ключі.
 */
class DocumentToolBoundaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `вкладення читається`() {
        val root = tmp.newFolder("attachments")
        val f = File(root, "угода.txt").apply { writeText("текст") }
        assertNotNull(stagedAttachment(f.path, root))
    }

    @Test
    fun `втеча через крапки не проходить`() {
        val root = tmp.newFolder("attachments")
        val secret = File(tmp.root, "keys.db").apply { writeText("ключі") }
        val escape = File(root, "../keys.db").path
        assertNull("вихід за межі теки вкладень пройшов", stagedAttachment(escape, root))
        assertNull("прямий шлях повз теку пройшов", stagedAttachment(secret.path, root))
    }

    @Test
    fun `тека не є документом`() {
        val root = tmp.newFolder("attachments")
        val dir = File(root, "тека").apply { mkdirs() }
        assertNull(stagedAttachment(dir.path, root))
    }

    @Test
    fun `сусідня тека з тим самим початком імені не проходить`() {
        val root = tmp.newFolder("attachments")
        val sibling = tmp.newFolder("attachments-old")
        val f = File(sibling, "x.txt").apply { writeText("x") }
        assertNull("префікс імені сплутано з межею теки", stagedAttachment(f.path, root))
    }
}
