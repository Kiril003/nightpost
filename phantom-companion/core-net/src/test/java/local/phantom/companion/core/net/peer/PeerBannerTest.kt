package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Банер профілю: опис у кадрі `PH-PRF:` (теги 11 і 12) і самі фото кадрами `PH-PBN:`.
 * Золоті рядки пораховані окремо за описом формату; ті самі стоять у
 * `profileWireCodec.test.ts` і `test_peer_frames_vectors.py`.
 */
class PeerBannerTest {

    private val h1 = "00112233445566778899aabbccddeeff"
    private val h2 = "ffeeddccbbaa99887766554433221100"
    private val banner = PeerProfile.Banner(
        items = listOf(PeerProfile.BannerItem(h1, 1000, 128, 77), PeerProfile.BannerItem(h2, 24576, 0, 255)),
        videoStill = true,
    )

    @Test fun `опис банера кодується рівно в золотий рядок`() {
        val body = PeerProfile.Body(rev = 7, banner = PeerProfile.Field.Set(banner))
        assertEquals(GOLDEN_PRF, PeerProfile.encode(body, pad = false))
        assertEquals(body, PeerProfile.decode(GOLDEN_PRF))
    }

    @Test fun `стертий банер — порожній тег 11`() {
        val body = PeerProfile.Body(rev = 8, banner = PeerProfile.Field.Cleared)
        assertEquals("PH-PRF:AQQAAAAICwA", PeerProfile.encode(body, pad = false))
        assertEquals(PeerProfile.Field.Cleared, PeerProfile.decode("PH-PRF:AQQAAAAICwA")!!.banner)
    }

    @Test fun `кадр без банера — Absent, старі золоті рядки не змінились`() {
        val d = PeerProfile.decode(PeerProfile.encode(PeerProfile.Body(rev = 3))!!)!!
        assertEquals(PeerProfile.Field.Absent, d.banner)
    }

    @Test fun `розбіжність кількості в тегах 11 і 12 — банер не повідомлено, а не чуже фото`() {
        val raw = byteArrayOf(1, 4, 0, 0, 0, 7, 11, 5, 1, 2, 0, 1, 1, 12, 19) +
            ByteArray(16) + byteArrayOf(0, 0, 9)
        val d = PeerProfile.decode(PeerProfile.TAG + java.util.Base64.getEncoder().withoutPadding().encodeToString(raw))
        assertNotNull(d)
        assertEquals(PeerProfile.Field.Absent, d!!.banner)
    }

    @Test fun `банер довшої майбутньої версії — не повідомлено, а імʼя людини лишається`() {
        val d = PeerProfile.decode("PH-PRF:AQQAAAAHBgJJYQsQAgcAAAAAAAAAAAAAAAAAAA")
        assertNotNull("профіль відкинуто через банер", d)
        assertEquals(PeerProfile.Field.Set("Ia"), d!!.name)
        assertEquals(PeerProfile.Field.Absent, d.banner)
    }

    @Test fun `понад шість фото або фото понад 24 КіБ — відмова до сокета`() {
        val seven = PeerProfile.Banner(List(7) { PeerProfile.BannerItem(h1, 10) })
        assertNull(PeerProfile.encode(PeerProfile.Body(rev = 1, banner = PeerProfile.Field.Set(seven))))
        val big = PeerProfile.Banner(listOf(PeerProfile.BannerItem(h1, 24 * 1024 + 1)))
        assertNull(PeerProfile.encode(PeerProfile.Body(rev = 1, banner = PeerProfile.Field.Set(big))))
        val badHash = PeerProfile.Banner(listOf(PeerProfile.BannerItem("xyz", 10)))
        assertNull(PeerProfile.encode(PeerProfile.Body(rev = 1, banner = PeerProfile.Field.Set(badHash))))
    }

    @Test fun `фото банера — один кадр PH-PBN, хеш звіряється`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
        assertEquals("fc16d7dcee9cae83ef3923222a81ccd8", PeerBanner.hashOf(jpeg))
        assertEquals(GOLDEN_PBN, PeerBanner.encode(jpeg))
        val piece = PeerBanner.decode(GOLDEN_PBN)!!
        assertEquals("fc16d7dcee9cae83ef3923222a81ccd8", piece.hash)
        assertTrue(piece.bytes.contentEquals(jpeg))
        // Підмінені байти під чужим хешем не проходять.
        val forged = GOLDEN_PBN.dropLast(2) + "AA"
        assertNull(PeerBanner.decode(forged))
    }

    @Test fun `не картинка або понад стелю — кадру немає`() {
        assertNull(PeerBanner.encode(byteArrayOf(1, 2, 3)))
        val big = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(PeerBanner.MAX_BYTES)
        assertNull(PeerBanner.encode(big))
    }

    @Test fun `найбільший кадр PH-PBN влазить у тіло дроту`() {
        val max = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(PeerBanner.MAX_BYTES - 3)
        val frame = PeerBanner.encode(max)!!
        // Конверт кодує тіло ще раз у base64: ×4/3 і запас на підпис.
        assertTrue(frame.length * 4 / 3 + 1024 < PeerWire.MAX_BODY_BYTES)
    }

    @Test fun `PH-PBN — службовий кадр, у розмову не йде`() {
        assertEquals(PeerFrames.Kind.BANNER, PeerFrames.kindOf(GOLDEN_PBN))
        assertFalse(PeerFrames.Kind.BANNER.reachesConversation)
        val m = PeerMessage(id = "b1", fromId = "a", toId = "b", body = GOLDEN_PBN, sentAtMs = 1L)
        assertFalse(PeerOutbox.isSpoken(m))
        // Байти, а не слова: на вузькій дорозі чекають разом зі шматками файлів.
        assertEquals(PeerLane.Lane.MEDIA, PeerLane.of(m))
    }

    @Test fun `реєстр запамʼятовує опис банера і стирання`() {
        val held = PeerIdentity(id = "p1", name = "Олег", kind = PeerIdentity.Kind.PERSON, pubKey = "k")
        val r1 = PeerRoster.absorbProfile(listOf(held), "p1", GOLDEN_PRF, 10L)
        val spec = r1.single().bannerSpec
        assertNotNull(spec)
        assertEquals(banner, PeerProfile.Banner.parse(spec!!))
        val r2 = PeerRoster.absorbProfile(r1, "p1", "PH-PRF:AQQAAAAICwA", 11L)
        assertEquals("", r2.single().bannerSpec)
    }

    companion object {
        const val GOLDEN_PRF = "PH-PRF:AQQAAAAHCwcBAgGATQD/DCYAESIzRFVmd4iZqrvM3e7/AAPo/+7dzLuqmYh3ZlVEMyIRAABgAA"
        const val GOLDEN_PBN = "PH-PBN:AfwW19zunK6D7zkjIiqBzNj/2P/gABA"
    }
}
