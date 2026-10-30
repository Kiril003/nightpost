package local.phantom.companion.core.solana

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Купюри в SPL-токені на справжньому devnet, лише з -Dphantom.devnet=1:
 * bash scripts/gate.sh :core-solana:testDebugUnitTest --tests '*DevnetSplCashProofTest*' -Dphantom.devnet=1
 * Без -Dphantom.devnetMint тест сам створює мінт з 6 знаками (як USDC) і карбує собі 100 одиниць.
 * З -Dphantom.devnetMint=4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU — справжній devnet USDC,
 * якщо на гаманці доказу є щонайменше 10 USDC; з HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr — так само EURC,
 * і купюра тоді їде як `eurc`. Ключ — той самий, що в DevnetCashProofTest.
 */
class DevnetSplCashProofTest {

    private val on = System.getProperty("phantom.devnet") == "1"
    private val proof = mutableListOf<String>()
    private val rpc by lazy { SolanaRpc(Cluster.DEVNET.publicRpc) }
    private val realMint = System.getProperty("phantom.devnetMint")?.takeIf { it.isNotBlank() }
    private val coin = realMint?.let { m -> Coin.entries.firstOrNull { it.mint(Cluster.DEVNET)?.base58 == m } } ?: Coin.USDC

    @Test
    fun tokenVoucherCreatesTheRecipientAccountIsRedeemedOnceAndItsTwinIsRefused() = runBlocking {
        assumeTrue(on)
        val (owner, pocket) = keys()
        val elena = Ed25519Keypair(ByteArray(32).also(java.security.SecureRandom()::nextBytes)).publicKey
        val carol = Ed25519Keypair(ByteArray(32).also(java.security.SecureRandom()::nextBytes)).publicKey
        note("owner", owner.publicKey.base58)
        note("pocket", pocket.publicKey.base58)
        note("elena", elena.base58)
        note("carol", carol.base58)
        note("owner_lamports_before", rpc.lamports(owner.publicKey).toString())

        val mint = realMint?.let(SolanaKey::of) ?: createMint(owner)
        note("mint", mint.base58)
        val pocketUnits = 10_000_000L
        val ownerUnits = rpc.tokenUnits(owner.publicKey, mint)
        note("owner_token_units_before", ownerUnits.toString())
        check(ownerUnits >= pocketUnits) { "owner holds $ownerUnits of ${mint.base58}, needs $pocketUnits" }

        val accountRent = rpc.rentExempt(165)
        note("token_account_rent", accountRent.toString())
        assertTrue(accountRent <= CashPlan.TOKEN_ACCOUNT_RENT)
        assertTrue(rpc.rentExempt(0) <= CashPlan.POCKET_RENT)

        val notes = CashPlan.notes(pocket.publicKey)
        val missing = notes.filter { rpc.nonceValue(SolanaKey.of(it.nonce)) == null }
        note("notes_missing_before", missing.size.toString())
        val pocketSolBefore = rpc.lamports(pocket.publicKey)
        val pocketTokenBefore = rpc.tokenUnits(pocket.publicKey, mint)
        val fundMsg = CashPlan.fund(
            owner.publicKey, pocket.publicKey, coin, Cluster.DEVNET, pocketUnits,
            rpc.rentExempt(Programs.NONCE_SPACE), missing, rpc.latestBlockhash(), mint = mint,
        )
        val fundSig = rpc.sendRaw(SolanaTx.sign(fundMsg, listOf(owner, pocket)))
        tx("fund", fundSig)
        confirmed(fundSig)
        val pocketSol = rpc.lamports(pocket.publicKey)
        assertEquals(pocketSolBefore + CashPlan.FEES, pocketSol)
        assertEquals(pocketTokenBefore + pocketUnits, rpc.tokenUnits(pocket.publicKey, mint))

        val values = notes.map { n -> waitFor { rpc.nonceValue(SolanaKey.of(n.nonce)) } }
        notes.forEachIndexed { i, n -> note("note$i", n.nonce) }
        assertTrue(CashPlan.solCovers(coin, pocketSol, 1))

        // ── офлайн: кишеня підписує, мережі не торкаємось ─────────────────
        fun issue(i: Int, to: SolanaKey, units: Long): String {
            val msg = CashVoucher.message(pocket.publicKey, SolanaKey.of(notes[i].nonce), values[i], to, coin, Cluster.DEVNET, units, mint = mint)
            return CashVoucher.of(Cluster.DEVNET, coin, units, to, SolanaTx.sign(msg, listOf(pocket))).encode()
        }
        val fiveToElena = issue(0, elena, 5_000_000L)
        val twoToElena = issue(1, elena, 2_000_000L)
        val twinToCarol = issue(0, carol, 5_000_000L)

        fun received(wire: String): CashVoucher = CashVoucher.decode(String(wire.toByteArray(Charsets.UTF_8), Charsets.UTF_8), mint).also(::assertNotNull)!!
        val atElena = received(fiveToElena)
        val atElenaAgain = received(twoToElena)
        val atCarol = received(twinToCarol)
        note("voucher_wire_chars", fiveToElena.length.toString())
        note("voucher_tx_bytes", atElena.tx.size.toString())

        val hold = System.getProperty("phantom.devnetHoldSec")?.toLongOrNull() ?: 100L
        note("held_offline_sec", hold.toString())
        delay(hold * 1000)

        // ── погашення у зворотному порядку; рахунку токена в Олени ще немає ─
        assertTrue(rpc.account(Programs.associatedTokenAddress(elena, mint)).jsonObject["value"] is JsonNull)
        val sig2 = rpc.sendRaw(atElenaAgain.tx)
        assertEquals(atElenaAgain.payment.signature, sig2)
        tx("redeem_note1_to_elena", sig2)
        val sig1 = rpc.sendRaw(atElena.tx)
        assertEquals(atElena.payment.signature, sig1)
        tx("redeem_note0_to_elena", sig1)
        val pocketKey = pocket.publicKey
        assertEquals(PaymentStatus.CONFIRMED, waitFor { PaymentCheck.check(atElena.payment, rpc, pocketKey, mint).takeIf { it.settled } })
        assertEquals(PaymentStatus.CONFIRMED, waitFor { PaymentCheck.check(atElenaAgain.payment, rpc, pocketKey, mint).takeIf { it.settled } })
        assertEquals(7_000_000L, rpc.tokenUnits(elena, mint))

        // ── подвійна витрата ───────────────────────────────────────────────
        val twin = runCatching { rpc.sendRaw(atCarol.tx) }
        note("twin_to_carol_refused", twin.exceptionOrNull()?.message ?: "ACCEPTED ${twin.getOrNull()}")
        note("twin_to_carol_signature", atCarol.payment.signature)
        assertTrue("twin voucher must be refused", twin.exceptionOrNull() is RpcError)
        delay(15_000)
        assertTrue(rpc.signatureStatus(atCarol.payment.signature) is JsonNull)
        assertTrue(rpc.account(Programs.associatedTokenAddress(carol, mint)).jsonObject["value"] is JsonNull)
        assertEquals(7_000_000L, rpc.tokenUnits(elena, mint))

        assertNotEquals(Base58.encode(values[0]), Base58.encode(rpc.nonceValue(SolanaKey.of(notes[0].nonce))!!))
        assertEquals(Base58.encode(values[2]), Base58.encode(rpc.nonceValue(SolanaKey.of(notes[2].nonce))!!))
        val tokenLeft = rpc.tokenUnits(pocket.publicKey, mint)
        val solLeft = rpc.lamports(pocket.publicKey)
        note("pocket_token_left", tokenLeft.toString())
        note("pocket_lamports_left", solLeft.toString())
        assertEquals(pocketTokenBefore + pocketUnits - 7_000_000L, tokenLeft)
        // Перша купюра створила рахунок Олени (рента — з кишені), друга знайшла його готовим.
        assertEquals(pocketSol - accountRent - 2 * CashPlan.NOTE_FEE, solLeft)
        write()
    }

    private suspend fun createMint(owner: Ed25519Keypair): SolanaKey {
        val mintKp = Ed25519Keypair(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        val mint = mintKp.publicKey
        val create = Instruction(
            Programs.SYSTEM,
            listOf(AccountMeta(owner.publicKey, signer = true, writable = true), AccountMeta(mint, signer = true, writable = true)),
            SolanaTx.u32(0) + SolanaTx.u64(rpc.rentExempt(82)) + SolanaTx.u64(82) + Programs.TOKEN.bytes,
        )
        val init = Instruction(
            Programs.TOKEN,
            listOf(AccountMeta(mint, signer = false, writable = true)),
            byteArrayOf(20, coin.decimals.toByte()) + owner.publicKey.bytes + byteArrayOf(0),
        )
        val mintTo = Instruction(
            Programs.TOKEN,
            listOf(
                AccountMeta(mint, signer = false, writable = true),
                AccountMeta(Programs.associatedTokenAddress(owner.publicKey, mint), signer = false, writable = true),
                AccountMeta(owner.publicKey, signer = true, writable = false),
            ),
            byteArrayOf(7) + SolanaTx.u64(100_000_000L),
        )
        val msg = SolanaTx.message(
            owner.publicKey, rpc.latestBlockhash(),
            listOf(create, init, Programs.createAssociatedIdempotent(owner.publicKey, owner.publicKey, mint), mintTo),
        )
        val sig = rpc.sendRaw(SolanaTx.sign(msg, listOf(owner, mintKp)))
        tx("create_mint", sig)
        confirmed(sig)
        return mint
    }

    private fun keys(): Pair<Ed25519Keypair, Ed25519Keypair> {
        val f = File(System.getProperty("user.home"), ".phantom-devnet/voucher-proof.json")
        check(f.exists()) { "no devnet proof key — run DevnetCashProofTest.proofAddress first" }
        val hex = Regex("\"seed_hex\":\"([0-9a-f]{128})\"").find(f.readText())!!.groupValues[1]
        val seed = ByteArray(64) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return (Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN)) to Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH))).also { seed.fill(0) }
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

    private fun write() = File("build/devnet-spl-proof.txt").writeText(proof.joinToString("\n") + "\n")
}
