package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T53/T63: петля банера в описі (тег 13) і прохання глядача `PH-PBQ:`. */
class PeerBannerVideoTest {

    private val sha = "0123456789abcdef".repeat(4)
    private val still = PeerProfile.BannerItem("00112233445566778899aabbccddeeff", 1000, 128, 77)
    private val video = PeerProfile.BannerVideo(sha, 3_456_789)

    @Test fun `петля їде в кадрі профілю й читається назад`() {
        val banner = PeerProfile.Banner(listOf(still), videoStill = true, video = video)
        val body = PeerProfile.Body(rev = 9, banner = PeerProfile.Field.Set(banner))
        val back = PeerProfile.decode(PeerProfile.encode(body)!!)!!
        assertEquals(body, back)
        assertEquals(sha.take(32), (back.banner as PeerProfile.Field.Set).value.video!!.blobId)
    }

    @Test fun `опис для реєстру несе петлю, старий опис без неї читається як раніше`() {
        val banner = PeerProfile.Banner(listOf(still), videoStill = true, video = video)
        assertEquals(banner, PeerProfile.Banner.parse(banner.spec()))
        val old = PeerProfile.Banner(listOf(still), videoStill = true)
        assertEquals("v1;1;${still.hash}:1000:128:77", old.spec())
        assertNull(PeerProfile.Banner.parse(old.spec())!!.video)
    }

    @Test fun `петля понад 8 МБ або без першого кадру — опис невалідний`() {
        val big = PeerProfile.Banner(listOf(still), videoStill = true, video = video.copy(size = PeerProfile.MAX_BANNER_VIDEO_BYTES + 1))
        assertNull(PeerProfile.encode(PeerProfile.Body(rev = 1, banner = PeerProfile.Field.Set(big))))
        val noStill = PeerProfile.Banner(listOf(still), videoStill = false, video = video)
        assertNull(PeerProfile.Banner.parse(noStill.spec()))
    }

    @Test fun `кадр без тега 13 лишається першим кадром`() {
        val body = PeerProfile.Body(rev = 2, banner = PeerProfile.Field.Set(PeerProfile.Banner(listOf(still), videoStill = true)))
        val back = PeerProfile.decode(PeerProfile.encode(body)!!)!!
        assertNull((back.banner as PeerProfile.Field.Set).value.video)
    }

    @Test fun `прохання глядача — службовий кадр, лише з блобом`() {
        val frame = PeerBanner.pull(sha.take(32))!!
        assertEquals(sha.take(32), PeerBanner.pulled(frame))
        assertEquals(PeerFrames.Kind.BANNER_PULL, PeerFrames.kindOf(frame))
        assertEquals(false, PeerFrames.Kind.BANNER_PULL.reachesConversation)
        assertNull(PeerBanner.pull("../etc"))
        assertNull(PeerBanner.pulled(PeerBanner.PULL + "zz"))
    }
}
