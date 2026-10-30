package local.phantom.companion.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Заявка на «першу оплачену частку» від моменту, коли веб сказав «ось мій
 * підпис», до моменту, коли людина сама її підтвердила.
 *
 * Чому не одразу. Сервер доводить оплату двома підписами: пристрою й самого
 * гаманця. Підпис гаманця — це відбиток пальця, а веб просить бали САМ, коли
 * малює картку оплати (і повторює це). Вікно відбитка, що вискакує посеред
 * читання розмови, — не згода, а настирливість; до того ж заява звʼязує адресу
 * гаманця з ключем пристрою на сервері, а це рішення власника: «лише коли
 * людина платить» і лише з її відома. Тому міст лише ПРИЙМАЄ заявку (веб
 * отримує чесне «ще не зараховано»), а відбиток просить Профіль, коли людина
 * сама натисне «Зарахувати».
 *
 * Заявка живе в памʼяті: веб повторює її сам, доки не отримає остаточної
 * відповіді, тож після перезапуску вона повертається. Зараховане запамʼятовується
 * ([Memory]) — щоб не питати відбитка вдруге за ту саму транзакцію.
 */
class PointsClaims(
    private val api: PointsApi,
    private val memory: Memory = Memory.None,
) {
    /** Що пережило перезапуск: підписи транзакцій, за які бали вже зараховано. */
    interface Memory {
        fun counted(): Set<String>
        fun remember(tx: String)

        data object None : Memory {
            override fun counted(): Set<String> = emptySet()
            override fun remember(tx: String) = Unit
        }
    }

    data class Claim(val cluster: String, val tx: String, val address: String, val ref: String)

    /** Що міст відповідає вебу на його заявку. */
    sealed interface Offered {
        /** Прийнято, чекає людину: веб повторить пізніше. */
        data object Waiting : Offered

        /** Ця транзакція вже зарахована — нових балів немає. */
        data object AlreadyCounted : Offered

        /**
         * Остаточна відмова сервера — повторювати марно. [reason] — код причини
         * (`wallet_taken`, `not_your_payment`…), за яким веб розрізняє «ні».
         */
        data class Refused(val uk: String?, val en: String?, val reason: String? = null) : Offered
    }

    /** Чим скінчилося «Зарахувати». */
    sealed interface Settled {
        data object NothingToSettle : Settled

        /** Гаманець, з якого платили, уже не активний — заявку знято. */
        data object WalletChanged : Settled

        /** Зараховано; [points] — скільки додалося цим викликом (0, якщо вже було). */
        data class Counted(val points: Int) : Settled

        /** Мережа ще не підтвердила транзакцію — спробувати за хвилину; заявка лишається. */
        data object NotConfirmedYet : Settled

        /** Остаточна відмова: заявку знято. [reason] — код причини від сервера. */
        data class Refused(val uk: String?, val en: String?, val reason: String? = null) : Settled

        /** Тимчасова: немає мережі / ключа / сервера. Заявка лишається. */
        data class Failed(val answer: VenueClient.Answer) : Settled
    }

    private val _pending = MutableStateFlow<Claim?>(null)

    /** Заявка, що чекає на «Зарахувати». Одна: нова заміняє стару (веб повторює останню). */
    val pending: StateFlow<Claim?> = _pending.asStateFlow()

    private val refused = java.util.concurrent.ConcurrentHashMap<String, Offered.Refused>()

    fun offer(claim: Claim): Offered {
        if (claim.tx in memory.counted()) return Offered.AlreadyCounted
        refused[claim.tx]?.let { return it }
        _pending.value = claim
        return Offered.Waiting
    }

    /**
     * Людина натиснула «Зарахувати». [activeAddress] — адреса гаманця, який зараз
     * активний: підписати можна лише його ключем, а заявка могла прийти від іншого.
     * [sign] дає лише виклик, що вже дістав згоду (див. `SolanaLayer.signStatement`).
     */
    suspend fun settle(activeAddress: String?, sign: (ByteArray) -> ByteArray): Settled {
        val claim = _pending.value ?: return Settled.NothingToSettle
        if (activeAddress != claim.address) {
            _pending.compareAndSet(claim, null)
            return Settled.WalletChanged
        }
        return when (val a = api.settled(claim.cluster, claim.tx, claim.address, claim.ref, sign)) {
            is VenueClient.Answer.Ok -> {
                val body = Json.parseToJsonElement(a.json).let { it as? JsonObject }
                if ((body?.get("status") as? JsonPrimitive)?.contentOrNull == "pending") {
                    Settled.NotConfirmedYet
                } else {
                    memory.remember(claim.tx)
                    _pending.compareAndSet(claim, null)
                    Settled.Counted(awardedPoints(body))
                }
            }
            is VenueClient.Answer.Refused ->
                if (a.status == 422) {
                    // Сервер прочитав заяву й сказав «ні» (не ваш гаманець, не цей рахунок, стара
                    // транзакція…): повтор нічого не змінить, заявку знято.
                    refused[claim.tx] = Offered.Refused(a.uk, a.en, a.reason)
                    _pending.compareAndSet(claim, null)
                    Settled.Refused(a.uk, a.en, a.reason)
                } else {
                    // 401 (годинник розійшовся), 429, 5xx: може минути — заявка лишається.
                    Settled.Failed(a)
                }
            VenueClient.Answer.BadInput -> {
                refused[claim.tx] = Offered.Refused(null, null)
                _pending.compareAndSet(claim, null)
                Settled.Refused(null, null)
            }
            VenueClient.Answer.Missing, VenueClient.Answer.NoKey, VenueClient.Answer.NotEnabled, VenueClient.Answer.Unreachable -> Settled.Failed(a)
        }
    }

    private fun awardedPoints(body: JsonObject?): Int =
        (body?.get("awarded") as? JsonArray).orEmpty().sumOf { event ->
            ((event as? JsonObject)?.get("points") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        }
}

/**
 * Зараховані транзакції на диску телефона (останні [KEEP]): щоб після перезапуску
 * не просити відбитка вдруге за ту саму оплату. Ні місця, ні адреси тут немає —
 * лише підписи транзакцій, які й так лежать у відкритій мережі.
 */
class PointsClaimsMemory(context: android.content.Context) : PointsClaims.Memory {
    private val prefs = context.getSharedPreferences("phantom_points", android.content.Context.MODE_PRIVATE)

    override fun counted(): Set<String> = runCatching { prefs.getString(KEY, "").orEmpty() }
        .getOrDefault("").split(' ').filter { it.isNotEmpty() }.toSet()

    @Synchronized
    override fun remember(tx: String) {
        val kept = (counted() - tx).toList().takeLast(KEEP - 1) + tx
        prefs.edit().putString(KEY, kept.joinToString(" ")).apply()
    }

    private companion object {
        const val KEY = "counted_tx"
        const val KEEP = 20
    }
}
