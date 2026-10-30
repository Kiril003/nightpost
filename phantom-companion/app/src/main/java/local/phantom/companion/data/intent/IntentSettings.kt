package local.phantom.companion.data.intent

import android.content.Context
import local.phantom.companion.core.net.intent.IntentBrain
import local.phantom.companion.data.EncryptedSlot
import local.phantom.companion.data.PeerLogSlot

/**
 * Налаштування SMS-смуги Намірів — лише на цьому апараті, зашифровано.
 *
 * Номер мого шлюзу (старий телефон удома, розд. 07 §3.3.1, «шлюз А») —
 * особисте; у git, у журнал і в бандл він не потрапляє ніколи. Порожній
 * номер означає «SMS-смуги немає», а не «пробувати навмання».
 */
class IntentSettings internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(EncryptedSlot(context, FILE_NAME, durable = true))

    var gatewayNumber: String
        get() = slot.read(KEY_GATEWAY).orEmpty()
        set(value) = slot.write(KEY_GATEWAY, normalize(value).ifEmpty { null })

    /** §9.3, правило 2: SMS для особистого — лише якщо людина дозволила сама. */
    var personalAllowed: Boolean
        get() = slot.read(KEY_PERSONAL) == "1"
        set(value) = slot.write(KEY_PERSONAL, if (value) "1" else null)

    /** «Тиха людина»: жодних SMS. */
    var quiet: Boolean
        get() = slot.read(KEY_QUIET) == "1"
        set(value) = slot.write(KEY_QUIET, if (value) "1" else null)

    /** «Цей телефон — домашній шлюз» (лише збірка field): приймає SMS-Наміри моїх телефонів. */
    var homeGateway: Boolean
        get() = slot.read(KEY_HOME_GATEWAY) == "1"
        set(value) = slot.write(KEY_HOME_GATEWAY, if (value) "1" else null)

    /** Номери МОЇХ телефонів, від яких шлюз бере SMS. Сміття не стає номером. */
    var allowed: List<String>
        get() = slot.read(KEY_ALLOWED).orEmpty().split(',').filter { it.isNotBlank() }
        set(value) = slot.write(
            KEY_ALLOWED,
            value.map(::normalize).filter { it.isNotEmpty() }.distinct().take(MAX_ALLOWED).joinToString(",").ifEmpty { null },
        )

    /** [smsCapable] — збірка вміє SMS і дозвіл SEND_SMS видано. */
    fun policy(smsCapable: Boolean): IntentBrain.SmsPolicy = IntentBrain.SmsPolicy(
        available = smsCapable && gatewayNumber.isNotEmpty(),
        personalAllowed = personalAllowed,
        quiet = quiet,
    )

    /** SMS прийшла саме від мого шлюзу: збіг останніх девʼяти цифр (+380… і 0… — той самий номер). */
    fun isGateway(from: String?): Boolean {
        val mine = digits(gatewayNumber)
        val theirs = digits(from.orEmpty())
        if (mine.length < MIN_DIGITS || theirs.length < MIN_DIGITS) return false
        return mine.takeLast(MIN_DIGITS) == theirs.takeLast(MIN_DIGITS)
    }

    companion object {
        const val FILE_NAME = "phantom_intent_settings"
        private const val KEY_GATEWAY = "gateway"
        private const val KEY_PERSONAL = "personal_sms"
        private const val KEY_QUIET = "quiet"
        private const val KEY_HOME_GATEWAY = "home_gateway"
        private const val KEY_ALLOWED = "allowed"
        private const val MAX_ALLOWED = 8
        private const val MIN_DIGITS = 9

        internal fun normalize(raw: String): String {
            val t = raw.trim()
            val d = digits(t)
            if (d.length < MIN_DIGITS || d.length > 15) return ""
            return if (t.startsWith("+")) "+$d" else d
        }

        private fun digits(s: String) = s.filter { it.isDigit() }
    }
}
