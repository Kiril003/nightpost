package local.phantom.companion.core.solana

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Відбиток файла в справжньому devnet, лише з -Dphantom.devnet=1:
 * bash scripts/gate.sh :core-solana:testDebugUnitTest --tests '*DevnetSealProofTest*' -Dphantom.devnet=1
 * Транзакцію складає той самий [SealFrame.message], що й `SolanaLayer.seal`; вердикт — той самий
 * [SealCheck.judge], що й `SolanaLayer.watchSeal`, над відповіддю `getTransaction` живого вузла.
 * Ключ — той самий devnet-ключ доказу, що в [DevnetCashProofTest] (~/.phantom-devnet/voucher-proof.json).
 * У вивід ідуть тільки адреси, хеші й підписи.
 */
class DevnetSealProofTest {

    private val on = System.getProperty("phantom.devnet") == "1"
    private val proof = mutableListOf<String>()
    private val rpc by lazy { SolanaRpc(Cluster.DEVNET.publicRpc) }

    @Test
    fun fileFingerprintIsAnchoredByMemoAndOnlyThatFingerprintMatches() = runBlocking {
        assumeTrue(on)
        val owner = ownerKey()
        note("owner", owner.publicKey.base58)
        val before = rpc.lamports(owner.publicKey)
        note("owner_lamports_before", before.toString())
        assertTrue("devnet key needs SOL for the fee: ${owner.publicKey.base58}", before >= 10_000L)

        // «Файл» — свіжі байти, щоб кожен прогін закріплював новий відбиток, а не повтор старого.
        val file = ByteArray(4096).also(java.security.SecureRandom()::nextBytes)
        val hex = SealFrame.sha256Hex(file)
        note("sha256", hex)
        note("memo", SealFrame.memo(hex))

        val msg = SealFrame.message(owner.publicKey, rpc.latestBlockhash(), hex)
        val sig = rpc.sendRaw(SolanaTx.sign(msg, listOf(owner)))
        note("seal", sig)
        note("seal.explorer", "https://explorer.solana.com/tx/$sig?cluster=devnet")
        confirmed(sig)

        val tx = waitFor { rpc.transaction(sig).takeIf { it is JsonObject } }
        val verdict = SealCheck.judge(hex, tx)
        note("verdict", verdict.toString())
        assertTrue("expected Anchored, got $verdict", verdict is SealVerdict.Anchored)
        assertNotNull((verdict as SealVerdict.Anchored).atSeconds)

        // Та сама транзакція не засвідчує інший файл.
        val other = SealFrame.sha256Hex(file + byteArrayOf(0))
        val wrong = SealCheck.judge(other, tx)
        note("verdict_other_hash", wrong.toString())
        assertEquals(SealVerdict.Mismatch, wrong)

        // Рядок, яким відбиток їде в розмову, відновлює ті самі хеш і підпис.
        val wire = SealFrame(Cluster.DEVNET, hex, sig).encode()
        note("wire", wire)
        assertEquals(SealFrame(Cluster.DEVNET, hex, sig), SealFrame.decode(String(wire.toByteArray(Charsets.UTF_8), Charsets.UTF_8)))

        val after = rpc.lamports(owner.publicKey)
        note("owner_lamports_after", after.toString())
        write()
    }

    private fun ownerKey(): Ed25519Keypair {
        val f = File(System.getProperty("user.home"), ".phantom-devnet/voucher-proof.json")
        assumeTrue("no devnet proof key at $f — run DevnetCashProofTest.proofAddress first", f.exists())
        val hex = Regex("\"seed_hex\":\"([0-9a-f]{128})\"").find(f.readText())!!.groupValues[1]
        val seed = ByteArray(64) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN)).also { seed.fill(0) }
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

    private fun note(k: String, v: String) {
        proof += "$k=$v"
        println("DEVNET-SEAL $k=$v")
    }

    private fun write() = File("build/devnet-seal-proof.txt").writeText(proof.joinToString("\n") + "\n")
}
