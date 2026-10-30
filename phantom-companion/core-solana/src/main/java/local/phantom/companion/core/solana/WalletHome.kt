package local.phantom.companion.core.solana

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Що каже поле адреси, поки людина друкує: картка-превʼю заповнюється одразу. */
sealed interface AddressCheck {
    data object Empty : AddressCheck
    data class BadChar(val char: Char, val at: Int) : AddressCheck
    data object TooShort : AddressCheck
    data object TooLong : AddressCheck
    data object NotKey : AddressCheck
    data class Ok(val key: SolanaKey) : AddressCheck

    companion object {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        fun of(text: String): AddressCheck {
            val t = text.trim()
            if (t.isEmpty()) return Empty
            t.forEachIndexed { i, c -> if (c !in ALPHABET) return BadChar(c, i) }
            if (t.length < 32) return TooShort
            if (t.length > 44) return TooLong
            return SolanaKey.parse(t)?.let(::Ok) ?: NotKey
        }
    }
}

/** Око на картці: ховає суми скрізь у гаманці й памʼятає вибір. */
class WalletVeil(private val prefs: SharedPreferences) {
    var on: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }

    companion object {
        private const val KEY = "veil"
        fun mask(text: String, on: Boolean): String = if (on) "••••" else text
    }
}

/** Стос: передня картка й до двох, що виглядають ззаду. */
sealed interface WalletStack {
    data object Empty : WalletStack
    data class Cards(val front: WalletCard, val peeks: List<WalletCard>) : WalletStack

    companion object {
        fun of(cards: List<WalletCard>): WalletStack {
            val real = cards.filter { it !is WalletCard.Add }
            if (real.isEmpty()) return Empty
            return Cards(real.first(), real.drop(1).take(2))
        }
    }
}

/** Рядок комісії в переказі. */
sealed interface FeeLine {
    data object Zero : FeeLine
    data class Paid(val lamports: Long) : FeeLine
    data object Unknown : FeeLine

    companion object {
        fun of(route: FeeRoute.Route, feeLamports: Long?): FeeLine = when {
            route is FeeRoute.Route.Sponsored -> Zero
            feeLamports != null -> Paid(feeLamports)
            else -> Unknown
        }
    }
}

/** Рента порожніх токенових рахунків, яку можна повернути. */
data class Reclaim(val lamports: Long, val accounts: Int) {
    companion object {
        fun of(responses: List<JsonElement>): Reclaim? {
            var lamports = 0L
            var count = 0
            responses.forEach { r ->
                ((r as? JsonObject)?.get("value") as? JsonArray)?.forEach { row ->
                    val acct = (row as? JsonObject)?.get("account") as? JsonObject ?: return@forEach
                    val lam = (acct["lamports"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val amount = acct.path("data", "parsed", "info", "tokenAmount", "amount")
                    if (amount == "0") { lamports += lam; count++ }
                }
            }
            return if (count == 0) null else Reclaim(lamports, count)
        }

        private fun JsonObject.path(vararg keys: String): String? {
            var cur: JsonElement = this
            keys.forEach { k -> cur = (cur as? JsonObject)?.get(k) ?: return null }
            return (cur as? JsonPrimitive)?.content
        }
    }
}

/** Останнє вдале читання балансів: мережа впала — показуємо його з часом, а не нуль. */
data class LastBalances(val units: Map<Coin, Long>, val atMs: Long) {
    companion object {
        fun next(prev: LastBalances?, fresh: Map<Coin, Long>?, nowMs: Long): LastBalances? =
            if (fresh != null) LastBalances(fresh, nowMs) else prev

        fun key(cluster: Cluster, owner: SolanaKey) = "last_" + cluster.wire + "_" + owner.base58

        fun decode(text: String?): LastBalances? {
            val parts = text?.split('|')?.takeIf { it.size == 2 } ?: return null
            val at = parts[0].toLongOrNull() ?: return null
            val units = parts[1].split(',').filter { it.isNotEmpty() }.associate { pair ->
                val kv = pair.split('=').takeIf { it.size == 2 } ?: return null
                (Coin.of(kv[0]) ?: return null) to (kv[1].toLongOrNull() ?: return null)
            }
            return LastBalances(units, at)
        }
    }

    fun encode(): String = atMs.toString() + "|" + units.entries.joinToString(",") { it.key.wire + "=" + it.value }
}

/** Рядок історії гаманця з людиною, якщо її адресу ми знаємо. */
data class MeLine(val coin: Coin, val line: ActivityLine, val peerId: String?, val name: String?)

object WalletMe {
    fun lines(byCoin: Map<Coin, List<ActivityLine>?>, contacts: List<SolanaContact>, limit: Int = 20): List<MeLine>? {
        if (byCoin.values.all { it == null }) return null
        val who = contacts.filter { it.address != null }.associateBy { it.address!! }
        return byCoin.flatMap { (coin, lines) -> lines.orEmpty().map { coin to it } }
            .sortedWith(compareByDescending<Pair<Coin, ActivityLine>> { it.second.atSec ?: Long.MIN_VALUE })
            .take(limit)
            .map { (coin, line) -> val c = line.counterparty?.let(who::get); MeLine(coin, line, c?.id, c?.name) }
    }
}

/** Тіло `GET /messenger/solana/me` для вебу: ті самі дані, що й рідний гаманець. */
object WalletMeReply {
    fun of(state: WalletState, activity: List<MeLine>?): JSONObject {
        val shown = state.balances ?: state.lastBalances?.units
        return JSONObject()
            .put("address", state.address?.base58 ?: JSONObject.NULL)
            .put("cluster", state.cluster.wire)
            .put("kind", state.kind.name.lowercase())
            .put("sponsored", state.sponsor?.cluster == state.cluster)
            .put("balances", shown?.let { m -> JSONObject().apply { m.forEach { (c, u) -> put(c.wire, u) } } } ?: JSONObject.NULL)
            .put("balances_at", state.lastBalances?.atMs ?: JSONObject.NULL)
            .put("stale", state.balances == null && state.lastBalances != null)
            .put("tokens", state.tokens?.let { t -> JSONArray().apply { t.forEach { put(JSONObject().put("mint", it.mint.base58).put("units", it.units).put("decimals", it.decimals)) } } } ?: JSONObject.NULL)
            .put("reclaim", state.reclaim?.takeIf { it.lamports > 0 }?.let { JSONObject().put("lamports", it.lamports).put("accounts", it.accounts) } ?: JSONObject.NULL)
            .put("activity", activity?.let { a -> JSONArray().apply { a.forEach { put(line(it)) } } } ?: JSONObject.NULL)
    }

    private fun line(m: MeLine): JSONObject = JSONObject()
        .put("signature", m.line.signature)
        .put("coin", m.coin.wire)
        .put("units", m.line.units)
        .put("at", m.line.atSec ?: JSONObject.NULL)
        .put("counterparty", m.line.counterparty?.base58 ?: JSONObject.NULL)
        .put("peer_id", m.peerId ?: JSONObject.NULL)
        .put("name", m.name ?: JSONObject.NULL)
        .put("failed", m.line.failed)
}
