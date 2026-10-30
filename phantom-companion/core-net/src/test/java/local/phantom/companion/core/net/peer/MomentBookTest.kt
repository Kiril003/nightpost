package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentBookTest {

    private val now = 1_790_000_000_000L
    private val sha = "a".repeat(64)

    private fun photo(blob: String = "0123456789abcdef0123456789abcdef", mime: String = "image/jpeg", extra: Map<String, Any> = emptyMap()): JsonObject =
        buildJsonObject {
            put("name", JsonPrimitive("m.jpg"))
            put("size", JsonPrimitive(1000))
            put("mime", JsonPrimitive(mime))
            put("sha256", JsonPrimitive(sha))
            put("blob_id", JsonPrimitive(blob))
            put("key_hex", JsonPrimitive("k".repeat(64)))
            put("nonce_hex", JsonPrimitive("n".repeat(24)))
            put("caption", JsonPrimitive("не має їхати"))
            extra.forEach { (k, v) -> put(k, if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
        }

    private fun moment(id: String = "mom_000001", rev: Int = 1, text: String = "кава", until: Long = now + MomentWire.DAY_MS, author: String = "anna") =
        MomentWire.Moment(
            id = id, author = author, media = listOf(photo()), text = text,
            audience = MomentWire.Audience(MomentWire.Reach.CIRCLES, listOf("close", "work")),
            untilMs = until, createdAtMs = now, editedAtMs = now, rev = rev,
        )

    private fun wire(op: MomentWire.Op, from: String = "anna") = MomentWire.decode(MomentWire.encode(op)!!, from)!!

    @Test
    fun `кадр — власний рід, не репліка й без сповіщення`() {
        val body = MomentWire.encode(MomentWire.Op.Put(moment()))!!
        assertEquals(PeerFrames.Kind.MOMENT, PeerFrames.kindOf(body))
        assertFalse(PeerFrames.isFuture(body))
        assertFalse(PeerOutbox.isSpoken(PeerMessage(id = "x", fromId = "anna", toId = "me", body = body, sentAtMs = now)))
    }

    @Test
    fun `put їде туди й назад, автор — відправник, підпис фото не їде`() {
        val op = wire(MomentWire.Op.Put(moment(author = "хтось-інший")), from = "anna") as MomentWire.Op.Put
        assertEquals("anna", op.moment.author)
        assertEquals("кава", op.moment.text)
        assertEquals(listOf("close", "work"), op.moment.audience.circles)
        assertNull(op.moment.media.single()["caption"])
    }

    @Test
    fun `чужа доба не довша за добу, keep лишається keep`() {
        val long = wire(MomentWire.Op.Put(moment(until = now + 10 * MomentWire.DAY_MS))) as MomentWire.Op.Put
        assertEquals(now + MomentWire.DAY_MS, long.moment.untilMs)
        val kept = wire(MomentWire.Op.Put(moment(until = 0L))) as MomentWire.Op.Put
        assertTrue(kept.moment.kept)
    }

    @Test
    fun `текст без медіа — можна, порожнє — ні, відео довше 15 с — ні`() {
        assertNotNull(MomentWire.encode(MomentWire.Op.Put(moment().copy(media = emptyList()))))
        assertNull(MomentWire.encode(MomentWire.Op.Put(moment().copy(media = emptyList(), text = ""))))
        assertNotNull(MomentWire.mediaOf(photo(mime = "video/mp4", extra = mapOf("duration_ms" to 9000))))
        assertNull(MomentWire.mediaOf(photo(mime = "video/mp4", extra = mapOf("duration_ms" to 30_000))))
        assertNull(MomentWire.mediaOf(photo(mime = "video/mp4")))
        assertNull(MomentWire.mediaOf(photo(mime = "application/pdf")))
    }

    @Test
    fun `правка доходить, стара ревізія не перемагає, видалення остаточне`() {
        var book = MomentBook().apply(wire(MomentWire.Op.Put(moment())), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Put(moment(rev = 2, text = "чай"))), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Put(moment(rev = 1, text = "кава"))), "anna", "me")
        assertEquals("чай", book.get("anna", "mom_000001")!!.moment.text)

        book = book.apply(wire(MomentWire.Op.Del("mom_000001", 3, now)), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Put(moment(rev = 9, text = "воскрес"))), "anna", "me")
        assertTrue(book.get("anna", "mom_000001")!!.moment.deleted)
        assertTrue(book.live(now).isEmpty())
    }

    @Test
    fun `видалення, що обігнало сам момент, не дає йому зʼявитись`() {
        var book = MomentBook().apply(wire(MomentWire.Op.Del("mom_000001", 1, now)), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Put(moment())), "anna", "me")
        assertTrue(book.live(now).isEmpty())
    }

    @Test
    fun `чужий не видалить і не перепише мій момент`() {
        var book = MomentBook().put(MomentBook.Entry(moment(author = "me"), recipients = setOf("anna")))
        book = book.apply(wire(MomentWire.Op.Put(moment(rev = 5, text = "підробка")), from = "me"), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Del("mom_000001", 9, now), from = "anna"), "anna", "me")
        assertEquals("кава", book.get("me", "mom_000001")!!.moment.text)
        assertFalse(book.get("me", "mom_000001")!!.moment.deleted)
    }

    @Test
    fun `бачили й смайли — лише від тих, кому я надіслав`() {
        var book = MomentBook().put(MomentBook.Entry(moment(author = "me"), recipients = setOf("anna")))
        book = book.apply(wire(MomentWire.Op.Seen("mom_000001", now), "anna"), "anna", "me")
        book = book.apply(wire(MomentWire.Op.Seen("mom_000001", now), "bob"), "bob", "me")
        book = book.apply(wire(MomentWire.Op.React("mom_000001", "🔥"), "anna"), "anna", "me")
        val e = book.get("me", "mom_000001")!!
        assertEquals(setOf("anna"), e.viewers.keys)
        assertEquals(mapOf("anna" to "🔥"), e.reactions)
        book = book.apply(wire(MomentWire.Op.React("mom_000001", ""), "anna"), "anna", "me")
        assertTrue(book.get("me", "mom_000001")!!.reactions.isEmpty())
        assertNull(MomentWire.encode(MomentWire.Op.React("mom_000001", "💩")))
    }

    @Test
    fun `доба минула — у прибирання, keep — у Highlights назавжди`() {
        val book = MomentBook()
            .put(MomentBook.Entry(moment(id = "mom_day0001")))
            .put(MomentBook.Entry(moment(id = "mom_keep001", until = 0L)))
        val later = now + MomentWire.DAY_MS + 1
        assertEquals(listOf("mom_day0001"), book.expired(later).map { it.id })
        assertEquals(listOf("mom_keep001"), book.live(later).map { it.moment.id })
        assertEquals(listOf("mom_keep001"), book.highlights("anna").map { it.id })
    }

    @Test
    fun `книга переживає запис і читання`() {
        val book = MomentBook().put(
            MomentBook.Entry(moment(author = "me"), recipients = setOf("anna"), viewers = mapOf("anna" to now), reactions = mapOf("anna" to "❤️")),
        )
        assertEquals(book, MomentBook.decode(book.encode()))
        assertEquals(MomentBook(), MomentBook.decode("не json"))
    }
}
