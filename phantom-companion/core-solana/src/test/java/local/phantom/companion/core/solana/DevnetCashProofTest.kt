package local.phantom.companion.core.solana

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Справжній devnet, лише з -Dphantom.devnet=1:
 * bash scripts/gate.sh :core-solana:testDebugUnitTest --tests '*DevnetCashProofTest*' -Dphantom.devnet=1
 * Ключ — лише devnet, у ~/.phantom-devnet/voucher-proof.json (поза репо, 600), щоб ручне поповнення
 * з faucet.solana.com не губилось між прогонами. У вивід ідуть тільки адреси й підписи.
 */
class DevnetCashProofTest {

    private val on = System.getProperty("phantom.devnet") == "1"
    private val FUNDED = 200_000_000L
    private val proof = mutableListOf<String>()
    private val rpc by lazy { SolanaRpc(Cluster.DEVNET.publicRpc) }

    @Test
    fun offlineVoucherIsRedeemedOnceAndItsTwinIsRefused() = runBlocking {
        assumeTrue(on)
        val (owner, pocket) = keys()
        val bob = Ed25519Keypair(ByteArray(32).also(java.security.SecureRandom()::nextBytes)).publicKey
        val carol = Ed25519Keypair(ByteArray(32).also(java.security.SecureRandom()::nextBytes)).publicKey
        note("owner", owner.publicKey.base58)
        note("pocket", pocket.publicKey.base58)
        note("bob", bob.base58)
        note("carol", carol.base58)

        if (rpc.lamports(owner.publicKey) < FUNDED) airdrop(owner.publicKey, 500_000_000L)
        val pocketBefore = rpc.lamports(pocket.publicKey)
        val ownerBefore = rpc.lamports(owner.publicKey)
        note("owner_lamports_before", ownerBefore.toString())

        val pocketUnits = 100_000_000L
        val notes = CashPlan.notes(pocket.publicKey)
        val rent = rpc.rentExempt(Programs.NONCE_SPACE)
        val missing = notes.filter { rpc.nonceValue(SolanaKey.of(it.nonce)) == null }
        note("notes_missing_before", missing.size.toString())
        val fundMsg = CashPlan.fund(owner.publicKey, pocket.publicKey, Coin.SOL, Cluster.DEVNET, pocketUnits, rent, missing, rpc.latestBlockhash())
        val fundTx = SolanaTx.sign(fundMsg, listOf(owner, pocket))
        note("fund_tx_bytes", fundTx.size.toString())
        val fundSig = rpc.sendRaw(fundTx)
        tx("fund", fundSig)
        confirmed(fundSig)
        assertEquals(pocketBefore + pocketUnits + CashPlan.FEES, rpc.lamports(pocket.publicKey))

        val values = notes.map { n -> waitFor { rpc.nonceValue(SolanaKey.of(n.nonce)) } }
        notes.forEachIndexed { i, n -> note("note$i", n.nonce) }

        // ── офлайн: кишеня підписує, мережі не торкаємось ─────────────────
        fun issue(i: Int, to: SolanaKey, units: Long): String {
            val msg = CashVoucher.message(pocket.publicKey, SolanaKey.of(notes[i].nonce), values[i], to, Coin.SOL, Cluster.DEVNET, units)
            return CashVoucher.of(Cluster.DEVNET, Coin.SOL, units, to, SolanaTx.sign(msg, listOf(pocket))).encode()
        }
        val toBob = issue(0, bob, 50_000_000L)
        val toBobAgain = issue(1, bob, 30_000_000L)
        // Модифікований клієнт: та сама купюра (той самий nonce) вдруге — вже іншій людині.
        val twinToCarol = issue(0, carol, 50_000_000L)

        // Передача — лише байти рядка, як через сітку.
        val atBob = CashVoucher.decode(String(toBob.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val atBobAgain = CashVoucher.decode(String(toBobAgain.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val atCarol = CashVoucher.decode(String(twinToCarol.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertNotNull(atBob); assertNotNull(atBobAgain); assertNotNull(atCarol)
        note("voucher_wire_chars", toBob.length.toString())

        // Звичайна транзакція тут уже б протухла (~150 слотів); купюра на nonce — ні.
        val hold = System.getProperty("phantom.devnetHoldSec")?.toLongOrNull() ?: 100L
        note("held_offline_sec", hold.toString())
        delay(hold * 1000)

        // ── погашення: хто перший онлайн. Порядок навмисно зворотний. ────
        val sig2 = rpc.sendRaw(atBobAgain!!.tx)
        assertEquals(atBobAgain.payment.signature, sig2)
        tx("redeem_note1_to_bob", sig2)
        val sig1 = rpc.sendRaw(atBob!!.tx)
        assertEquals(atBob.payment.signature, sig1)
        tx("redeem_note0_to_bob", sig1)
        assertEquals(PaymentStatus.CONFIRMED, waitFor { PaymentCheck.check(atBob.payment, rpc).takeIf { it.settled } })
        assertEquals(PaymentStatus.CONFIRMED, waitFor { PaymentCheck.check(atBobAgain.payment, rpc).takeIf { it.settled } })
        assertEquals(80_000_000L, rpc.lamports(bob))

        // ── подвійна витрата ───────────────────────────────────────────────
        val twin = runCatching { rpc.sendRaw(atCarol!!.tx) }
        note("twin_to_carol_refused", (twin.exceptionOrNull()?.message ?: "ACCEPTED ${twin.getOrNull()}"))
        note("twin_to_carol_signature", atCarol!!.payment.signature)
        assertTrue("twin voucher must be refused", twin.exceptionOrNull() is RpcError)
        val replay = runCatching { rpc.sendRaw(atBob.tx) }
        note("replay_to_bob", replay.exceptionOrNull()?.message ?: "returned ${replay.getOrNull()}")
        delay(15_000)
        assertEquals(0L, rpc.lamports(carol))
        assertEquals(80_000_000L, rpc.lamports(bob))
        assertTrue(rpc.signatureStatus(atCarol.payment.signature) is JsonNull)
        assertEquals(PaymentStatus.PENDING, PaymentCheck.check(atCarol.payment, rpc))

        // refreshCash вважає купюру погашеною, коли значення nonce змінилось.
        val after = rpc.nonceValue(SolanaKey.of(notes[0].nonce))!!
        assertNotEquals(Base58.encode(values[0]), Base58.encode(after))
        assertArrayEquals(values[2], rpc.nonceValue(SolanaKey.of(notes[2].nonce)))
        val left = rpc.lamports(pocket.publicKey)
        note("pocket_left_lamports", left.toString())
        assertEquals(pocketBefore + pocketUnits + CashPlan.FEES - 80_000_000L - 2 * 5_000L, left)
        write()
    }

    @Test
    fun proofAddress() {
        assumeTrue(on)
        note("owner", keys().first.publicKey.base58)
        write()
    }

    private fun keys(): Pair<Ed25519Keypair, Ed25519Keypair> {
        val f = File(System.getProperty("user.home"), ".phantom-devnet/voucher-proof.json")
        if (!f.exists()) {
            f.parentFile.mkdirs()
            val hex = ByteArray(64).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            f.writeText("""{"cluster":"devnet","purpose":"phantom voucher proof, devnet only","seed_hex":"$hex"}""")
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        val hex = Regex("\"seed_hex\":\"([0-9a-f]{128})\"").find(f.readText())!!.groupValues[1]
        val seed = ByteArray(64) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return (Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN)) to Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH))).also { seed.fill(0) }
    }

    @Test
    fun failoverWalksPastKeylessProvidersToThePublicNode() = runBlocking {
        assumeTrue(on)
        val keyless = listOf(
            "https://rpc.ankr.com/solana_devnet",
            "https://solana-devnet.g.alchemy.com/v2/demo",
            "https://devnet.helius-rpc.com",
            "https://solana-devnet.drpc.org",
        )
        val rpc = SolanaRpc(keyless + Cluster.DEVNET.publicRpc)
        val genesis = rpc.call("getGenesisHash", kotlinx.serialization.json.JsonArray(emptyList())).jsonPrimitive.content
        assertEquals("EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG", genesis)
        assertTrue(rpc.latestBlockhash().size == 32)
        val garbage = runCatching { rpc.sendRaw(ByteArray(200)) }.exceptionOrNull()
        assertTrue("$garbage", garbage is RpcError && garbage.fromChain)
        note("failover_garbage_tx", garbage!!.message ?: "")
        note("failover_genesis", genesis)
        note("failover_list", (keyless + Cluster.DEVNET.publicRpc).joinToString(" "))
    }

    private suspend fun airdrop(to: SolanaKey, lamports: Long) {
        var wait = 10_000L
        val attempts = System.getProperty("phantom.devnetAirdropTries")?.toIntOrNull() ?: 3
        for (attempt in 0 until attempts) {
            val r = runCatching { rpc.requestAirdrop(to, lamports) }
            r.getOrNull()?.let { sig ->
                tx("airdrop", sig)
                confirmed(sig)
                return
            }
            note("airdrop_retry_$attempt", r.exceptionOrNull()?.message ?: "?")
            delay(wait)
            wait = (wait * 2).coerceAtMost(120_000L)
        }
        write()
        error("devnet airdrop refused $attempts times — fund ${to.base58} at faucet.solana.com and rerun")
    }

    private suspend fun confirmed(sig: String) {
        val s = waitFor {
            (rpc.signatureStatus(sig) as? JsonObject)?.takeIf {
                it["confirmationStatus"]?.jsonPrimitive?.content in setOf("confirmed", "finalized")
            }
        }
        assertTrue("$sig failed: ${s["err"]}", s["err"] == null || s["err"] is JsonNull)
    }

    private suspend fun <T : Any> waitFor(tries: Int = 40, get: suspend () -> T?): T {
        repeat(tries) {
            runCatching { get() }.getOrNull()?.let { return it }
            delay(1_500)
        }
        error("timed out")
    }

    private fun tx(label: String, sig: String) {
        note(label, sig)
        note("$label.explorer", "https://explorer.solana.com/tx/$sig?cluster=devnet")
    }

    private fun note(k: String, v: String) {
        proof += "$k=$v"
        println("DEVNET-PROOF $k=$v")
    }

    private fun write() = File("build/devnet-proof.txt").writeText(proof.joinToString("\n") + "\n")
}
