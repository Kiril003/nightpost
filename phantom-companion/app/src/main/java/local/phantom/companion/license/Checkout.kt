package local.phantom.companion.license

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Купівля ліцензії всередині застосунку.
 *
 * Є лише в store-збірці: Google Play дозволяє продавати цифрове в застосунку
 * з Play тільки через Google Play Billing, а польова збірка, що їде повз Play,
 * продає ключ на сайті й такої кнопки не має взагалі
 * (`CheckoutFactory` у src/field повертає null). Тому весь інтерфейс тут, у
 * main, і жодного класу Play Billing — їх бачить лише src/store.
 */
interface Checkout {

    /** Що можна купити, з цінами від Play у валюті людини. Порожньо — не завантажили. */
    val offers: StateFlow<List<CheckoutOffer>>

    val status: StateFlow<CheckoutStatus>

    /** Підʼєднатись до Play, підтягнути ціни й довести до кінця незавершені покупки. */
    fun start()

    fun buy(activity: Activity, offerId: String)

    /** «Відновити покупку»: після перевстановлення ключ повертається з Play. */
    fun restore()
}

data class CheckoutOffer(
    val productId: String,
    /** "inapp" | "subs" — тим самим словом, яким його розуміє /api/play/verify. */
    val kind: String,
    val title: String,
    val price: String,
)

sealed interface CheckoutStatus {
    data object Idle : CheckoutStatus

    /** Play недоступний на цьому пристрої (немає Play Store, інший акаунт тощо). */
    data object Unavailable : CheckoutStatus

    data object Working : CheckoutStatus

    /** Оплату прийнято до виконання, але гроші ще не дійшли (готівка, переказ). */
    data object Pending : CheckoutStatus

    data object Activated : CheckoutStatus

    data object NothingToRestore : CheckoutStatus

    data class Failed(val code: Int, val message: String) : CheckoutStatus
}

/**
 * Відповідь сервера ліцензій на покупку Play — у трьох чесних станах.
 * Окремо від мережі, щоб рішення можна було довести тестом без Play і без
 * сервера.
 */
sealed interface PlayRedemption {
    data class Key(val licenseKey: String, val tier: String) : PlayRedemption

    data object Pending : PlayRedemption

    data class Refused(val code: Int, val message: String) : PlayRedemption

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(status: Int, body: String): PlayRedemption {
            val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun field(name: String) = (obj?.get(name) as? JsonPrimitive)?.contentOrNull
            return when (status) {
                200 -> {
                    val key = field("license_key")
                    // 200 без ключа — не «успіх»: активувати нема чого.
                    if (key.isNullOrBlank()) {
                        Refused(status, "no license_key in reply")
                    } else {
                        Key(key, field("tier").orEmpty())
                    }
                }
                202 -> Pending
                else -> Refused(status, field("detail") ?: body.take(200))
            }
        }
    }
}
