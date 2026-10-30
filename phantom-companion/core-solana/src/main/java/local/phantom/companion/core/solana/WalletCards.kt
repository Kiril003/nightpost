package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Одна картка гаманця — один справжній актив людини. */
sealed interface WalletCard {
    val key: String

    /** [units] null — баланс ще не прочитано або мережа не відповіла. */
    data class Money(val coin: Coin, val units: Long?, val cluster: Cluster, val address: SolanaKey) : WalletCard {
        override val key: String get() = "coin:" + coin.wire
    }

    /** [total] null — сервер балів є, але зараз не відповів. */
    data class Points(val total: Int?) : WalletCard {
        override val key: String get() = "points"
    }

    /** Інший токен на рахунку чи доданий за адресою; [units]/[decimals] null — ще не прочитано. */
    data class Token(val mint: SolanaKey, val units: Long?, val decimals: Int?, val cluster: Cluster, val address: SolanaKey) : WalletCard {
        override val key: String get() = "mint:" + mint.base58
    }

    /** Чужа адреса «лише дивитись»: баланс SOL, жодних дій з грошима. */
    data class Watch(val address: SolanaKey, val lamports: Long?, val cluster: Cluster) : WalletCard {
        override val key: String get() = "watch:" + address.base58
    }

    /** Остання картка каруселі: «+», щоб додати ще одну. */
    data object Add : WalletCard {
        override val key: String get() = "add"
    }
}

/** Бали так, як їх читає застосунок (`/points/me`); гаманець їх лише показує. */
sealed interface WalletPoints {
    data object Hidden : WalletPoints
    data object Unreachable : WalletPoints
    data class Shown(val total: Int, val events: List<PointsLine>) : WalletPoints
}

data class PointsLine(val label: String, val points: Int, val atMs: Long)

object WalletCards {

    /** Постійні картки: SOL і USDC — навіть із чесним нулем; решта монет — лише коли вони є. */
    private val ALWAYS = setOf(Coin.SOL, Coin.USDC)

    /** Гаманець читається як доларовий рахунок: першою — картка долара, тоді SOL, тоді решта. */
    private val FIRST = listOf(Coin.USDC, Coin.SOL)

    fun of(state: WalletState, points: WalletPoints?): List<WalletCard> {
        val address = state.address ?: return emptyList()
        val b = state.balances
        val cards = Coin.on(state.cluster)
            .sortedBy { FIRST.indexOf(it).takeIf { i -> i >= 0 } ?: (FIRST.size + it.ordinal) }
            .mapNotNull { coin ->
                val units = b?.get(coin)
                if (coin in ALWAYS || (units ?: 0L) > 0) WalletCard.Money(coin, units, state.cluster, address) else null
            }
        val held = state.tokens?.associateBy { it.mint.base58 }
        val tokens = (state.tokens.orEmpty().map { it.mint.base58 } + state.shelf.mints).distinct().mapNotNull { m ->
            val mint = SolanaKey.parse(m) ?: return@mapNotNull null
            val h = held?.get(m)
            // Доданий за адресою, а на рахунку його немає — чесний нуль, коли рахунок прочитано.
            WalletCard.Token(mint, h?.units ?: if (held != null) 0L else null, h?.decimals, state.cluster, address)
        }
        val pts = when (points) {
            is WalletPoints.Shown -> listOf(WalletCard.Points(points.total))
            WalletPoints.Unreachable -> listOf(WalletCard.Points(null))
            WalletPoints.Hidden, null -> emptyList()
        }
        val watched = state.shelf.watched.mapNotNull { a ->
            SolanaKey.parse(a)?.let { WalletCard.Watch(it, state.watchedLamports[a], state.cluster) }
        }
        val all = (cards + tokens + pts + watched).filter { it.key !in state.shelf.hidden }
        val order = state.shelf.order
        val sorted = all.sortedBy { c -> order.indexOf(c.key).takeIf { it >= 0 } ?: (order.size + all.indexOf(c)) }
        return sorted + WalletCard.Add
    }
}

/** Рядок історії картки: [units] зі знаком — плюс прийшло, мінус пішло. */
data class ActivityLine(val signature: String, val units: Long, val atSec: Long?, val counterparty: SolanaKey?, val failed: Boolean = false)

/** Історія монети з мережі: що справді прийшло й пішло, а не що записав месенджер. */
object WalletFeed {

    /** Відповідь getTransaction (jsonParsed) → рядок; null — ця транзакція цю монету в людини не рухала. */
    fun lineOf(tx: JsonElement, signature: String, owner: SolanaKey, coin: Coin, cluster: Cluster): ActivityLine? {
        val root = tx as? JsonObject ?: return null
        val meta = root["meta"] as? JsonObject ?: return null
        val at = (root["blockTime"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
        if (meta["err"].let { it != null && it !is JsonNull }) {
            // Не пройшло: гроші не рушили, тож показуємо лише власну спробу віддати — з тією сумою, що мала піти.
            val tried = if (coin.native) native(root, meta, signature, owner, at) else tokenTry(root, meta, signature, owner, coin.mint(cluster)?.base58 ?: return null, at)
            return tried?.takeIf { it.units < 0 }?.copy(failed = true)
        }
        return if (coin.native) native(root, meta, signature, owner, at) else token(meta, signature, owner, coin.mint(cluster)?.base58 ?: return null, at)
    }

    private fun tokenTry(root: JsonObject, meta: JsonObject, signature: String, owner: SolanaKey, mint: String, at: Long?): ActivityLine? {
        val keys = (root.path("transaction", "message", "accountKeys") as? JsonArray).orEmpty().map {
            (it as? JsonObject)?.get("pubkey")?.jsonPrimitive?.content ?: (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        val held = ((meta["preTokenBalances"] as? JsonArray).orEmpty() + (meta["postTokenBalances"] as? JsonArray).orEmpty()).mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            val account = o["accountIndex"]?.jsonPrimitive?.longOrNull?.toInt()?.let(keys::getOrNull) ?: return@mapNotNull null
            account to (o["owner"]?.jsonPrimitive?.content to o["mint"]?.jsonPrimitive?.content)
        }.toMap()
        val inner = (meta["innerInstructions"] as? JsonArray).orEmpty().flatMap { ((it as? JsonObject)?.get("instructions") as? JsonArray).orEmpty() }
        val outer = (root.path("transaction", "message", "instructions") as? JsonArray).orEmpty()
        var units = 0L
        var other: String? = null
        for (ix in outer + inner) {
            val o = ix as? JsonObject ?: continue
            if (o["program"]?.jsonPrimitive?.content != "spl-token") continue
            val parsed = o["parsed"] as? JsonObject ?: continue
            val type = parsed["type"]?.jsonPrimitive?.content
            if (type != "transfer" && type != "transferChecked") continue
            val info = parsed["info"] as? JsonObject ?: continue
            if (info["authority"]?.jsonPrimitive?.content != owner.base58) continue
            val source = info["source"]?.jsonPrimitive?.content
            if ((info["mint"]?.jsonPrimitive?.content ?: held[source]?.second) != mint) continue
            val amount = (info.path("tokenAmount", "amount") ?: info["amount"])?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            units -= amount
            other = held[info["destination"]?.jsonPrimitive?.content]?.first
        }
        if (units == 0L) return null
        return ActivityLine(signature, units, at, other?.let(SolanaKey::parse))
    }

    /** SOL — лише перекази системної програми: рента й комісія токенових переказів — не «переказ SOL». */
    private fun native(root: JsonObject, meta: JsonObject, signature: String, owner: SolanaKey, at: Long?): ActivityLine? {
        val inner = (meta["innerInstructions"] as? JsonArray).orEmpty().flatMap { ((it as? JsonObject)?.get("instructions") as? JsonArray).orEmpty() }
        val outer = (root.path("transaction", "message", "instructions") as? JsonArray).orEmpty()
        var units = 0L
        var other: String? = null
        var moved = false
        for (ix in outer + inner) {
            val o = ix as? JsonObject ?: continue
            if (o["program"]?.jsonPrimitive?.content != "system") continue
            val parsed = o["parsed"] as? JsonObject ?: continue
            if (parsed["type"]?.jsonPrimitive?.content != "transfer") continue
            val info = parsed["info"] as? JsonObject ?: continue
            val from = info["source"]?.jsonPrimitive?.content
            val to = info["destination"]?.jsonPrimitive?.content
            val lamports = info["lamports"]?.jsonPrimitive?.longOrNull ?: continue
            if (from == to) continue
            when (owner.base58) {
                to -> { units += lamports; other = from; moved = true }
                from -> { units -= lamports; other = to; moved = true }
            }
        }
        if (!moved || units == 0L) return null
        return ActivityLine(signature, units, at, other?.let(SolanaKey::parse))
    }

    private fun token(meta: JsonObject, signature: String, owner: SolanaKey, mint: String, at: Long?): ActivityLine? {
        fun byOwner(key: String): Map<String, Long> = (meta[key] as? JsonArray).orEmpty().mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            if (o["mint"]?.jsonPrimitive?.content != mint) return@mapNotNull null
            val who = o["owner"]?.jsonPrimitive?.content ?: return@mapNotNull null
            who to (o.path("uiTokenAmount", "amount")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val pre = byOwner("preTokenBalances")
        val post = byOwner("postTokenBalances")
        val delta = (pre.keys + post.keys).associateWith { (post[it] ?: 0L) - (pre[it] ?: 0L) }
        val mine = delta[owner.base58] ?: 0L
        if (mine == 0L) return null
        val other = delta.entries.firstOrNull { it.key != owner.base58 && it.value != 0L && (it.value > 0) != (mine > 0) }?.key
        return ActivityLine(signature, mine, at, other?.let(SolanaKey::parse))
    }
}


