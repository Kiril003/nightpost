package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerMomentTest {

    private val me = "me000000"
    private val her = "her00000"
    private val him = "him00000"
    private val t0 = 1_790_000_000_000L
    private val blob = "0123456789abcdef0123456789abcdef"

    private fun photo(blobId: String = blob, mime: String = "image/jpeg", caption: String = "") = buildJsonObject {
        put("name", "p.jpg")
        put("size", 1200)
        put("mime", mime)
        put("sha256", "ab".repeat(32))
        put("blob_id", blobId)
        put("key_hex", "cd".repeat(32))
        put("nonce_hex", "ef".repeat(12))
        put("width", 1080)
        put("height", 1440)
        if (caption.isNotEmpty()) put("caption", caption)
        put("evil", "x")
    }

    private fun line(id: String, from: String, to: String, body: String, at: Long, deleted: Long = 0L) =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at, deletedAtMs = deleted)

    @Test fun `момент — це опис фото з полем moment, і нічого зайвого`() {
        val body = PeerMoment.compose(photo(caption = "  хліб  "), PeerMoment.EVERYONE, t0)!!
        assertTrue(PeerMoment.isMoment(body))
        assertNotNull("стара збірка бачить звичайне фото", PeerFile.describe(body))
        assertFalse(body.contains("evil"))
        val m = PeerMoment.read(line("a", her, me, body, t0))!!
        assertEquals(blob, m.id)
        assertEquals("хліб", m.caption)
        assertEquals(t0 + PeerMoment.TTL_MS, m.untilMs)
        assertEquals(PeerMoment.EVERYONE, m.audience)
        assertEquals(JsonPrimitive("ef".repeat(12)), m.media["nonce_hex"])
    }

    @Test fun `не фото і не опис — не момент`() {
        assertNull(PeerMoment.compose(photo(mime = "application/pdf"), PeerMoment.EVERYONE, t0))
        assertNull(PeerMoment.compose(photo(blobId = "nope"), PeerMoment.EVERYONE, t0))
        assertNotNull(PeerFile.describe(photo().toString()))
        assertFalse("звичайне фото в розмові — не момент", PeerMoment.isMoment(photo().toString()))
        assertFalse(PeerMoment.isMoment("привіт"))
    }

    @Test fun `підпис обрізано, аудиторія лише знайома`() {
        val long = "а".repeat(500)
        val m = PeerMoment.read(line("a", her, me, PeerMoment.compose(photo(caption = long), "circle:Close friends", t0)!!, t0))!!
        assertEquals(PeerMoment.CAPTION_MAX, m.caption.length)
        assertEquals("circle:Close friends", m.audience)
        assertEquals(PeerMoment.PEOPLE, PeerMoment.audienceOf("circle:"))
        assertEquals(PeerMoment.PEOPLE, PeerMoment.audienceOf("всім світу"))
        assertEquals(PeerMoment.PEOPLE, PeerMoment.audienceOf("circle:" + "x".repeat(80)))
        assertEquals(PeerMoment.EVERYONE, PeerMoment.audienceOf("everyone"))
    }

    @Test fun `строк відправника не довший за добу від надсилання`() {
        val forged = PeerMoment.compose(photo(), PeerMoment.EVERYONE, t0)!!
            .replace("\"until\":${t0 + PeerMoment.TTL_MS}", "\"until\":${t0 + 30 * PeerMoment.TTL_MS}")
        assertTrue(forged.contains("${t0 + 30 * PeerMoment.TTL_MS}"))
        assertEquals(t0 + PeerMoment.TTL_MS, PeerMoment.read(line("a", her, me, forged, t0))!!.untilMs)
    }

    @Test fun `живі — свіжі першими, свої копії одним моментом, стерті й прострочені геть`() {
        val mine = PeerMoment.compose(photo(), PeerMoment.EVERYONE, t0)!!
        val hers = PeerMoment.compose(photo(blobId = "f".repeat(32)), PeerMoment.EVERYONE, t0 + 1000)!!
        val old = PeerMoment.compose(photo(blobId = "e".repeat(32)), PeerMoment.EVERYONE, t0 - PeerMoment.TTL_MS)!!
        val gone = PeerMoment.compose(photo(blobId = "d".repeat(32)), PeerMoment.EVERYONE, t0)!!
        val log = listOf(
            line("1", me, her, mine, t0),
            line("2", me, him, mine, t0),
            line("3", her, me, hers, t0 + 1000),
            line("4", him, me, old, t0 - PeerMoment.TTL_MS),
            line("5", him, me, gone, t0, deleted = t0 + 5),
            line("6", her, me, "привіт", t0 + 2000),
        )
        val now = t0 + 60_000
        val live = PeerMoment.live(log, now)
        assertEquals(listOf("f".repeat(32), blob), live.map { it.id })
        assertEquals(listOf("1", "2"), live[1].lines.map { it.id })
        assertEquals(listOf("4"), PeerMoment.expired(log, now).map { it.id })
        assertEquals(listOf("1", "2", "3", "4"), PeerMoment.expired(log, t0 + 2 * PeerMoment.TTL_MS).map { it.id })
    }

    @Test fun `момент із майбутнього не живе й прибирається`() {
        val body = PeerMoment.compose(photo(), PeerMoment.EVERYONE, t0 + 3 * PeerMoment.TTL_MS)!!
        val log = listOf(line("1", her, me, body, t0 + 3 * PeerMoment.TTL_MS))
        assertTrue(PeerMoment.live(log, t0).isEmpty())
        assertEquals(listOf("1"), PeerMoment.expired(log, t0).map { it.id })
    }
}
