package local.phantom.companion.data

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.CardVerdict
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.Ed25519Keypair
import local.phantom.companion.core.solana.NearbyState
import local.phantom.companion.core.solana.PaymentStatus
import local.phantom.companion.core.solana.Programs
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaTx
import local.phantom.companion.core.solana.WalletAddressFrame
import local.phantom.companion.data.nearby.NearbyCourier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Купюра без інтернету й без спільної Wi-Fi: справжні дві [PeerMesh], між
 * ними — підставне радіо, що просто передає байти. Доводиться все, крім
 * самого ефіру: вибір дороги, конверт під ключем пари, квитанція, двері
 * отримувача й одна картка на одну купюру.
 */
class PeerMeshNearbyTest {

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class Phone(name: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val pubB64: String = b64(signing.publicKey)
        val dhPubB64: String = b64(dh.publicKey)
        val id: String = PeerIntro.fingerprint(pubB64)
        val nameOf = name

        fun row() = PeerIdentity(
            id = id,
            name = nameOf,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = pubB64,
            dhPubKey = dhPubB64,
            addedAtMs = 1L,
        )
    }

    /** Радіо без радіо: кадр одразу в двері того, кому він адресований. */
    private inner class Air(private val receivers: () -> Map<String, PeerMesh>) : NearbyCourier {
        val handOffs = java.util.concurrent.atomic.AtomicInteger()
        var landed = 0
        var missed = 0
        override val state: StateFlow<NearbyState> = MutableStateFlow(NearbyState.IDLE)
        override fun missingPermissions(): List<String> = emptyList()
        override fun wantedNow(): Boolean = true
        override fun listen() = Unit
        override fun landed() {
            landed++
        }
        override fun missed() {
            missed++
        }
        override suspend fun handOff(toId: String, frames: List<String>): List<String> {
            handOffs.incrementAndGet()
            val door = receivers()[toId] ?: return emptyList()
            return frames.mapNotNull { door.acceptNearby(it) }
        }
    }

    private val petro = Phone("Петро")
    private val olena = Phone("Олена")
    private val stranger = Phone("Чужий")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meshes = mutableListOf<PeerMesh>()
    private val olenaGot = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()

    private fun mesh(me: Phone, knows: List<Phone>, air: NearbyCourier, onArrived: (PeerMessage) -> Unit = {}) = PeerMesh(
        roster = PeerRosterStore(MemSlot()).apply { knows.forEach { upsert(it.row()) } },
        selfId = { me.id },
        privateKey = { me.signing.privateKey },
        publicKey = { me.pubB64 },
        dhPrivateKey = { me.dh.privateKey },
        dhPublicKey = { me.dhPubB64 },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        online = { false },
        onArrived = onArrived,
        nowMs = { NOW },
        nearby = air,
    ).also { meshes += it }

    private lateinit var receiver: PeerMesh
    private val air = Air { mapOf(olena.id to receiver) }

    @After
    fun tearDown() {
        meshes.forEach { it.stop() }
        scope.cancel()
    }

    private suspend fun started(mesh: PeerMesh): PeerMesh {
        mesh.start(scope)
        repeat(200) {
            if (mesh.selfIdFlow.value.isNotBlank()) return mesh
            delay(10)
        }
        error("сітка не прочитала себе")
    }

    private suspend fun until(what: String, check: () -> Boolean) {
        repeat(300) {
            if (check()) return
            delay(20)
        }
        error(what)
    }

    @Test
    fun `без інтернету й без Wi-Fi купюра доходить поруч і має квитанцію`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val sender = started(mesh(petro, listOf(olena), air))
        val note = voucher(5_000_000).encode()

        val sent = sender.send(olena.id, note)!!

        until("купюра не доїхала") { olenaGot.isNotEmpty() }
        until("відправник не отримав квитанції") {
            sender.outbox.value.single { it.id == sent.id }.state == PeerMessage.State.DELIVERED
        }
        assertEquals(note, olenaGot.single().body)
        assertEquals(petro.id, olenaGot.single().fromId)
        assertEquals(1, air.landed)
    }

    @Test
    fun `слово, а не купюра, поруч не їде`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val sender = started(mesh(petro, listOf(olena), air))
        val sent = sender.send(olena.id, "привіт")!!
        until("лист так і не дійшов до черги") {
            sender.outbox.value.single { it.id == sent.id }.nextAttemptMs > 0L
        }
        delay(200)
        assertEquals(0, air.handOffs.get())
        assertTrue(olenaGot.isEmpty())
    }

    @Test
    fun `кадр від чужого поруч не відчиняється`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val outsider = started(mesh(stranger, listOf(olena), air))
        val sent = outsider.send(olena.id, voucher(7).encode())!!

        until("спроби поруч не було") { air.handOffs.get() > 0 }
        delay(200)
        assertTrue(olenaGot.isEmpty())
        assertNotEquals(PeerMessage.State.DELIVERED, outsider.outbox.value.single { it.id == sent.id }.state)
        assertEquals(0, air.landed)
        until("невдала спроба мусить сказати «не дійшло», інакше екран так і шукатиме") { air.missed > 0 }
    }

    @Test
    fun `та сама купюра двічі — одна картка, квитанція обом`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val sender = started(mesh(petro, listOf(olena), air))
        val note = voucher(3).encode()

        val first = sender.send(olena.id, note)!!
        val again = sender.send(olena.id, note)!!

        until("обидві копії мусять бути доставлені") {
            sender.outbox.value.filter { it.id == first.id || it.id == again.id }
                .all { it.state == PeerMessage.State.DELIVERED }
        }
        assertEquals(1, olenaGot.size)
        assertEquals(1, receiver.outbox.value.count { it.body == note })
    }

    @Test
    fun `без радіо стан чесний, і черга лишається як була`() = runBlocking {
        val quiet = local.phantom.companion.data.nearby.NoNearby(NearbyState.NO_SERVICES)
        val sender = started(mesh(petro, listOf(olena), quiet))
        val sent = sender.send(olena.id, voucher(9).encode())!!
        until("лист не повернувся в чергу") {
            sender.outbox.value.single { it.id == sent.id }.nextAttemptMs > 0L
        }
        assertEquals(PeerMessage.State.QUEUED, sender.outbox.value.single { it.id == sent.id }.state)
        assertEquals(NearbyState.NO_SERVICES, quiet.state.value)
        assertTrue(quiet.handOff(olena.id, listOf("x")).isEmpty())
    }

    @Test
    fun `кишеня, що ще не дійшла, їде поруч разом із купюрою — картка чекає мережі, а не червона`() = runBlocking {
        val pocketAtArrival = java.util.concurrent.atomic.AtomicReference<SolanaKey?>()
        receiver = started(
            mesh(olena, listOf(petro), air) {
                // Спершу кишеня, потім olenaGot: тест чекає на olenaGot і одразу читає кишеню.
                if (CashVoucher.isVoucher(it.body)) pocketAtArrival.set(pocketNamedBy(receiver.conversation(petro.id), petro.id))
                olenaGot += it
            },
        )
        val sender = started(mesh(petro, listOf(olena), air))
        val cash = voucher(5_000_000)
        val pocketLine = sender.send(olena.id, WalletAddressFrame.encodePocket(cash.signer!!))!!
        val addressLine = sender.send(olena.id, WalletAddressFrame.encode(SolanaKey.of(ByteArray(32) { 3 })!!))!!
        sender.send(olena.id, cash.encode())!!

        until("купюра не доїхала") { olenaGot.any { CashVoucher.isVoucher(it.body) } }
        assertEquals("кишеня мала лягти в розмову раніше за купюру", cash.signer, pocketAtArrival.get())
        val payer = pocketNamedBy(receiver.conversation(petro.id), petro.id)
        assertNull(CardVerdict.before(claimed = true, mine = false, payer = payer, signer = cash.signer))
        until("адреса й кишеня мали отримати квитанцію поруч") {
            sender.outbox.value.filter { it.id == pocketLine.id || it.id == addressLine.id }
                .all { it.state == PeerMessage.State.DELIVERED }
        }
        assertEquals(1, air.handOffs.get())
    }

    @Test
    fun `купюра чужої кишені й далі не збігається`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val sender = started(mesh(petro, listOf(olena), air))
        val cash = voucher(5_000_000)
        val named = SolanaKey.of(ByteArray(32) { 5 })!!
        sender.send(olena.id, WalletAddressFrame.encodePocket(named))!!
        sender.send(olena.id, cash.encode())!!

        until("купюра не доїхала") { olenaGot.any { CashVoucher.isVoucher(it.body) } }
        val payer = pocketNamedBy(receiver.conversation(petro.id), petro.id)
        assertEquals(named, payer)
        assertEquals(
            CardVerdict(PaymentStatus.MISMATCH, CardVerdict.OTHER_PAYER),
            CardVerdict.before(claimed = true, mine = false, payer = payer, signer = cash.signer),
        )
    }

    @Test
    fun `слово з черги поруч не їде навіть разом із купюрою`() = runBlocking {
        receiver = started(mesh(olena, listOf(petro), air) { olenaGot += it })
        val sender = started(mesh(petro, listOf(olena), air))
        sender.send(olena.id, "привіт")!!
        sender.send(olena.id, voucher(11).encode())!!

        until("купюра не доїхала") { olenaGot.any { CashVoucher.isVoucher(it.body) } }
        delay(100)
        assertTrue(olenaGot.none { it.body == "привіт" })
    }

    private fun voucher(units: Long): CashVoucher {
        val pocket = Ed25519Keypair(ByteArray(32) { 7 })
        val to = SolanaKey.of(ByteArray(32) { 42 })!!
        val nonce = Programs.seededAddress(pocket.publicKey, "phantom-cash-0", Programs.SYSTEM)
        val msg = CashVoucher.message(pocket.publicKey, nonce, ByteArray(32) { 9 }, to, Coin.USDC, Cluster.DEVNET, units)
        return CashVoucher.of(Cluster.DEVNET, Coin.USDC, units, to, SolanaTx.sign(msg, listOf(pocket)))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        fun b64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
