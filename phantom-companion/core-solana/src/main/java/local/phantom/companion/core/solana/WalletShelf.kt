package local.phantom.companion.core.solana

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Що людина сама поклала в гаманець і як розклала картки — окремо для кожної мережі:
 * токени за адресою, адреси «лише дивитись», сховані картки й порядок.
 */
@Serializable
data class Shelf(
    val mints: List<String> = emptyList(),
    val watched: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val order: List<String> = emptyList(),
) {
    fun withMint(mint: SolanaKey) = if (mint.base58 in mints) this else copy(mints = mints + mint.base58)
    fun withWatched(address: SolanaKey) = if (address.base58 in watched) this else copy(watched = watched + address.base58)
    fun hide(key: String) = copy(hidden = (hidden + key).distinct())
    fun show(key: String) = copy(hidden = hidden - key)

    /** Покласти картку на верх стосу серед [keys]; порядок зберігається з полицею. */
    fun front(key: String, keys: List<String>): Shelf {
        val at = keys.indexOf(key)
        if (at <= 0) return this
        return copy(order = listOf(key) + keys.filter { it != key })
    }

    /** Зсунути картку на [delta] місць серед [keys] — тих, що зараз видно, у їхньому порядку. */
    fun move(key: String, delta: Int, keys: List<String>): Shelf {
        val at = keys.indexOf(key)
        if (at < 0) return this
        val to = (at + delta).coerceIn(0, keys.lastIndex)
        if (to == at) return this
        val next = keys.toMutableList().apply { removeAt(at); add(to, key) }
        return copy(order = next)
    }
}

/** Токен на рахунку, якого немає серед відомих монет: адреса монети, сума й скільки знаків. */
data class HeldToken(val mint: SolanaKey, val units: Long, val decimals: Int)

object TokenHoldings {

    /** Відповідь `getTokenAccountsByOwner` (jsonParsed) → суми за монетою; рахунки однієї монети складаються. */
    fun parse(resp: JsonElement): List<HeldToken> {
        val rows = (resp as? JsonObject)?.get("value")?.jsonArray ?: return emptyList()
        val sums = LinkedHashMap<String, HeldToken>()
        for (row in rows) {
            val info = row.path("account", "data", "parsed", "info") as? JsonObject ?: continue
            val mint = info["mint"]?.jsonPrimitive?.content?.let(SolanaKey::parse) ?: continue
            val amount = info.path("tokenAmount", "amount")?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val decimals = info.path("tokenAmount", "decimals")?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val had = sums[mint.base58]
            sums[mint.base58] = HeldToken(mint, (had?.units ?: 0L) + amount, decimals)
        }
        return sums.values.toList()
    }

    /** Монети, що мають власну картку ([Coin]), тут не дублюються. */
    fun foreign(held: List<HeldToken>, cluster: Cluster): List<HeldToken> {
        val known = Coin.on(cluster).mapNotNull { it.mint(cluster)?.base58 }.toSet()
        return held.filter { it.mint.base58 !in known }
    }
}

/** Сума токена з довільною кількістю знаків: «12.5», без хвоста нулів. */
internal fun tokenAmount(units: Long, decimals: Int): String =
    if (units == 0L) "0" else java.math.BigDecimal.valueOf(units).movePointLeft(decimals).stripTrailingZeros().toPlainString()
