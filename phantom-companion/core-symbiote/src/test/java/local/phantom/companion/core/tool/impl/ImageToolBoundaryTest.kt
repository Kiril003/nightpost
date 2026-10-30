package local.phantom.companion.core.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Межі дієслова `image`, які можна перевірити без Android.
 *
 * Дзеркало [DocumentToolBoundaryTest]: там доведено, що читання документа не
 * стає читанням усього телефона. Тут те саме для світлини — плюс сторож на
 * розпізнавання типу, бо саме воно вирішує, чи піде кадр у рушій узагалі.
 */
class ImageToolBoundaryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `mime зображення визнається`() {
        assertTrue(looksLikeImage("image/jpeg", null))
        assertTrue(looksLikeImage("image/png", "будь-що"))
    }

    @Test
    fun `octet-stream рятує розширення`() {
        // Постачальники content:// часто віддають octet-stream, а вкладення
        // зі скріпки приходить узагалі без mime. Якби ми вірили лише mime,
        // світлина людини діставала б відмову «це не світлина».
        assertTrue(looksLikeImage("application/octet-stream", "photo.JPG"))
        assertTrue(looksLikeImage("", "скан.heic"))
    }

    @Test
    fun `текстовий документ не йде в рушій зору`() {
        assertFalse(looksLikeImage("text/plain", "угода.txt"))
        assertFalse(looksLikeImage("application/pdf", "рахунок.pdf"))
        assertFalse(looksLikeImage("", "нотатка.md"))
    }

    @Test
    fun `вкладення поза текою відхиляється`() {
        val root = temp.newFolder("attachments")
        val outside = File(temp.root, "чуже.jpg").apply { writeText("x") }
        assertNull("прямий шлях повз теку пройшов", stagedAttachment(outside.path, root))
        assertNull(
            "підйом через .. вивів за межу",
            stagedAttachment(File(root, "../чуже.jpg").path, root),
        )
    }

    @Test
    fun `справжнє вкладення приймається`() {
        val root = temp.newFolder("attachments")
        val inside = File(root, "скан.jpg").apply { writeText("x") }
        assertEquals(
            inside.canonicalPath,
            stagedAttachment(inside.path, root)?.canonicalPath,
        )
    }
}
