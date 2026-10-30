package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Чи тримає гаманець Seeker Genesis Token. За процедурою Solana Mobile: акаунти Token-2022
 * з ненульовим балансом, у мінта яких metadataPointer і tokenGroupMember вказують на SGT.
 * SGT існує лише в mainnet — перевірка завжди йде туди, хоч би яку мережу обрано для переказів.
 */
object SeekerGenesis {
    const val SGT_METADATA = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"
    const val SGT_GROUP = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"

    sealed interface Verdict {
        data class Holder(val mint: String) : Verdict
        data object NotHolder : Verdict
    }

    /** Мінти з ненульовим балансом із відповіді getTokenAccountsByOwner (jsonParsed). */
    fun heldMints(tokenAccounts: JsonElement): List<String> {
        val list = (tokenAccounts as? JsonObject)?.get("value") as? JsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val info = entry.path("account", "data", "parsed", "info") as? JsonObject ?: return@mapNotNull null
            val amount = info.path("tokenAmount", "amount")?.jsonPrimitive?.content?.toBigIntegerOrNull()
            if (amount == null || amount.signum() <= 0) return@mapNotNull null
            info["mint"]?.jsonPrimitive?.content
        }.distinct()
    }

    /** Перший мінт із getMultipleAccounts, що справді є членом групи SGT. */
    fun genesisMint(mints: List<String>, multipleAccounts: JsonElement): String? {
        val values = (multipleAccounts as? JsonObject)?.get("value") as? JsonArray ?: return null
        return mints.zip(values).firstOrNull { (mint, account) -> isGenesis(mint, account) }?.first
    }

    fun isGenesis(mint: String, account: JsonElement): Boolean {
        val acc = account as? JsonObject ?: return false
        if (acc["owner"]?.jsonPrimitive?.content != Programs.TOKEN_2022.base58) return false
        val info = acc.path("data", "parsed", "info") as? JsonObject ?: return false
        val ext = (info["extensions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun state(name: String) = ext.firstOrNull { it["extension"]?.jsonPrimitive?.content == name }?.get("state") as? JsonObject
        val pointer = state("metadataPointer")?.get("metadataAddress")?.jsonPrimitive?.content
        val member = state("tokenGroupMember") ?: return false
        return pointer == SGT_METADATA &&
            member["group"]?.jsonPrimitive?.content == SGT_GROUP &&
            member["mint"]?.jsonPrimitive?.content == mint
    }

    suspend fun check(owner: SolanaKey, mainnet: SolanaRpc): Verdict {
        val mints = heldMints(mainnet.tokenAccounts(owner, Programs.TOKEN_2022))
        if (mints.isEmpty()) return Verdict.NotHolder
        for (chunk in mints.chunked(100)) {
            genesisMint(chunk, mainnet.accounts(chunk))?.let { return Verdict.Holder(it) }
        }
        return Verdict.NotHolder
    }
}

internal fun JsonElement.path(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k)
    return cur
}
