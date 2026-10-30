package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.sha256
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.util.Base64

/**
 * «Намір» — запечатане прохання до МОГО ПК, що їде будь-якою щілиною.
 *
 * Біблія месенджера, розд. 07 «Обхідний інтернет», §2.2. ДЖЕРЕЛО ПРАВДИ ПРО
 * ВИКОНАННЯ — ПК: `phantom-os/src/backend/intents/wire.py`. Кожен байт нижче
 * звірено зі спільним вектором `intent_wire_vector.json`, який рахує Python;
 * розходження тут не дає помилки — Намір просто не відчиниться на ПК.
 *
 * Запечатаний кадр (24 Б накладних):
 *
 *     тип 1 · id пари 3 · лічильник 4 · шифротекст · мітка 16
 *
 *  * тип — [INTENT] (телефон → ПК), [ANSWER] (щабель 1, SMS) або
 *    [ANSWER_FULL] (щабель 2, сховок і LAN);
 *  * id пари — підказка, яким ключем відчиняти, а не імʼя;
 *  * лічильник — телефон НІКОЛИ не повторює його в межах пари; відповідь
 *    відлунює лічильник Наміру, тож за ним видно, на що вона;
 *  * AAD — перші 8 Б (заголовок), nonce — заголовок і чотири нулі.
 *
 * Ключі — HKDF-гілки ключа пари телефон↔ПК ([PeerChannel], той самий, що в
 * листів). Ключ відповіді містить id Наміру і тип кадру: щаблі 1 і 2 ніколи
 * не шифруються одним ключем і одним nonce.
 *
 * Відкритий Намір: id 8 · op 1 · created 3 (хвилини від [EPOCH_MS]) ·
 * deadline 2 (хвилини; 0 — сім діб) · flags 1 · reply_to 1 · args.
 *
 * Сховком Намір їде звичайним підписаним листом із тілом [TAG] + base64,
 * відповідь — листом ПК із тілом [ANSWER_TAG] + base64. Обидва — кадри
 * пристрою: у розмову не потрапляють ([PeerFrames.Kind.INTENT],
 * [PeerFrames.Kind.ANSWER]).
 */
object PeerIntent {

    const val TAG = "PH-INT:"
    const val ANSWER_TAG = "PH-ANS:"

    /**
     * Намір, який приніс мій домашній шлюз (шлюз А): ПК виконує його як
     * SMS-смугу й відповідає щаблем 1 листом самому шлюзу, а шлюз шле SMS.
     */
    const val GATEWAY_TAG = "PH-ING:"

    const val INTENT: Int = 0x01
    const val ANSWER: Int = 0x02
    const val ANSWER_FULL: Int = 0x03

    const val HEADER_BYTES = 8
    const val TAG_BYTES = 16
    const val SEAL_OVERHEAD = HEADER_BYTES + TAG_BYTES
    const val PLAIN_HEADER_BYTES = 16
    const val ID_BYTES = 8

    const val OP_ALERTS = 0x01
    const val OP_WHO_ONLINE = 0x02
    const val OP_ASK = 0x04
    const val OP_PAGE_GIST = 0x07

    /** «Сирена-маяк»: підписаний знімок тривог (92 Б), перевіряє [local.phantom.companion.core.net.siren.SirenSnapshot]. */
    const val OP_SIREN = 0x0B

    /** 2026-01-01T00:00Z — нуль годинника Намірів. */
    const val EPOCH_MS = 1_767_225_600_000L
    private const val MAX_MINUTES = (1 shl 24) - 1

    /** Молодші 4 біти байта стану відповіді. Номери не міняються ніколи. */
    object Status {
        const val OK = 0x0

        /** Джерело мовчить. Це НЕ «тривог немає». */
        const val SOURCE_SILENT = 0x1
        const val NOT_FOUND = 0x2
        const val REFUSED = 0x3

        /** ПК не встиг — людині «ПК думає довше», а не тиша. */
        const val TIMEOUT = 0x4
        const val UNSUPPORTED = 0x5
        const val EXPIRED = 0x6
        const val FAILED = 0x7
    }

    /** Смуги відповіді в reply_to. Біти 7–6 — скільки SMS дозволено (0 означає 1). */
    object Lane {
        const val SMS = 0x01
        const val RELAY = 0x02
        const val LAN = 0x04

        /** Корисне в 1/2/3 SMS, уже без печатки (розд. 07 §3.3.2). */
        val SMS_BUDGET = mapOf(1 to 96, 2 to 205, 3 to 320)
        const val RELAY_BUDGET = 4096 - SEAL_OVERHEAD

        fun replyTo(smsParts: Int = 0, relay: Boolean = false, lan: Boolean = false): Int {
            var bits = (if (relay) RELAY else 0) or (if (lan) LAN else 0)
            if (smsParts != 0) {
                require(smsParts in SMS_BUDGET) { "SMS на відповідь — від 1 до 3" }
                bits = bits or SMS or (smsParts shl 6)
            }
            return bits
        }
    }

    /**
     * Номер області в Намірі «тривоги» і біт у бітмапі відповіді. ЗАМОРОЖЕНО
     * разом із ПК (`intents/handlers.py`, OBLAST_ORDER): нова лише дописується.
     */
    val OBLAST_ORDER: List<String> = listOf(
        "cherkasy", "chernihiv", "chernivtsi", "crimea", "dnipro", "donetsk",
        "ivano-frankivsk", "kharkiv", "kherson", "khmelnytskyi", "kirovohrad",
        "kyiv-city", "kyiv", "luhansk", "lviv", "mykolaiv", "odesa", "poltava",
        "rivne", "sevastopol", "sumy", "ternopil", "vinnytsia", "volyn",
        "zakarpattia", "zaporizhzhia", "zhytomyr",
    )

    class Intent(
        val id: ByteArray,
        val op: Int,
        val createdMin: Int,
        val deadlineMin: Int,
        val flags: Int = 0,
        val replyTo: Int = 0,
        val args: ByteArray = ByteArray(0),
    ) {
        init {
            require(id.size == ID_BYTES) { "id Наміру — рівно 8 байтів" }
        }

        fun toBytes(): ByteArray {
            val head = ByteArray(PLAIN_HEADER_BYTES)
            id.copyInto(head, 0)
            head[8] = op.toByte()
            putInt(head, 9, createdMin and MAX_MINUTES, 3)
            putInt(head, 12, deadlineMin and 0xFFFF, 2)
            head[14] = flags.toByte()
            head[15] = replyTo.toByte()
            return head + args
        }

        companion object {
            fun fromBytes(raw: ByteArray): Intent? {
                if (raw.size < PLAIN_HEADER_BYTES) return null
                return Intent(
                    id = raw.copyOfRange(0, 8),
                    op = raw[8].toInt() and 0xFF,
                    createdMin = getInt(raw, 9, 3),
                    deadlineMin = getInt(raw, 12, 2),
                    flags = raw[14].toInt() and 0xFF,
                    replyTo = raw[15].toInt() and 0xFF,
                    args = raw.copyOfRange(PLAIN_HEADER_BYTES, raw.size),
                )
            }
        }
    }

    /** Відчинена відповідь ПК. [rung] — щабель драбини з типу кадру. */
    class Answer(val rung: Int, val status: Int, val body: ByteArray, val counter: Long)

    /** Тіло відповіді «тривоги» (8 Б). [sinceMin] null — невідомо; [ageS] 255 — ≥ 255 с. */
    data class Alerts(val oblasts: Set<String>, val myType: Int, val sinceMin: Int?, val ageS: Int)

    /** Тіло «хто на звʼязку» (4 Б). [myLastState] null — листів їй ще не було. */
    data class Presence(val minutesAgo: Int?, val lane: Int, val myLastState: Int?)

    fun minutesOf(atMs: Long): Int = ((atMs - EPOCH_MS) / 60_000L).coerceIn(0L, MAX_MINUTES.toLong()).toInt()

    fun pairHint(phoneId: String, pcId: String): ByteArray =
        sha256("PHANTOM intent pair\u001f$phoneId\u001f$pcId".toByteArray(Charsets.UTF_8)).copyOfRange(0, 3)

    fun intentKey(pairKey: ByteArray, phoneId: String, pcId: String): ByteArray =
        Hkdf.derive(pairKey, ByteArray(0), "PHANTOM intent v0\u001f$phoneId\u001f$pcId".toByteArray(Charsets.UTF_8))

    fun answerKey(pairKey: ByteArray, phoneId: String, pcId: String, intentId: ByteArray, kind: Int): ByteArray =
        Hkdf.derive(
            pairKey,
            ByteArray(0),
            "PHANTOM answer v0\u001f$phoneId\u001f$pcId\u001f".toByteArray(Charsets.UTF_8) +
                byteArrayOf(kind.toByte()) + intentId,
        )

    /**
     * 4 Б, якими телефон називає контакт у «хто на звʼязку». Імʼя, номер чи
     * ключ контакту не їдуть жодною смугою (розд. 07 §9.3, правило 1).
     */
    fun contactTag(pairKey: ByteArray, peerNodeId: String): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(pairKey))
        val msg = "PHANTOM contact tag\u001f$peerNodeId".toByteArray(Charsets.UTF_8)
        mac.update(msg, 0, msg.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out.copyOfRange(0, 4)
    }

    fun sealIntent(intent: Intent, pairKey: ByteArray, phoneId: String, pcId: String, counter: Long): ByteArray =
        seal(INTENT, intentKey(pairKey, phoneId, pcId), pairHint(phoneId, pcId), counter, intent.toBytes())

    /** Бік ПК — телефону потрібен лише для власних тестів і стенду. */
    fun openIntent(frame: ByteArray, pairKey: ByteArray, phoneId: String, pcId: String): Intent? {
        if (!headerFits(frame, INTENT, phoneId, pcId)) return null
        return open(frame, intentKey(pairKey, phoneId, pcId))?.let(Intent::fromBytes)
    }

    /** Бік ПК: запечатати відповідь щаблем [rung]. Для вектора й стенду. */
    fun sealAnswer(
        plain: ByteArray,
        pairKey: ByteArray,
        phoneId: String,
        pcId: String,
        intentId: ByteArray,
        counter: Long,
        rung: Int,
    ): ByteArray {
        val kind = kindOfRung(rung)
        return seal(kind, answerKey(pairKey, phoneId, pcId, intentId, kind), pairHint(phoneId, pcId), counter, plain)
    }

    /** Відповідь на мій Намір [intentId], або null — не моя, зіпсована або чужа. */
    fun openAnswer(frame: ByteArray, pairKey: ByteArray, phoneId: String, pcId: String, intentId: ByteArray): Answer? {
        if (frame.size < SEAL_OVERHEAD) return null
        val kind = frame[0].toInt() and 0xFF
        if (kind != ANSWER && kind != ANSWER_FULL) return null
        if (!headerFits(frame, kind, phoneId, pcId)) return null
        val plain = open(frame, answerKey(pairKey, phoneId, pcId, intentId, kind)) ?: return null
        if (plain.isEmpty()) return null
        val state = plain[0].toInt() and 0xFF
        return Answer(
            rung = state ushr 4,
            status = state and 0x0F,
            body = plain.copyOfRange(1, plain.size),
            counter = counterOf(frame),
        )
    }

    /** Лічильник із заголовка: за ним видно, на який Намір прийшла відповідь. */
    fun counterOf(frame: ByteArray): Long = getInt(frame, 4, 4).toLong() and 0xFFFF_FFFFL

    fun decodeAlerts(body: ByteArray): Alerts? {
        if (body.size != 8) return null
        val bitmap = getInt(body, 0, 4).toLong() and 0xFFFF_FFFFL
        val oblasts = OBLAST_ORDER.filterIndexed { i, _ -> (bitmap shr i) and 1L == 1L }.toSet()
        val since = getInt(body, 5, 2)
        return Alerts(oblasts, body[4].toInt() and 0xFF, since.takeIf { it != 0xFFFF }, body[7].toInt() and 0xFF)
    }

    fun decodePresence(body: ByteArray): Presence? {
        if (body.size != 4) return null
        val minutes = getInt(body, 0, 2)
        val state = body[3].toInt() and 0xFF
        return Presence(minutes.takeIf { it != 0xFFFF }, body[2].toInt() and 0xFF, state.takeIf { it != 0xFF })
    }

    // ── тіло листа сховком ──────────────────────────────────────────────────

    fun isIntent(body: String): Boolean = body.startsWith(TAG)

    fun isAnswer(body: String): Boolean = body.startsWith(ANSWER_TAG)

    fun isGateway(body: String): Boolean = body.startsWith(GATEWAY_TAG)

    fun isFrame(body: String): Boolean = isIntent(body) || isAnswer(body) || isGateway(body)

    fun encodeIntent(sealed: ByteArray): String = TAG + B64.encodeToString(sealed)

    fun encodeAnswer(sealed: ByteArray): String = ANSWER_TAG + B64.encodeToString(sealed)

    /** Запечатаний кадр із тіла листа ПК, або null — це не відповідь. */
    fun answerFrame(body: String): ByteArray? =
        if (!isAnswer(body)) null else runCatching { B64D.decode(body.removePrefix(ANSWER_TAG)) }.getOrNull()

    // ── печатка ──────────────────────────────────────────────────────────────

    private val B64 = Base64.getEncoder().withoutPadding()
    private val B64D = Base64.getDecoder()

    private fun kindOfRung(rung: Int): Int = when (rung) {
        1 -> ANSWER
        2 -> ANSWER_FULL
        else -> throw IllegalArgumentException("щабель — 1 або 2")
    }

    private fun headerFits(frame: ByteArray, kind: Int, phoneId: String, pcId: String): Boolean =
        frame.size >= SEAL_OVERHEAD &&
            (frame[0].toInt() and 0xFF) == kind &&
            frame.copyOfRange(1, 4).contentEquals(pairHint(phoneId, pcId))

    private fun header(kind: Int, pair: ByteArray, counter: Long): ByteArray {
        val head = ByteArray(HEADER_BYTES)
        head[0] = kind.toByte()
        pair.copyInto(head, 1)
        putInt(head, 4, (counter and 0xFFFF_FFFFL).toInt(), 4)
        return head
    }

    private fun nonce(head: ByteArray): ByteArray = head + ByteArray(4)

    private fun seal(kind: Int, key: ByteArray, pair: ByteArray, counter: Long, plain: ByteArray): ByteArray {
        val head = header(kind, pair, counter)
        val aead = ChaCha20Poly1305()
        aead.init(true, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce(head), head))
        val out = ByteArray(aead.getOutputSize(plain.size))
        val n = aead.processBytes(plain, 0, plain.size, out, 0)
        aead.doFinal(out, n)
        return head + out
    }

    private fun open(frame: ByteArray, key: ByteArray): ByteArray? {
        val head = frame.copyOfRange(0, HEADER_BYTES)
        val aead = ChaCha20Poly1305()
        aead.init(false, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce(head), head))
        val body = frame.copyOfRange(HEADER_BYTES, frame.size)
        val out = ByteArray(aead.getOutputSize(body.size))
        return try {
            val n = aead.processBytes(body, 0, body.size, out, 0)
            aead.doFinal(out, n)
            out
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    private fun putInt(dst: ByteArray, at: Int, value: Int, width: Int) {
        for (i in 0 until width) dst[at + i] = (value ushr (8 * (width - 1 - i))).toByte()
    }

    private fun getInt(src: ByteArray, at: Int, width: Int): Int {
        var v = 0
        for (i in 0 until width) v = (v shl 8) or (src[at + i].toInt() and 0xFF)
        return v
    }
}
