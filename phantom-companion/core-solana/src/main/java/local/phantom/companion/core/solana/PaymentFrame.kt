package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Переказ у розмові. На дроті — лише факти, які кожен може звірити з мережею:
 * що, скільки, кому і підпис транзакції. Суми «від себе» тут немає: картка
 * отримувача показує «підтверджено» лише після того, як мережа це підтвердила.
 *
 * [ref] — id рахунку, частку якого цим переказом платять (збірка 18,
 * `docs/scene/MEETUP_CONTRACT.md` §4). Без нього кадр їде як v1, байт у байт
 * як раніше; з ним — v2 = v1 + `;ref`. Веб зараховує частку лише з `ref`.
 */
data class PaymentFrame(
    val cluster: Cluster,
    val coin: Coin,
    val units: Long,
    val to: SolanaKey,
    val signature: String,
    val ref: String? = null,
    val note: String? = null,
) {
    init {
        // `;` у ref розірвав би кадр, і чужий телефон прочитав би інше, ніж підписано.
        require(ref == null || isRef(ref)) { "bad ref" }
        require(note == null || (ref != null && FrameNote.isValid(note))) { "bad note" }
    }

    val amount: String get() = Amounts.format(units, coin)

    fun encode(): String {
        val head = listOf(cluster.wire, coin.wire, units.toString(), to.base58, signature)
        if (ref == null) return (listOf(TAG + "1") + head).joinToString(";")
        val v2 = listOf(TAG + "2") + head + ref
        return (if (note == null) v2 else v2 + FrameNote.encode(note)).joinToString(";")
    }

    companion object {
        const val TAG = "PH-PAY:"

        /** Межа id рахунку: uuid (36) влазить, і `dish:<id>`-подібні id теж. */
        const val MAX_REF = 36
        private val REF = Regex("[A-Za-z0-9_-]{1,$MAX_REF}")
        private const val REF_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val rng = java.security.SecureRandom()

        fun isRef(s: String?): Boolean = s != null && REF.matches(s)

        fun refKey(ref: String): SolanaKey {
            require(isRef(ref)) { "bad ref" }
            return SolanaKey.of(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("nightpost-ref:v1:$ref").toByteArray(Charsets.UTF_8))
            )!!
        }

        fun newRef(): String = buildString(12) {
            append('n')
            repeat(11) { append(REF_ALPHABET[rng.nextInt(REF_ALPHABET.length)]) }
        }

        /** Початок Memo-запису переказу частки: далі id рахунку (до 36 знаків, у межах 256 байт спонсора). */
        const val MEMO_PREFIX = "nightpost-bill:v1:"

        /** Що писати в Memo переказу, який платить частку рахунку [ref]; null — переказ без рахунку, без Memo. */
        fun memo(ref: String?): String? = ref?.takeIf(::isRef)?.let { MEMO_PREFIX + it }

        /** Id рахунку з Memo-запису транзакції; null — запис чужий чи зіпсований. */
        fun refOfMemo(text: String?): String? =
            text?.takeIf { it.startsWith(MEMO_PREFIX) }?.removePrefix(MEMO_PREFIX)?.takeIf(::isRef)

        fun isPayment(body: String): Boolean = body.trimStart().startsWith(TAG)

        fun decode(body: String): PaymentFrame? {
            val wire = body.trim()
            if (!wire.startsWith(TAG)) return null
            val f = wire.removePrefix(TAG).split(';')
            val ref = when {
                f.size == 6 && f[0] == "1" -> null
                // v2 читає свої сім полів і мовчки пропускає те, що допише наступна версія.
                f.size >= 7 && f[0] == "2" -> f[6].takeIf(::isRef) ?: return null
                else -> return null
            }
            val cluster = Cluster.of(f[1]) ?: return null
            val coin = Coin.of(f[2])?.takeIf { it.availableOn(cluster) } ?: return null
            val units = f[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val to = SolanaKey.parse(f[4]) ?: return null
            val sig = f[5].takeIf { s -> Base58.decode(s)?.size == 64 } ?: return null
            val note = if (f[0] == "2" && f.size >= 8) FrameNote.decode(f[7]) else null
            return PaymentFrame(cluster, coin, units, to, sig, ref, note)
        }
    }
}

/**
 * Звідки людина прийшла платити: [chatId] — розмова (група зустрічі), куди має
 * лягти картка; [ref] — id рахунку. Порожній — звичайний переказ у 1:1.
 */
data class PayRoute(
    val chatId: String? = null,
    val ref: String? = null,
    val reference: SolanaKey? = null,
    val memo: String? = null,
)

/** Що стається з карткою переказу після відповіді гаманця. */
object PaymentPost {

    /**
     * Транзакція переказу в розмові. Частка рахунку ([ref]) пишеться ще й НА МЕРЕЖУ — Memo-інструкцією
     * поруч з переказом, як і в купівлі ліцензії: id рахунку видно в провіднику, і він не залежить від
     * картки в чаті. Без [ref] — той самий переказ, що й був, байт у байт. Кривий [ref] — відмова ДО
     * підпису: гроші не підуть з невписаним рахунком.
     */
    fun transfer(
        owner: SolanaKey,
        feePayer: SolanaKey,
        to: SolanaKey,
        coin: Coin,
        cluster: Cluster,
        units: Long,
        recentBlockhash: ByteArray,
        ref: String?,
        reference: SolanaKey? = null,
        memo: String? = null,
    ): ByteArray {
        require(ref == null || PaymentFrame.isRef(ref)) { "bad ref" }
        require(ref == null || (reference == null && memo == null)) { "bill or request" }
        return Transfers.build(owner, to, coin, cluster, units, recentBlockhash, reference = ref?.let(PaymentFrame::refKey) ?: reference, memo = PaymentFrame.memo(ref) ?: memo, feePayer = feePayer)
    }

    /**
     * Кадр складається й їде ЛИШЕ з підписом мережі. Скасований відбиток, відмова
     * гаманця чи збій — нічого не складено і нікуди не поїхало.
     */
    suspend fun after(
        outcome: WalletOutcome<String>,
        frame: (signature: String) -> PaymentFrame,
        post: suspend (PaymentFrame) -> Boolean,
    ): WalletOutcome<Paid> = when (outcome) {
        is WalletOutcome.Done -> frame(outcome.value).let { f -> WalletOutcome.Done(Paid(f, post(f))) }
        WalletOutcome.NoWallet -> WalletOutcome.NoWallet
        WalletOutcome.Declined -> WalletOutcome.Declined
        is WalletOutcome.Failed -> outcome
    }

    /**
     * Що сказати після переказу. Гроші пішли завжди, коли [WalletOutcome.Done]; а от
     * «картка в розмові» — лише якщо її справді поклали: інакше людина шукала б
     * картку, якої ніде немає.
     */
    fun paidLine(r: WalletOutcome<Paid>): Int =
        if ((r as? WalletOutcome.Done)?.value?.posted == false) R.string.sol_paid_unposted else R.string.sol_paid

    /**
     * Кому до картки їде моя адреса (`PH-SOLKEY:`). Картку в групі звіряє кожен: без
     * моєї адреси чужий телефон не знає, чи платив саме я, і частку не зарахує.
     * Тож для групи — отримувач і всі учасники ([members] — інші, без мене); для
     * 1:1 — як раніше, лише отримувач. Гаманець закладу ([peerId] null) — нікому
     * поза групою.
     */
    fun addressTo(peerId: String?, route: PayRoute, members: List<String>): List<String> {
        val inGroup = route.chatId?.takeIf { it.isNotBlank() && it != peerId } != null
        return (listOfNotNull(peerId) + if (inGroup) members else emptyList()).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Куди лягає картка: у розмову з [PayRoute.chatId], якщо вона є й це не та сама
     * 1:1; інакше — отримувачу [peerId]. Гроші вже пішли, тож коли групи вже нема
     * (вийшов, видалили), картка не губиться, а йде отримувачу напряму.
     * Адреса закладу без групи ([peerId] null) — картку нікому показати.
     * Вертає, чи картка кудись лягла: false — гроші пішли, а картки ніхто не побачить.
     */
    suspend fun deliver(
        frame: PaymentFrame,
        peerId: String?,
        route: PayRoute,
        toPeer: suspend (peerId: String, body: String) -> Boolean,
        toChat: suspend (chatId: String, body: String) -> Boolean,
    ): Boolean {
        val body = frame.encode()
        val chat = route.chatId?.takeIf { it.isNotBlank() && it != peerId }
        if (chat != null && toChat(chat, body)) return true
        return peerId?.let { toPeer(it, body) } ?: false
    }
}

/** Переказ пішов мережею; [posted] — чи лягла його картка хоч у якусь розмову. */
data class Paid(val frame: PaymentFrame, val posted: Boolean)

/** Адреса для переказів, яку людина дала співрозмовнику сама, всередині зашифрованої розмови. */
object WalletAddressFrame {
    const val TAG = "PH-SOLKEY:"

    fun encode(key: SolanaKey): String = TAG + "1;" + key.base58

    fun isAddress(body: String): Boolean = body.trimStart().startsWith(TAG)

    fun decode(body: String): SolanaKey? {
        val wire = body.trim()
        if (!wire.startsWith(TAG)) return null
        val f = wire.removePrefix(TAG).split(';')
        if (f.size != 2 || f[0] != "1") return null
        return SolanaKey.parse(f[1])
    }

    // Окремим рядком і ПЕРЕД адресою: старі збірки беруть останній PH-SOLKEY і читають лише «1;».
    private const val POCKET = "cash1"

    fun encodePocket(pocket: SolanaKey): String = "$TAG$POCKET;${pocket.base58}"

    fun decodePocket(body: String): SolanaKey? {
        val f = body.trim().takeIf { it.startsWith(TAG) }?.removePrefix(TAG)?.split(';') ?: return null
        if (f.size != 2 || f[0] != POCKET) return null
        return SolanaKey.parse(f[1])
    }
}

enum class PaymentStatus(val wire: String) {
    UNKNOWN("unknown"),
    PENDING("pending"),
    CONFIRMED("confirmed"),
    FAILED("failed"),
    MISMATCH("mismatch"),
    ;

    val settled: Boolean get() = this == CONFIRMED || this == FAILED || this == MISMATCH
}

object PaymentCheck {

    /** Вердикт за відповіддю getTransaction (jsonParsed). Null-відповідь — транзакції ще не видно. */
    /** [payer] — чий гаманець мав платити; null лише для власних карток. */
    fun judge(frame: PaymentFrame, tx: JsonElement, payer: SolanaKey? = null, mint: SolanaKey? = frame.coin.mint(frame.cluster)): PaymentStatus {
        val root = tx as? JsonObject ?: return PaymentStatus.PENDING
        val meta = root["meta"] as? JsonObject ?: return PaymentStatus.PENDING
        if (meta["err"].let { it != null && it !is JsonNull }) return PaymentStatus.FAILED
        val paid = if (frame.coin.native) nativePaid(frame, root, payer) else tokenDelta(frame, meta, payer, mint?.base58 ?: return PaymentStatus.MISMATCH)
        return if (paid >= frame.units) PaymentStatus.CONFIRMED else PaymentStatus.MISMATCH
    }

    private fun nativePaid(frame: PaymentFrame, root: JsonObject, payer: SolanaKey?): Long {
        val ixs = root.path("transaction", "message", "instructions") as? JsonArray ?: return 0
        return ixs.sumOf { ix ->
            val o = ix as? JsonObject ?: return@sumOf 0L
            val parsed = o["parsed"] as? JsonObject ?: return@sumOf 0L
            if (o["program"]?.jsonPrimitive?.content != "system") return@sumOf 0L
            if (parsed["type"]?.jsonPrimitive?.content != "transfer") return@sumOf 0L
            val info = parsed["info"] as? JsonObject ?: return@sumOf 0L
            if (info["destination"]?.jsonPrimitive?.content != frame.to.base58) return@sumOf 0L
            if (payer != null && info["source"]?.jsonPrimitive?.content != payer.base58) return@sumOf 0L
            info["lamports"]?.jsonPrimitive?.longOrNull ?: 0L
        }
    }

    private fun tokenDelta(frame: PaymentFrame, meta: JsonObject, payer: SolanaKey?, mint: String): Long {
        fun total(key: String, owner: SolanaKey): Long = (meta[key] as? JsonArray).orEmpty().sumOf { b ->
            val o = b as? JsonObject ?: return@sumOf 0L
            if (o["mint"]?.jsonPrimitive?.content != mint) return@sumOf 0L
            if (o["owner"]?.jsonPrimitive?.content != owner.base58) return@sumOf 0L
            o.path("uiTokenAmount", "amount")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        }
        val received = total("postTokenBalances", frame.to) - total("preTokenBalances", frame.to)
        if (payer == null) return received
        val spent = total("preTokenBalances", payer) - total("postTokenBalances", payer)
        return minOf(received, spent)
    }

    suspend fun check(frame: PaymentFrame, rpc: SolanaRpc, payer: SolanaKey? = null, mint: SolanaKey? = frame.coin.mint(frame.cluster)): PaymentStatus =
        runCatching { judge(frame, rpc.transaction(frame.signature), payer, mint) }.getOrDefault(PaymentStatus.UNKNOWN)
}
