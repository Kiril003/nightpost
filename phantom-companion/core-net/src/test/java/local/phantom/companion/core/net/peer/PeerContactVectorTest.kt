package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запити в контакти — на тому самому векторі, який звіряє ПК
 * (`tests/test_contact_requests_vector.py`). Розбіжність тут не дає помилки на
 * апараті: вузол мовчки відкине чужий кадр, а запит просто не з'явиться.
 */
class PeerContactVectorTest {

    private val v: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("contact_requests_vector.json")) {
            "вектор не знайдено у test resources"
        }.reader(Charsets.UTF_8).readText(),
    ).jsonObject

    private fun JsonArray.objs() = map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    private class Mem : ContactRequestBook.Store {
        var raw: String? = null
        override fun read() = raw
        override fun write(raw: String) { this.raw = raw }
    }

    @Test
    fun `кадр на дроті той самий, що пише вузол`() {
        for (f in v.getValue("frames").jsonArray.objs()) {
            val op = checkNotNull(PeerContact.decode(f.s("frame"))) { f.s("kind") }
            val again = PeerContact.encode(op)
            assertEquals(f.s("kind"), kindOf(again))
            assertEquals(op, PeerContact.decode(again))
            assertEquals(f.s("frame").substringBefore('\n'), again.substringBefore('\n'))
            if (op is PeerContact.Op.Request) {
                assertEquals(Json.parseToJsonElement(f.s("body")), Json.parseToJsonElement(again.substringAfter('\n')))
            } else {
                assertEquals(f.s("body"), again.substringAfter('\n'))
            }
        }
    }

    private fun kindOf(frame: String) = "contact:" + frame.removePrefix(PeerContact.TAG).substringBefore('\n')

    @Test
    fun `вхідний запит лягає так, як каже вектор`() {
        for (c in v.getValue("cases").jsonArray.objs()) {
            val book = ContactRequestBook(Mem())
            val frame = v.s("mark") + c.s("kind") + "\n" + c.s("body")
            PeerContact.decode(frame)?.let { book.absorb("owner", c.s("peer"), c.s("display_name"), it) }
            val rows = book.listOpen("owner")
            val expect = c["expect"]
            if (expect is JsonObject) {
                assertEquals(c.s("name"), 1, rows.size)
                assertEquals(c.s("name"), expect.s("id"), rows[0].id)
                assertEquals(c.s("name"), expect.s("name"), rows[0].name)
                assertEquals(c.s("name"), expect.s("text"), rows[0].text)
            } else {
                assertTrue(c.s("name"), rows.isEmpty())
            }
        }
    }

    @Test
    fun `відкликання й згода чіпають лише свій рядок свого напряму`() {
        val book = ContactRequestBook(Mem())
        book.absorb("o", "p1", "A", PeerContact.Op.Request("r1", "A", "hi"))
        book.absorb("o", "p2", "B", PeerContact.Op.Withdraw("r1"))
        assertEquals(1, book.listOpen("o").size)
        book.absorb("o", "p1", "A", PeerContact.Op.Accept("r1"))
        assertEquals(1, book.listOpen("o").size)
        book.absorb("o", "p1", "A", PeerContact.Op.Withdraw("r1"))
        assertTrue(book.listOpen("o").isEmpty())
        assertEquals("withdrawn", book.find("o", "r1")?.state)
    }

    @Test
    fun `чужий власник не бачить і не зачіпає`() {
        val book = ContactRequestBook(Mem())
        book.absorb("o", "p1", "A", PeerContact.Op.Request("r1", "A", "hi"))
        assertTrue(book.listOpen("other").isEmpty())
        assertNull(book.find("other", "r1"))
        assertFalse(book.mark("other", "r1", "in", "declined"))
    }

    @Test
    fun `ліміти на добу - 30 вихідних і 50 вхідних`() {
        var t = 1_000_000L
        val book = ContactRequestBook(Mem()) { t }
        repeat(30) { book.createOutgoing("o", "o$it", "p$it", "N", "") }
        val over = runCatching { book.createOutgoing("o", "x", "px", "N", "") }.exceptionOrNull()
        assertTrue(over is ContactRequestBook.QuotaExceeded)
        repeat(50) { assertTrue(book.addIncoming("o", "i$it", "q$it", "N", "")) }
        assertFalse(book.addIncoming("o", "ix", "qx", "N", ""))
        t += 86_400_001L
        assertTrue(book.addIncoming("o", "iy", "qy", "N", ""))
    }

    @Test
    fun `другий запит тій самій людині повертає перший`() {
        val book = ContactRequestBook(Mem())
        val (a, fresh) = book.createOutgoing("o", "r1", "p", "N", "")
        val (b, again) = book.createOutgoing("o", "r2", "p", "N", "")
        assertTrue(fresh)
        assertFalse(again)
        assertEquals(a.id, b.id)
    }

    @Test
    fun `рядки переживають перезапуск`() {
        val mem = Mem()
        ContactRequestBook(mem).absorb("o", "p", "A", PeerContact.Op.Request("r1", "A", "hi"))
        assertEquals("hi", ContactRequestBook(mem).listOpen("o").single().text)
    }

    @Test
    fun `кадр запиту не слово людини`() {
        val frame = PeerContact.encode(PeerContact.Op.Request("r1", "A", "hi"))
        assertEquals(PeerFrames.Kind.CONTACT, PeerFrames.kindOf(frame))
        assertFalse(PeerOutbox.isSpoken(PeerMessage(id = "m", fromId = "a", toId = "b", body = frame, sentAtMs = 1L)))
    }

    @Test
    fun `посилання в тексті запиту - і кирилична назва сайту теж`() {
        assertTrue(ContactRequestBook.hasLink("зайди на приклад.com"))
        assertTrue(ContactRequestBook.hasLink("WWW.example.org"))
        assertFalse(ContactRequestBook.hasLink("Привіт, це Оля з роботи"))
    }
}
