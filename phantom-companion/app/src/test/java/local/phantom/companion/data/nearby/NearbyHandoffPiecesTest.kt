package local.phantom.companion.data.nearby

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.Ed25519Keypair
import local.phantom.companion.core.solana.NearbyState
import local.phantom.companion.core.solana.Programs
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaTx
import local.phantom.companion.core.solana.WalletAddressFrame
import local.phantom.companion.data.CashRepeat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyHandoffPiecesTest {

    private val olena = "olena-id"
    private val petro = "petro-id"

    @Test
    fun `знайомий впізнає імʼя в ефірі, чужий — ні`() {
        val name = NearbyTag.of(olena)
        assertEquals(olena, NearbyTag.whose(name, listOf(petro, olena)))
        assertNull(NearbyTag.whose(name, listOf(petro)))
        assertNull(NearbyTag.whose("Olena's Galaxy", listOf(olena)))
        assertNull(NearbyTag.whose("P1!!!", listOf(olena)))
    }

    @Test
    fun `імʼя не несе id і щоразу інше`() {
        val a = NearbyTag.of(olena)
        val b = NearbyTag.of(olena)
        assertNotEquals(a, b)
        assertFalse(olena in a)
        assertEquals(olena, NearbyTag.whose(b, listOf(olena)))
    }

    @Test
    fun `пакет туди й назад`() {
        val frame = NearbyPacket.Frame("""{"v":3,"from":"a","to":"b","n":"x","ct":"y"}""")
        assertEquals(frame, NearbyPacket.decode(NearbyPacket.encode(frame)))
        val receipt = NearbyPacket.Receipt("r".repeat(43))
        assertEquals(receipt, NearbyPacket.decode(NearbyPacket.encode(receipt)))
        assertNull(NearbyPacket.decode("hello".toByteArray()))
        assertNull(NearbyPacket.decode("PHN1F".toByteArray()))
        assertNull(NearbyPacket.encode(NearbyPacket.Frame("x".repeat(NearbyPacket.MAX_BYTES))))
    }

    @Test
    fun `поруч пробується лише для купюри і лише без інтернету`() {
        val cash = voucher(1).encode()
        assertTrue(NearbyPlan.worthTrying(cash, online = false))
        assertFalse(NearbyPlan.worthTrying(cash, online = true))
        assertFalse(NearbyPlan.worthTrying("привіт", online = false))
    }

    @Test
    fun `з купюрою поруч їдуть лише адреса й кишеня`() {
        val pocket = SolanaKey.of(ByteArray(32) { 1 })!!
        assertTrue(NearbyPlan.ridesWithCash(WalletAddressFrame.encodePocket(pocket)))
        assertTrue(NearbyPlan.ridesWithCash(WalletAddressFrame.encode(pocket)))
        assertFalse(NearbyPlan.ridesWithCash("привіт"))
        assertFalse(NearbyPlan.ridesWithCash(voucher(1).encode()))
    }

    @Test
    fun `невдала передача після вдалої не каже «передано»`() {
        val s = NearbyStatus()
        s.searching()
        assertEquals(NearbyState.SEARCHING, s.state())
        s.landed()
        assertEquals(NearbyState.HANDED, s.state())
        s.searching()
        assertEquals(NearbyState.SEARCHING, s.state())
        s.missed()
        assertEquals(NearbyState.IDLE, s.state())
    }

    @Test
    fun `«передано» видно й тоді, коли телефон сам слухає поруч`() {
        val s = NearbyStatus()
        s.listen(true)
        assertEquals(NearbyState.LISTENING, s.state())
        s.searching()
        s.landed()
        assertEquals(NearbyState.HANDED, s.state())
        s.listen(true)
        assertEquals(NearbyState.LISTENING, s.state())
        s.listen(false)
        assertEquals(NearbyState.IDLE, s.state())
    }

    @Test
    fun `між пошуками радіо відпочиває`() {
        assertTrue(NearbyPlan.due(null, 1_000L))
        assertFalse(NearbyPlan.due(1_000L, 1_000L + NearbyPlan.PAUSE_MS - 1))
        assertTrue(NearbyPlan.due(1_000L, 1_000L + NearbyPlan.PAUSE_MS))
    }

    @Test
    fun `дозволи за версією Android`() {
        val loc = android.Manifest.permission.ACCESS_FINE_LOCATION
        val wifi = android.Manifest.permission.NEARBY_WIFI_DEVICES
        val scan = android.Manifest.permission.BLUETOOTH_SCAN
        assertEquals(listOf(loc), NearbyPlan.permissions(30))
        assertTrue(NearbyPlan.permissions(31).containsAll(listOf(loc, scan)))
        assertFalse(wifi in NearbyPlan.permissions(32))
        assertTrue(wifi in NearbyPlan.permissions(34))
        assertFalse(loc in NearbyPlan.permissions(34))
    }

    @Test
    fun `без сервісів Google — чесний стан і жодної спроби`() = runBlocking {
        val none = NoNearby(NearbyState.NO_SERVICES)
        assertEquals(NearbyState.NO_SERVICES, none.state.value)
        assertTrue(none.missingPermissions().isEmpty())
        assertFalse(none.wantedNow())
        assertTrue(none.handOff(olena, listOf("frame")).isEmpty())
    }

    @Test
    fun `та сама купюра з іншим id — повтор, інша купюра — ні`() {
        val note = voucher(5).encode()
        val first = line("m1", note)
        assertFalse(CashRepeat.seen(emptyList(), first))
        assertFalse(CashRepeat.seen(listOf(first), first))
        assertTrue(CashRepeat.seen(listOf(first), line("m2", note)))
        assertFalse(CashRepeat.seen(listOf(first), line("m3", voucher(6).encode())))
        assertFalse(CashRepeat.seen(listOf(line("t", "текст")), line("t2", "текст")))
    }

    private fun line(id: String, body: String) =
        PeerMessage(id = id, fromId = petro, toId = olena, body = body, sentAtMs = 1L)

    private fun voucher(units: Long): CashVoucher {
        val pocket = Ed25519Keypair(ByteArray(32) { 7 })
        val to = SolanaKey.of(ByteArray(32) { 42 })!!
        val nonce = Programs.seededAddress(pocket.publicKey, "phantom-cash-0", Programs.SYSTEM)
        val msg = CashVoucher.message(pocket.publicKey, nonce, ByteArray(32) { 9 }, to, Coin.USDC, Cluster.DEVNET, units)
        return CashVoucher.of(Cluster.DEVNET, Coin.USDC, units, to, SolanaTx.sign(msg, listOf(pocket)))
    }
}
