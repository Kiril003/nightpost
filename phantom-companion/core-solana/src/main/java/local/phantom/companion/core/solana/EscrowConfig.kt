package local.phantom.companion.core.solana

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class EscrowConfig(
    val cluster: Cluster,
    val billProgram: SolanaKey?,
    val usdcMint: SolanaKey,
    val usdcDecimals: Int,
) {
    val escrowOn: Boolean get() = billProgram != null

    /** Escrow only on the network the wallet is on: a mainnet program never serves a devnet wallet. */
    fun programFor(wallet: Cluster): SolanaKey? = billProgram?.takeIf { cluster == wallet }

    companion object {
        private val SERVER_CLUSTERS = mapOf("devnet" to Cluster.DEVNET, "mainnet-beta" to Cluster.MAINNET)

        fun parse(text: String): EscrowConfig? = runCatching {
            val o = SolanaRpc.JSON.parseToJsonElement(text).jsonObject
            val cluster = SERVER_CLUSTERS[o["cluster"]?.jsonPrimitive?.content] ?: return null
            val mint = o["usdcMint"]?.jsonPrimitive?.content?.let(SolanaKey::parse) ?: return null
            if (mint != Coin.USDC.mint(cluster)) return null
            val decimals = o["usdcDecimals"]?.jsonPrimitive?.intOrNull?.takeIf { it == Coin.USDC.decimals } ?: return null
            val on = o["escrow"]?.jsonPrimitive?.booleanOrNull == true
            val named = o["programId"]?.takeIf { on }?.jsonPrimitive?.contentOrNull?.let(SolanaKey::parse)
            val program = named?.takeUnless { cluster == Cluster.MAINNET && it == BillProgram.DEVNET_ID }
            EscrowConfig(cluster, program, mint, decimals)
        }.getOrNull()
    }
}

class EscrowConfigClient(private val baseUrl: String, private val http: HttpCall = PlainHttp) {

    suspend fun fetch(): EscrowConfig? {
        if (baseUrl.isBlank()) return null
        val (code, text) = runCatching { http.request(baseUrl.trimEnd('/') + "/api/solana/config", null) }
            .getOrElse { return null }
        return if (code == 200) EscrowConfig.parse(text) else null
    }
}
