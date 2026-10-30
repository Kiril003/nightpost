package local.phantom.companion.ui.home

import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.MomentWire
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerMoment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFaceTest {

    private val me = "me000000"
    private val her = "her00000"
    private val t0 = 1_790_000_000_000L
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun moment(id: String, from: String, at: Long, thumb: String? = null, sha: String = "ab".repeat(32)): PeerMessage {
        val photo = buildJsonObject {
            put("name", "p.jpg")
            put("size", 1200)
            put("mime", "image/jpeg")
            put("sha256", sha)
            put("blob_id", id.repeat(32).take(32))
            put("key_hex", "cd".repeat(32))
            put("nonce_hex", "ef".repeat(12))
            if (thumb != null) put("thumb", thumb)
        }
        val body = PeerMoment.compose(photo, PeerMoment.EVERYONE, at)!!
        return PeerMessage(id = id + from, fromId = from, toId = "x", body = body, sentAtMs = at)
    }

    @Test
    fun `кола лише непорожні, з лічильником і в порядку застосунку`() {
        val got = ProfileFace.circles(mapOf("a" to "work", "b" to "close", "c" to "work", "d" to "nope"))
        assertEquals(listOf("close" to 1, "work" to 2), got.map { it.key to it.count })
        assertEquals(R.string.circle_work, got[1].label)
        assertTrue("порожні кола — чесно порожньо", ProfileFace.circles(emptyMap<String, String>()).isEmpty())
    }

    @Test
    fun `набори членств рахуються по кожному колу, власні й чужі не малюються`() {
        val got = ProfileFace.circles(
            mapOf("a" to setOf("close", "work"), "b" to setOf("work", "u:0123abcd"), "c" to setOf("nope")),
        )
        assertEquals(listOf("close" to 1, "work" to 2), got.map { it.key to it.count })
        assertEquals(R.string.circle_work, got[1].label)
        assertTrue(ProfileFace.circles(emptyMap<String, Set<String>>()).isEmpty())
        assertEquals(R.string.circle_personal, ProfileFace.circleLabel("personal"))
        assertEquals(R.string.circle_social, ProfileFace.circleLabel("social"))
    }

    @Test
    fun `моменти — лише свої й живі, з мініатюрою`() {
        val thumb = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val log = listOf(
            moment("a", me, t0, thumb),
            moment("b", her, t0),
            moment("c", me, t0 - PeerMoment.TTL_MS - 1),
        )
        val got = ProfileFace.highlights(log, me, t0 + 1000)
        assertEquals(1, got.size)
        assertArrayEquals(jpeg, got[0].thumb)
        assertTrue("без власного id нічого не приписуємо собі", ProfileFace.highlights(log, "", t0).isEmpty())
    }

    @Test
    fun `битий data url не валить обличчя`() {
        assertNull(ProfileFace.dataUrlBytes("data:image/jpeg;base64,"))
        assertNull(ProfileFace.dataUrlBytes("data:image/jpeg;base64,@@@"))
    }

    @Test
    fun `те саме фото двічі — одна мініатюра, з описом оригіналу`() {
        val log = listOf(
            moment("a", me, t0),
            moment("d", me, t0 + 10),
            moment("e", me, t0 + 20, sha = "99".repeat(32)),
        )
        val got = ProfileFace.highlights(log, me, t0 + 1000)
        assertEquals(2, got.size)
        assertEquals("dddddddddddddddddddddddddddddddd", got[1].media?.get("blob_id")?.toString()?.trim('"'))
    }

    @Test
    fun `залишений момент і живий з тим самим фото не двояться`() {
        val photo = buildJsonObject {
            put("mime", "image/jpeg")
            put("sha256", "ab".repeat(32))
            put("blob_id", "k".repeat(32))
        }
        val kept = MomentWire.Moment(
            id = "kept0001", author = me, media = listOf(photo), text = "",
            audience = MomentWire.Audience(MomentWire.Reach.EVERYONE), untilMs = 0L,
            createdAtMs = t0, editedAtMs = t0, rev = 1,
        )
        val got = ProfileFace.highlights(listOf(moment("a", me, t0)), me, t0 + 1000, kept = listOf(kept))
        assertEquals(listOf("kept0001"), got.map { it.id })
    }

    @Test
    fun `залишені відео і текстові моменти та живі з MomentBook не губляться на обличчі`() {
        val videoMedia = buildJsonObject {
            put("mime", "video/mp4")
            put("sha256", "cd".repeat(32))
            put("blob_id", "v".repeat(32))
        }
        val keptVideo = MomentWire.Moment(
            id = "kept_vid", author = me, media = listOf(videoMedia), text = "",
            audience = MomentWire.Audience(MomentWire.Reach.CONTACTS), untilMs = 0L,
            createdAtMs = t0, editedAtMs = t0, rev = 1,
        )
        val keptText = MomentWire.Moment(
            id = "kept_txt", author = me, media = emptyList(), text = "нотатка",
            audience = MomentWire.Audience(MomentWire.Reach.ONLY_ME), untilMs = 0L,
            createdAtMs = t0 + 10, editedAtMs = t0 + 10, rev = 1,
        )
        val liveBookMoment = MomentWire.Moment(
            id = "live_book", author = me, media = emptyList(), text = "сьогодні",
            audience = MomentWire.Audience(MomentWire.Reach.CONTACTS), untilMs = t0 + MomentWire.DAY_MS,
            createdAtMs = t0 + 20, editedAtMs = t0 + 20, rev = 1,
        )
        val got = ProfileFace.highlights(
            emptyList(),
            me,
            t0 + 1000,
            kept = listOf(keptVideo, keptText),
            liveBook = listOf(liveBookMoment),
        )
        assertEquals(listOf("kept_vid", "kept_txt", "live_book"), got.map { it.id })
    }
}
