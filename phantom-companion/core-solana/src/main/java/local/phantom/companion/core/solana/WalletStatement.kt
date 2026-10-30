package local.phantom.companion.core.solana

/** Чим скінчилась спроба підписати речення гаманцем. */
sealed interface StatementOutcome<out T> {
    /** Людина підтвердила; значення — те, що повернув виклик, який користувався підписувачем. */
    data class Signed<T>(val value: T) : StatementOutcome<T>

    /** Відбиток чи PIN не прийнято (або відмовлено в діалозі): ключ навіть не розшифровували. */
    data object Declined : StatementOutcome<Nothing>

    /** Зовнішній гаманець (MWA/Seed Vault) уміє підписати транзакцію, а не довільне речення. */
    data object Unsupported : StatementOutcome<Nothing>

    /** Гаманця немає. */
    data object NoWallet : StatementOutcome<Nothing>
}

/**
 * Підпис ЗАКРИТОГО речення ключем гаманця — для заяв, у яких гаманець сам
 * свідчить, що це його адреса (бали: оплачену частку рахують лише тому, хто
 * тримає ключ гаманця).
 *
 * Правила ті самі, що й для переказу: підпис — лише після відбитка/PIN, і ключ
 * розшифровується ПІСЛЯ згоди, а не до неї. Підписувач живе рівно стільки, скільки
 * триває виклик: його не можна зберегти й підписати ним щось інше потім.
 * Речення складає викликач (а не веб), тож це не оракул підпису під довільним
 * текстом з чужої сторінки.
 */
internal object WalletStatement {

    suspend fun <T> run(
        kind: WalletKind?,
        confirm: suspend () -> Boolean,
        keypair: suspend () -> Ed25519Keypair,
        use: suspend ((ByteArray) -> ByteArray) -> T,
    ): StatementOutcome<T> {
        if (kind == null) return StatementOutcome.NoWallet
        if (kind != WalletKind.BUILT_IN) return StatementOutcome.Unsupported
        if (!confirm()) return StatementOutcome.Declined
        val key = try { keypair() } catch (e: VaultLocked) { return StatementOutcome.Declined }
        return StatementOutcome.Signed(use { message -> key.sign(message) })
    }
}
