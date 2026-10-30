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
 * Справжній devnet, лише з -Dphantom.devnet=1: гаманець, у якого 0 SOL, пересилає USDC,
 * а комісію й ренту ATA платить спонсор — локально запущений сервер сайту.
 *
 * 1) Сервер (ключ спонсора лише devnet, поза репо, права 600):
 *    cd platform-site/server && LICENSE_DB=/tmp/sponsor-proof.db ADMIN_TOKEN=x \
 *      SOLANA_SPONSOR_CLUSTER=devnet SOLANA_SPONSOR_KEY="$(cat ~/.phantom-devnet/sponsor-devnet.json)" \
 *      .venv/bin/uvicorn main:app --port 8787
 * 2) bash scripts/gate.sh :core-solana:testDebugUnitTest --tests '*DevnetSponsorProofTest*' \
 *      -Dphantom.devnet=1 -Dphantom.sponsorUrl=http://127.0.0.1:8787
 *
 * Потрібно заздалегідь: ≥0.05 devnet SOL на адресі спонсора і devnet USDC на адресі
 * відправника (faucet.circle.com) — адреси друкує `proofAddresses`. SOL відправнику не дається ніколи.
 */
class DevnetSponsorProofTest {

    private val on = System.getProperty("phantom.devnet") == "1"
    private val sponsorUrl = System.getProperty("phantom.sponsorUrl") ?: "http://127.0.0.1:8787"
    private val proof = mutableListOf<String>()
    private val rpc by lazy { SolanaRpc(Cluster.DEVNET.publicRpc) }
    private val mint = Coin.USDC.mint(Cluster.DEVNET)!!

    @Test
    fun walletWithZeroSolSendsUsdcThroughTheSponsor() = runBlocking {
        assumeTrue(on)
        val (sender, recipient) = keys()
        note("sender", sender.publicKey.base58)
        note("recipient", recipient.base58)

        val client = SponsorClient(sponsorUrl)
        val info = client.info()
        assertNotNull("sponsor at $sponsorUrl must answer GET /api/solana/sponsor", info)
        info!!
        assertEquals(Cluster.DEVNET, info.cluster)
        note("sponsor", info.feePayer.base58)
        note("sponsor_lamports_before", rpc.lamports(info.feePayer).toString())

        assertEquals("the sender must hold no SOL at all", 0L, rpc.lamports(sender.publicKey))
        val units = 10_000L
        val before = rpc.tokenUnits(sender.publicKey, mint)
        note("sender_usdc_before", before.toString())
        check(before >= units) { "fund ${sender.publicKey.base58} with devnet USDC (faucet.circle.com) and rerun" }
        val recipientBefore = rpc.tokenUnits(recipient, mint)

        val message = Transfers.build(sender.publicKey, recipient, Coin.USDC, Cluster.DEVNET, units, rpc.latestBlockhash(), memo = "PHANTOM sponsor proof", feePayer = info.feePayer)
        val result = client.submit(SolanaTx.partiallySigned(message, listOf(sender)), sender)
        note("sponsor_result", result.toString())
        assertTrue("$result", result is SponsorResult.Sent)
        val sig = (result as SponsorResult.Sent).signature
        note("transfer", sig)
        note("transfer.explorer", "https://explorer.solana.com/tx/$sig?cluster=devnet")
        confirmed(sig)

        assertEquals(0L, rpc.lamports(sender.publicKey))
        assertEquals(before - units, rpc.tokenUnits(sender.publicKey, mint))
        assertEquals(recipientBefore + units, rpc.tokenUnits(recipient, mint))
        note("sponsor_lamports_after", rpc.lamports(info.feePayer).toString())
        write()
    }

    @Test
    fun proofAddresses() {
        assumeTrue(on)
        val (sender, recipient) = keys()
        note("sender", sender.publicKey.base58)
        note("recipient", recipient.base58)
        sponsorAddress()?.let { note("sponsor", it) }
        write()
    }

    private fun sponsorAddress(): String? {
        val f = File(System.getProperty("user.home"), ".phantom-devnet/sponsor-devnet.json")
        if (!f.exists()) return null
        val bytes = Regex("\\d+").findAll(f.readText()).map { it.value.toInt().toByte() }.toList().toByteArray()
        return if (bytes.size == 64) Base58.encode(bytes.copyOfRange(32, 64)) else null
    }

    /** Відправник і отримувач — лише devnet, поза репо; отримувач сталий, щоб ATA спонсор створив раз. */
    private fun keys(): Pair<Ed25519Keypair, SolanaKey> {
        val f = File(System.getProperty("user.home"), ".phantom-devnet/sponsor-proof.json")
        if (!f.exists()) {
            f.parentFile.mkdirs()
            val hex = ByteArray(64).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            f.writeText("""{"cluster":"devnet","purpose":"phantom sponsor proof, devnet only","seed_hex":"$hex"}""")
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        val hex = Regex("\"seed_hex\":\"([0-9a-f]{128})\"").find(f.readText())!!.groupValues[1]
        val seed = ByteArray(64) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return (Ed25519Keypair(seed.copyOfRange(0, 32)) to Ed25519Keypair(seed.copyOfRange(32, 64)).publicKey).also { seed.fill(0) }
    }

    private suspend fun confirmed(sig: String) {
        repeat(40) {
            val s = runCatching { rpc.signatureStatus(sig) as? JsonObject }.getOrNull()
            if (s?.get("confirmationStatus")?.jsonPrimitive?.content in setOf("confirmed", "finalized")) {
                assertTrue("$sig failed: ${s!!["err"]}", s["err"] == null || s["err"] is JsonNull)
                return
            }
            delay(1_500)
        }
        error("timed out waiting for $sig")
    }

    private fun note(k: String, v: String) {
        proof += "$k=$v"
        println("DEVNET-PROOF $k=$v")
    }

    private fun write() = File("build/devnet-sponsor-proof.txt").writeText(proof.joinToString("\n") + "\n")
}
