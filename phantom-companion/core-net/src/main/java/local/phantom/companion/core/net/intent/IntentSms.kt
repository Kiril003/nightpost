package local.phantom.companion.core.net.intent

import local.phantom.companion.core.net.peer.PeerIntent
import java.util.Base64

/**
 * Намір у текстовій SMS (розд. 07 §3.3.2, режим «текст GSM-7, алфавіт base64»).
 *
 * Саме текст, не data SMS на порт: data SMS щільніша на 14 Б, але не всі
 * оператори, роумінг і D2C її пропускають (§12.4 — перший ризик демо). Усі 64
 * знаки base64 є в базовій таблиці GSM-7, тож SMS не переходить у UCS-2:
 * 160 знаків × 6 біт = 120 Б кадру = 96 Б корисного після 24 Б печатки.
 *
 * Префікса немає, і це рішення: навіть один знак зсунув би кадр 120 Б за
 * межу однієї SMS. Свою SMS відрізняє заголовок кадру — тип і підказка пари —
 * а остаточно мітка AEAD; чужа SMS, що випадково скидається на base64, не
 * відчиниться ключем пари.
 */
object IntentSms {

    /** Межа однієї SMS у знаках GSM-7. */
    const val SMS_CHARS = 160

    fun encode(frame: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(frame)

    /** Кадр Наміру чи відповіді з тексту SMS, або null — це звичайна SMS. */
    fun decode(text: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < MIN_CHARS || compact.any { it !in ALPHABET }) return null
        val raw = runCatching { Base64.getDecoder().decode(compact) }.getOrNull() ?: return null
        val kind = raw[0].toInt() and 0xFF
        if (kind != PeerIntent.INTENT && kind != PeerIntent.ANSWER && kind != PeerIntent.ANSWER_FULL) return null
        return raw
    }

    /** Скільки SMS займе кадр (склеєні мають 153 знаки на частину). */
    fun parts(frame: ByteArray): Int {
        val chars = encode(frame).length
        return if (chars <= SMS_CHARS) 1 else (chars + 152) / 153
    }

    private const val MIN_CHARS = (PeerIntent.SEAL_OVERHEAD * 4 + 2) / 3
    private val ALPHABET = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/").toSet()
}
