package local.phantom.companion.core.net.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PeerPreviewTest {

    private val t0 = 1_790_000_000_000L

    @Test fun `перше https-посилання без кінцевої пунктуації, http не береться`() {
        assertEquals("https://uk.wikipedia.org/wiki/Київ", PeerPreview.firstUrl("глянь https://uk.wikipedia.org/wiki/Київ."))
        assertEquals("https://a.example/x?y=1", PeerPreview.firstUrl("(https://a.example/x?y=1), а потім https://b.example"))
        assertNull(PeerPreview.firstUrl("http://plain.example/"))
        assertNull(PeerPreview.firstUrl("без посилань"))
    }

    @Test fun `кадр туди й назад, з мініатюрою, і межі тримаються`() {
        val img = ByteArray(2_000) { it.toByte() }
        val p = PeerPreview.Preview("https://news.example/a", "Заголовок", "Опис", "news.example", img)
        val body = PeerPreview.encode("m-1", p)!!
        val (target, back) = PeerPreview.decode(body)!!
        assertEquals("m-1", target)
        assertEquals(p, back)
        assertArrayEquals(img, back.image)
        // Завелика мініатюра — картка без зображення, а не довший кадр.
        val big = PeerPreview.encode("m-1", p.copy(image = ByteArray(PeerPreview.MAX_IMAGE_BYTES + 1)))!!
        assertNull(PeerPreview.decode(big)!!.second.image)
        // Без заголовка картки немає; адреса не https — теж.
        assertNull(PeerPreview.encode("m-1", p.copy(title = " ")))
        assertNull(PeerPreview.decode("PH-LPV:m-1;{\"u\":\"http://x.example\",\"t\":\"x\"}"))
        assertEquals(PeerFrames.Kind.PREVIEW, PeerFrames.kindOf(body))
        assertFalse(PeerOutbox.isSpoken(PeerMessage("f", "her", "me", body, t0)))
    }

    @Test fun `картка належить листу лише від того самого автора і з тією самою адресою`() {
        val said = PeerMessage("m-1", "her", "me", "читай https://news.example/a", t0)
        val p = PeerPreview.Preview("https://news.example/a", "Новина")
        val own = PeerMessage("f1", "her", "me", PeerPreview.encode("m-1", p)!!, t0 + 1)
        val forged = PeerMessage("f2", "eve", "me", PeerPreview.encode("m-1", p.copy(title = "Підміна"))!!, t0 + 2)
        val elsewhere = PeerMessage("f3", "her", "me", PeerPreview.encode("m-1", p.copy(url = "https://evil.example/", title = "Інше"))!!, t0 + 3)
        assertEquals("Новина", PeerPreview.of(listOf(said, own, forged, elsewhere), said)!!.title)
        assertNull(PeerPreview.of(listOf(said, forged), said))
    }

    @Test fun `сторінка — Open Graph, потім title, а відносна картинка стає повною`() {
        val html = """
            <html><head><title>Запасний &amp; заголовок</title>
            <meta property="og:title" content="Справжній заголовок">
            <meta name='description' content='Опис сторінки'>
            <meta property="og:image" content="/img/cover.jpg">
            <meta property="og:site_name" content="Новини">
            </head><body>…</body></html>
        """.trimIndent()
        val m = PeerPreview.parse(html, "https://www.news.example/a/b")!!
        assertEquals("Справжній заголовок", m.title)
        assertEquals("Опис сторінки", m.description)
        assertEquals("Новини", m.site)
        assertEquals("https://www.news.example/img/cover.jpg", m.imageUrl)
        val bare = PeerPreview.parse("<title>Лише &#1050;иїв</title>", "https://www.kyiv.example/")!!
        assertEquals("Лише Київ", bare.title)
        assertEquals("kyiv.example", bare.site)
        assertNull(bare.imageUrl)
        assertNull(PeerPreview.parse("<html><body>нічого</body></html>", "https://x.example/"))
    }
}
