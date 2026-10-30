package local.phantom.companion.data

import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBannerStoreTest {

    private val dir = Files.createTempDirectory("banners").toFile()
    private val store = PeerBannerStore(dir)
    private val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
    private val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)
    private val ha = PeerBanner.hashOf(a)
    private val hb = PeerBanner.hashOf(b)
    private val spec = PeerProfile.Banner(listOf(PeerProfile.BannerItem(ha, a.size))).spec()

    @Test
    fun `фото з опису лягає за хешем`() {
        assertTrue(store.absorb("ana", PeerBanner.encode(a)!!, spec))
        assertTrue(store.file(ha)!!.readBytes().contentEquals(a))
    }

    @Test
    fun `фото, якого нема в описі людини, на диск не лягає`() {
        assertFalse(store.absorb("ana", PeerBanner.encode(b)!!, spec))
        assertFalse(store.has(hb))
        // Опис іншої людини чуже фото не відмикає.
        store.adopt("bo", PeerProfile.Banner(listOf(PeerProfile.BannerItem(hb, b.size))).spec())
        assertFalse(store.has(hb))
    }

    @Test
    fun `фото, що випередило опис, забирається з приходом опису`() {
        assertFalse(store.absorb("ana", PeerBanner.encode(a)!!, null))
        assertFalse(store.has(ha))
        store.adopt("ana", spec)
        assertTrue(store.has(ha))
    }

    @Test
    fun `стерті з усіх описів фото прибираються`() {
        store.absorb("ana", PeerBanner.encode(a)!!, spec)
        store.keepOnly(listOf(spec, null))
        assertTrue(store.has(ha))
        store.keepOnly(listOf("", null))
        assertFalse(store.has(ha))
    }

    @Test
    fun `рядок для веба — приїхале фото адресою, в дорозі null`() {
        val two = PeerProfile.Banner(listOf(PeerProfile.BannerItem(ha, a.size, 5, 6), PeerProfile.BannerItem(hb, b.size))).spec()
        store.absorb("ana", PeerBanner.encode(a)!!, two)
        val row = store.row(two, "/m/")!!
        assertEquals(listOf(PeerBannerStore.Shown("/m/$ha", 5, 6), PeerBannerStore.Shown(null, 128, 128)), row.items)
        assertNull(store.row("", "/m/"))
        assertNull(store.row(null, "/m/"))
    }

    @Test
    fun `адреса банера веде на маршрут фото, а не в бандл`() {
        assertEquals(
            local.phantom.companion.ui.messenger.Served.Banner(ha),
            local.phantom.companion.ui.messenger.WebMessengerAssets.resolve("https://phantom.local/webmsgr/_banners/$ha?v=2") { null },
        )
    }

    @Test
    fun `імʼя з адреси — лише 32 hex`() {
        assertNull(store.file("../x"))
        assertNull(store.file(ha.uppercase()))
        assertEquals(32, ha.length)
    }

    @Test
    fun `вхід з дроту - фото раніше за опис, потім профіль, і чуже прибирається`() {
        val old = PeerProfile.Banner(listOf(PeerProfile.BannerItem(hb, b.size))).spec()
        store.take("bo", PeerBanner.encode(b)!!, profileApplied = false, spec = old) { listOf(old) }
        assertTrue(store.has(hb))
        store.take("ana", PeerBanner.encode(a)!!, profileApplied = false, spec = null) { listOf(old) }
        assertFalse(store.has(ha))
        store.take("ana", "PH-PRF:профіль", profileApplied = true, spec = spec) { listOf(spec) }
        assertTrue("фото, що випередило опис, не забрано", store.has(ha))
        assertFalse("фото людини без банера лишилось", store.has(hb))
    }

    @Test
    fun `контейнер веде кожен лист через take`() {
        val src = java.io.File("src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        val body = src.substringAfter("private fun onPeerMessage(").substringBefore("announceMessage(message)")
        assertTrue(body.contains("peerBanners.take(message.fromId, message.body, applied, specOf(message.fromId))"))
        assertTrue(body.contains("val applied = runCatching { peers.absorbProfile(message.fromId, message.body) }"))
    }
}
