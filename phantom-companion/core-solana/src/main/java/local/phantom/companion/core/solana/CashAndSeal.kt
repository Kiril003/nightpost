package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Готівка без інтернету: повністю підписаний переказ із «кишені», привʼязаний до durable nonce.
 * Його може подати в мережу будь-хто, хто першим вийде онлайн; удруге той самий nonce не спрацює.
 * Ризик подвійної витрати обмежений залишком кишені — і кишеня окрема від основного гаманця.
 */
data class CashVoucher(
    val payment: PaymentFrame,
    val tx: ByteArray,
    /** Знак «Seeker перевірено» відправника ([SeekerBadge]), сирий JSON; на купюрі необовʼязковий. */
    val badge: String? = null,
) {

    /** Кишеня, що підписала купюру, — її й має назвати відправник у PH-SOLKEY. */
    val signer: SolanaKey? get() = SolanaTx.messageOf(tx)?.let(SolanaTx::parse)?.keys?.firstOrNull()

    fun encode(): String = listOfNotNull(
        TAG + "1", payment.cluster.wire, payment.coin.wire, payment.units.toString(), payment.to.base58,
        Base64.getUrlEncoder().withoutPadding().encodeToString(tx),
        badge?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) },
    ).joinToString(";")

    override fun equals(other: Any?): Boolean = other is CashVoucher && other.payment == payment && other.tx.contentEquals(tx)
    override fun hashCode(): Int = payment.hashCode()

    companion object {
        const val TAG = "PH-CASH:"
        private const val MAX_TX = 1232
        private const val MAX_BADGE = 1024

        fun isVoucher(body: String): Boolean = body.trimStart().startsWith(TAG)

        fun of(cluster: Cluster, coin: Coin, units: Long, to: SolanaKey, tx: ByteArray): CashVoucher {
            val sig = Base58.encode(requireNotNull(SolanaTx.firstSignature(tx)))
            return CashVoucher(PaymentFrame(cluster, coin, units, to, sig), tx)
        }

        /** [mint] — лише для доказу на власному тестовому мінті; у застосунку мінт дає монета. */
        fun decode(body: String, mint: SolanaKey? = null): CashVoucher? {
            val wire = body.trim()
            if (!wire.startsWith(TAG)) return null
            val f = wire.removePrefix(TAG).split(';')
            // Сьоме поле — знак Seeker; купюри без нього (і старі телефони) лишаються шестиполовими.
            if (f.size !in 6..7 || f[0] != "1") return null
            val cluster = Cluster.of(f[1]) ?: return null
            val coin = Coin.of(f[2])?.takeIf { it.availableOn(cluster) } ?: return null
            val units = f[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val to = SolanaKey.parse(f[4]) ?: return null
            val tx = runCatching { Base64.getUrlDecoder().decode(f[5]) }.getOrNull()?.takeIf { it.size in 100..MAX_TX } ?: return null
            val sig = SolanaTx.firstSignature(tx) ?: return null
            val message = SolanaTx.messageOf(tx) ?: return null
            val parsed = SolanaTx.parse(message)?.takeIf { it.signers == 1 } ?: return null
            if (!verify(parsed.keys[0], sig, message)) return null
            if (!pays(parsed, coin, mint ?: coin.mint(cluster), to, units)) return null
            val badge = f.getOrNull(6)?.let { b ->
                runCatching { String(Base64.getUrlDecoder().decode(b), Charsets.UTF_8) }.getOrNull()?.takeIf { it.length <= MAX_BADGE } ?: return null
            }
            return CashVoucher(PaymentFrame(cluster, coin, units, to, Base58.encode(sig)), tx, badge)
        }

        private fun verify(key: SolanaKey, sig: ByteArray, message: ByteArray): Boolean =
            org.bouncycastle.crypto.signers.Ed25519Signer().apply {
                init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(key.bytes, 0))
                update(message, 0, message.size)
            }.verifySignature(sig)

        /** Підпис чесний — але чи платить ця транзакція саме те, що написано на купюрі? */
        private fun pays(p: SolanaTx.Parsed, coin: Coin, mint: SolanaKey?, to: SolanaKey, units: Long): Boolean {
            fun u64(d: ByteArray, from: Int) = (0 until 8).fold(0L) { acc, i -> acc or ((d[from + i].toLong() and 0xff) shl (8 * i)) }
            val first = p.instructions.firstOrNull() ?: return false
            if (first.first != Programs.SYSTEM || first.third.size != 4 || first.third[0].toInt() != 4) return false
            return p.instructions.any { (program, accounts, data) ->
                if (coin.native) {
                    program == Programs.SYSTEM && data.size == 12 && data[0].toInt() == 2 && accounts.getOrNull(1) == to && u64(data, 4) == units
                } else {
                    mint ?: return false
                    program == Programs.TOKEN && data.size == 10 && data[0].toInt() == 12 &&
                        accounts.getOrNull(2) == Programs.associatedTokenAddress(to, mint) && u64(data, 1) == units
                }
            }
        }

        /** Переказ із кишені: nonce першим, далі сам переказ; платник комісії — кишеня. */
        fun message(
            pocket: SolanaKey, nonce: SolanaKey, nonceValue: ByteArray, to: SolanaKey, coin: Coin, cluster: Cluster, units: Long,
            mint: SolanaKey? = coin.mint(cluster),
        ): ByteArray = Transfers.build(pocket, to, coin, cluster, units, nonceValue, nonce = nonce, mint = mint)
    }
}

/** Відбиток файла, закріплений у мережі. Сам файл і місце нікуди не їдуть — лише SHA-256. */
data class SealFrame(val cluster: Cluster, val sha256Hex: String, val signature: String) {

    fun encode(): String = listOf(TAG + "1", cluster.wire, sha256Hex, signature).joinToString(";")

    companion object {
        const val TAG = "PH-SEAL:"
        const val MEMO_PREFIX = "phantom-seal:v1:"

        fun memo(sha256Hex: String) = MEMO_PREFIX + sha256Hex

        /** Транзакція відбитка: одна Memo-інструкція `phantom-seal:v1:<sha256>`, платить і підписує власник. */
        fun message(owner: SolanaKey, recentBlockhash: ByteArray, sha256Hex: String): ByteArray =
            SolanaTx.message(owner, recentBlockhash, listOf(Programs.memo(memo(sha256Hex))))

        fun isSeal(body: String): Boolean = body.trimStart().startsWith(TAG)

        fun decode(body: String): SealFrame? {
            val wire = body.trim()
            if (!wire.startsWith(TAG)) return null
            val f = wire.removePrefix(TAG).split(';')
            if (f.size != 4 || f[0] != "1") return null
            val cluster = Cluster.of(f[1]) ?: return null
            val hex = f[2].takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            val sig = f[3].takeIf { s -> Base58.decode(s)?.size == 64 } ?: return null
            return SealFrame(cluster, hex, sig)
        }

        fun sha256Hex(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

sealed interface SealVerdict {
    /** [atSeconds] — час блоку, який засвідчив відбиток; раніше нього файлу не могло не бути. */
    data class Anchored(val atSeconds: Long?) : SealVerdict
    data object Pending : SealVerdict
    data object Mismatch : SealVerdict
    data object Failed : SealVerdict
}

object SealCheck {
    fun judge(sha256Hex: String, tx: JsonElement): SealVerdict {
        val root = tx as? JsonObject ?: return SealVerdict.Pending
        val meta = root["meta"] as? JsonObject ?: return SealVerdict.Pending
        if (meta["err"].let { it != null && it !is JsonNull }) return SealVerdict.Failed
        val ixs = root.path("transaction", "message", "instructions") as? JsonArray ?: return SealVerdict.Mismatch
        val wanted = SealFrame.memo(sha256Hex)
        val found = ixs.any { ix ->
            val o = ix as? JsonObject ?: return@any false
            val memo = (o["parsed"] as? JsonPrimitive)?.content
            o["programId"]?.jsonPrimitive?.content == Programs.MEMO.base58 && memo == wanted
        }
        if (!found) return SealVerdict.Mismatch
        return SealVerdict.Anchored((root["blockTime"] as? JsonPrimitive)?.longOrNull)
    }
}

object CashPlan {
    const val NOTES = 3
    /**
     * Запас SOL у кишені; кишеня — платник комісії, бо купюру підписує лише вона. Купюра в токені
     * ще й створює рахунок токена отримувача (офлайн невідомо, чи він є), і ренту платить теж кишеня.
     */
    const val FEES = 20_000_000L
    const val NOTE_FEE = 5_000L
    // Стара (вища) рента: мережа її вже знизила, але офлайн-купюра не може спитати поточну — беремо стелю.
    const val TOKEN_ACCOUNT_RENT = 2_039_280L
    const val POCKET_RENT = 890_880L

    /**
     * Купюри перевикористовуються після погашення, тож разового запасу [FEES] колись забракне.
     * Купюра без SOL на ренту не впаде, а зависне в дорозі назавжди — тому відмова до підпису.
     * [lamports] < 0 — кишеню ще не перевіряли.
     */
    fun solCovers(coin: Coin, lamports: Long, inFlight: Int): Boolean =
        coin.native || lamports < 0 || lamports - POCKET_RENT >= (inFlight + 1) * (TOKEN_ACCOUNT_RENT + NOTE_FEE)

    fun notes(pocket: SolanaKey): List<CashNote> = (0 until NOTES).map { i ->
        val seed = "phantom-cash-$i"
        CashNote(seed, Programs.seededAddress(pocket, seed, Programs.SYSTEM).base58)
    }

    /**
     * Поповнення кишені не забуває купюр у дорозі: видана, але ще не погашена купюра
     * лишається з підписом і сумою, інакше її гроші знову виглядали б вільними.
     * Null — у дорозі купюри іншої монети: кишеня веде одну монету за раз.
     */
    fun merge(old: CashPocket, coin: Coin, fresh: List<CashNote>): CashPocket? {
        val inFlight = old.notes.any { it.voucherSig != null }
        if (old.coin.isNotEmpty() && old.coin != coin.wire && inFlight) return null
        val known = old.notes.associateBy { it.nonce }
        val notes = fresh.map { known[it.nonce] ?: it } + old.notes.filter { n -> fresh.none { it.nonce == n.nonce } }
        return old.copy(coin = coin.wire, notes = notes)
    }

    /** Одна онлайн-транзакція: гроші в кишеню й nonce-рахунки для купюр, яких ще немає. Підписують гаманець і кишеня. */
    fun fund(
        owner: SolanaKey,
        pocket: SolanaKey,
        coin: Coin,
        cluster: Cluster,
        units: Long,
        rent: Long,
        missing: List<CashNote>,
        recentBlockhash: ByteArray,
        mint: SolanaKey? = coin.mint(cluster),
    ): ByteArray {
        val ixs = buildList {
            if (coin.native) {
                add(Programs.systemTransfer(owner, pocket, units + FEES))
            } else {
                requireNotNull(mint)
                add(Programs.systemTransfer(owner, pocket, FEES))
                add(Programs.createAssociatedIdempotent(owner, pocket, mint))
                add(Programs.tokenTransferChecked(owner, pocket, mint, units, coin.decimals))
            }
            missing.forEach { addAll(Programs.createNonceWithSeed(owner, pocket, it.seed, rent, pocket)) }
        }
        return SolanaTx.message(owner, recentBlockhash, ixs)
    }
}
